package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.provided.DemoCredentialIssueCommand
import com.paycore.payment.payment.application.provided.DemoCredentialIssueResult
import com.paycore.payment.payment.application.provided.DemoCredentialIssueUseCase
import com.paycore.payment.payment.application.provided.DemoCredentialRefusal
import com.paycore.payment.payment.application.required.DemoCredentialIssuance
import com.paycore.payment.payment.application.required.DemoCredentialIssuanceStore
import com.paycore.payment.payment.application.required.DemoCredentialProjection
import com.paycore.payment.payment.application.required.DemoCredentialProjectionWriter
import com.paycore.payment.payment.application.required.MidPolicyProfilePort
import com.paycore.payment.payment.application.required.PaymentClock
import com.paycore.payment.payment.application.required.PersistenceFailure
import com.paycore.payment.payment.application.required.StoreConstraint
import com.paycore.payment.payment.domain.MerchantAccountId
import org.springframework.transaction.PlatformTransactionManager
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.HexFormat

/**
 * Demo API key 발급(DEMO-SEED-001 §4.5 — D-DEMO-002·004). 한 PAYMENT 로컬 TX: MID 확인(최신 Snapshot·정책 전부 DEMO) → 1회성 행 사전 조회(ALREADY_ISSUED)
 * → 키 생성 → 1회성 행 INSERT(PK 1062 → LOST) → 투영 행 INSERT(PK 1062 → KEY_ID_COLLISION, TX 전체 rollback). 커밋 뒤에만 원문을 돌려준다.
 * 쓰기 Port 두 개의 **유일한 사용자**다(I-22). 명령 프로세스에만 빈이 있다(I-23).
 *
 * 값: environment TEST·status ACTIVE·revision 1(adapter 상수), valid_from = 발급 시각(ms), valid_until = lease_until = valid_from + [VALIDITY](P7D — D-DEMO-004).
 *
 * **빈 등록**: stereotype annotation이 없다 — 명령 프로세스(`paycore.payment.command=demo-credential`)의 `OneShotModeGuard`(adapter.batch)만 이 서비스와
 * [SecureRandomKeySource]를 등록한다(I-23: 웹 컨텍스트에는 빈 정의 자체가 없다). application은 Spring Boot 조건 annotation에 의존하지 않는다(ArchUnit allowlist).
 */
class DemoCredentialIssueService(
    private val profiles: MidPolicyProfilePort,
    private val issuances: DemoCredentialIssuanceStore,
    private val projections: DemoCredentialProjectionWriter,
    private val keys: KeySource,
    private val clock: PaymentClock,
    transactionManager: PlatformTransactionManager,
) : DemoCredentialIssueUseCase {

    private val transactions = LocalTransactions(transactionManager, maxAttempts = 1)

    override fun issue(command: DemoCredentialIssueCommand): DemoCredentialIssueResult = try {
        transactions.execute(isRetryable = { false }) { issueLocally(command) }
    } catch (_: IssuanceRaceDeadlock) {
        // 동시 실행이 같은 고정 발급 행(id 1)을 INSERT하다 InnoDB가 이 TX를 교착 희생자로 rollback했다 — 이 실행은 아무것도 쓰지 않았고 다른 실행이 발급 중이다.
        // 1062 패자와 같은 경합 패배(LOST, exit 5)로 분류한다. 상대가 끝났는지는 재실행(ALREADY_ISSUED)으로 확인한다. 재시도하지 않는다(1회성).
        DemoCredentialIssueResult.Lost
    } catch (e: PersistenceFailure.UniqueConflict) {
        when (e.constraint) {
            StoreConstraint.DEMO_CREDENTIAL_ISSUANCE -> DemoCredentialIssueResult.Lost
            StoreConstraint.MERCHANT_API_CREDENTIAL_KEY_ID -> DemoCredentialIssueResult.KeyIdCollision
            else -> throw e
        }
    }

    private fun issueLocally(command: DemoCredentialIssueCommand): DemoCredentialIssueResult {
        val mid = MerchantAccountId(command.merchantAccountId)
        val profile = profiles.find(mid) ?: return refused(DemoCredentialRefusal.MID_UNKNOWN)
        if (!profile.allDemo) return refused(DemoCredentialRefusal.NOT_DEMO_MERCHANT)
        if (issuances.find() != null) return refused(DemoCredentialRefusal.ALREADY_ISSUED)
        val key = keys.next()
        require(KEY_ID.matches(key.keyId) && SECRET.matches(key.secret)) { "demo key material format" }
        val apiKey = "$KEY_PREFIX${key.keyId}.${key.secret}"
        val issuedAt = clock.now().truncatedTo(ChronoUnit.MILLIS)
        val until = issuedAt.plus(VALIDITY)
        try {
            issuances.insertOnce(DemoCredentialIssuance(key.keyId, mid, issuedAt, ACTOR, command.correlationId))
        } catch (e: PersistenceFailure.Deadlock) {
            throw IssuanceRaceDeadlock(e)
        }
        projections.insert(DemoCredentialProjection(key.keyId, mid, sha256Hex(apiKey), issuedAt, until, until))
        return DemoCredentialIssueResult.Issued(mid.value, key.keyId, apiKey, until)
    }

    private fun refused(reason: DemoCredentialRefusal) = DemoCredentialIssueResult.Refused(reason)

    private fun sha256Hex(value: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)))

    /** 1회성 행 INSERT 중 교착 희생(DB가 TX를 rollback) — [issue]가 LOST로 옮긴다. 다른 단계의 교착은 그대로 전파된다(분류 밖 → exit 1). */
    private class IssuanceRaceDeadlock(cause: Throwable) : RuntimeException("demo credential issuance insert was a deadlock victim", cause)

    /** 키 재료(keyId·secret). 운영 구현은 [SecureRandomKeySource] — 시험은 고정 생성기로 바꿔 keyId 충돌(I-25)을 만든다. */
    fun interface KeySource {
        fun next(): KeyMaterial
    }

    /** [secret]은 toString에 넣지 않는다. */
    class KeyMaterial(val keyId: String, val secret: String) {
        override fun toString(): String = "KeyMaterial(keyId=$keyId)"
    }

    /** keyId = `demo` + 영숫자 16자(편향 없는 `nextInt`), secret = 256bit `SecureRandom` base64url 무패딩 43자(D-CPA-001 형식). */
    class SecureRandomKeySource : KeySource {
        private val random = SecureRandom()

        override fun next(): KeyMaterial {
            val id = (1..KEY_ID_RANDOM_LENGTH).map { ALPHANUMERIC[random.nextInt(ALPHANUMERIC.length)] }.joinToString("")
            val secret = ByteArray(SECRET_BYTES).also(random::nextBytes)
            return KeyMaterial(KEY_ID_PREFIX + id, Base64.getUrlEncoder().withoutPadding().encodeToString(secret))
        }
    }

    companion object {
        const val COMMAND = "demo-credential"
        const val ACTOR = "system:demo-credential"
        const val KEY_PREFIX = "pc_test_sk_"
        const val KEY_ID_PREFIX = "demo"
        private const val KEY_ID_RANDOM_LENGTH = 16
        private const val SECRET_BYTES = 32
        private const val ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

        /** D-DEMO-004: Demo 키 유효 7일(갱신·재발급 없음) — Demo 값, 운영값 아님. */
        val VALIDITY: Duration = Duration.ofDays(7)

        private val KEY_ID = Regex("^demo[A-Za-z0-9]{4,28}$")
        private val SECRET = Regex("^[A-Za-z0-9_-]{43}$")
    }
}

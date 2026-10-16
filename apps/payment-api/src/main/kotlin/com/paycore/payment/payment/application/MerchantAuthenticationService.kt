package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.provided.MerchantAuthenticator
import com.paycore.payment.payment.application.provided.AuthenticationFailureReason
import com.paycore.payment.payment.application.provided.CredentialKeyId
import com.paycore.payment.payment.application.provided.MerchantAuthentication
import com.paycore.payment.payment.application.provided.MerchantAuthenticationResult
import com.paycore.payment.payment.application.provided.MerchantAuthenticationUnavailable
import com.paycore.payment.payment.application.required.CredentialStatus
import com.paycore.payment.payment.application.required.MerchantCredentialStore
import com.paycore.payment.payment.application.required.PaymentClock
import com.paycore.payment.payment.application.required.PersistenceFailure
import com.paycore.payment.payment.application.required.StoredMerchantCredential
import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.application.provided.FailureKind
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Bearer API 키 검증(D-CPA-001, CP-API-001 명세 §5).
 *
 * 키 원문 형식: `pc_<test|live>_sk_<keyId>.<secret>` — keyId 8..32 영숫자, secret = 256bit base64url(무패딩) 43자. 접두의 환경은 저장된 환경과
 * 같아야 한다(잘못된 환경 키를 쓰면 거절). 검증값 = 키 원문 전체(UTF-8 — 형식상 ASCII)의 SHA-256 lowercase hex를 상수 시간 비교한다
 * (256bit 난수라 느린 hash가 필요 없다 — D-CPA-001). 상태 ACTIVE, 유효 기간 안, 투영 lease 안이어야 한다(lease가 지나면 최신 폐기 여부를
 * 확인할 수 없으므로 거절 — fail-closed).
 *
 * 읽기는 짧은 읽기 전용 로컬 TX 하나(잠금 없음, 생성 TX의 잠금 순서 밖). 저장소·연결 실패는 거절이 아니라 [MerchantAuthenticationUnavailable](503). 캐시 없음: 폐기 투영이 반영되면 다음 요청부터 거절된다.
 * 결과의 실패 원인은 응답에서 구분하지 않는다(모두 401). 원문 키·검증값을 로그에 남기지 않는다.
 */
@Service
class MerchantAuthenticationService(
    private val credentials: MerchantCredentialStore,
    private val clock: PaymentClock,
    transactionManager: PlatformTransactionManager,
) : MerchantAuthenticator {

    /**
     * 읽기 전용 로컬 TX(CP-DB-001 관례 [LocalTransactions] — review round2 m-1). 선언적 `@Transactional`은 연결이 끊겨 rollback·commit이
     * 실패하면 `TransactionSystemException`으로 원래 분류([MerchantAuthenticationUnavailable])를 덮어 500이 됐다. 여기서는 원 예외를 보존하고,
     * TX 시작·commit 단계 실패도 검증 불가(503)로 옮긴다. 재시도 없음(1회).
     */
    private val transactions = LocalTransactions(transactionManager, maxAttempts = 1, readOnly = true)

    override fun authenticate(presentedKey: String?): MerchantAuthenticationResult = try {
        transactions.execute(isRetryable = { false }) { verify(presentedKey) }
    } catch (e: MerchantAuthenticationUnavailable) {
        throw e
    } catch (e: CommitOutcomeUnknownException) {
        // 읽기 TX의 commit 실패(연결 끊김 등). 읽은 값을 신뢰하지 않고 검증 불가로 본다.
        throw MerchantAuthenticationUnavailable(FailureKind.DB_UNAVAILABLE, null, e)
    } catch (e: TransactionException) {
        // TX 시작 실패(연결 없음)·rollback 확정 등 — 검증 불가.
        throw MerchantAuthenticationUnavailable(FailureKind.TRANSACTION_UNAVAILABLE, null, e)
    }

    private fun verify(presentedKey: String?): MerchantAuthenticationResult {
        if (presentedKey == null) return rejected(AuthenticationFailureReason.MISSING)
        val match = KEY_FORMAT.matchEntire(presentedKey) ?: return rejected(AuthenticationFailureReason.MALFORMED)
        val environment = if (match.groupValues[1] == "test") Environment.TEST else Environment.LIVE
        val keyId = CredentialKeyId(match.groupValues[2])
        val presentedHash = sha256Hex(presentedKey)
        val stored = findOrUnavailable(keyId) ?: return rejected(AuthenticationFailureReason.UNKNOWN_KEY)
        if (!MessageDigest.isEqual(presentedHash.toByteArray(StandardCharsets.US_ASCII), stored.secretSha256Hex.toByteArray(StandardCharsets.US_ASCII))) {
            return rejected(AuthenticationFailureReason.SECRET_MISMATCH)
        }
        if (stored.environment != environment) return rejected(AuthenticationFailureReason.ENVIRONMENT_MISMATCH)
        if (stored.status != CredentialStatus.ACTIVE) return rejected(AuthenticationFailureReason.REVOKED)
        val now = clock.now()
        if (now.isBefore(stored.validFrom) || stored.validUntil?.let { !now.isBefore(it) } == true) {
            return rejected(AuthenticationFailureReason.NOT_VALID_NOW)
        }
        if (!now.isBefore(stored.leaseUntil)) return rejected(AuthenticationFailureReason.LEASE_EXPIRED)
        return MerchantAuthenticationResult.Authenticated(MerchantAuthentication(stored.keyId, stored.merchantAccountId, stored.environment))
    }

    /** 저장소 일시 실패는 거절이 아니라 검증 불가다 — provided 타입으로 옮긴다(웹은 application.required를 보지 않는다). */
    private fun findOrUnavailable(keyId: CredentialKeyId): StoredMerchantCredential? = try {
        credentials.find(keyId)
    } catch (e: PersistenceFailure) {
        throw MerchantAuthenticationUnavailable(e.failureKind, e.diagnostic, e)
    }

    private fun rejected(reason: AuthenticationFailureReason) =MerchantAuthenticationResult.Rejected(reason)

    private fun sha256Hex(key: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.toByteArray(StandardCharsets.UTF_8)))

    companion object {
        /** D-CPA-001 키 형식. keyId에 `_`·`.`이 없어 해석이 모호하지 않다. */
        val KEY_FORMAT = Regex("^pc_(test|live)_sk_([A-Za-z0-9]{8,32})\\.([A-Za-z0-9_-]{43})$")
    }
}

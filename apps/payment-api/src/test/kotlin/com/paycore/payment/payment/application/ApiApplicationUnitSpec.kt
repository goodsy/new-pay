package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.provided.AuthenticationFailureReason
import com.paycore.payment.payment.application.provided.CreatePaymentCommand
import com.paycore.payment.payment.application.provided.CreatePaymentFailure
import com.paycore.payment.payment.application.provided.CreatePaymentRequest
import com.paycore.payment.payment.application.provided.CreatePaymentResult
import com.paycore.payment.payment.application.provided.CreatePaymentUseCase
import com.paycore.payment.payment.application.provided.CredentialKeyId
import com.paycore.payment.payment.application.provided.FailureKind
import com.paycore.payment.payment.application.provided.GeneratedIdentifier
import com.paycore.payment.payment.application.provided.MerchantAuthentication
import com.paycore.payment.payment.application.provided.MerchantAuthenticationResult
import com.paycore.payment.payment.application.provided.MerchantAuthenticationUnavailable
import com.paycore.payment.payment.application.provided.StoreDiagnostic
import com.paycore.payment.payment.application.required.PersistenceFailure
import com.paycore.payment.payment.application.required.CredentialStatus
import com.paycore.payment.payment.application.required.MerchantCredentialStore
import com.paycore.payment.payment.application.required.PaymentClock
import com.paycore.payment.payment.application.required.StoredMerchantCredential
import com.paycore.payment.payment.domain.CurrencyCode
import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.IdempotencyKey
import com.paycore.payment.payment.domain.MerchantAccountId
import com.paycore.payment.payment.domain.MerchantOrderId
import com.paycore.payment.payment.domain.MerchantSiteId
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.PaymentMethod
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.support.SimpleTransactionStatus
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat

/**
 * CP-API-001 application 단위 규칙(DB 없음): 식별자 생성기 형식·유일성, Bearer API 키 검증(D-CPA-001)의 모든 거절 원인, facade의 식별자 충돌
 * 1회 재시도와 인증 주체 범위. 실제 DB·HTTP 경로는 API 통합 시험(CreatePaymentApiSpec 등)이 본다.
 */
class ApiApplicationUnitSpec : BehaviorSpec({

    val t0 = Instant.parse("2026-10-14T01:02:03.456Z")
    val fixedClock = object : PaymentClock {
        override fun now(): Instant = t0
    }

    Given("the server identifier generator (CP-API-001 §3.4)") {
        val generator = SecureRandomPaymentIdentifierGenerator(fixedClock)
        Then("formats: pay_ + ULID(26, Crockford), pk_ + 256-bit base64url, UUID v4") {
            val ids = generator.newIdentifiers()
            ids.paymentId.value shouldMatch Regex("^pay_[0-7][0-9A-HJKMNP-TV-Z]{25}$")
            ids.paymentKey.value shouldMatch Regex("^pk_[A-Za-z0-9_-]{43}$")
            ids.idempotencyRecordId.value shouldMatch Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
            ids.eventId.value shouldMatch Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
        }
        Then("the ULID time part encodes the server clock in milliseconds") {
            generator.newIdentifiers().paymentId.value.substring(4, 14) shouldBe crockfordTime(t0.toEpochMilli())
        }
        Then("100,000 identifiers generated at the same millisecond are all distinct") {
            val many = (1..100_000).map { generator.newIdentifiers() }
            many.map { it.paymentId }.toSet() shouldHaveSize 100_000
            many.map { it.paymentKey }.toSet() shouldHaveSize 100_000
        }
    }

    Given("Bearer API key verification (D-CPA-001)") {
        val mid = MerchantAccountId("mid_01HZY3K8Q4M2N6P8R0T2V4X6Z8")
        val key = "pc_test_sk_demoKey0001." + "A".repeat(43)
        fun stored(
            environment: Environment = Environment.TEST,
            status: CredentialStatus = CredentialStatus.ACTIVE,
            validFrom: Instant = t0.minusSeconds(60),
            validUntil: Instant? = null,
            leaseUntil: Instant = t0.plusSeconds(60),
            hash: String = sha256(key),
        ) = StoredMerchantCredential(CredentialKeyId("demoKey0001"), mid, environment, hash, status, validFrom, validUntil, leaseUntil)
        fun service(row: StoredMerchantCredential?) = MerchantAuthenticationService(
            object : MerchantCredentialStore {
                override fun find(keyId: CredentialKeyId) = row?.takeIf { it.keyId == keyId }
            },
            fixedClock, RecordingTransactionManager(),
        )

        Then("a valid active key yields the stored MID and environment (never the request body)") {
            service(stored()).authenticate(key) shouldBe
                MerchantAuthenticationResult.Authenticated(MerchantAuthentication(CredentialKeyId("demoKey0001"), mid, Environment.TEST))
        }
        Then("each failure reason is classified (the response does not distinguish them)") {
            mapOf(
                service(stored()).authenticate(null) to AuthenticationFailureReason.MISSING,
                service(stored()).authenticate("sk_test_demoKey0001.x") to AuthenticationFailureReason.MALFORMED,
                service(stored()).authenticate(key + "A") to AuthenticationFailureReason.MALFORMED,
                service(null).authenticate(key) to AuthenticationFailureReason.UNKNOWN_KEY,
                service(stored(hash = sha256(key.replace('A', 'B')))).authenticate(key) to AuthenticationFailureReason.SECRET_MISMATCH,
                service(stored(environment = Environment.LIVE)).authenticate(key) to AuthenticationFailureReason.ENVIRONMENT_MISMATCH,
                service(stored(status = CredentialStatus.REVOKED)).authenticate(key) to AuthenticationFailureReason.REVOKED,
                service(stored(validFrom = t0.plusMillis(1))).authenticate(key) to AuthenticationFailureReason.NOT_VALID_NOW,
                service(stored(validUntil = t0)).authenticate(key) to AuthenticationFailureReason.NOT_VALID_NOW,
                service(stored(leaseUntil = t0)).authenticate(key) to AuthenticationFailureReason.LEASE_EXPIRED,
            ).forEach { (result, reason) -> result shouldBe MerchantAuthenticationResult.Rejected(reason) }
        }
        Then("a store failure is not a rejection: it becomes MerchantAuthenticationUnavailable (provided type, kind and diagnostic kept) for a 503") {
            listOf(
                PersistenceFailure.LockTimeout(RuntimeException("x"), StoreDiagnostic(1205, "40001")) to FailureKind.LOCK_TIMEOUT,
                PersistenceFailure.Deadlock(RuntimeException("x")) to FailureKind.DEADLOCK,
                PersistenceFailure.Unavailable(RuntimeException("x")) to FailureKind.DB_UNAVAILABLE,
            ).forEach { (failure, kind) ->
                val failing = MerchantAuthenticationService(
                    object : MerchantCredentialStore {
                        override fun find(keyId: CredentialKeyId): StoredMerchantCredential? = throw failure
                    },
                    fixedClock, RecordingTransactionManager(),
                )
                val e = shouldThrow<MerchantAuthenticationUnavailable> { failing.authenticate(key) }
                e.kind shouldBe kind
                e.diagnostic shouldBe failure.diagnostic
            }
        }
        Then("transaction failures are not rejections either (review round2 m-1): begin and commit failures become MerchantAuthenticationUnavailable, a failed rollback never hides the store failure") {
            val store = object : MerchantCredentialStore {
                override fun find(keyId: CredentialKeyId) = stored().takeIf { it.keyId == keyId }
            }
            val noConnection = RecordingTransactionManager(beginFailure = CannotCreateTransactionException("no connection"))
            shouldThrow<MerchantAuthenticationUnavailable> { MerchantAuthenticationService(store, fixedClock, noConnection).authenticate(key) }
                .kind shouldBe FailureKind.TRANSACTION_UNAVAILABLE
            val commitFails = RecordingTransactionManager(commitFailure = TransactionSystemException("commit failed"))
            shouldThrow<MerchantAuthenticationUnavailable> { MerchantAuthenticationService(store, fixedClock, commitFails).authenticate(key) }
                .kind shouldBe FailureKind.DB_UNAVAILABLE

            val rollbackFails = RecordingTransactionManager(rollbackFailure = TransactionSystemException("rollback failed"))
            val failingStore = object : MerchantCredentialStore {
                override fun find(keyId: CredentialKeyId): StoredMerchantCredential? = throw PersistenceFailure.Unavailable(RuntimeException("x"))
            }
            val e = shouldThrow<MerchantAuthenticationUnavailable> { MerchantAuthenticationService(failingStore, fixedClock, rollbackFails).authenticate(key) }
            e.kind shouldBe FailureKind.DB_UNAVAILABLE
            e.suppressed.single().shouldBeInstanceOf<TransactionSystemException>()
            rollbackFails.readOnlySeen shouldBe listOf(true)
        }
        Then("validity and lease are half-open: one millisecond before the end is still accepted") {
            service(stored(validUntil = t0.plusMillis(1), leaseUntil = t0.plusMillis(1))).authenticate(key)
                .shouldBeInstanceOf<MerchantAuthenticationResult.Authenticated>()
        }
    }

    Given("the CreatePayment facade (CP-API-001 §3.1·§3.5)") {
        val auth = MerchantAuthentication(CredentialKeyId("demoKey0001"), MerchantAccountId("mid-a"), Environment.LIVE)
        fun request(bodyMid: String = "mid-a") = CreatePaymentRequest(
            authentication = auth, idempotencyKey = IdempotencyKey("key-1"), merchantAccountId = MerchantAccountId(bodyMid),
            merchantSiteId = MerchantSiteId("site-1"), merchantOrderId = MerchantOrderId("order-1"), money = Money(1000, CurrencyCode("KRW")),
            paymentMethod = PaymentMethod.CARD, orderName = null, correlationId = "req-1",
        )
        val generator = SecureRandomPaymentIdentifierGenerator(fixedClock)

        Then("the order key comes from the authenticated environment and MID, the actor is the credential, the time is the server clock") {
            val seen = mutableListOf<CreatePaymentCommand>()
            val facade = SubmitCreatePaymentService(
                object : CreatePaymentUseCase {
                    override fun create(command: CreatePaymentCommand): CreatePaymentResult = CreatePaymentResult.IdempotencyKeyReused.also { seen += command }
                },
                fixedClock, generator,
            )
            facade.submit(request())
            seen.single().orderKey.environment shouldBe Environment.LIVE
            seen.single().orderKey.merchantAccountId shouldBe MerchantAccountId("mid-a")
            seen.single().actor shouldBe CredentialKeyId("demoKey0001")
            seen.single().requestedAt shouldBe t0
        }
        Then("a body MID different from the authenticated MID is a defect here (the web adapter answers 403 first)") {
            val facade = SubmitCreatePaymentService(
                object : CreatePaymentUseCase {
                    override fun create(command: CreatePaymentCommand): CreatePaymentResult = error("must not be called")
                },
                fixedClock, generator,
            )
            shouldThrow<IllegalArgumentException> { facade.submit(request(bodyMid = "mid-b")) }
        }
        Then("an identifier collision is retried once with new identifiers; a second collision becomes TemporarilyUnavailable") {
            val attempts = mutableListOf<CreatePaymentCommand>()
            val collideOnce = SubmitCreatePaymentService(
                object : CreatePaymentUseCase {
                    override fun create(command: CreatePaymentCommand): CreatePaymentResult {
                        attempts += command
                        if (attempts.size == 1) throw CreatePaymentFailure.IdentifierCollision(GeneratedIdentifier.PAYMENT_KEY)
                        return CreatePaymentResult.IdempotencyKeyReused
                    }
                },
                fixedClock, generator,
            )
            collideOnce.submit(request()) shouldBe CreatePaymentResult.IdempotencyKeyReused
            attempts shouldHaveSize 2
            (attempts[0].newIdentifiers != attempts[1].newIdentifiers) shouldBe true
            attempts[1].idempotencyKey shouldBe attempts[0].idempotencyKey
            attempts[1].requestedAt shouldBe attempts[0].requestedAt

            val alwaysCollide = SubmitCreatePaymentService(
                object : CreatePaymentUseCase {
                    override fun create(command: CreatePaymentCommand): CreatePaymentResult =
                        throw CreatePaymentFailure.IdentifierCollision(GeneratedIdentifier.PAYMENT_ID)
                },
                fixedClock, generator,
            )
            shouldThrow<CreatePaymentFailure.TemporarilyUnavailable> { alwaysCollide.submit(request()) }.kind shouldBe FailureKind.IDENTIFIER_COLLISION
        }
    }
}) {
    companion object {
        fun sha256(s: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)))

        /** ULID 시각 10자를 시험이 독립적으로 계산한다(생성기 코드 복제가 아니라 정의: 48bit ms, 상위부터 5bit씩). */
        fun crockfordTime(millis: Long): String {
            val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
            return (9 downTo 0).joinToString("") { i -> alphabet[((millis shr (5 * i)) and 31).toInt()].toString() }
        }
    }
}

/** 시험용 TX 관리자(실제 자원 없음): 시작·commit·rollback 실패를 주입하고 정의의 read-only 여부를 기록한다. */
private class RecordingTransactionManager(
    private val beginFailure: RuntimeException? = null,
    private val commitFailure: RuntimeException? = null,
    private val rollbackFailure: RuntimeException? = null,
) : PlatformTransactionManager {
    val readOnlySeen = mutableListOf<Boolean>()

    override fun getTransaction(definition: TransactionDefinition?): TransactionStatus {
        beginFailure?.let { throw it }
        readOnlySeen += definition?.isReadOnly == true
        return SimpleTransactionStatus()
    }

    override fun commit(status: TransactionStatus) {
        commitFailure?.let { throw it }
    }

    override fun rollback(status: TransactionStatus) {
        rollbackFailure?.let { throw it }
    }
}

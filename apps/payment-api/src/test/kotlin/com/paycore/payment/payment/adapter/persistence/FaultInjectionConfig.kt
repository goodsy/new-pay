package com.paycore.payment.payment.adapter.persistence

import com.paycore.payment.payment.adapter.query.JooqAuthorizationViewReader
import com.paycore.payment.payment.application.required.CancellationRecoverySchedule
import com.paycore.payment.payment.application.required.CancellationRecoveryScheduleStore
import com.paycore.payment.payment.application.required.OperatorCancelRejection
import com.paycore.payment.payment.application.required.OperatorCancelRejectionStore
import com.paycore.payment.payment.application.required.PaymentCancellationRepository
import com.paycore.payment.payment.application.required.ReturnBalanceRepository
import com.paycore.payment.payment.domain.CancelAttemptId
import com.paycore.payment.payment.domain.CancellationId
import com.paycore.payment.payment.domain.MerchantCancelId
import com.paycore.payment.payment.domain.OperatorApprovalId
import com.paycore.payment.payment.domain.PaymentCancellation
import com.paycore.payment.payment.domain.ReturnBalance
import com.paycore.payment.payment.application.provided.CredentialKeyId
import com.paycore.payment.payment.application.required.AuthenticationSessionAttributes
import com.paycore.payment.payment.application.required.AuthenticationSessionEventRecord
import com.paycore.payment.payment.application.required.AuthenticationSessionEventStore
import com.paycore.payment.payment.application.required.AuthenticationSessionRepository
import com.paycore.payment.payment.application.required.AuthorizationAttemptViewRow
import com.paycore.payment.payment.application.required.AuthorizationViewReader
import com.paycore.payment.payment.application.required.AuthorizationViewRow
import com.paycore.payment.payment.application.required.CheckoutSessionRecord
import com.paycore.payment.payment.application.required.CheckoutSessionStore
import com.paycore.payment.payment.application.required.CheckoutTokenRecord
import com.paycore.payment.payment.application.required.CheckoutTokenStore
import com.paycore.payment.payment.application.required.ControlAcquisition
import com.paycore.payment.payment.application.required.DailyLimitUsageRepository
import com.paycore.payment.payment.application.required.EventAppendResult
import com.paycore.payment.payment.application.required.IdempotencyRecordStore
import com.paycore.payment.payment.application.required.IdempotencyReservation
import com.paycore.payment.payment.application.required.IdempotencyReservationOutcome
import com.paycore.payment.payment.application.required.IdempotencyScope
import com.paycore.payment.payment.application.required.MerchantCredentialStore
import com.paycore.payment.payment.application.required.OrderPaymentControlRepository
import com.paycore.payment.payment.application.required.OutboxEvent
import com.paycore.payment.payment.application.required.OutboxStore
import com.paycore.payment.payment.application.required.PaymentAuthorizationRepository
import com.paycore.payment.payment.application.required.PaymentCreationAttributes
import com.paycore.payment.payment.application.required.PaymentCreationRecord
import com.paycore.payment.payment.application.required.PaymentRepository
import com.paycore.payment.payment.application.required.StoredMerchantCredential
import com.paycore.payment.payment.application.required.StoredRequestResult
import com.paycore.payment.payment.application.required.Versioned
import com.paycore.payment.payment.domain.AttemptId
import com.paycore.payment.payment.domain.AuthenticationSession
import com.paycore.payment.payment.domain.AuthenticationSessionId
import com.paycore.payment.payment.domain.AuthorizationId
import com.paycore.payment.payment.domain.CheckoutHandle
import com.paycore.payment.payment.domain.CheckoutSessionId
import com.paycore.payment.payment.domain.CheckoutTokenId
import com.paycore.payment.payment.domain.DailyLimitUsage
import com.paycore.payment.payment.domain.LimitUsageKey
import com.paycore.payment.payment.domain.OrderKey
import com.paycore.payment.payment.domain.OrderPaymentControl
import com.paycore.payment.payment.domain.Payment
import com.paycore.payment.payment.domain.PaymentAuthorization
import com.paycore.payment.payment.domain.PaymentId
import com.paycore.payment.payment.domain.SecretDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

/** 장애 주입 지점(Port 호출 직후). */
enum class FaultPoint {
    IDEMPOTENCY_RESERVE, IDEMPOTENCY_RENEW, IDEMPOTENCY_COMPLETE,
    CONTROL_OPEN, CONTROL_LOCK, CONTROL_UPDATE,
    PAYMENT_INSERT, PAYMENT_LOCK, PAYMENT_UPDATE, PAYMENT_FIND,
    OUTBOX_APPEND,

    /** 인증 Credential 투영 읽기 직전(같은 읽기 TX 안 — 연결 끊김 주입용)과 직후. */
    CREDENTIAL_BEFORE_FIND, CREDENTIAL_FIND,

    /** AUTHN-001 결제창·인증 세션 Store(쓰기·잠금 직후 — rollback·경합 시험). */
    TOKEN_INSERT, TOKEN_LOCK, TOKEN_UPDATE,
    CHECKOUT_SESSION_INSERT, CHECKOUT_SESSION_LOCK, CHECKOUT_SESSION_UPDATE,
    AUTH_SESSION_INSERT, AUTH_SESSION_LOCK, AUTH_SESSION_UPDATE, AUTH_EVENT_APPEND,

    /** AUTH-001 승인·일 한도 Port(쓰기·잠금 직후 — TX1·TX2 rollback·경합 시험). */
    AUTHORIZATION_INSERT, AUTHORIZATION_LOCK, AUTHORIZATION_UPDATE, LIMIT_USAGE_LOCK, LIMIT_USAGE_UPDATE,

    /** AUTH-001 회복: 선점 커밋 뒤 Attempt 조회 **직전**(hook 인자 = AttemptId — 선점과 TXd 경합 재현, 검토 #3 m-1). */
    RECOVERY_BEFORE_FIND_ATTEMPT,

    /** CANCEL-001 취소 Port(쓰기·잠금 직후 — TX1·TXd·TX2 rollback·경합 시험). */
    CANCELLATION_INSERT, CANCELLATION_LOCK, CANCELLATION_UPDATE, RETURN_BALANCE_INSERT, RETURN_BALANCE_LOCK, RETURN_BALANCE_UPDATE,
    CANCEL_SCHEDULE_INSERT, CANCEL_SCHEDULE_UPDATE, OPERATOR_REJECTION_APPEND,

    /** CANCEL-001 S7: 취소 비권위 조회 직후(회복 판정과 잠금 사이의 경합 재현 — hook 인자 = 조회 행). */
    CANCEL_VIEW_FIND,
}

/**
 * 시험 전용 장애 주입기. 실제 JPA Port가 **성공한 직후**(같은 TX 안, 아직 commit 전)에 hook을 실행한다. hook은 예외를 던져 그 지점의
 * 실패를 만들거나(rollback 시험), 다른 연결이 잠금 대기에 들어갈 때까지 TX를 붙잡는다(경합 관찰). 운영 코드에는 없다.
 */
class FaultInjector {
    private val hooks = ConcurrentHashMap<FaultPoint, (Any?) -> Unit>()

    fun after(point: FaultPoint, hook: (result: Any?) -> Unit) {
        hooks[point] = hook
    }

    fun reset() = hooks.clear()

    fun <T> around(point: FaultPoint, block: () -> T): T {
        val result = block()
        hooks[point]?.invoke(result)
        return result
    }
}

/** 실제 JPA Store를 감싸 [FaultInjector] hook을 거치게 하는 Port 대역(@Primary — CreatePaymentService가 이것을 주입받는다). */
@TestConfiguration(proxyBeanMethods = false)
class FaultInjectionConfig {

    @Bean
    fun faultInjector(): FaultInjector = FaultInjector()

    /** CP-QUERY-001 검토 #1 m-3: 결정적 스냅샷 시험용 jOOQ listener(무장 전에는 무동작). Boot jOOQ 자동 구성이 ExecuteListenerProvider bean을 모은다. */
    @Bean
    fun snapshotProbe(dataSource: javax.sql.DataSource): SnapshotProbe = SnapshotProbe(dataSource)

    @Bean
    fun snapshotProbeListenerProvider(probe: SnapshotProbe): org.jooq.ExecuteListenerProvider = org.jooq.impl.DefaultExecuteListenerProvider(probe)

    @Bean
    @Primary
    fun faultInjectingAuthorizationViewReader(real: JooqAuthorizationViewReader, f: FaultInjector): AuthorizationViewReader =
        object : AuthorizationViewReader {
            override fun find(authorizationId: AuthorizationId): AuthorizationViewRow? = real.find(authorizationId)

            override fun findAttempt(attemptId: AttemptId): AuthorizationAttemptViewRow? {
                f.around(FaultPoint.RECOVERY_BEFORE_FIND_ATTEMPT) { attemptId }
                return real.findAttempt(attemptId)
            }
        }

    @Bean
    @Primary
    fun faultInjectingIdempotencyRecordStore(real: JpaIdempotencyRecordStore, f: FaultInjector): IdempotencyRecordStore =
        object : IdempotencyRecordStore {
            override fun reserveOrLock(reservation: IdempotencyReservation): IdempotencyReservationOutcome =
                f.around(FaultPoint.IDEMPOTENCY_RESERVE) { real.reserveOrLock(reservation) }

            override fun renewExpired(reservation: IdempotencyReservation) =
                f.around(FaultPoint.IDEMPOTENCY_RENEW) { real.renewExpired(reservation) }

            override fun complete(scope: IdempotencyScope, result: StoredRequestResult, completedAt: Instant) =
                f.around(FaultPoint.IDEMPOTENCY_COMPLETE) { real.complete(scope, result, completedAt) }
        }

    @Bean
    @Primary
    fun faultInjectingOrderPaymentControlStore(real: JpaOrderPaymentControlRepository, f: FaultInjector): OrderPaymentControlRepository =
        object : OrderPaymentControlRepository {
            override fun openOrLock(initial: OrderPaymentControl, at: Instant): ControlAcquisition =
                f.around(FaultPoint.CONTROL_OPEN) { real.openOrLock(initial, at) }

            override fun lock(orderKey: OrderKey): Versioned<OrderPaymentControl>? = f.around(FaultPoint.CONTROL_LOCK) { real.lock(orderKey) }

            override fun update(control: OrderPaymentControl, expectedVersion: Long, at: Instant): Long =
                f.around(FaultPoint.CONTROL_UPDATE) { real.update(control, expectedVersion, at) }
        }

    @Bean
    @Primary
    fun faultInjectingPaymentStore(real: JpaPaymentRepository, f: FaultInjector): PaymentRepository =
        object : PaymentRepository {
            override fun insertNew(payment: Payment, attributes: PaymentCreationAttributes): Long =
                f.around(FaultPoint.PAYMENT_INSERT) { real.insertNew(payment, attributes) }

            override fun lock(paymentId: PaymentId): Versioned<Payment>? = f.around(FaultPoint.PAYMENT_LOCK) { real.lock(paymentId) }

            override fun update(payment: Payment, expectedVersion: Long): Long =
                f.around(FaultPoint.PAYMENT_UPDATE) { real.update(payment, expectedVersion) }

            override fun findCreationRecord(paymentId: PaymentId): PaymentCreationRecord? =
                f.around(FaultPoint.PAYMENT_FIND) { real.findCreationRecord(paymentId) }
        }

    @Bean
    @Primary
    fun faultInjectingOutboxStore(real: JpaOutboxStore, f: FaultInjector): OutboxStore =
        object : OutboxStore {
            override fun append(event: OutboxEvent) = f.around(FaultPoint.OUTBOX_APPEND) { real.append(event) }
        }

    @Bean
    @Primary
    fun faultInjectingCheckoutTokenStore(real: JpaCheckoutTokenStore, f: FaultInjector): CheckoutTokenStore =
        object : CheckoutTokenStore {
            override fun insert(token: CheckoutTokenRecord): Long = f.around(FaultPoint.TOKEN_INSERT) { real.insert(token) }

            override fun findByDigest(digest: SecretDigest): CheckoutTokenRecord? = real.findByDigest(digest)

            override fun lock(id: CheckoutTokenId): Versioned<CheckoutTokenRecord>? = f.around(FaultPoint.TOKEN_LOCK) { real.lock(id) }

            override fun lockIssuedOfPayment(paymentId: PaymentId): Versioned<CheckoutTokenRecord>? =
                f.around(FaultPoint.TOKEN_LOCK) { real.lockIssuedOfPayment(paymentId) }

            override fun update(token: CheckoutTokenRecord, expectedVersion: Long): Long =
                f.around(FaultPoint.TOKEN_UPDATE) { real.update(token, expectedVersion) }
        }

    @Bean
    @Primary
    fun faultInjectingCheckoutSessionStore(real: JpaCheckoutSessionStore, f: FaultInjector): CheckoutSessionStore =
        object : CheckoutSessionStore {
            override fun insert(session: CheckoutSessionRecord): Long = f.around(FaultPoint.CHECKOUT_SESSION_INSERT) { real.insert(session) }

            override fun findByHandle(handle: CheckoutHandle): CheckoutSessionRecord? = real.findByHandle(handle)

            override fun lock(id: CheckoutSessionId): Versioned<CheckoutSessionRecord>? = f.around(FaultPoint.CHECKOUT_SESSION_LOCK) { real.lock(id) }

            override fun lockActiveOfPayment(paymentId: PaymentId): Versioned<CheckoutSessionRecord>? =
                f.around(FaultPoint.CHECKOUT_SESSION_LOCK) { real.lockActiveOfPayment(paymentId) }

            override fun update(session: CheckoutSessionRecord, expectedVersion: Long): Long =
                f.around(FaultPoint.CHECKOUT_SESSION_UPDATE) { real.update(session, expectedVersion) }
        }

    @Bean
    @Primary
    fun faultInjectingAuthenticationSessionRepository(real: JpaAuthenticationSessionRepository, f: FaultInjector): AuthenticationSessionRepository =
        object : AuthenticationSessionRepository {
            override fun insertNew(session: AuthenticationSession, attributes: AuthenticationSessionAttributes): Long =
                f.around(FaultPoint.AUTH_SESSION_INSERT) { real.insertNew(session, attributes) }

            override fun lock(id: AuthenticationSessionId): Versioned<AuthenticationSession>? = f.around(FaultPoint.AUTH_SESSION_LOCK) { real.lock(id) }

            override fun lockAllOfPayment(paymentId: PaymentId): List<Versioned<AuthenticationSession>> =
                f.around(FaultPoint.AUTH_SESSION_LOCK) { real.lockAllOfPayment(paymentId) }

            override fun findIdByState(checkoutSessionId: CheckoutSessionId, stateDigest: SecretDigest): AuthenticationSessionId? =
                real.findIdByState(checkoutSessionId, stateDigest)

            override fun findIdByAuthenticationRef(authenticationRef: com.paycore.payment.payment.domain.AuthenticationRef): AuthenticationSessionId? =
                real.findIdByAuthenticationRef(authenticationRef)

            override fun update(session: AuthenticationSession, expectedVersion: Long): Long =
                f.around(FaultPoint.AUTH_SESSION_UPDATE) { real.update(session, expectedVersion) }
        }

    @Bean
    @Primary
    fun faultInjectingPaymentAuthorizationRepository(real: JpaPaymentAuthorizationRepository, f: FaultInjector): PaymentAuthorizationRepository =
        object : PaymentAuthorizationRepository {
            override fun insertNew(authorization: PaymentAuthorization): Long = f.around(FaultPoint.AUTHORIZATION_INSERT) { real.insertNew(authorization) }

            override fun lock(id: AuthorizationId): Versioned<PaymentAuthorization>? = f.around(FaultPoint.AUTHORIZATION_LOCK) { real.lock(id) }

            override fun lockByPaymentId(paymentId: PaymentId): Versioned<PaymentAuthorization>? = f.around(FaultPoint.AUTHORIZATION_LOCK) { real.lockByPaymentId(paymentId) }

            override fun update(authorization: PaymentAuthorization, expectedVersion: Long): Long =
                f.around(FaultPoint.AUTHORIZATION_UPDATE) { real.update(authorization, expectedVersion) }
        }

    @Bean
    @Primary
    fun faultInjectingDailyLimitUsageRepository(real: JpaDailyLimitUsageRepository, f: FaultInjector): DailyLimitUsageRepository =
        object : DailyLimitUsageRepository {
            override fun lockOrCreate(key: LimitUsageKey, at: java.time.Instant): Versioned<DailyLimitUsage> = f.around(FaultPoint.LIMIT_USAGE_LOCK) { real.lockOrCreate(key, at) }

            override fun update(usage: DailyLimitUsage, expectedVersion: Long, at: java.time.Instant): Long =
                f.around(FaultPoint.LIMIT_USAGE_UPDATE) { real.update(usage, expectedVersion, at) }
        }

    @Bean
    @Primary
    fun faultInjectingAuthenticationSessionEventStore(real: JpaAuthenticationSessionEventStore, f: FaultInjector): AuthenticationSessionEventStore =
        object : AuthenticationSessionEventStore {
            override fun append(event: AuthenticationSessionEventRecord): EventAppendResult = f.around(FaultPoint.AUTH_EVENT_APPEND) { real.append(event) }

            override fun findBySession(sessionId: AuthenticationSessionId): List<AuthenticationSessionEventRecord> = real.findBySession(sessionId)
        }

    @Bean
    @Primary
    fun faultInjectingCancellationViewReader(real: com.paycore.payment.payment.adapter.query.JooqCancellationViewReader, f: FaultInjector):
        com.paycore.payment.payment.application.required.CancellationViewReader = object : com.paycore.payment.payment.application.required.CancellationViewReader {
            override fun find(cancellationId: CancellationId) = f.around(FaultPoint.CANCEL_VIEW_FIND) { real.find(cancellationId) }

            override fun findTarget(paymentId: PaymentId) = real.findTarget(paymentId)

            override fun findAttempt(attemptId: CancelAttemptId) = real.findAttempt(attemptId)

            override fun backlog() = real.backlog()
        }

    @Bean
    @Primary
    fun faultInjectingPaymentCancellationRepository(real: JpaPaymentCancellationRepository, f: FaultInjector): PaymentCancellationRepository =
        object : PaymentCancellationRepository {
            override fun insertNew(cancellation: PaymentCancellation): Long = f.around(FaultPoint.CANCELLATION_INSERT) { real.insertNew(cancellation) }

            override fun lock(id: CancellationId): Versioned<PaymentCancellation>? = f.around(FaultPoint.CANCELLATION_LOCK) { real.lock(id) }

            override fun find(id: CancellationId): PaymentCancellation? = real.find(id)

            override fun findIdByMerchantKey(paymentId: PaymentId, merchantCancelId: MerchantCancelId): CancellationId? = real.findIdByMerchantKey(paymentId, merchantCancelId)

            override fun findIdByApprovalId(approvalId: OperatorApprovalId): CancellationId? = real.findIdByApprovalId(approvalId)

            override fun update(cancellation: PaymentCancellation, expectedVersion: Long): Long =
                f.around(FaultPoint.CANCELLATION_UPDATE) { real.update(cancellation, expectedVersion) }
        }

    @Bean
    @Primary
    fun faultInjectingReturnBalanceRepository(real: JpaReturnBalanceRepository, f: FaultInjector): ReturnBalanceRepository =
        object : ReturnBalanceRepository {
            override fun insertNew(balance: ReturnBalance, at: java.time.Instant): Long = f.around(FaultPoint.RETURN_BALANCE_INSERT) { real.insertNew(balance, at) }

            override fun lockByPaymentId(paymentId: PaymentId): Versioned<ReturnBalance>? = f.around(FaultPoint.RETURN_BALANCE_LOCK) { real.lockByPaymentId(paymentId) }

            override fun update(balance: ReturnBalance, expectedVersion: Long, at: java.time.Instant): Long =
                f.around(FaultPoint.RETURN_BALANCE_UPDATE) { real.update(balance, expectedVersion, at) }
        }

    @Bean
    @Primary
    fun faultInjectingCancellationRecoveryScheduleStore(real: JpaCancellationRecoveryScheduleStore, f: FaultInjector): CancellationRecoveryScheduleStore =
        object : CancellationRecoveryScheduleStore {
            override fun insert(schedule: CancellationRecoverySchedule, at: java.time.Instant) = f.around(FaultPoint.CANCEL_SCHEDULE_INSERT) { real.insert(schedule, at) }

            override fun lock(attemptId: CancelAttemptId): CancellationRecoverySchedule? = real.lock(attemptId)

            override fun update(schedule: CancellationRecoverySchedule, at: java.time.Instant) = f.around(FaultPoint.CANCEL_SCHEDULE_UPDATE) { real.update(schedule, at) }

            override fun claimDue(now: java.time.Instant, limit: Int): List<CancellationRecoverySchedule> = real.claimDue(now, limit)
        }

    @Bean
    @Primary
    fun faultInjectingOperatorCancelRejectionStore(real: JpaOperatorCancelRejectionStore, f: FaultInjector): OperatorCancelRejectionStore =
        object : OperatorCancelRejectionStore {
            override fun append(rejection: OperatorCancelRejection) = f.around(FaultPoint.OPERATOR_REJECTION_APPEND) { real.append(rejection) }

            override fun findByApproval(approvalId: OperatorApprovalId): List<OperatorCancelRejection> = real.findByApproval(approvalId)
        }

    @Bean
    @Primary
    fun faultInjectingMerchantCredentialStore(real: JpaMerchantCredentialStore, f: FaultInjector): MerchantCredentialStore =
        object : MerchantCredentialStore {
            override fun find(keyId: CredentialKeyId): StoredMerchantCredential? {
                f.around(FaultPoint.CREDENTIAL_BEFORE_FIND) { keyId }
                return f.around(FaultPoint.CREDENTIAL_FIND) { real.find(keyId) }
            }
        }
}

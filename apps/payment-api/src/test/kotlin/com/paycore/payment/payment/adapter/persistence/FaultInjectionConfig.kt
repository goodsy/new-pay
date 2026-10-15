package com.paycore.payment.payment.adapter.persistence

import com.paycore.payment.payment.application.required.ControlAcquisition
import com.paycore.payment.payment.application.provided.CredentialKeyId
import com.paycore.payment.payment.application.required.IdempotencyRecordStore
import com.paycore.payment.payment.application.required.MerchantCredentialStore
import com.paycore.payment.payment.application.required.StoredMerchantCredential
import com.paycore.payment.payment.application.required.IdempotencyReservation
import com.paycore.payment.payment.application.required.IdempotencyReservationOutcome
import com.paycore.payment.payment.application.required.IdempotencyScope
import com.paycore.payment.payment.application.required.OrderPaymentControlStore
import com.paycore.payment.payment.application.required.OutboxEvent
import com.paycore.payment.payment.application.required.OutboxStore
import com.paycore.payment.payment.application.required.PaymentCreationAttributes
import com.paycore.payment.payment.application.required.PaymentCreationRecord
import com.paycore.payment.payment.application.required.PaymentStore
import com.paycore.payment.payment.application.required.StoredRequestResult
import com.paycore.payment.payment.application.required.Versioned
import com.paycore.payment.payment.domain.OrderKey
import com.paycore.payment.payment.domain.OrderPaymentControl
import com.paycore.payment.payment.domain.Payment
import com.paycore.payment.payment.domain.PaymentId
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** 장애 주입 지점(Port 호출 직후). */
enum class FaultPoint {
    IDEMPOTENCY_RESERVE, IDEMPOTENCY_RENEW, IDEMPOTENCY_COMPLETE,
    CONTROL_OPEN, CONTROL_LOCK, CONTROL_UPDATE,
    PAYMENT_INSERT, PAYMENT_LOCK, PAYMENT_UPDATE, PAYMENT_FIND,
    OUTBOX_APPEND,

    /** 인증 Credential 투영 읽기 직전(같은 읽기 TX 안 — 연결 끊김 주입용)과 직후. */
    CREDENTIAL_BEFORE_FIND, CREDENTIAL_FIND,
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
    fun faultInjectingOrderPaymentControlStore(real: JpaOrderPaymentControlStore, f: FaultInjector): OrderPaymentControlStore =
        object : OrderPaymentControlStore {
            override fun openOrLock(initial: OrderPaymentControl, at: Instant): ControlAcquisition =
                f.around(FaultPoint.CONTROL_OPEN) { real.openOrLock(initial, at) }

            override fun lock(orderKey: OrderKey): Versioned<OrderPaymentControl>? = f.around(FaultPoint.CONTROL_LOCK) { real.lock(orderKey) }

            override fun update(control: OrderPaymentControl, expectedVersion: Long, at: Instant): Long =
                f.around(FaultPoint.CONTROL_UPDATE) { real.update(control, expectedVersion, at) }
        }

    @Bean
    @Primary
    fun faultInjectingPaymentStore(real: JpaPaymentStore, f: FaultInjector): PaymentStore =
        object : PaymentStore {
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
    fun faultInjectingMerchantCredentialStore(real: JpaMerchantCredentialStore, f: FaultInjector): MerchantCredentialStore =
        object : MerchantCredentialStore {
            override fun find(keyId: CredentialKeyId): StoredMerchantCredential? {
                f.around(FaultPoint.CREDENTIAL_BEFORE_FIND) { keyId }
                return f.around(FaultPoint.CREDENTIAL_FIND) { real.find(keyId) }
            }
        }
}

package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.required.AuthenticationSessionEventRecord
import com.paycore.payment.payment.application.required.AuthenticationSessionEventStore
import com.paycore.payment.payment.application.required.AuthenticationSessionRepository
import com.paycore.payment.payment.application.required.CheckoutSessionCloseReason
import com.paycore.payment.payment.application.required.CheckoutSessionStore
import com.paycore.payment.payment.application.required.CheckoutTokenStatus
import com.paycore.payment.payment.application.required.CheckoutTokenStore
import com.paycore.payment.payment.application.required.IntegrationEventIdGenerator
import com.paycore.payment.payment.application.required.OutboxStore
import com.paycore.payment.payment.application.required.PaymentRepository
import com.paycore.payment.payment.application.required.Versioned
import com.paycore.payment.payment.domain.AuthenticationEventDetail
import com.paycore.payment.payment.domain.AuthenticationEventKind
import com.paycore.payment.payment.domain.OrderPaymentControl
import com.paycore.payment.payment.domain.Payment
import com.paycore.payment.payment.domain.PaymentStatus
import com.paycore.payment.payment.domain.RequestedExpiryOutcome
import com.paycore.payment.payment.domain.RequestedPaymentExpiry
import org.springframework.stereotype.Component
import java.time.Instant

/** 만료 이벤트의 인과 값(D-AUTHN-008 envelope): 요청 경로는 requestId·멱등 기록 ID/requestId, 주기 작업은 회차 ID·`checkout-expiry-job:<회차>`, confirm은 AUTH-001. */
data class ExpiryCausation(val correlationId: String, val causationId: String) {
    init {
        require(correlationId.length in 1..MAX_CORRELATION && causationId.length in 1..MAX_CAUSATION) { "causation values exceed the envelope columns" }
    }

    private companion object {
        const val MAX_CORRELATION = 128
        const val MAX_CAUSATION = 64
    }
}

/** [RequestedPaymentClosing.closeIfElapsed] 결과 — **커밋되는 결과 값**이다(n-d: 만료를 예외로 표현하지 않는다 — 예외는 rollback이다). */
sealed interface ClosingOutcome {
    /** 이번 호출이 Payment를 EXPIRED로 만들고 결제창 기록을 닫고 PaymentExpired를 append했다. [paymentVersion]은 갱신 뒤 version. */
    data class Closed(val paymentVersion: Long) : ClosingOutcome

    /** REQUESTED이고 결제창 시각 전 — 변경 없음. */
    data object Open : ClosingOutcome

    /** 이미 REQUESTED가 아니다(만료됨·승인 시작·종료) — 변경 없음. */
    data object NotRequested : ClosingOutcome
}

/**
 * control 보유 경로의 inline 만료 단계 하나(AUTHN-001 §7.5.3 판정 U-3, AN-30): CreatePayment·토큰 발급 TX-I·주기 작업·AUTH-001 confirm이 **같은 단계**를 쓴다 —
 * 경로마다 다른 조합이 생기지 않는다. 호출자 TX 안에서 control(X) → payment(X)를 이미 쥔 채 부른다. 순서(잠금 순서 §10.3):
 * 인증 세션 잠금 → `RequestedPaymentExpiry.expireIfElapsed`(미폐기 세션 EXPIRED(PAYMENT_CLOSED) → Payment EXPIRED(NOT_TRANSMITTED_AND_SESSION_DISCARDED)) → 바뀐 세션
 * 저장·결정 로그 EXPIRED(PAYMENT_CLOSED) → ACTIVE 결제창 세션 EXPIRED(PAYMENT_CLOSED) → ISSUED 토큰 CLOSED → Payment 저장 → PaymentExpired append(D-AUTHN-008).
 * 결제창 시각이 없는 REQUESTED는 V8_1 뒤 존재할 수 없다 — 만나면 결함(IllegalStateException → rollback·500, `NO_WINDOW` 결함 방어, §7.5.6).
 */
@Component
class RequestedPaymentClosing(
    private val payments: PaymentRepository,
    private val sessions: AuthenticationSessionRepository,
    private val sessionEvents: AuthenticationSessionEventStore,
    private val checkoutSessions: CheckoutSessionStore,
    private val tokens: CheckoutTokenStore,
    private val outbox: OutboxStore,
    private val eventIds: IntegrationEventIdGenerator,
) {

    fun closeIfElapsed(control: Versioned<OrderPaymentControl>, payment: Versioned<Payment>, now: Instant, causation: ExpiryCausation): ClosingOutcome {
        val p = payment.value
        require(control.value.orderKey == p.orderKey) { "the locked control must be the payment's order control" }
        if (p.status != PaymentStatus.REQUESTED) return ClosingOutcome.NotRequested
        val window = checkNotNull(p.checkoutWindow) { "a REQUESTED payment without a checkout window is a defect after V8_1 (NO_WINDOW)" }
        if (now.isBefore(window.expiresAt)) return ClosingOutcome.Open

        val locked = sessions.lockAllOfPayment(p.id)
        val toExpire = locked.filterNot { it.value.isDiscarded() }
        val outcome = RequestedPaymentExpiry.expireIfElapsed(p, locked.map { it.value }, now)
        check(outcome == RequestedExpiryOutcome.EXPIRED) { "expiry must happen for an elapsed REQUESTED payment: $outcome" }
        toExpire.forEach { s ->
            sessions.update(s.value, s.version)
            sessionEvents.append(
                AuthenticationSessionEventRecord(s.value.id, AuthenticationEventKind.EXPIRED, AuthenticationEventDetail.PAYMENT_CLOSED, now, causation.correlationId),
            )
        }
        checkoutSessions.lockActiveOfPayment(p.id)?.let { active ->
            checkoutSessions.update(active.value.closed(CheckoutSessionCloseReason.PAYMENT_CLOSED, now), active.version)
        }
        tokens.lockIssuedOfPayment(p.id)?.let { issued -> tokens.update(issued.value.closed(CheckoutTokenStatus.CLOSED, now), issued.version) }
        val version = payments.update(p, payment.version)
        val creation = checkNotNull(payments.findCreationRecord(p.id)) { "a locked payment has a creation record" }
        outbox.append(PaymentExpiredEvents.of(p, creation, version, now, eventIds.newEventId(), causation.correlationId, causation.causationId))
        return ClosingOutcome.Closed(version)
    }
}

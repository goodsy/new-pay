package com.paycore.payment.payment.domain

import java.time.Instant

/**
 * 결제창 만료(D-AUTH-005, D-CPD-002 EXPIRED 근거 NOT_TRANSMITTED_AND_SESSION_DISCARDED의 유일한 출처 — 명세 §5.5). REQUESTED Payment는
 * 승인을 시작하지 않아 Attempt가 없다(미전송). 이 연산이 같은 Payment의 폐기되지 않은 세션(CREATED·IN_PROGRESS·미귀속 VERIFIED)을 모두
 * EXPIRED로 만든 뒤 Payment를 EXPIRED로 종료한다 — 근거의 "세션 폐기" 조건을 domain이 직접 만족시킨다.
 * 승인 시작과의 직렬화(control 행 잠금)는 application(AUTH-001)의 책임이다.
 */
/** [RequestedPaymentExpiry.expireIfElapsed] 결과. */
enum class RequestedExpiryOutcome {
    /** 이번 호출이 Payment를 EXPIRED로 만들었다. */
    EXPIRED,

    /** 결제창 시각 전 — 변경 없음. */
    NOT_DUE,

    /** 이미 REQUESTED가 아니다(이미 만료됨·승인 시작) — 변경 없음(두 번째 시도는 여기로 온다). */
    NOT_REQUESTED,

    /** 결제창 시각이 없다 — 업무 경로가 아니라 결함 방어(control 보유 경로가 먼저 채운다, AUTHN-001 §7.5.6). 변경 없음. */
    NO_WINDOW,
}

object RequestedPaymentExpiry {
    /**
     * 결제창 시각 경과 만료(D-AUTHN-005, AUTHN-001 §7.5.2): REQUESTED이고 `now ≥ checkoutWindow.expiresAt`일 때만 [expire]를 실행한다. 직렬화(control →
     * payment 잠금)와 결제창 세션·토큰 닫기·PaymentExpired append는 application(같은 TX)의 책임이다.
     */
    fun expireIfElapsed(payment: Payment, sessionsOfPayment: List<AuthenticationSession>, now: Instant): RequestedExpiryOutcome {
        if (payment.status != PaymentStatus.REQUESTED) return RequestedExpiryOutcome.NOT_REQUESTED
        val window = payment.checkoutWindow ?: return RequestedExpiryOutcome.NO_WINDOW
        if (now.isBefore(window.expiresAt)) return RequestedExpiryOutcome.NOT_DUE
        expire(payment, sessionsOfPayment, now)
        return RequestedExpiryOutcome.EXPIRED
    }

    fun expire(payment: Payment, sessionsOfPayment: List<AuthenticationSession>, now: Instant) {
        if (payment.status != PaymentStatus.REQUESTED) throw InvalidPaymentTransitionException(payment.id, payment.status, "expireRequested")
        require(sessionsOfPayment.all { it.binding.paymentId == payment.id }) { "sessions must belong to this payment" }
        check(sessionsOfPayment.none { it.isBound() }) { "a REQUESTED payment cannot have a bound authentication session" }
        sessionsOfPayment.filterNot { it.isDiscarded() }.forEach { it.expire(AuthenticationExpiryCause.PAYMENT_CLOSED, now) }
        payment.expire(TerminationEvidence.NOT_TRANSMITTED_AND_SESSION_DISCARDED, TerminationEvidenceSource.checkoutExpiry(payment.id, now))
    }
}

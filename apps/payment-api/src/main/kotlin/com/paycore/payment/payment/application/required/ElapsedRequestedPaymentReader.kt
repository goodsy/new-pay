package com.paycore.payment.payment.application.required

import com.paycore.payment.payment.domain.OrderKey
import com.paycore.payment.payment.domain.PaymentId
import java.time.Instant

/** 만료 후보 한 건(비권위 — 판정은 잠금 재판정). 키셋 위치는 (결제창 시각, paymentId). */
data class ElapsedRequestedCandidate(val paymentId: PaymentId, val orderKey: OrderKey, val checkoutExpiresAt: Instant)

/**
 * 결제창 만료 후보 조회 Port(CONVENTIONS §14 jOOQ 조회 `…Reader`, AUTHN-001 §7.5.4): `status = 'REQUESTED' AND checkout_expires_at <= now`, 키셋
 * `(checkout_expires_at, payment_id) > after` 정렬·LIMIT(인덱스 `ix_payment_requested_expiry`). 읽기 전용 TX 하나. 저장소 오류는 [PersistenceFailure].
 */
interface ElapsedRequestedPaymentReader {
    fun findElapsed(now: Instant, after: ElapsedRequestedCandidate?, limit: Int): List<ElapsedRequestedCandidate>
}

package com.paycore.payment.payment.domain

import java.time.Instant

enum class PaymentTransactionType { AUTHORIZATION }

/**
 * 확정 금융 사실(D-AUTH-003, 사용자 확정 2026-10-15): 기관이 확정한 금융 효과 1건 = PaymentTransaction 1건(append-only). 승인 성공 =
 * AUTHORIZATION 거래(취소·부분취소는 CANCEL-CONTRACT-001에서 새 타입). PaymentAuthorized 이벤트 1건 = 이 거래 1건, FINANCIAL 업무 키 = [id].
 * 늦은/상충 승인은 hold 해제 판정 전에는 거래를 만들지 않는다.
 */
data class PaymentTransaction(
    val id: PaymentTransactionId,
    val type: PaymentTransactionType,
    val authorizationId: AuthorizationId,
    val paymentId: PaymentId,
    val terms: ApprovedTerms,
    val sourceObservation: ObservationKey,
    val confirmedAt: Instant,
)

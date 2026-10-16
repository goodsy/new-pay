package com.paycore.payment.payment.domain

import com.paycore.payment.payment.domain.PaymentStatus.AUTHORIZING
import com.paycore.payment.payment.domain.PaymentStatus.FAILED
import com.paycore.payment.payment.domain.PaymentStatus.REQUESTED
import com.paycore.payment.payment.domain.PaymentStatus.UNKNOWN

/**
 * 같은 주문의 새 Payment 판정 결과(구현 계약 §4 표).
 *
 * 같은 멱등키 재요청(과거 결과 반환)은 application/idempotency 범위(CP-API)라 여기서 다루지 않는다. 여기서 "새 Payment"는 새 요청키의
 * 새 고객 시도다. 오류 코드·HTTP 매핑은 CP-CONTRACT-001에서 정한다.
 */
sealed interface PaymentSlotDecision {
    /** 새 REQUESTED Payment를 [nextSlotGeneration]으로 만들고 슬롯을 점유한다(control이 없으면 1). */
    data class CreateNewPayment(val nextSlotGeneration: Long) : PaymentSlotDecision

    /** 같은 고정 입력의 활성 시도(REQUESTED·AUTHORIZING·UNKNOWN)를 재사용한다. 새 Payment 없음. */
    data class UseExistingPayment(val paymentId: PaymentId, val status: PaymentStatus) : PaymentSlotDecision

    /** 주문의 고정 입력과 다르다. 상태와 무관하게 거절하며 변경 주문은 새 주문 ID를 써야 한다. */
    data class FixedIntentConflict(val existingPaymentId: PaymentId) : PaymentSlotDecision

    /** 새 Payment 금지. [referencePaymentId]는 확인·조회할 기존 Payment다. */
    data class Blocked(val reason: BlockReason, val referencePaymentId: PaymentId) : PaymentSlotDecision
}

enum class BlockReason {
    /**
     * 늦은 성공 등 상충 결과로 주문이 사건 보류 중이다. 조사·대사가 필요하다(해제 규칙은 미정). 현재 시도가 활성(REQUESTED 등)이어도
     * 같은 입력 재요청은 기존 시도 재사용이 아니라 이 차단으로 응답하고 기존 paymentId를 참조로 담는다(D-CPD-004, 사용자 확정 2026-10-06).
     */
    INCIDENT_HOLD,

    /** 과거 승인 성공이 있다. 취소/환불 여부와 무관하게 이 주문은 다시 승인하지 않으며 재구매는 새 주문 ID다. */
    AUTHORIZED_NEW_ORDER_REQUIRED,

    /** 현재 Payment가 FAILED/EXPIRED지만 D-CPD-002 충분 근거가 없다(근거 없음·timeout·NOT_FOUND·lease 만료). 확인/복구가 먼저다. */
    TERMINATION_EVIDENCE_INSUFFICIENT,
}

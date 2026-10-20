package com.paycore.payment.payment.application.required

import com.paycore.payment.payment.domain.OperatorApprovalId
import com.paycore.payment.payment.domain.OperatorId
import com.paycore.payment.payment.domain.PaymentId
import java.time.Instant

/**
 * 운영자 보정 취소 명령의 거절 감사 한 건(D-CANCEL1-006). 개인정보 없음: 운영자는 내부 불변 ID(D-ADMIN-004 — 값 타입이 64자·올바른 UTF-16을 강제)만, 사유는 고정 분류 코드([reason], 예
 * `OPERATOR_SAME_PERSON`)만이며 운영자 사유 문구는 담지 않는다.
 */
data class OperatorCancelRejection(
    val approvalId: OperatorApprovalId,
    val paymentId: PaymentId,
    val requestedBy: OperatorId,
    val approvedBy: OperatorId,
    val reason: String,
    val rejectedAt: Instant,
) {
    init {
        require(REASON.matches(reason)) { "rejection reason must be a fixed code" }
    }

    private companion object {
        val REASON = Regex("^[A-Z_]{1,48}$")
    }
}

/**
 * 운영자 명령 거절 감사 저장 Port(CANCEL-001 — CONVENTIONS §14 Store ③ 감사 기록, append-only, TX 필수 MANDATORY). 거절 판정과 **같은 로컬 TX**에서 부른다(D-CANCEL1-006).
 * 거절 한 번 = 행 하나(D-CANCEL1-008): 같은 승인의 같은 사유 반복 거절도 각각 시각을 가진 새 행이며 중복 제거하지 않는다.
 */
interface OperatorCancelRejectionStore {
    fun append(rejection: OperatorCancelRejection)

    /** 기록 순서대로. */
    fun findByApproval(approvalId: OperatorApprovalId): List<OperatorCancelRejection>
}

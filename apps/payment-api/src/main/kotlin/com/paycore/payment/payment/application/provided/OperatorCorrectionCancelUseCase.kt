package com.paycore.payment.payment.application.provided

import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.OperatorApprovalId
import com.paycore.payment.payment.domain.OperatorCancelPurpose
import com.paycore.payment.payment.domain.OperatorId

/**
 * 운영자 보정 취소(CANCEL-001 §5 — D-CANCEL-005, D-ADMIN-007). **진입점이 없다**(확정 D-CANCEL1-001 — ADMIN-BOOT-001이 서비스 간 인증과 함께 연다, ArchUnit m-5). PAYMENT는 요청자 ≠ 승인자·
 * 사유·근거·상한·승인 대상 지문을 다시 검사하고(ADMIN 승인 상태를 동기 조회하지 않는다), approvalId를 영구 업무 키로 재요청을 판정한다(CN-10). 업무 거절은 같은 로컬 TX에 감사 행 하나를
 * 남긴다(D-CANCEL1-006·008). TXd·TX2·회복은 가맹점 취소와 공용.
 */
interface OperatorCorrectionCancelUseCase {
    fun cancel(command: OperatorCorrectionCancelCommand): OperatorCorrectionCancelResult
}

/** 운영자 보정 취소 명령. [approvedTargetFingerprint]는 ADMIN이 승인한 대상 `oct1:` 지문(D-ADMIN-007 — 실행 시점 값과 다르면 거절). [reason]은 감사 저장만(로그·거절 감사에 쓰지 않는다). */
data class OperatorCorrectionCancelCommand(
    val paymentId: String,
    val money: Money,
    val requestedBy: OperatorId,
    val approvedBy: OperatorId,
    val approvalId: OperatorApprovalId,
    val reason: String,
    val purpose: OperatorCancelPurpose,
    val approvedTargetFingerprint: String,
    val correlationId: String,
) {
    override fun toString(): String = "OperatorCorrectionCancelCommand(paymentId=$paymentId, approvalId=${approvalId.value}, correlationId=$correlationId)"
}

/** 운영자 보정 취소 거절 분류(감사 행 `rejection_reason` 값 — 고정 code, 개인정보·사유 문구 없음). */
enum class OperatorCancelRejectionCode {
    PAYMENT_NOT_FOUND,

    /** paymentId가 형식이 아니다(감사 행 payment_id는 자리표시 MALFORMED — 원문은 저장하지 않는다, 검토 #3 m-3). 결과는 NotFound. */
    PAYMENT_ID_MALFORMED,
    PAYMENT_NOT_CANCELLABLE,
    CANCEL_IN_PROGRESS,
    CURRENCY_MISMATCH,
    ALREADY_FULLY_CANCELLED,
    AMOUNT_EXCEEDS_BALANCE,
    PARTIAL_CANCEL_NOT_SUPPORTED,
    OPERATOR_SAME_PERSON,
    OPERATOR_REASON_MISSING,
    OPERATOR_BASIS_MISSING,
    OPERATOR_REASON_INVALID,
    OPERATOR_BASIS_INVALID,
    OPERATOR_TARGET_STALE,

    /** 같은 approvalId가 다른 금액·다른 Payment로 쓰였다(CC-12 — 영구 업무 키 충돌). */
    APPROVAL_CONFLICT,

    /**
     * 같은 approvalId·같은 Payment·같은 금액이지만 요청자·승인자가 기존 취소와 다르다(검토 #3 n-3, CN-32 — fail-closed).
     * 같은 요청이 아니므로 기존 결과를 돌려주지 않고 감사한다.
     */
    APPROVAL_IDENTITY_MISMATCH,
}

sealed interface OperatorCorrectionCancelResult {
    /** 시작됨([existing] = 같은 approvalId·같은 내용의 기존 취소). [view]는 현재 상태. */
    data class Accepted(val view: CancellationView, val existing: Boolean) : OperatorCorrectionCancelResult

    /** 업무 거절(감사 행 저장됨). */
    data class Rejected(val code: OperatorCancelRejectionCode) : OperatorCorrectionCancelResult

    /** Payment가 없다(감사 행 저장됨 — CN-30). */
    data object NotFound : OperatorCorrectionCancelResult
}

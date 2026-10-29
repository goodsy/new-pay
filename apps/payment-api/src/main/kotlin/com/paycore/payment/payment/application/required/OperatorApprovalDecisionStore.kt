package com.paycore.payment.payment.application.required

import com.paycore.payment.payment.domain.CancellationId
import com.paycore.payment.payment.domain.CurrencyCode
import com.paycore.payment.payment.domain.OperatorApprovalId
import com.paycore.payment.payment.domain.OperatorId
import com.paycore.payment.payment.domain.PaymentId
import java.time.Duration
import java.time.Instant

/** 결정 종류(D-OPS-005): 실행 수락 또는 업무 거절. */
enum class OperatorDecisionKind { STARTED, REJECTED }

/** 결정을 만든 호출(I-37): 실행 명령 또는 기한 확인 전용 호출(D-OPS-009). */
enum class OperatorDecisionOrigin { EXECUTION_COMMAND, DEADLINE_CONFIRMATION }

/**
 * approvalId별 최초 확정 결정 한 건(ADMIN-PAYMENT-OPS-001 §4.4, D-OPS-005). [contentHash]는 명령 내용 hash `opd1:`(사유·근거는 hash로만 — 문구 저장 없음). STARTED는
 * [cancellationId], REJECTED는 [rejectionCode]만 가진다. [decidedAt]은 TX 업무 시각(PaymentClock).
 */
data class OperatorApprovalDecision(
    val approvalId: OperatorApprovalId,
    val paymentId: PaymentId,
    val kind: OperatorDecisionKind,
    val rejectionCode: String?,
    val cancellationId: CancellationId?,
    val contentHash: String,
    val amountMinor: Long,
    val currency: CurrencyCode,
    val requestedBy: OperatorId,
    val approvedBy: OperatorId,
    val approvedTargetFingerprint: String,
    val approvedAt: Instant,
    val executeBefore: Instant,
    val decidedAt: Instant,
    val origin: OperatorDecisionOrigin,
) {
    init {
        require((kind == OperatorDecisionKind.STARTED) == (cancellationId != null)) { "a STARTED decision has exactly a cancellation id" }
        require((kind == OperatorDecisionKind.REJECTED) == (rejectionCode != null)) { "a REJECTED decision has exactly a rejection code" }
        require(rejectionCode == null || CODE.matches(rejectionCode)) { "rejection code must be a fixed code" }
        require(amountMinor >= 1) { "amount must be positive" }
    }

    override fun toString(): String = "OperatorApprovalDecision(approvalId=${approvalId.value}, kind=$kind, code=$rejectionCode, origin=$origin)"

    private companion object {
        val CODE = Regex("^[A-Z_]{1,48}$")
    }
}

/**
 * 결정 기록 Port(CONVENTIONS §14 Store ③ 프로토콜 기록 — INSERT 전용, TX 필수). [append]는 쓰기 끝에서 flush해 UNIQUE(approval_id) 1062를 [PersistenceFailure.UniqueConflict]로 던진다 —
 * **호출자는 잡아서 같은 TX를 계속하지 않는다**(InnoDB 1062는 문장만 되돌린다: 잡고 커밋하면 결정 없는 취소가 남는다 — rev 0.4 구현 주의). TX 전체 rollback 뒤 새 TX에서 재판정한다.
 */
interface OperatorApprovalDecisionStore {
    fun find(approvalId: OperatorApprovalId): OperatorApprovalDecision?

    fun append(decision: OperatorApprovalDecision)
}

/**
 * 실행 기한 검사 값(D-OPS-002, §4.4.3-7 — PAYMENT 설정). [maxExecutionWindow] = `executeBefore − approvedAt` 상한(Demo 24시간), [approvedAtSkew] = `approvedAt ≤ now + skew` 허용 시계 차이
 * (서명 시각 창과 같은 값). 운영값은 LIVE Gate.
 */
data class OperatorApprovalWindow(val maxExecutionWindow: Duration, val approvedAtSkew: Duration) {
    init {
        require(!maxExecutionWindow.isNegative && !maxExecutionWindow.isZero) { "max execution window must be positive" }
        require(!approvedAtSkew.isNegative) { "approvedAt skew must not be negative" }
    }
}

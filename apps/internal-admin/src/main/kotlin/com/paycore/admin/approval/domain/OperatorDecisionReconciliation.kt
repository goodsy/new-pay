package com.paycore.admin.approval.domain

import java.time.Instant

/**
 * PAYMENT 기록 결정 ↔ ADMIN 승인 대조 판정(ADMIN-PAYMENT-OPS-001 §4.17 — 순수 함수, 상태를 바꾸지 않는다). 입력은 PAYMENT 결정 한 행과 그 approvalId의 ADMIN 보기(없으면 null).
 */
object OperatorDecisionReconciliation {
    /** PAYMENT 결정(목록 한 행의 대조 재료). */
    data class PaymentDecision(
        val approvalId: String, val decision: String, val code: String?, val cancellationId: String?, val paymentId: String, val amountMinor: Long,
        val currency: String, val requestedBy: String, val approvedBy: String, val fingerprint: String, val recordedAt: Instant,
    )

    /** ADMIN 쪽 보기(승인 행 + 명령 행 + 실행 행 — 실행 행은 승인된 뒤에만). */
    data class AdminView(
        val kind: ApprovalKind, val status: ApprovalStatus, val resultCode: String?, val paymentId: String?, val requestedBy: String,
        val amountMinor: Long?, val currency: String?, val executor: String?, val fingerprint: String?, val ownerReference: String?,
    )

    data class Finding(val kind: ReconciliationFindingKind, val detail: String?)

    private val UNAPPROVED = setOf(ApprovalStatus.PENDING, ApprovalStatus.REJECTED, ApprovalStatus.WITHDRAWN, ApprovalStatus.EXPIRED, ApprovalStatus.INVALIDATED)

    /**
     * 판정(우선순위 = [ReconciliationFindingKind] 선언 순서): ADMIN 승인 없음·다른 종류 → ORPHAN. 승인되지 않은 상태(PENDING·REJECTED·WITHDRAWN·EXPIRED·INVALIDATED)
     * → DECISION_FOR_UNAPPROVED(detail = 다른 열 이름 `PAYMENT_ID`·`AMOUNT`·`CURRENCY`·`REQUESTER`, 모두 같으면 `CONTENT_MATCH` — 승인자는 실행 행이 없어 비교 불가).
     * 그 밖은 FINGERPRINT_MISMATCH(지문·Payment·금액·통화·요청자·승인자) → STATE_DIVERGENCE(EXECUTED vs REJECTED, OWNER_REJECTED vs STARTED, code 다름, 취소 ID 다름)
     * → UNRESOLVED_WITH_DECISION(EXECUTING·UNKNOWN인데 결정 기록이 [unresolvedAfter]보다 오래됨). 여럿이면 첫 종류 + 나머지는 detail(쉼표). 해당 없으면 null.
     */
    fun judge(decision: PaymentDecision, admin: AdminView?, now: Instant, unresolvedAfter: java.time.Duration): Finding? {
        if (admin == null || admin.kind != ApprovalKind.OPERATOR_CORRECTION_CANCEL) return Finding(ReconciliationFindingKind.ORPHAN_DECISION, null)
        if (admin.status in UNAPPROVED) {
            val columns = listOfNotNull(
                "PAYMENT_ID".takeIf { admin.paymentId != decision.paymentId }, "AMOUNT".takeIf { admin.amountMinor != decision.amountMinor },
                "CURRENCY".takeIf { admin.currency != decision.currency }, "REQUESTER".takeIf { admin.requestedBy != decision.requestedBy },
            )
            return Finding(ReconciliationFindingKind.DECISION_FOR_UNAPPROVED, columns.joinToString(",").ifEmpty { "CONTENT_MATCH" })
        }
        val kinds = mutableListOf<ReconciliationFindingKind>()
        val contentDiffers = admin.fingerprint != decision.fingerprint || admin.paymentId != decision.paymentId || admin.amountMinor != decision.amountMinor ||
            admin.currency != decision.currency || admin.requestedBy != decision.requestedBy || admin.executor != decision.approvedBy
        if (contentDiffers) kinds += ReconciliationFindingKind.FINGERPRINT_MISMATCH
        val diverges = when (admin.status) {
            ApprovalStatus.EXECUTED -> decision.decision != STARTED || (admin.ownerReference != null && admin.ownerReference != decision.cancellationId)
            ApprovalStatus.OWNER_REJECTED -> decision.decision != REJECTED || admin.resultCode != decision.code
            else -> false
        }
        if (diverges) kinds += ReconciliationFindingKind.STATE_DIVERGENCE
        if (admin.status in ApprovalStatus.OWNER_SERVICE_ONLY && decision.recordedAt.isBefore(now.minus(unresolvedAfter))) kinds += ReconciliationFindingKind.UNRESOLVED_WITH_DECISION
        return kinds.firstOrNull()?.let { Finding(it, kinds.drop(1).joinToString(",").ifEmpty { null }) }
    }

    private const val STARTED = "STARTED"
    private const val REJECTED = "REJECTED"
}

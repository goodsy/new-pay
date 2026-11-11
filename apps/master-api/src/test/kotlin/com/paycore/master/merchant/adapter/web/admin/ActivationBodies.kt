package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.merchant.adapter.web.admin.MerchantApprovalBodies.Approval
import com.paycore.master.merchant.adapter.web.admin.MerchantOperationsBodies.Submission
import com.paycore.master.merchant.adapter.web.admin.MerchantOperationsBodies.raw
import com.paycore.master.merchant.adapter.web.admin.MerchantOperationsBodies.str
import com.paycore.master.merchant.application.provided.AdminOperation

/**
 * MERCHANT-OPS-002 B3-1 본문 Named Fixture(src/test 전용). 1인(B1 공통 필드 — `mo1.…`·`actor`·`mos.…`)과 2인(근거 공통 9 + **`operation`**(C-39) + 명령 필드 §9.14 순서).
 * 사유 코드는 C-37 Demo 목록 값, 근거 참조는 시험 값이다.
 */
object ActivationBodies {
    const val PAYMENT_METHOD_ACTIVATIONS = "/internal/v1/admin/merchant-operations/payment-method-activations"
    const val SITE_REVIEW_REJECTIONS = "/internal/v1/admin/merchant-operations/site-review-rejections"
    const val PAYMENT_METHOD_ACTIVATION_APPROVALS = "/internal/v1/admin/merchant-operations/payment-method-activation-approvals"
    const val SITE_REVIEW_APPROVALS = "/internal/v1/admin/merchant-operations/site-review-approvals"

    const val APPROVE_REASON = "REVIEW_PASSED"
    const val REJECT_REASON = "DOCUMENT_INSUFFICIENT"

    private fun common(s: Submission) = listOf("idempotencyKey" to s.idempotencyKey, "actor" to s.actor, "correlationId" to s.correlationId)

    /** 1인 수단 개통 등록(`payment-method-activations`). */
    fun registerActivation(mid: String, s: Submission = Submission(), method: String = "CARD"): String =
        raw((common(s) + listOf("merchantAccountId" to mid, "paymentMethod" to method)).map { (k, v) -> k to str(v) })

    /** 1인 Site 심사 거절(`site-review-rejections`) — 결정값 필드 없음(C-40), 근거 참조 선택. */
    fun rejectSiteReview(siteId: String, s: Submission = Submission(), reasonCode: String = REJECT_REASON, evidenceRef: String? = null): String =
        raw((common(s) + listOf("merchantSiteId" to siteId, "reasonCode" to reasonCode) + listOfNotNull(evidenceRef?.let { "evidenceRef" to it })).map { (k, v) -> k to str(v) })

    private fun evidenceFields(a: Approval, fp: String, operation: AdminOperation): List<Pair<String, String>> = listOf(
        "idempotencyKey" to a.idempotencyKey, "correlationId" to a.correlationId, "approvalId" to a.approvalId, "requestedBy" to a.requestedBy,
        "approvedBy" to a.approvedBy, "approvedAt" to a.approvedAt, "executeBefore" to a.executeBefore, "evidenceRef" to a.evidenceRef, "approvalFingerprint" to fp,
        "operation" to operation.code,
    )

    /** 2인 개통 승인 본문 필드(명령 필드 activationId, primarySiteId — C-45). */
    fun activationApprovalFields(activationId: String, primarySiteId: String, a: Approval = Approval(), fingerprint: String? = null): List<Pair<String, String>> {
        val op = AdminOperation.APPROVE_PAYMENT_METHOD_ACTIVATION
        val fp = fingerprint ?: a.fingerprint(op, listOf(activationId, primarySiteId))
        return (evidenceFields(a, fp, op) + listOf("activationId" to activationId, "primarySiteId" to primarySiteId)).map { (k, v) -> k to str(v) }
    }

    fun activationApproval(activationId: String, primarySiteId: String, a: Approval = Approval(), fingerprint: String? = null): String =
        raw(activationApprovalFields(activationId, primarySiteId, a, fingerprint))

    /** 2인 Site 심사 승인 본문 필드(명령 필드 merchantSiteId, reasonCode). */
    fun siteReviewApprovalFields(siteId: String, a: Approval = Approval(), reasonCode: String = APPROVE_REASON, fingerprint: String? = null): List<Pair<String, String>> {
        val op = AdminOperation.DECIDE_SITE_REVIEW
        val fp = fingerprint ?: a.fingerprint(op, listOf(siteId, reasonCode))
        return (evidenceFields(a, fp, op) + listOf("merchantSiteId" to siteId, "reasonCode" to reasonCode)).map { (k, v) -> k to str(v) }
    }

    fun siteReviewApproval(siteId: String, a: Approval = Approval(), reasonCode: String = APPROVE_REASON, fingerprint: String? = null): String =
        raw(siteReviewApprovalFields(siteId, a, reasonCode, fingerprint))
}

package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.merchant.adapter.web.admin.MerchantApprovalBodies.Approval
import com.paycore.master.merchant.adapter.web.admin.MerchantOperationsBodies.raw
import com.paycore.master.merchant.adapter.web.admin.MerchantOperationsBodies.str
import com.paycore.master.merchant.application.provided.AdminOperation

/**
 * MERCHANT-CODE-001 MC-1b `merchant-code-changes` 본문 Named Fixture(src/test 전용 — §3.7.2): 근거 공통 9 + `operation` = `ChangeMerchantCode` + 명령 필드
 * `merchantId`, `currentCode`, `newCode`, `reasonCode`(`mop1` 끝 줄 순서). 등록자 필드 없음(Q-MC-10).
 */
object MerchantCodeChangeBodies {
    const val MERCHANT_CODE_CHANGES = "/internal/v1/admin/merchant-operations/merchant-code-changes"

    @Suppress("LongParameterList")
    fun fields(
        merchantId: String, currentCode: String, newCode: String, reasonCode: String = "TYPO_CORRECTION", a: Approval = Approval(), fingerprint: String? = null,
    ): List<Pair<String, String>> {
        val op = AdminOperation.CHANGE_MERCHANT_CODE
        val fp = fingerprint ?: a.fingerprint(op, listOf(merchantId, currentCode, newCode, reasonCode))
        return listOf(
            "idempotencyKey" to a.idempotencyKey, "correlationId" to a.correlationId, "approvalId" to a.approvalId, "requestedBy" to a.requestedBy,
            "approvedBy" to a.approvedBy, "approvedAt" to a.approvedAt, "executeBefore" to a.executeBefore, "evidenceRef" to a.evidenceRef, "approvalFingerprint" to fp,
            "operation" to op.code, "merchantId" to merchantId, "currentCode" to currentCode, "newCode" to newCode, "reasonCode" to reasonCode,
        ).map { (k, v) -> k to str(v) }
    }

    @Suppress("LongParameterList")
    fun body(merchantId: String, currentCode: String, newCode: String, reasonCode: String = "TYPO_CORRECTION", a: Approval = Approval(), fingerprint: String? = null): String =
        raw(fields(merchantId, currentCode, newCode, reasonCode, a, fingerprint))
}

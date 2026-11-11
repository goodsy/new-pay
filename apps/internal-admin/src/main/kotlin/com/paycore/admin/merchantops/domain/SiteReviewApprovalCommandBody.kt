package com.paycore.admin.merchantops.domain

import java.time.Instant

/**
 * Site 심사 승인 명령 본문과 `mop1` 지문(MERCHANT-OPS-002 B3-1 §9.14 — APPROVED 고정, 결정값 필드 없음 C-40). MASTER `siteReviewApproval` 파서와 코드 공유 없이 같은 계약(독립 벡터
 * `SiteReviewApprovalCommandBodySpec`).
 * - 지문 줄: `mop1`, `DecideSiteReview`, approvalId, requestedBy, approvedBy, approvedAt ms, executeBefore ms, evidenceRef, merchantSiteId, reasonCode.
 * - [json]: 근거 공통 9 + `operation` + merchantSiteId, reasonCode = 12개 문자열 필드. 사유 코드는 승인 목록(C-37 — `REVIEW_PASSED`)만. 근거 라벨 "PG 심사 근거"(minor-5).
 */
data class SiteReviewApprovalCommandBody(
    val approvalId: String,
    val requestedBy: String,
    val approvedBy: String,
    val approvedAt: Instant,
    val executeBefore: Instant,
    val evidenceRef: String,
    val merchantSiteId: String,
    val reasonCode: String,
) {
    init {
        ApprovalBodyRules.common(approvalId, requestedBy, approvedBy, approvedAt, executeBefore, evidenceRef)
        require(Regex("^site_${ApprovalBodyRules.ULID}$").matches(merchantSiteId)) { "site id format" }
        require(reasonCode in APPROVE_REASONS) { "approve reason code (C-37)" }
    }

    fun fingerprint(): String = ApprovalBodyRules.fingerprint(OPERATION, approvalId, requestedBy, approvedBy, approvedAt, executeBefore, evidenceRef, listOf(merchantSiteId, reasonCode))

    fun json(): ByteArray = ApprovalBodyRules.json(
        approvalId, requestedBy, approvedBy, approvedAt, executeBefore, evidenceRef, fingerprint(), OPERATION, listOf("merchantSiteId" to merchantSiteId, "reasonCode" to reasonCode),
    )

    override fun toString(): String = "SiteReviewApprovalCommandBody(approvalId=$approvalId, merchantSiteId=$merchantSiteId)"

    companion object {
        const val OPERATION = "DecideSiteReview"
        val APPROVE_REASONS = setOf("REVIEW_PASSED")
    }
}

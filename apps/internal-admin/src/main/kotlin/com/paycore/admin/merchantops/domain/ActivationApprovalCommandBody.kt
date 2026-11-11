package com.paycore.admin.merchantops.domain

import java.time.Instant

/**
 * 결제수단 개통 승인 명령 본문과 `mop1` 지문(MERCHANT-OPS-002 B3-1 §9.14·§9.15 — MASTER `MerchantApprovalCommandParser.paymentMethodActivationApproval`과 코드 공유 없이 같은 계약,
 * 독립 벡터 `ActivationApprovalCommandBodySpec` = MASTER 시험 벡터). 순수 계산(승인 TX1 안에서 만든다).
 * - 지문 줄: `mop1`, `ApprovePaymentMethodActivation`, approvalId, requestedBy, approvedBy, approvedAt ms, executeBefore ms, evidenceRef, activationId, primarySiteId(C-45).
 * - [json]: 근거 공통 9개 + **`operation`**(C-39 — 기한 확인이 본문으로 해석기를 고른다) + 명령 필드 2개 = 정확히 12개 문자열 필드(고정 순서, `actor` 없음 — C-17).
 * - 근거 참조 = Domain 기관 근거(C-36 — 화면 라벨 "기관(카드사/VAN) 회신 참조"). toString에 근거 참조가 없다.
 */
data class ActivationApprovalCommandBody(
    val approvalId: String,
    val requestedBy: String,
    val approvedBy: String,
    val approvedAt: Instant,
    val executeBefore: Instant,
    val evidenceRef: String,
    val activationId: String,
    val primarySiteId: String,
) {
    init {
        ApprovalBodyRules.common(approvalId, requestedBy, approvedBy, approvedAt, executeBefore, evidenceRef)
        require(Regex("^act_${ApprovalBodyRules.ULID}$").matches(activationId)) { "activation id format" }
        require(Regex("^site_${ApprovalBodyRules.ULID}$").matches(primarySiteId)) { "site id format" }
    }

    fun fingerprint(): String = ApprovalBodyRules.fingerprint(OPERATION, approvalId, requestedBy, approvedBy, approvedAt, executeBefore, evidenceRef, listOf(activationId, primarySiteId))

    fun json(): ByteArray = ApprovalBodyRules.json(
        approvalId, requestedBy, approvedBy, approvedAt, executeBefore, evidenceRef, fingerprint(), OPERATION, listOf("activationId" to activationId, "primarySiteId" to primarySiteId),
    )

    override fun toString(): String = "ActivationApprovalCommandBody(approvalId=$approvalId, activationId=$activationId)"

    companion object {
        const val OPERATION = "ApprovePaymentMethodActivation"
    }
}

package com.paycore.admin.approval.domain

/**
 * Site 심사 승인 요청 시점의 고정 대상(MERCHANT-OPS-002 B3-1 §9.16 pinned): `{merchantId, merchantName, merchantSiteId, channelType, reviewStatus, reasonCode, registeredBy}`.
 * 채널 식별값·근거 참조 없음. [reviewStatus]는 요청 시점 심사 상태(PENDING — 심사 결정 없음, 또는 SUSPENDED), [reasonCode]는 승인 사유 코드(C-37 — `REVIEW_PASSED`만).
 * [registeredBy] = `merchant_site.created_by`(MAJOR-1 — 표시 + **TX1 403 판정 근거**, D-MOPS-009 ③·D-MOPS-010 ② §9.27). 표시 이름은 비교하지 않는다(C-16).
 */
data class PinnedSiteReviewApprovalTarget(
    override val merchantId: String,
    val merchantName: String,
    val merchantSiteId: String,
    val channelType: String,
    val reviewStatus: String,
    val reasonCode: String,
    val registeredBy: String,
) : PinnedMerchantTarget {
    init {
        require(ApprovalTarget.Merchant.MERCHANT_ID.matches(merchantId)) { "merchant id format" }
        require(PinnedContractApprovalTarget.NAME.matches(merchantName)) { "merchant name" }
        require(PinnedContractApprovalTarget.SITE_ID.matches(merchantSiteId)) { "site id format" }
        require(PinnedActivationApprovalTarget.CODE.matches(channelType) && PinnedActivationApprovalTarget.CODE.matches(reviewStatus)) { "channel / review status code" }
        require(reasonCode in APPROVE_REASONS) { "approve reason code (C-37)" }
        require(PinnedActivationApprovalTarget.ACTOR.matches(registeredBy)) { "registeredBy (actor reference)" }
    }

    fun sameTargetAs(current: PinnedSiteReviewApprovalTarget): Boolean = copy(merchantName = current.merchantName) == current

    override fun toSnapshotJson(): String =
        "{\"merchantId\":\"$merchantId\",\"merchantName\":${PinnedContractApprovalTarget.quote(merchantName)},\"merchantSiteId\":\"$merchantSiteId\"," +
            "\"channelType\":\"$channelType\",\"reviewStatus\":\"$reviewStatus\",\"reasonCode\":\"$reasonCode\",\"registeredBy\":\"$registeredBy\"}"

    companion object {
        /** C-37 승인 사유 코드(Demo 목록 — 운영 사유 체계 아님, MASTER parser와 같은 목록 복제). */
        val APPROVE_REASONS = setOf("REVIEW_PASSED")
    }
}

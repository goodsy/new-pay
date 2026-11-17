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
    /**
     * MERCHANT-CODE-001 MC-3a(§5.3·§5.6.4 — C-19): 요청 시점 가맹점 코드(**표시용**). 새 행 생성 시 필수(요청 경로가 MASTER 상세 코드를 넣는다), 저장된 기존 행 읽기에서는 선택
     * (MC-3a 전 PENDING·회복 대상 행이 그대로 해석돼야 한다). A 단계 비교에서 뺀다 — 코드 변경 때문에 승인이 INVALIDATED되지 않게.
     */
    val merchantCode: String? = null,
) : PinnedMerchantTarget {
    init {
        require(ApprovalTarget.Merchant.MERCHANT_ID.matches(merchantId)) { "merchant id format" }
        require(PinnedContractApprovalTarget.NAME.matches(merchantName)) { "merchant name" }
        require(PinnedContractApprovalTarget.SITE_ID.matches(merchantSiteId)) { "site id format" }
        require(PinnedActivationApprovalTarget.CODE.matches(channelType) && PinnedActivationApprovalTarget.CODE.matches(reviewStatus)) { "channel / review status code" }
        require(reasonCode in APPROVE_REASONS) { "approve reason code (C-37)" }
        require(PinnedActivationApprovalTarget.ACTOR.matches(registeredBy)) { "registeredBy (actor reference)" }
        require(merchantCode == null || ApprovalTarget.Merchant.CODE.matches(merchantCode)) { "merchant code format (§3.1)" }
    }

    fun sameTargetAs(current: PinnedSiteReviewApprovalTarget): Boolean = copy(merchantName = current.merchantName, merchantCode = current.merchantCode) == current

    override fun toSnapshotJson(): String =
        "{\"merchantId\":\"$merchantId\",\"merchantName\":${PinnedContractApprovalTarget.quote(merchantName)}${codeJson()},\"merchantSiteId\":\"$merchantSiteId\"," +
            "\"channelType\":\"$channelType\",\"reviewStatus\":\"$reviewStatus\",\"reasonCode\":\"$reasonCode\",\"registeredBy\":\"$registeredBy\"}"

    /** 코드 키는 값이 있을 때만(MC-3a 전 행의 결정적 JSON은 그대로 — 기존 행 바이트 불변). */
    private fun codeJson(): String = merchantCode?.let { ",\"merchantCode\":\"$it\"" } ?: ""

    companion object {
        /** C-37 승인 사유 코드(Demo 목록 — 운영 사유 체계 아님, MASTER parser와 같은 목록 복제). */
        val APPROVE_REASONS = setOf("REVIEW_PASSED")
    }
}

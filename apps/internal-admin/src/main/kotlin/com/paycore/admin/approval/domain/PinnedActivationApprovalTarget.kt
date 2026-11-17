package com.paycore.admin.approval.domain

/**
 * 결제수단 개통 승인 요청 시점의 고정 대상(MERCHANT-OPS-002 B3-1 §9.16 pinned — 표시·비교용): `{merchantId, merchantName, merchantAccountId, activationId, paymentMethod, status,
 * primarySiteId, registeredBy}`. 사업자등록번호·채널 식별값·근거 참조 없음. [primarySiteId]는 요청 시점 MID의 열린 PRIMARY Site(C-45 — 승인 본문 `primarySiteId`로 고정된다).
 * [registeredBy]는 개통 등록자(MASTER 상세 `paymentMethods[].registeredBy`) — 표시 + **TX1 403 판정 근거**(D-MOPS-009 ③ — 승인자 = 이 값이면 `APPROVER_IS_REGISTRANT`, §9.27).
 * 다만 pinned 비교 대상이므로 A 단계 재조회 값과 다르면 INVALIDATED(TARGET_CHANGED). 표시 이름은 비교하지 않는다(C-16).
 */
data class PinnedActivationApprovalTarget(
    override val merchantId: String,
    val merchantName: String,
    val merchantAccountId: String,
    val activationId: String,
    val paymentMethod: String,
    val status: String,
    val primarySiteId: String,
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
        require(MERCHANT_ACCOUNT_ID.matches(merchantAccountId)) { "merchant account id format" }
        require(ACTIVATION_ID.matches(activationId)) { "activation id format" }
        require(CODE.matches(paymentMethod) && CODE.matches(status)) { "payment method / status code" }
        require(PinnedContractApprovalTarget.SITE_ID.matches(primarySiteId)) { "primary site id format" }
        require(ACTOR.matches(registeredBy)) { "registeredBy (actor reference)" }
        require(merchantCode == null || ApprovalTarget.Merchant.CODE.matches(merchantCode)) { "merchant code format (§3.1)" }
    }

    fun sameTargetAs(current: PinnedActivationApprovalTarget): Boolean = copy(merchantName = current.merchantName, merchantCode = current.merchantCode) == current

    override fun toSnapshotJson(): String =
        "{\"merchantId\":\"$merchantId\",\"merchantName\":${PinnedContractApprovalTarget.quote(merchantName)}${codeJson()},\"merchantAccountId\":\"$merchantAccountId\"," +
            "\"activationId\":\"$activationId\",\"paymentMethod\":\"$paymentMethod\",\"status\":\"$status\",\"primarySiteId\":\"$primarySiteId\",\"registeredBy\":\"$registeredBy\"}"

    /** 코드 키는 값이 있을 때만(MC-3a 전 행의 결정적 JSON은 그대로 — 기존 행 바이트 불변). */
    private fun codeJson(): String = merchantCode?.let { ",\"merchantCode\":\"$it\"" } ?: ""

    companion object {
        private const val ULID = "[0-9A-HJKMNP-TV-Z]{26}"
        val MERCHANT_ACCOUNT_ID = Regex("^mid_$ULID$")
        val ACTIVATION_ID = Regex("^act_$ULID$")
        internal val CODE = Regex("^[A-Z][A-Z_]{0,31}$")
        internal val ACTOR = Regex("^[A-Za-z0-9._:@-]{1,64}$")
    }
}

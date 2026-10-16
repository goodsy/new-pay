package com.paycore.master.merchant.domain

/** Snapshot 조립 입력: 같은 MASTER TX에서 읽은 Merchant 전체 상태(MID 1개 이상). */
data class EntitlementSource(
    val business: Business,
    val merchant: Merchant,
    val sites: List<MerchantSite>,
    val siteReviews: Map<MerchantSiteId, SiteReviewStatus>,
    val accounts: List<AccountSource>,
)

/**
 * MID 하나의 조립 입력. 승인 시작 조건(MASTER-SNAPSHOT-002): [limitPolicies] = 그 Merchant의 한도 정책 전체(조립이 발급 CSA 기본 + 이 MID
 * Override만 고른다), [policyAssignments] = 이 MID의 Assignment, [policyVersions] = 그 Assignment가 가리키는 버전, [cardRoutes] = 이 MID의 카드사 개통.
 */
data class AccountSource(
    val account: MerchantAccount,
    val issuingContract: Contract,
    val assignments: List<SiteAssignment>,
    val activations: List<PaymentMethodActivation>,
    val confirmations: List<SiteActivationConfirmation>,
    val currencies: List<CurrencyEnablement>,
    val limitPolicies: List<TransactionLimitPolicy> = emptyList(),
    val policyAssignments: List<PolicyAssignment> = emptyList(),
    val policyVersions: List<PolicyVersion> = emptyList(),
    val cardRoutes: List<CardRouteActivation> = emptyList(),
)

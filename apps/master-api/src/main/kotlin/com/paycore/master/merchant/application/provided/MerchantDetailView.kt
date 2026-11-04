package com.paycore.master.merchant.application.provided

import java.time.Instant

/**
 * 가맹점 상세(MERCHANT-OPS-001 §3.2) — 한 읽기 전용 REPEATABLE READ TX에서 읽은 일관 스냅샷(전파 상태 [propagation]만 별도 읽기).
 * 사업자등록번호는 마스킹 값만, 카드 Route는 인수사 코드·Route 참조·상태만(기관 가맹점 번호 없음 — D-MS2-005).
 */
data class MerchantDetailView(
    val merchant: MerchantSummary,
    val business: BusinessSummary,
    val sites: List<SiteDetail>,
    val contracts: List<ContractDetail>,
    val accounts: List<AccountDetail>,
    val limits: List<LimitDetail>,
    val propagation: MerchantPropagationView?,
) {
    data class MerchantSummary(val merchantId: String, val name: String, val lifecycle: String, val entitlementRevision: Long, val createdAt: Instant)

    data class BusinessSummary(val businessId: String, val name: String, val registrationNumberMasked: String, val lifecycle: String)

    data class SiteDetail(
        val merchantSiteId: String,
        val channelType: String,
        val channelIdentifier: String,
        val lifecycle: String,
        val review: SiteReview?,
    )

    data class SiteReview(val decision: String, val decidedAt: Instant)

    data class ContractDetail(val contractId: String, val createdAt: Instant, val revisions: List<RevisionDetail>, val agreements: List<AgreementDetail>)

    data class RevisionDetail(
        val revisionNo: Int,
        val scopeKind: String,
        val effectiveFrom: Instant,
        val effectiveTo: Instant?,
        val approvedAt: Instant?,
        val scopeSiteIds: List<String>,
    )

    data class AgreementDetail(val agreementId: String, val serviceKind: String, val addedAt: Instant)

    data class AccountDetail(
        val merchantAccountId: String,
        val lifecycle: String,
        val isDefault: Boolean,
        val issuingContractId: String,
        val issuingAgreementId: String,
        val issuingRevisionNo: Int,
        val issuedAt: Instant,
        val assignments: List<AssignmentView>,
        val paymentMethods: List<PaymentMethodDetail>,
        val currencies: List<CurrencyDetail>,
        val cardRoutes: List<CardRouteDetail>,
    )

    data class PaymentMethodDetail(val activationId: String, val paymentMethod: String, val status: String?, val registeredAt: Instant)

    data class CurrencyDetail(val enablementId: String, val currencyCode: String, val effectiveFrom: Instant, val effectiveTo: Instant?, val endedAt: Instant?)

    /** 카드 Route 개통 — `institutionMerchantRef`는 의도적으로 없다(D-MS2-005 감사 전용). */
    data class CardRouteDetail(
        val activationId: String,
        val paymentMethod: String,
        val acquirerCode: String,
        val routeRef: String,
        val status: String,
        val registeredAt: Instant,
    )

    data class LimitDetail(
        val policyId: String,
        val scopeKind: String,
        val agreementId: String?,
        val merchantAccountId: String?,
        val currencyCode: String,
        val perTxKind: String,
        val perTxAmount: Long?,
        val dailyKind: String,
        val dailyAmount: Long?,
        val effectiveFrom: Instant,
        val effectiveTo: Instant?,
        val endedAt: Instant?,
    )
}

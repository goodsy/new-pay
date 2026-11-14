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
    /** MERCHANT-CODE-001 §3.8: 코드 배정 이력(seq 오름차순, 최대 2 — 승인 ID 없음: 근거 조회는 ADMIN 근거 보기 경로). */
    val merchantCodeHistory: List<CodeAssignmentDetail>,
    /** §3.8 advisory(= seq 2 없음 ∧ 계약 승인 사실 없음 ∧ ACTIVE). 권위 판정은 변경 명령(MC-1b §3.7.3)이다. */
    val merchantCodeChangeable: Boolean,
) {
    /** [merchantCode] = 현재 코드(가장 큰 seq — MERCHANT-CODE-001 §3.8). */
    data class MerchantSummary(val merchantId: String, val name: String, val lifecycle: String, val entitlementRevision: Long, val createdAt: Instant, val merchantCode: String)

    /** 배정 이력 한 행(§3.8 `{code, seq, source, replacesCode?, assignedAt, assignedBy, reasonCode?}`). */
    data class CodeAssignmentDetail(
        val code: String,
        val seq: Int,
        val source: String,
        val replacesCode: String?,
        val assignedAt: Instant,
        val assignedBy: String,
        val reasonCode: String?,
    )

    data class BusinessSummary(val businessId: String, val name: String, val registrationNumberMasked: String, val lifecycle: String)

    data class SiteDetail(
        val merchantSiteId: String,
        val channelType: String,
        val channelIdentifier: String,
        val lifecycle: String,
        val review: SiteReview?,
        /** MERCHANT-OPS-002 B3-1(MAJOR-1 — 표시용): Site 등록자 = 기존 `merchant_site.created_by`(정규 actor 문자열). */
        val registeredBy: String,
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
        /** Revision 등록자(정규 actor 문자열 — 기존 `contract_revision.registered_by`, Revision 1 = 계약 등록자). MERCHANT-OPS-002 B2-2 D-MOPS-008 ③: ADMIN pinned·등록자≠승인자 판정. */
        val registeredBy: String,
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
        val siteConfirmations: List<SiteConfirmationDetail>,
    )

    /** [registeredBy]: 개통 등록자(기존 `payment_method_activation.registered_by` — MERCHANT-OPS-002 B3-1 표시용). 근거 참조는 없다. */
    data class PaymentMethodDetail(val activationId: String, val paymentMethod: String, val status: String?, val registeredAt: Instant, val registeredBy: String)

    /** Site 적용 확인(D-MD-016, MERCHANT-OPS-002 B3-1 C-43): 근거 참조·철회 사유는 의도적으로 없다. [revoked] = 철회 기록 있음. */
    data class SiteConfirmationDetail(
        val confirmationId: String,
        val merchantSiteId: String,
        val paymentMethod: String,
        val activationId: String,
        val confirmedAt: Instant,
        val revoked: Boolean,
    )

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

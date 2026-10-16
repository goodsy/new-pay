package com.paycore.master.merchant.domain

import java.time.Instant

/** 최초 Default 발급 결과: 같은 MASTER 로컬 TX로 함께 저장한다(구조 경계 §1). */
data class FirstDefaultIssuance(
    val account: MerchantAccount,
    val designation: DefaultDesignation,
    val primaryAssignment: SiteAssignment,
)

/**
 * 최초 Default MID 발급(domain service). 확정 규칙:
 * - Merchant의 최초 MID = Default(초기 "최초 Default MID 하나를 발급받고"). 이미 MID가 있으면 거절(다른 키 동시 최초 발급은 하나만 성공 — 초기).
 * - 근거 계약 당사자 = Merchant의 Business(D-MD-004), 계약 대상 Merchant = 이 Merchant(D-MD-013), 근거 Revision은 승인됐고 발급 시각에
 *   적용되는 Revision(D-MD-011·012), 적용 Revision이 없으면(효력 시작 전) 자기 시작 시각에 적용될 승인 Revision만 — 선발급(D-MD-015).
 * - Production MID는 같은 Merchant 소속이고 Scope가 허용하는 Site를 하나 연결하며 첫 연결은 PRIMARY(구조 §7·§9, 구조 경계 §1).
 * - PRIMARY 기간은 발급 시각부터 종료 예정 없음(소급 없음, D-MD-005).
 * 호출자(application)는 merchant 행을 잠그고 [existingAccountCount]를 그 잠금 아래에서 센다.
 */
object MerchantAccountIssuance {
    @Suppress("LongParameterList")
    fun issueFirstDefault(
        accountId: MerchantAccountId,
        assignmentId: SiteAssignmentId,
        business: Business,
        merchant: Merchant,
        existingDefault: MerchantAccountId?,
        existingAccountCount: Long,
        contract: Contract,
        agreementId: ContractServiceAgreementId,
        revisionNo: Int,
        primarySite: MerchantSite,
        now: Instant,
        by: ActorRef,
    ): FirstDefaultIssuance {
        require(business.id == merchant.businessId) { "business must be the merchant's business" }
        business.requireActive()
        merchant.requireActive()
        if (existingAccountCount > 0 || existingDefault != null) {
            throw MasterRuleViolation(MasterRejection.DEFAULT_ALREADY_ISSUED, existingDefault?.value)
        }
        if (contract.businessId != merchant.businessId) throw MasterRuleViolation(MasterRejection.CONTRACT_PARTY_MISMATCH)
        if (contract.merchantId != merchant.id) throw MasterRuleViolation(MasterRejection.CONTRACT_MERCHANT_MISMATCH)
        if (primarySite.merchantId != merchant.id) throw MasterRuleViolation(MasterRejection.SITE_OF_OTHER_MERCHANT)
        primarySite.requireActive()
        val basis = contract.issuingBasis(agreementId, revisionNo, primarySite, now)

        val account = MerchantAccount.issue(accountId, merchant, basis, now, by)
        val primary = SiteAssignment.start(
            assignmentId, account, primarySite, SiteAssignmentRole.PRIMARY, EffectivePeriod(now), now, by,
        )
        SiteAssignmentRules.requireCanStart(emptyList(), primary)
        merchant.recordEntitlementChange()
        return FirstDefaultIssuance(account, DefaultDesignation(merchant.id, account.id, now, by), primary)
    }
}

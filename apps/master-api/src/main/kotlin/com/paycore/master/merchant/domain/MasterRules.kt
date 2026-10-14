package com.paycore.master.merchant.domain

import java.time.Instant

/**
 * MASTER 업무 규칙 거절 사유. application이 관리 명령의 **업무 거절 결과**로 저장·재생한다(같은 키 재요청에 같은 거절). 이름은 영속 값이라
 * 바꾸지 않는다(추가만).
 */
enum class MasterRejection {
    BUSINESS_NOT_FOUND,
    BUSINESS_NOT_ACTIVE,
    BUSINESS_REGISTRATION_NUMBER_DUPLICATE,
    MERCHANT_NOT_FOUND,
    MERCHANT_NOT_ACTIVE,
    SITE_NOT_FOUND,
    SITE_NOT_ACTIVE,
    SITE_CHANNEL_DUPLICATE,
    SITE_OF_OTHER_MERCHANT,
    CONTRACT_NOT_FOUND,
    CONTRACT_PARTY_MISMATCH,
    SCOPE_MERCHANT_OF_OTHER_BUSINESS,
    SCOPE_SITE_OF_OTHER_MERCHANT,
    REVISION_NOT_FOUND,
    REVISION_ALREADY_APPROVED,
    REVISION_NOT_APPROVED,
    REVISION_ENDED,
    SITE_OUTSIDE_CONTRACT_SCOPE,
    AGREEMENT_NOT_FOUND,
    SERVICE_KIND_DUPLICATE,
    DEFAULT_ALREADY_ISSUED,
    MERCHANT_ACCOUNT_NOT_FOUND,
    MERCHANT_ACCOUNT_NOT_ACTIVE,
    ASSIGNMENT_RETROACTIVE,
    ASSIGNMENT_PERIOD_OVERLAP,
    PRIMARY_ALREADY_EFFECTIVE,
    ACTIVATION_DUPLICATE,
    REVISION_NOT_APPLICABLE,
    CONTRACT_MERCHANT_MISMATCH,
    EFFECTIVE_PERIOD_EMPTY,

    // MASTER-SNAPSHOT-001(D-MD-016~018, D-MS-002·003)
    ACTIVATION_NOT_FOUND,
    ACTIVATION_TRANSITION_NOT_ALLOWED,
    ACTIVATION_EVIDENCE_NOT_NEW,
    ACTIVATION_TERMINATED,
    ASSIGNMENT_NOT_OPEN,
    SITE_CONFIRMATION_DUPLICATE,
    SITE_CONFIRMATION_NOT_FOUND,
    SITE_CONFIRMATION_ALREADY_REVOKED,
    CURRENCY_UNKNOWN,
    CURRENCY_ENABLEMENT_RETROACTIVE,
    CURRENCY_ENABLEMENT_OVERLAP,
    CURRENCY_ENABLEMENT_NOT_FOUND,
    CURRENCY_ENABLEMENT_ALREADY_ENDED,
    SITE_REVIEW_TRANSITION_NOT_ALLOWED,
    PRIMARY_CONFIRMED_BY_APPROVAL,
}

/**
 * MASTER 업무 규칙 위반. [reference]는 거절 판단의 근거가 된 기존 대상 ID(예: 이미 발급된 Default MID)이며 없을 수 있다.
 * 메시지에는 사유 이름만 넣는다(입력값·등록번호 없음).
 */
class MasterRuleViolation(val rejection: MasterRejection, val reference: String? = null) :
    RuntimeException("master rule violation: $rejection", null, false, false)

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

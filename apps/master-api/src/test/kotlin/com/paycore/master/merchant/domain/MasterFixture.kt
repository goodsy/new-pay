package com.paycore.master.merchant.domain

import java.security.SecureRandom
import java.time.Instant

/**
 * MASTER domain의 Named Test Fixture(Test Fixture Policy: Domain Factory → Named Fixture → Fixture Monkey, src/test 전용).
 * 모든 정상 Fixture는 **공식 생성 경로**(register·issue·start·approve)로 만든다. 업무 조건(같은 Business, 승인 여부, Scope, 기간)은
 * 시험 본문에서 이름으로 읽히게 한다. 값(등록번호·채널)은 시험 전용이며 실제 사업자·도메인이 아니다.
 */
object MasterFixture {
    private val random = SecureRandom()
    private const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    val t0: Instant = Instant.parse("2026-10-09T01:00:00.000Z")
    val operator = ActorRef("operator-1")

    fun ulid(): String = (1..26).map { CROCKFORD[random.nextInt(CROCKFORD.length)] }.joinToString("")

    fun businessId() = BusinessId("biz_${ulid()}")
    fun merchantId() = MerchantId("mer_${ulid()}")
    fun siteId() = MerchantSiteId("site_${ulid()}")
    fun accountId() = MerchantAccountId("mid_${ulid()}")
    fun contractId() = ContractId("ctr_${ulid()}")
    fun agreementId() = ContractServiceAgreementId("csa_${ulid()}")
    fun assignmentId() = SiteAssignmentId("asg_${ulid()}")
    fun activationId() = PaymentMethodActivationId("act_${ulid()}")

    fun registrationNumber() = BusinessRegistrationNumber((1..10).map { random.nextInt(10) }.joinToString(""))

    fun webChannel(host: String = "shop-${ulid().lowercase()}.example.test") = SiteChannel(ChannelType.WEB, host)

    /** 기대값 변경(BUSINESS-PROFILE-001 BIZ-4 C-27): 옛 `Business.register`(DRAFT) 제거 — 가맹점을 둘 수 있는 REGISTERED 개인 사업자(D-BIZ-001, 개인 필수값 Named Fixture). */
    fun business(at: Instant = t0): Business =
        Business.createRegistered(businessId(), BusinessType.INDIVIDUAL, registrationNumber(), DisplayName("Demo Business"), BusinessFixtures.individualProfile(), at, operator).business

    fun merchant(business: Business = business(), at: Instant = t0): Merchant =
        // FLOW-3(§4B.8): 새 가맹점 등록에는 유효 사업자 계약이 필요하다 — 입력만 보강(저장 행 모양의 유효 계약).
        Merchant.register(merchantId(), business, BusinessContractFixtures.storedEffectiveFor(business.id, at), DisplayName("Demo Merchant"), MerchantCodeFixtures.unique(), at, operator)

    fun site(merchant: Merchant, channel: SiteChannel = webChannel(), at: Instant = t0): MerchantSite =
        MerchantSite.register(siteId(), merchant, channel, at, operator)

    /** 당사자 = [merchant]의 Business, MERCHANT Scope, 효력 [t0, 종료 없음), **미승인** Revision 1. */
    fun contract(merchant: Merchant, business: Business, period: EffectivePeriod = EffectivePeriod(t0)): Contract =
        Contract.register(contractId(), business, ContractScope.MerchantWide(merchant.id), period, t0, operator)

    /** Revision 1 승인 + 카드 PG 서비스 계약 하나가 있는 계약. */
    fun approvedCardContract(merchant: Merchant, business: Business, period: EffectivePeriod = EffectivePeriod(t0)): Contract =
        contract(merchant, business, period).also {
            it.approve(1, t0, operator)
            it.addAgreement(agreementId(), ServiceKind.CARD_PG_PAYMENT, t0, operator)
        }

    /** 최초 Default 발급까지 끝난 Merchant 구조(공식 경로). */
    data class Issued(
        val business: Business,
        val merchant: Merchant,
        val site: MerchantSite,
        val contract: Contract,
        val issuance: FirstDefaultIssuance,
    )

    fun issued(at: Instant = t0): Issued {
        val business = business()
        val merchant = merchant(business)
        val site = site(merchant)
        val contract = approvedCardContract(merchant, business)
        val issuance = MerchantAccountIssuance.issueFirstDefault(
            accountId(), assignmentId(), business, merchant, null, 0, contract, contract.agreements.single().id, 1, site, at, operator,
        )
        return Issued(business, merchant, site, contract, issuance)
    }

    /**
     * 저장소에서 복원된 **종료된** Merchant(Invalid 목적 Fixture: 종료 명령은 MASTER-LIFECYCLE-001이라 공식 전이 경로가 아직 없다. persistence
     * Mapper가 쓰는 공식 복원 경로 [Merchant.reconstitute]로 저장 상태를 그대로 만든다 — 존재할 수 없는 상태가 아니다: DB lifecycle CHECK가 허용).
     */
    fun terminatedMerchantAsStored(business: Business): Merchant =
        Merchant.reconstitute(
            merchantId(), business.id, DisplayName("Closed Merchant"), Lifecycle.TERMINATED, 3, t0, operator,
            listOf(MerchantCodeAssignment.registration(MerchantCodeFixtures.unique(), t0, operator)),
        )
}

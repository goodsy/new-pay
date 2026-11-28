package com.paycore.master.merchant.domain

import java.security.MessageDigest
import java.util.HexFormat

/**
 * BIZ-CRIT-APPROVAL-001 §7.2 Named Test Fixture(Test Fixture Policy — Domain Factory → Named Fixture, src/test 전용). 사업자는 공식 생성 경로([Business.createDraft]·
 * [Business.createRegistered]) 또는 저장 행 복원([Business.reconstitute] — INACTIVE·TERMINATED처럼 앱 전이 경로가 없는 상태만)으로 만든다.
 * 인원 판정 입력(등록 상태·계약·가맹점 이력)·version·요청자는 모두 이름과 인자로 명시한다(자동 생성 없음). 법인 사업자로 만들어 상호·대표자·법인등록번호 변경을 한 사업자에서 다룬다.
 *
 * '계약·가맹점 이력'은 Application이 사업자 잠금 뒤 행 존재로 판정해 넘기는 값([BusinessContractMerchantHistory])이다. 접수 건만 있는 사업자는 이력 없음으로 넘어온다(BR-BIZ-386) —
 * [aRegisteredBusinessWithIntakeOnly]는 그 업무 조건을 이름으로 드러낸 것이고 값은 [NO_HISTORY]이다(행 판정 자체는 S2 MySQL 시험).
 */
object BusinessCriticalChangeFixtures {
    val REQUESTER: ActorRef = ApprovalEvidenceFixtures.REQUESTER
    val APPROVER: ActorRef = ApprovalEvidenceFixtures.APPROVER
    val OTHER_OPERATOR: ActorRef = ApprovalEvidenceFixtures.OTHER

    /** 이력 없음(계약 접수 건만 있는 사업자 포함). main에는 상수를 두지 않는다(MINOR-4 — 운영 코드가 판정 없이 '이력 없음'을 넘기지 못하게, 값은 잠금 뒤 판정으로만 만든다). */
    val NO_HISTORY = BusinessContractMerchantHistory(contractEver = false, merchantEver = false)
    val CONTRACT_ONLY = BusinessContractMerchantHistory(contractEver = true, merchantEver = false)
    val MERCHANT_ONLY = BusinessContractMerchantHistory(contractEver = false, merchantEver = true)
    val CONTRACT_AND_MERCHANT = BusinessContractMerchantHistory(contractEver = true, merchantEver = true)

    const val STORED_CORPORATE_NUMBER = "1101110000001"
    const val CORRECTED_CORPORATE_NUMBER = "1101110000002"
    const val EVIDENCE = "BIZ-DOC/2026-0001#p1"

    /** 저장된 사업자(현재 profile 포함) + 잠금 뒤 판정된 계약·가맹점 이력. */
    data class StoredBusiness(val edit: BusinessProfileEdit, val history: BusinessContractMerchantHistory) {
        val business: Business get() = edit.business
        val profile: BusinessProfile get() = edit.profile
    }

    private fun draftCorporation() =
        Business.createDraft(MasterFixture.businessId(), BusinessType.CORPORATION, BusinessFixtures.registrationNumber(), DisplayName("임시 법인 상호"),
            BusinessFixtures.corporationProfile(), BusinessFixtures.t0, BusinessFixtures.operator)

    private fun registeredCorporation() = BusinessFixtures.corporationRegistered()

    fun aDraftBusinessWithoutHistory() = StoredBusiness(draftCorporation(), NO_HISTORY)
    fun aDraftBusinessWithContractHistory() = StoredBusiness(draftCorporation(), CONTRACT_ONLY)

    /** 이관 행 모양(§2.5): DRAFT + 가맹점 이력만(사업자 계약 없음). */
    fun aDraftBusinessWithMerchantOnly() = StoredBusiness(draftCorporation(), MERCHANT_ONLY)
    fun aDraftBusinessWithContractAndMerchant() = StoredBusiness(draftCorporation(), CONTRACT_AND_MERCHANT)
    fun aRegisteredBusinessWithoutHistory() = StoredBusiness(registeredCorporation(), NO_HISTORY)

    /** 해지된 사업자 계약만 있음 — 상태 무관 이력이라 '있음'(BR-BIZ-386). */
    fun aRegisteredBusinessWithTerminatedContract() = StoredBusiness(registeredCorporation(), CONTRACT_ONLY)

    /** 종료된 가맹점만 있음 — 종료 포함 이력이라 '있음'. */
    fun aRegisteredBusinessWithEndedMerchantOnly() = StoredBusiness(registeredCorporation(), MERCHANT_ONLY)
    fun aRegisteredBusinessWithContractAndMerchant() = StoredBusiness(registeredCorporation(), CONTRACT_AND_MERCHANT)

    /** 계약 접수 건만 있음 — 이력 없음(BR-BIZ-386, D-GOV-026 ②). */
    fun aRegisteredBusinessWithIntakeOnly() = StoredBusiness(registeredCorporation(), NO_HISTORY)

    /** 같은 사업자의 저장 행을 다른 등록 상태·생명주기로 복원(INACTIVE·TERMINATED — 앱 전이 경로 없음, 이관·수동 데이터 모양). */
    fun asStored(business: Business, status: BusinessRegistrationStatus = business.registrationStatus, lifecycle: Lifecycle = business.lifecycle): Business =
        Business.reconstitute(
            business.id, business.registrationNumber, business.displayName, business.businessType, status, business.verificationStatus, lifecycle,
            business.createdAt, business.createdBy, business.updatedAt, business.updatedBy, business.version,
        )

    // 변경 종류(§1.1 열) — 업무 의미가 이름에 드러난다.
    /** 일반정보만: 사업체 전화번호. */
    fun generalChange(p: BusinessProfile) = p.copy(registration = p.registration.copy(phoneNumber = PhoneNumber("0299990000")))

    /** 기존 법인등록번호 변경(정정 대상 — 구분·법인번호 열). */
    fun corporateNumberChange(p: BusinessProfile) =
        p.copy(registration = p.registration.copy(corporateRegistrationNumber = CorporateRegistrationNumber(CORRECTED_CORPORATE_NUMBER)))

    /** 대표자 교체(중요 — 상호·대표자 열). */
    fun representativeChange(p: BusinessProfile) = p.copy(representatives = listOf(BusinessFixtures.joint("새공동"), BusinessFixtures.joint("박공동")))

    fun proposalId() = BusinessChangeProposalId("bcp_${MasterFixture.ulid()}")

    /** 시험용 digest(무염 SHA-256 — 운영 HMAC 아님, 형식만 같다). 실제 키 계산은 adapter(S2). */
    fun testDigest(input: String) = BusinessChangeDigest("h1:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.toByteArray())))

    /** 이력 없는 등록 완료 사업자의 상호 변경 제안(2인 칸). 요청자는 명시 인자. */
    fun aCriticalChangeProposal(
        stored: StoredBusiness = aRegisteredBusinessWithoutHistory(),
        requestedBy: ActorRef = REQUESTER,
        name: DisplayName = DisplayName("새 법인 상호"),
        profile: BusinessProfile = stored.profile,
        type: BusinessType = BusinessType.CORPORATION,
    ): BusinessChangeProposal = stored.business.proposeCriticalChange(
        stored.profile, type, name, profile, BusinessChangeReason("상호 변경 신고"), EvidenceRef(EVIDENCE), stored.business.version, stored.history, proposalId(),
        BusinessFixtures.t0.plusSeconds(10), requestedBy,
    )

    /** 제안을 그대로 고정한 승인 명령(요청자 = 제안 요청자, digest = 저장 내용 재계산 값). 무효 의도는 인자로 명시한다. */
    fun approvedFor(
        proposal: BusinessChangeProposal,
        requestedBy: ActorRef = proposal.requestedBy,
        businessId: BusinessId = proposal.businessId,
        baseVersion: Long = proposal.baseVersion,
        proposalId: BusinessChangeProposalId = proposal.id,
        digest: BusinessChangeDigest = testDigest(proposal.digestInput()),
    ) = ApprovedBusinessChange(businessId, baseVersion, proposalId, digest, ApprovalEvidenceFixtures.distinctActors(requestedBy = requestedBy, approvedBy = APPROVER))
}

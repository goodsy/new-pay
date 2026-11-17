package com.paycore.master.merchant.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * BUSINESS-PROFILE-001 BIZ-4-1(C-27 전환 — D-BIZ-001 "새 가맹점 등록은 REGISTERED 사업자만"): `Merchant.register`의 판정 순서
 * `BUSINESS_NOT_ACTIVE` → `BUSINESS_NOT_REGISTERED` → 코드 예약어(§4.4). 사업자 없음(`BUSINESS_NOT_FOUND`)은 Application이 잠금에서 판정한다(MerchantRegistrationGateUseCaseSpec).
 */
class MerchantRegistrationGateSpec : BehaviorSpec({

    fun register(business: Business, code: String = MerchantCodeFixtures.unique().value) =
        Merchant.register(MasterFixture.merchantId(), business, DisplayName("Demo Merchant"), MerchantCode(code), MasterFixture.t0, MasterFixture.operator)

    /** 생명주기·등록 상태를 정한 사업자(저장된 행의 복원 — 종료 명령은 아직 없어 TERMINATED는 reconstitute로만 만든다). */
    fun stored(lifecycle: Lifecycle, status: BusinessRegistrationStatus, type: BusinessType? = BusinessType.INDIVIDUAL) = Business.reconstitute(
        MasterFixture.businessId(), MasterFixture.registrationNumber(), DisplayName("저장된 사업자"), type, status, BusinessVerificationStatus.NOT_CHECKED, lifecycle,
        MasterFixture.t0, MasterFixture.operator, MasterFixture.t0, MasterFixture.operator, 0,
    )

    Given("BIZ-4-1 D-BIZ-001: a new merchant needs a REGISTERED business") {
        Then("a DRAFT business (D-BIZ-002 minimum) and a migration-form DRAFT business (type NULL — X-1) are refused with BUSINESS_NOT_REGISTERED") {
            listOf(BusinessFixtures.draftMinimal().business, BusinessFixtures.legacyDraft()).forEach { draft ->
                shouldThrow<MasterRuleViolation> { register(draft) }.rejection shouldBe MasterRejection.BUSINESS_NOT_REGISTERED
            }
        }

        Then("a REGISTERED business (individual and corporation) registers the merchant (control)") {
            listOf(BusinessFixtures.individualRegistered().business, BusinessFixtures.corporationRegistered().business).forEach { registered ->
                register(registered).businessId shouldBe registered.id
            }
        }

        Then("§4.4 order: NOT_ACTIVE before NOT_REGISTERED (TERMINATED + DRAFT), NOT_REGISTERED before the reserved-code check (DRAFT + reserved code)") {
            shouldThrow<MasterRuleViolation> { register(stored(Lifecycle.TERMINATED, BusinessRegistrationStatus.DRAFT, null)) }.rejection shouldBe
                MasterRejection.BUSINESS_NOT_ACTIVE
            shouldThrow<MasterRuleViolation> { register(stored(Lifecycle.ACTIVE, BusinessRegistrationStatus.DRAFT), code = "adminshop") }.rejection shouldBe
                MasterRejection.BUSINESS_NOT_REGISTERED
            // 대조: REGISTERED면 같은 예약어 코드는 MERCHANT_CODE_RESERVED(기존 MC-1 규칙 그대로).
            shouldThrow<MasterRuleViolation> { register(stored(Lifecycle.ACTIVE, BusinessRegistrationStatus.REGISTERED), code = "adminshop") }.rejection shouldBe
                MasterRejection.MERCHANT_CODE_RESERVED
        }
    }
})

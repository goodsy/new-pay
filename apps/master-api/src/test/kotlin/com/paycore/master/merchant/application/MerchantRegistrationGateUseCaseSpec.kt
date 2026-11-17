package com.paycore.master.merchant.application

import com.paycore.master.MasterDbTest
import com.paycore.master.merchant.application.OnboardingFixture.host
import com.paycore.master.merchant.application.OnboardingFixture.request
import com.paycore.master.merchant.application.OnboardingFixture.runtime
import com.paycore.master.merchant.application.OnboardingFixture.seedRequest
import com.paycore.master.merchant.application.OnboardingFixture.succeeded
import com.paycore.master.merchant.application.OnboardingFixture.t0
import com.paycore.master.merchant.application.provided.AddServiceAgreementCommand
import com.paycore.master.merchant.application.provided.AdminCommandResult
import com.paycore.master.merchant.application.provided.ApproveContractRevisionCommand
import com.paycore.master.merchant.application.provided.BusinessProfileCommandUseCase
import com.paycore.master.merchant.application.provided.IssueFirstDefaultMerchantAccountCommand
import com.paycore.master.merchant.application.provided.MerchantOnboardingUseCase
import com.paycore.master.merchant.application.provided.RegisterContractCommand
import com.paycore.master.merchant.application.provided.RegisterMerchantCommand
import com.paycore.master.merchant.application.provided.RegisterMerchantSiteCommand
import com.paycore.master.merchant.application.provided.RegisterPaymentMethodActivationCommand
import com.paycore.master.merchant.application.provided.ScopeInput
import com.paycore.master.merchant.domain.BusinessFixtures
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.BusinessProfile
import com.paycore.master.merchant.domain.BusinessRegistrationStatus
import com.paycore.master.merchant.domain.ChannelType
import com.paycore.master.merchant.domain.ContractId
import com.paycore.master.merchant.domain.ContractServiceAgreementId
import com.paycore.master.merchant.domain.DisplayName
import com.paycore.master.merchant.domain.EffectivePeriod
import com.paycore.master.merchant.domain.MasterRejection
import com.paycore.master.merchant.domain.MerchantAccountId
import com.paycore.master.merchant.domain.MerchantCodeFixtures
import com.paycore.master.merchant.domain.MerchantId
import com.paycore.master.merchant.domain.MerchantSiteId
import com.paycore.master.merchant.domain.PaymentMethod
import com.paycore.master.merchant.domain.ServiceKind
import com.paycore.master.scalar
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.testcontainers.mysql.MySQLContainer

/**
 * BUSINESS-PROFILE-001 BIZ-4-1(C-27 전환, 실제 MySQL — Use Case 수준): 새 가맹점 등록은 REGISTERED 사업자만(D-BIZ-001 — `BUSINESS_NOT_REGISTERED`, 저장된 거절·재생),
 * Use Case 판정 순서(§4.4·E-38 — 리뷰 MINOR-2 (a) MC-1 동작 유지): 코드 예약어 `MERCHANT_CODE_RESERVED`(MC-1 §3.3 — `requireAssignable`, 사업자 잠금 전) → `BUSINESS_NOT_FOUND` →
 * `BUSINESS_NOT_ACTIVE` → `BUSINESS_NOT_REGISTERED` → 코드 중복(`MERCHANT_CODE_TAKEN`).
 * **D-BIZ-001 "기존 가맹점·계약·MID 무영향" 결정 고정**: 이미 가맹점이 있는 DRAFT 사업자(V15 백필 모양 — 구분 NULL·DRAFT)도 Site·계약·승인·CSA·첫 MID·개통 등록이 그대로 성공한다.
 */
@MasterDbTest
class MerchantRegistrationGateUseCaseSpec(
    private val onboarding: MerchantOnboardingUseCase,
    private val businesses: BusinessProfileCommandUseCase,
    private val container: MySQLContainer,
) : BehaviorSpec({

    fun count(sql: String, vararg args: Any?): Long = runtime(container).use { it.scalar(sql, *args).toString().toLong() }

    fun draft(): BusinessId =
        BusinessCommandFixture.withoutMerchants(businesses, name = "임시저장 사업자", status = BusinessRegistrationStatus.DRAFT, profile = BusinessProfile.EMPTY).businessId

    fun registerMerchant(business: BusinessId, code: String = MerchantCodeFixtures.unique().value, key: com.paycore.master.merchant.application.provided.AdminRequest = request()) =
        onboarding.registerMerchant(RegisterMerchantCommand(key, business, DisplayName("시험 가맹점"), com.paycore.master.merchant.domain.MerchantCode(code)))

    /**
     * 가맹점이 이미 있는 사업자를 V15 백필 모양(DRAFT·구분 NULL — D-BIZ-001 "기존 사업자 → DRAFT")으로 되돌린다. 전환 뒤에는 DRAFT 사업자에 새 가맹점을 만들 공식 경로가 없으므로
     * migration이 남긴 기존 데이터와 같은 행 모양을 runtime 계정 UPDATE로 재현한다(운영 데이터 형태 재현 — 업무 규칙 우회 아님).
     */
    fun backfillAsDraft(business: BusinessId) = runtime(container).use { c ->
        c.prepareStatement("UPDATE business SET registration_status = 'DRAFT', business_type = NULL WHERE business_id = ?").use { ps ->
            ps.setString(1, business.value)
            ps.executeUpdate() shouldBe 1
        }
    }

    Given("BIZ-4-1 new merchant registration after the C-27 transition (D-BIZ-001)") {
        Then("a DRAFT business is a stored BUSINESS_NOT_REGISTERED (no reference, no merchant row); the same key replays it") {
            val business = draft()
            val key = request()
            val code = MerchantCodeFixtures.unique().value
            registerMerchant(business, code, key) shouldBe AdminCommandResult.Rejected(MasterRejection.BUSINESS_NOT_REGISTERED, null, false)
            registerMerchant(business, code, key) shouldBe AdminCommandResult.Rejected(MasterRejection.BUSINESS_NOT_REGISTERED, null, true)
            count("SELECT COUNT(*) FROM merchant WHERE business_id = ?", business.value) shouldBe 0L
            count("SELECT COUNT(*) FROM merchant_code_assignment WHERE merchant_code = ?", code) shouldBe 0L
        }

        Then("a REGISTERED business registers the merchant (control); an unknown business is BUSINESS_NOT_FOUND") {
            val registered = BusinessCommandFixture.withoutMerchants(businesses, name = "등록 완료 사업자").businessId
            registerMerchant(registered).shouldBeInstanceOf<AdminCommandResult.Succeeded>()
            registerMerchant(BusinessId("biz_01JC0000000000000000000000")).shouldBeInstanceOf<AdminCommandResult.Rejected>().rejection shouldBe
                MasterRejection.BUSINESS_NOT_FOUND
        }

        Then("§4.4 order: a DRAFT business with an already taken code is BUSINESS_NOT_REGISTERED (registration status before the code duplicate check)") {
            val registered = BusinessCommandFixture.withoutMerchants(businesses, name = "코드 소유 사업자").businessId
            val taken = MerchantCodeFixtures.unique().value
            registerMerchant(registered, taken).shouldBeInstanceOf<AdminCommandResult.Succeeded>()
            registerMerchant(draft(), taken).shouldBeInstanceOf<AdminCommandResult.Rejected>().rejection shouldBe MasterRejection.BUSINESS_NOT_REGISTERED
            // 대조: REGISTERED 사업자의 같은 코드는 기존 MERCHANT_CODE_TAKEN.
            val other = BusinessCommandFixture.withoutMerchants(businesses, name = "다른 등록 사업자").businessId
            registerMerchant(other, taken).shouldBeInstanceOf<AdminCommandResult.Rejected>().rejection shouldBe MasterRejection.MERCHANT_CODE_TAKEN
        }

        Then("§4.4·E-38 order (review MINOR-2 (a) — MC-1 kept): a DRAFT business with a reserved code is MERCHANT_CODE_RESERVED (reserved check before the business lock); no merchant row") {
            val business = draft()
            registerMerchant(business, "adminshop").shouldBeInstanceOf<AdminCommandResult.Rejected>().rejection shouldBe MasterRejection.MERCHANT_CODE_RESERVED
            count("SELECT COUNT(*) FROM merchant WHERE business_id = ?", business.value) shouldBe 0L
            // 대조: 같은 DRAFT 사업자에 예약어가 아닌 코드는 BUSINESS_NOT_REGISTERED.
            registerMerchant(business).shouldBeInstanceOf<AdminCommandResult.Rejected>().rejection shouldBe MasterRejection.BUSINESS_NOT_REGISTERED
        }
    }

    Given("D-BIZ-001 결정 고정 — existing merchants of a DRAFT business are unaffected (Site·contract·approval·CSA·first MID·activation)") {
        Then("every follow-up command of the existing merchant succeeds while a new merchant for the same business is BUSINESS_NOT_REGISTERED") {
            val business = BusinessCommandFixture.withoutMerchants(businesses, name = "기존 가맹점 사업자").businessId
            val merchant = MerchantId(succeeded(registerMerchant(business)))
            backfillAsDraft(business)
            count("SELECT COUNT(*) FROM business WHERE business_id = ? AND registration_status = 'DRAFT' AND business_type IS NULL", business.value) shouldBe 1L

            val site = MerchantSiteId(succeeded(onboarding.registerMerchantSite(RegisterMerchantSiteCommand(request(), merchant, ChannelType.WEB, host()))))
            val contract = ContractId(succeeded(onboarding.registerContract(RegisterContractCommand(request(), business, ScopeInput(merchant), EffectivePeriod(t0)))))
            succeeded(onboarding.approveContractRevision(ApproveContractRevisionCommand(seedRequest(), contract, 1)))
            val agreement = ContractServiceAgreementId(succeeded(onboarding.addServiceAgreement(AddServiceAgreementCommand(request(), contract, ServiceKind.CARD_PG_PAYMENT))))
            val mid = MerchantAccountId(
                succeeded(onboarding.issueFirstDefaultMerchantAccount(IssueFirstDefaultMerchantAccountCommand(seedRequest(), merchant, agreement, contract, 1, site))),
            )
            succeeded(onboarding.registerPaymentMethodActivation(RegisterPaymentMethodActivationCommand(request(), mid, PaymentMethod.CARD)))

            registerMerchant(business).shouldBeInstanceOf<AdminCommandResult.Rejected>().rejection shouldBe MasterRejection.BUSINESS_NOT_REGISTERED
            count("SELECT COUNT(*) FROM merchant WHERE business_id = ?", business.value) shouldBe 1L
        }

        Then("BusinessFixtures sanity: the Use Case fixture creates REGISTERED businesses with the individual profile (D-BIZ-001 근거)") {
            val created = BusinessCommandFixture.withoutMerchants(businesses, number = BusinessFixtures.registrationNumber())
            count("SELECT COUNT(*) FROM business WHERE business_id = ? AND registration_status = 'REGISTERED'", created.businessId.value) shouldBe 1L
        }
    }
})

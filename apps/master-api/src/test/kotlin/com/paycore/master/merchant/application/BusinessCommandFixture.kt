package com.paycore.master.merchant.application

import com.paycore.master.merchant.application.OnboardingFixture.request
import com.paycore.master.merchant.application.OnboardingFixture.succeeded
import com.paycore.master.merchant.application.provided.BusinessProfileCommandUseCase
import com.paycore.master.merchant.application.provided.CreateBusinessCommand
import com.paycore.master.merchant.application.provided.MerchantOnboardingUseCase
import com.paycore.master.merchant.application.provided.RegisterMerchantCommand
import com.paycore.master.merchant.domain.BusinessFixtures
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.BusinessProfile
import com.paycore.master.merchant.domain.BusinessRegistrationNumber
import com.paycore.master.merchant.domain.BusinessRegistrationStatus
import com.paycore.master.merchant.domain.BusinessType
import com.paycore.master.merchant.domain.DisplayName
import com.paycore.master.merchant.domain.MerchantCodeFixtures
import com.paycore.master.merchant.domain.MerchantId

/**
 * BUSINESS-PROFILE-001 BIZ-2 조회 시험의 Named Fixture(§14 — Test Fixture Policy, src/test 전용). 공식 Use Case로만 만든다(규칙 우회 없음):
 * - [withoutMerchants]: 가맹점 0개 사업자(D-BIZ-004 — 가맹점 검색에는 나오지 않고 사업자 목록·번호 조회에는 나온다).
 * - [withMerchant]: 가맹점 1개가 등록된 사업자(가맹점 수 대조군). 새 가맹점 등록의 REGISTERED 강제는 BIZ-4 전환이므로 여기서는 REGISTERED 사업자로 만든다(D-BIZ-001 근거).
 * 독립 random은 사업자등록번호뿐(BusinessFixtures — SecureRandom), 상태·내용은 이름이 드러내는 고정값이다.
 */
object BusinessCommandFixture {

    data class Created(val businessId: BusinessId, val number: BusinessRegistrationNumber, val name: DisplayName)

    fun withoutMerchants(
        businesses: BusinessProfileCommandUseCase,
        number: BusinessRegistrationNumber = BusinessFixtures.registrationNumber(),
        name: String = "가맹점 없는 사업자",
        status: BusinessRegistrationStatus = BusinessRegistrationStatus.REGISTERED,
        type: BusinessType = BusinessType.INDIVIDUAL,
        profile: BusinessProfile = if (status == BusinessRegistrationStatus.DRAFT) BusinessProfile.EMPTY else BusinessFixtures.individualProfile(),
    ): Created {
        val id = succeeded(businesses.create(CreateBusinessCommand(request(), status, type, number, DisplayName(name), profile)))
        return Created(BusinessId(id), number, DisplayName(name))
    }

    fun withMerchant(
        businesses: BusinessProfileCommandUseCase,
        onboarding: MerchantOnboardingUseCase,
        number: BusinessRegistrationNumber = BusinessFixtures.registrationNumber(),
        name: String = "가맹점 있는 사업자",
    ): Pair<Created, MerchantId> {
        val created = withoutMerchants(businesses, number, name)
        val merchant = succeeded(onboarding.registerMerchant(RegisterMerchantCommand(request(), created.businessId, DisplayName("시험 가맹점"), MerchantCodeFixtures.unique())))
        return created to MerchantId(merchant)
    }
}

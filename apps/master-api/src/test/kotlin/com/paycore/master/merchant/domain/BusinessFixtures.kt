package com.paycore.master.merchant.domain

import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate

/**
 * BUSINESS-PROFILE-001 Named Test Fixture(Test Fixture Policy — Domain Factory → Named Fixture, src/test 전용). 정상 Fixture는 공식 생성 경로
 * ([Business.createDraft]·[Business.createRegistered], migration 행은 [Business.reconstitute])로만 만들고 §4.3 불변식을 만족한다(규칙 우회 없음). 업무 조건(개인/법인, DRAFT/REGISTERED,
 * migration 행 = 구분 NULL)이 이름에 드러난다. 독립 random은 사업자등록번호·ID뿐이다(관련 금액·상태 없음). 모든 값은 시험 값이며 실제 사업자·개인이 아니다.
 */
object BusinessFixtures {
    private val random = SecureRandom()

    val t0: Instant = Instant.parse("2026-11-17T01:00:00.000Z")
    val operator = ActorRef("admin:adm_01JCBZ0000000000000000OP01")

    // 명세 §9 캔어리(대조 값) — 원문이 이력·지문·Outbox·로그에 나오지 않음을 시험한다.
    const val CANARY_NUMBER = "7319024865"
    const val CANARY_REPRESENTATIVE = "캔어리대표"
    const val CANARY_EMAIL = "canary.person@example.test"
    const val CANARY_PHONE = "01073190248"
    const val CANARY_DETAIL_ADDRESS = "캔어리동 101호"

    fun registrationNumber() = BusinessRegistrationNumber((1..10).map { random.nextInt(10) }.joinToString(""))

    fun registration(
        corporate: String? = null,
        openingDate: LocalDate? = LocalDate.parse("2026-01-01"),
        phone: String? = "0212345678",
        email: String? = "office@example.test",
        englishName: String? = null,
    ) = BusinessRegistration(corporate?.let(::CorporateRegistrationNumber), openingDate, BusinessRegistration.KOREA, englishName?.let(::EnglishBusinessName),
        phone?.let(::PhoneNumber), email?.let(::EmailAddress))

    fun sole(name: String = "홍길동") = BusinessRepresentative(PersonName(name), RepresentativeType.SOLE)
    fun joint(name: String) = BusinessRepresentative(PersonName(name), RepresentativeType.JOINT)
    fun businessType(name: String = "서비스업") = BusinessCategory(BusinessCategoryType.BUSINESS_TYPE, CategoryName(name))
    fun businessItem(name: String = "전자상거래") = BusinessCategory(BusinessCategoryType.BUSINESS_ITEM, CategoryName(name))
    fun registeredAddress(detail: String? = "101호") =
        BusinessAddress(BusinessAddressType.REGISTERED, PostalCode("06236"), AddressLine("서울특별시 강남구 테헤란로 1"), detail?.let(::AddressLine))
    fun actualAddress(detail: String? = "2층") =
        BusinessAddress(BusinessAddressType.ACTUAL, PostalCode("04524"), AddressLine("서울특별시 중구 세종대로 2"), detail?.let(::AddressLine))
    fun contact(name: String = "김담당", primary: Boolean = true, email: String = "contact@example.test", phone: String = "01012345678") =
        BusinessContact(PersonName(name), EmailAddress(email), PhoneNumber(phone), primary)

    /** 개인 사업자 REGISTERED 필수값을 모두 채운 내용(법인등록번호 없음). */
    fun individualProfile() = BusinessProfile(
        registration(), listOf(sole()), listOf(businessType(), businessItem()), listOf(registeredAddress()), listOf(contact()),
    )

    /** 법인 사업자 REGISTERED 필수값(법인등록번호 포함, 공동대표 2명 — TC-08). */
    fun corporationProfile() = BusinessProfile(
        registration(corporate = "1101110000001", englishName = "Demo Corp"),
        listOf(joint("이공동"), joint("박공동")),
        listOf(businessType("도소매"), businessType("서비스"), businessItem("소프트웨어"), businessItem("전자상거래")),
        listOf(registeredAddress(), actualAddress()),
        listOf(contact(), contact("최보조", primary = false, email = "sub@example.test", phone = "0298765432")),
    )

    /** 캔어리 값이 들어간 개인 REGISTERED 내용(비노출 시험 대조값). */
    fun canaryProfile() = BusinessProfile(
        registration(phone = CANARY_PHONE, email = CANARY_EMAIL),
        listOf(sole(CANARY_REPRESENTATIVE)),
        listOf(businessType(), businessItem()),
        listOf(registeredAddress(detail = CANARY_DETAIL_ADDRESS)),
        listOf(contact(name = CANARY_REPRESENTATIVE, email = CANARY_EMAIL, phone = CANARY_PHONE)),
    )

    fun individualRegistered(number: BusinessRegistrationNumber = registrationNumber(), name: String = "개인 상호"): BusinessProfileEdit =
        Business.createRegistered(MasterFixture.businessId(), BusinessType.INDIVIDUAL, number, DisplayName(name), individualProfile(), t0, operator)

    fun corporationRegistered(number: BusinessRegistrationNumber = registrationNumber()): BusinessProfileEdit =
        Business.createRegistered(MasterFixture.businessId(), BusinessType.CORPORATION, number, DisplayName("법인 상호"), corporationProfile(), t0, operator)

    /** D-BIZ-002 DRAFT 최소(구분 + 번호 + 상호만, 하위 정보 없음). */
    fun draftMinimal(type: BusinessType = BusinessType.INDIVIDUAL, number: BusinessRegistrationNumber = registrationNumber()): BusinessProfileEdit =
        Business.createDraft(MasterFixture.businessId(), type, number, DisplayName("임시 상호"), BusinessProfile.EMPTY, t0, operator)

    /**
     * migration·과도기 옛 경로 행(D-BIZ-001·X-1·X-10): DRAFT·구분 NULL·하위 정보 없음. 옛 생성 경로(`Business.register`)는 C-27 전환에서 제거됐으므로 V15 백필 행과 같은 값의
     * 저장 행 복원으로 만든다(생성·갱신 = 같은 시각·행위자, version 0 — V15 문장 (2)와 같은 모양).
     */
    fun legacyDraft(number: BusinessRegistrationNumber = registrationNumber()): Business = Business.reconstitute(
        MasterFixture.businessId(), number, DisplayName("옛 사업자"), null, BusinessRegistrationStatus.DRAFT, BusinessVerificationStatus.NOT_CHECKED, Lifecycle.ACTIVE,
        t0, operator, t0, operator, 0,
    )
}

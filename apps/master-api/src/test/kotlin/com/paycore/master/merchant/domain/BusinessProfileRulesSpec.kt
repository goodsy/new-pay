package com.paycore.master.merchant.domain

import com.paycore.master.merchant.domain.BusinessFieldErrorCode.ACTUAL_SAME_AS_REGISTERED
import com.paycore.master.merchant.domain.BusinessFieldErrorCode.BUSINESS_TYPE_ITEM_REQUIRED
import com.paycore.master.merchant.domain.BusinessFieldErrorCode.DUPLICATE
import com.paycore.master.merchant.domain.BusinessFieldErrorCode.FORMAT
import com.paycore.master.merchant.domain.BusinessFieldErrorCode.NOT_ALLOWED
import com.paycore.master.merchant.domain.BusinessFieldErrorCode.PRIMARY_MULTIPLE
import com.paycore.master.merchant.domain.BusinessFieldErrorCode.PRIMARY_REQUIRED
import com.paycore.master.merchant.domain.BusinessFieldErrorCode.REQUIRED
import com.paycore.master.merchant.domain.BusinessFieldErrorCode.SOLE_WITH_OTHERS
import com.paycore.master.merchant.domain.BusinessFieldErrorCode.TOO_LONG
import com.paycore.master.merchant.domain.BusinessFieldErrorCode.TOO_MANY
import com.paycore.master.merchant.domain.BusinessFixtures.actualAddress
import com.paycore.master.merchant.domain.BusinessFixtures.businessItem
import com.paycore.master.merchant.domain.BusinessFixtures.businessType
import com.paycore.master.merchant.domain.BusinessFixtures.contact
import com.paycore.master.merchant.domain.BusinessFixtures.individualProfile
import com.paycore.master.merchant.domain.BusinessFixtures.joint
import com.paycore.master.merchant.domain.BusinessFixtures.registeredAddress
import com.paycore.master.merchant.domain.BusinessFixtures.registration
import com.paycore.master.merchant.domain.BusinessFixtures.sole
import com.paycore.master.merchant.domain.BusinessRegistrationStatus.DRAFT
import com.paycore.master.merchant.domain.BusinessRegistrationStatus.REGISTERED
import com.paycore.master.merchant.domain.BusinessType.CORPORATION
import com.paycore.master.merchant.domain.BusinessType.INDIVIDUAL
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

/**
 * BUSINESS-PROFILE-001 BIZ-1-1(명세 §4.2·§4.3·§4.6, TC-01~05·09·11): 값 경계(±1)·행/Aggregate 규칙·DRAFT/REGISTERED 필수 표, 필드 오류 path·code 결정적 순서·값 미포함·최대 50.
 * 변이 (c)(REGISTERED primary 검사 제거) → "REGISTERED requires exactly one primary contact"가 RED.
 */
class BusinessProfileRulesSpec : BehaviorSpec({

    val number = BusinessRegistrationNumber("1234567890")
    val name = DisplayName("상호")

    fun errors(type: BusinessType?, profile: BusinessProfile, target: BusinessRegistrationStatus, n: BusinessRegistrationNumber? = number, d: DisplayName? = name) =
        BusinessProfileRules.validate(type, n, d, profile, target).map { it.path to it.code }

    Given("§4.2 value boundaries (no normalization, no implicit trim — values are rejected as entered)") {
        Then("names, category names, address lines and reasons accept exactly up to their code-point limit and reject one more") {
            mapOf(
                "PersonName" to (50 to PersonName.Companion::errorOf),
                "CategoryName" to (50 to CategoryName.Companion::errorOf),
                "AddressLine" to (200 to AddressLine.Companion::errorOf),
                "BusinessChangeReason" to (200 to BusinessChangeReason.Companion::errorOf),
                "EnglishBusinessName" to (100 to EnglishBusinessName.Companion::errorOf),
            ).mapValues { (_, spec) ->
                val (max, check) = spec
                listOf(check("a"), check("a".repeat(max)), check("a".repeat(max + 1)), check(""))
            } shouldBe mapOf(
                "PersonName" to listOf(null, null, TOO_LONG, REQUIRED),
                "CategoryName" to listOf(null, null, TOO_LONG, REQUIRED),
                "AddressLine" to listOf(null, null, TOO_LONG, REQUIRED),
                "BusinessChangeReason" to listOf(null, null, TOO_LONG, REQUIRED),
                "EnglishBusinessName" to listOf(null, null, TOO_LONG, REQUIRED),
            )
        }

        Then("Korean text counts code points (50 Hangul syllables and 50 supplementary characters fit; 51 do not)") {
            listOf(PersonName.errorOf("가".repeat(50)), PersonName.errorOf("가".repeat(51)), PersonName.errorOf("😀".repeat(50)), PersonName.errorOf("😀".repeat(51))) shouldBe
                listOf(null, TOO_LONG, null, TOO_LONG)
        }

        Then("leading/trailing spaces, control characters (incl. newline) and unpaired surrogates are FORMAT for every text value") {
            val bad = listOf(" 홍길동", "홍길동 ", "홍\n길동", "홍\t길동", "홍\u0000", "홍\uD800", "\uDC00홍")
            mapOf(
                "PersonName" to bad.map(PersonName.Companion::errorOf), "CategoryName" to bad.map(CategoryName.Companion::errorOf),
                "AddressLine" to bad.map(AddressLine.Companion::errorOf), "BusinessChangeReason" to bad.map(BusinessChangeReason.Companion::errorOf),
            ) shouldBe listOf("PersonName", "CategoryName", "AddressLine", "BusinessChangeReason").associateWith { bad.map { FORMAT } }
            shouldThrow<IllegalArgumentException> { PersonName(" 홍길동") }
            PersonName("홍 길동").value shouldBe "홍 길동"
        }

        Then("English business name allows printable ASCII only (0x20..0x7E), no leading/trailing space") {
            listOf("Demo Corp.", "A-B & C (1)", "~", "데모", "Demo\u007F", " Demo", "Demo ").map(EnglishBusinessName.Companion::errorOf) shouldBe
                listOf(null, null, null, FORMAT, FORMAT, FORMAT, FORMAT)
        }

        Then("corporate registration number = 13 ASCII digits (12, 14, hyphen, full-width rejected); masked to the first 6 digits") {
            listOf("1101110000001", "110111000000", "11011100000012", "110111-0000001", "１１０１１１０００００００１", "").map(CorporateRegistrationNumber.Companion::errorOf) shouldBe
                listOf(null, FORMAT, FORMAT, FORMAT, FORMAT, REQUIRED)
            CorporateRegistrationNumber("1101110000001").let { listOf(it.masked(), it.toString()) } shouldBe listOf("110111*******", "CorporateRegistrationNumber(110111*******)")
        }

        Then("postal code = 5 ASCII digits") {
            listOf("06236", "0623", "062361", "0623a", "").map(PostalCode.Companion::errorOf) shouldBe listOf(null, FORMAT, FORMAT, FORMAT, REQUIRED)
        }

        Then("phone = 8..12 ASCII digits starting with 0 or 1 (hyphen only for display)") {
            listOf("15881234", "1588123", "021234567890", "0212345678901", "212345678", "02-1234-5678", "").map(PhoneNumber.Companion::errorOf) shouldBe
                listOf(null, FORMAT, null, TOO_LONG, FORMAT, FORMAT, REQUIRED)
            PhoneNumber("01012345678").toString() shouldNotContain "0101234"
        }

        Then("email = local@domain (local 1..64 of [A-Za-z0-9._%+-], dotted labels, last label >= 2), case preserved, <= 254 chars") {
            val longLocal = "a".repeat(64) + "@example.test"
            listOf(
                "a@b.co", "A.B+c%d-e_f@Mail-1.Example.TEST", longLocal, "a".repeat(65) + "@example.test", "ab@example", "a@@example.test",
                "a@example.t", "a@-.test".replace("-", ""), "a b@example.test", "a@" + "b".repeat(64) + ".test", "a@" + "b.".repeat(130) + "test", "",
            ).map(EmailAddress.Companion::errorOf) shouldBe
                listOf(null, null, null, FORMAT, FORMAT, FORMAT, FORMAT, FORMAT, FORMAT, FORMAT, TOO_LONG, REQUIRED)
            EmailAddress("A.B@Example.TEST").value shouldBe "A.B@Example.TEST"
        }

        Then("value objects that hold personal data never print the raw value") {
            listOf(PersonName("홍길동").toString(), EmailAddress("canary.person@example.test").toString(), AddressLine("캔어리동 101호").toString())
                .joinToString() shouldNotContain "길동"
            EmailAddress("canary.person@example.test").toString() shouldNotContain "canary.person"
            AddressLine("캔어리동 101호").toString() shouldNotContain "캔어리"
        }
    }

    Given("§4.3 DRAFT minimum (D-BIZ-002: business type + registration number + display name) and REGISTERED required fields") {
        Then("TC-04 a DRAFT with only type, number and name is valid; each missing one is REQUIRED") {
            errors(INDIVIDUAL, BusinessProfile.EMPTY, DRAFT).shouldBeEmpty()
            errors(null, BusinessProfile.EMPTY, DRAFT, n = null, d = null) shouldBe
                listOf("businessType" to REQUIRED, "displayName" to REQUIRED, "registrationNumber" to REQUIRED)
        }

        Then("TC-01 / TC-02 complete individual and corporation profiles are valid as REGISTERED") {
            errors(INDIVIDUAL, individualProfile(), REGISTERED).shouldBeEmpty()
            errors(CORPORATION, BusinessFixtures.corporationProfile(), REGISTERED).shouldBeEmpty()
        }

        Then("TC-05 an empty profile as REGISTERED reports every required field (path order, no values)") {
            errors(INDIVIDUAL, BusinessProfile.EMPTY, REGISTERED) shouldBe listOf(
                "addresses" to REQUIRED, "categories" to BUSINESS_TYPE_ITEM_REQUIRED, "contacts" to REQUIRED,
                "registration.openingDate" to REQUIRED, "registration.phoneNumber" to REQUIRED, "representatives" to REQUIRED,
            )
        }

        Then("TC-03 a REGISTERED corporation needs its corporate registration number; an individual may not have one (DRAFT too)") {
            val noCorporate = BusinessFixtures.corporationProfile().let { it.copy(registration = it.registration.copy(corporateRegistrationNumber = null)) }
            errors(CORPORATION, noCorporate, REGISTERED) shouldBe listOf("registration.corporateRegistrationNumber" to REQUIRED)
            errors(CORPORATION, noCorporate, DRAFT).shouldBeEmpty()
            val individualWithCorporate = individualProfile().copy(registration = registration(corporate = "1101110000001"))
            errors(INDIVIDUAL, individualWithCorporate, DRAFT) shouldBe listOf("registration.corporateRegistrationNumber" to NOT_ALLOWED)
        }

        Then("TC-09 / TC-11 REGISTERED without representatives or contacts fails; DRAFT may leave lists empty") {
            errors(INDIVIDUAL, individualProfile().copy(representatives = emptyList()), REGISTERED) shouldBe listOf("representatives" to REQUIRED)
            errors(INDIVIDUAL, individualProfile().copy(contacts = emptyList()), REGISTERED) shouldBe listOf("contacts" to REQUIRED)
            errors(INDIVIDUAL, individualProfile().copy(representatives = emptyList(), contacts = emptyList()), DRAFT).shouldBeEmpty()
        }

        Then("REGISTERED requires exactly one primary contact (mutation (c)); more than one primary is rejected in every state") {
            errors(INDIVIDUAL, individualProfile().copy(contacts = listOf(contact(primary = false))), REGISTERED) shouldBe listOf("contacts" to PRIMARY_REQUIRED)
            errors(INDIVIDUAL, individualProfile().copy(contacts = listOf(contact(primary = false))), DRAFT).shouldBeEmpty()
            val two = listOf(contact(primary = true), contact("이담당", primary = true, email = "b@example.test"))
            errors(INDIVIDUAL, individualProfile().copy(contacts = two), DRAFT) shouldBe listOf("contacts" to PRIMARY_MULTIPLE)
        }

        Then("REGISTERED needs at least one business type and one business item, and the REGISTERED address") {
            errors(INDIVIDUAL, individualProfile().copy(categories = listOf(businessType())), REGISTERED) shouldBe listOf("categories" to BUSINESS_TYPE_ITEM_REQUIRED)
            errors(INDIVIDUAL, individualProfile().copy(addresses = listOf(actualAddress())), REGISTERED) shouldBe listOf("addresses" to REQUIRED)
        }
    }

    Given("§4.3 aggregate rules (every state)") {
        Then("SOLE means exactly one representative; JOINT/EACH mixes are allowed (C-22)") {
            errors(INDIVIDUAL, individualProfile().copy(representatives = listOf(sole(), joint("이공동"))), DRAFT) shouldBe listOf("representatives" to SOLE_WITH_OTHERS)
            errors(INDIVIDUAL, individualProfile().copy(representatives = listOf(joint("이공동"), BusinessRepresentative(PersonName("박각자"), RepresentativeType.EACH))), DRAFT)
                .shouldBeEmpty()
        }

        Then("m-5 two representatives with the same name and type are allowed (control: the same business type name twice is DUPLICATE)") {
            errors(INDIVIDUAL, individualProfile().copy(representatives = listOf(joint("홍길동"), joint("홍길동"))), REGISTERED).shouldBeEmpty()
            errors(INDIVIDUAL, individualProfile().copy(categories = listOf(businessType("도소매"), businessItem(), businessType("도소매"))), REGISTERED) shouldBe
                listOf("categories[2].name" to DUPLICATE)
            // 같은 이름이라도 유형이 다르면(업태 "서비스" · 종목 "서비스") 중복이 아니다.
            errors(INDIVIDUAL, individualProfile().copy(categories = listOf(businessType("서비스"), businessItem("서비스"))), REGISTERED).shouldBeEmpty()
        }

        Then("one address per type, ACTUAL equal to REGISTERED is rejected, ACTUAL that differs only in the detail is accepted") {
            errors(INDIVIDUAL, individualProfile().copy(addresses = listOf(registeredAddress(), registeredAddress("202호"))), DRAFT) shouldBe listOf("addresses[1].type" to TOO_MANY)
            val same = registeredAddress().copy(type = BusinessAddressType.ACTUAL)
            errors(INDIVIDUAL, individualProfile().copy(addresses = listOf(registeredAddress(), same)), DRAFT) shouldBe listOf("addresses" to ACTUAL_SAME_AS_REGISTERED)
            errors(INDIVIDUAL, individualProfile().copy(addresses = listOf(registeredAddress(), same.copy(detailAddress = AddressLine("별관")))), DRAFT).shouldBeEmpty()
        }

        Then("list limits (C-19): representatives 5, business types 10, business items 10, addresses 2, contacts 5") {
            val six = (1..6).map { joint("공동$it") }
            errors(INDIVIDUAL, individualProfile().copy(representatives = six.take(5)), DRAFT).shouldBeEmpty()
            errors(INDIVIDUAL, individualProfile().copy(representatives = six), DRAFT) shouldBe listOf("representatives" to TOO_MANY)
            val types = (1..11).map { businessType("업태$it") }
            errors(INDIVIDUAL, individualProfile().copy(categories = types.take(10) + businessItem()), REGISTERED).shouldBeEmpty()
            errors(INDIVIDUAL, individualProfile().copy(categories = types + businessItem()), REGISTERED) shouldBe listOf("categories" to TOO_MANY)
            val contacts = (1..6).map { contact("담당$it", primary = it == 1, email = "c$it@example.test") }
            errors(INDIVIDUAL, individualProfile().copy(contacts = contacts.take(5)), REGISTERED).shouldBeEmpty()
            errors(INDIVIDUAL, individualProfile().copy(contacts = contacts), REGISTERED) shouldBe listOf("contacts" to TOO_MANY)
            errors(INDIVIDUAL, individualProfile().copy(addresses = listOf(registeredAddress(), actualAddress(), actualAddress("3층"))), DRAFT) shouldBe
                listOf("addresses" to TOO_MANY, "addresses[2].type" to TOO_MANY)
        }

        Then("country is KR only (TBD-10)") {
            errors(INDIVIDUAL, individualProfile().copy(registration = individualProfile().registration.copy(countryCode = "US")), DRAFT) shouldBe
                listOf("registration.countryCode" to NOT_ALLOWED)
        }
    }

    Given("§4.6 field error output") {
        Then("errors are sorted by path (then code), carry no input values, and are capped at 50") {
            val many = BusinessProfile(
                registration(corporate = "1101110000001").copy(countryCode = "US"),
                (1..6).map { sole("대표$it") },
                (1..11).flatMap { listOf(businessType("중복"), businessItem("중복")) },
                listOf(registeredAddress(), registeredAddress(), registeredAddress()),
                (1..6).map { contact("담당$it", primary = true, email = "d$it@example.test") },
            )
            val result = BusinessProfileRules.validate(INDIVIDUAL, number, name, many, REGISTERED)
            result.map { it.path } shouldBe result.map { it.path }.sorted()
            (result.size <= BusinessProfileRules.MAX_ERRORS) shouldBe true
            result.joinToString().let { text -> listOf("대표", "중복", "담당", "1234567890").filter { text.contains(it) } }.shouldBeEmpty()
        }

        Then("the cap keeps the first 50 in path order (deterministic)") {
            val categories = (1..60).map { businessType("같은업태") }
            val result = BusinessProfileRules.validate(INDIVIDUAL, number, name, individualProfile().copy(categories = categories), DRAFT)
            result.size shouldBe BusinessProfileRules.MAX_ERRORS
            result.first() shouldBe BusinessFieldError("categories", TOO_MANY)
            result.map { it.path } shouldBe ((1 until 60).map { "categories[$it].name" } + "categories").sorted().take(BusinessProfileRules.MAX_ERRORS)
        }

        Then("the Domain transition re-checks the same rules and throws a defect carrying only paths and codes") {
            val failure = shouldThrow<BusinessProfileRuleViolation> {
                Business.createRegistered(MasterFixture.businessId(), INDIVIDUAL, number, name, BusinessProfile.EMPTY, BusinessFixtures.t0, BusinessFixtures.operator)
            }
            failure.errors.map { it.path } shouldBe errors(INDIVIDUAL, BusinessProfile.EMPTY, REGISTERED).map { it.first }
            (failure.message ?: "") shouldNotContain "1234567890"
        }
    }
})

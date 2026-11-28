// BIZ-CRIT-APPROVAL-001 S1: 옛 1인 오버로드(@Deprecated, S2에서 제거·이 파일 호출 전환)를 아직 부른다.
@file:Suppress("DEPRECATION")

package com.paycore.master.merchant.domain

import com.paycore.master.merchant.domain.BusinessFixtures.businessItem
import com.paycore.master.merchant.domain.BusinessFixtures.businessType
import com.paycore.master.merchant.domain.BusinessFixtures.contact
import com.paycore.master.merchant.domain.BusinessFixtures.individualProfile
import com.paycore.master.merchant.domain.BusinessFixtures.joint
import com.paycore.master.merchant.domain.BusinessFixtures.operator
import com.paycore.master.merchant.domain.BusinessFixtures.registration
import com.paycore.master.merchant.domain.BusinessFixtures.sole
import com.paycore.master.merchant.domain.BusinessFixtures.t0
import com.paycore.master.merchant.domain.BusinessRegistrationStatus.DRAFT
import com.paycore.master.merchant.domain.BusinessRegistrationStatus.REGISTERED
import com.paycore.master.merchant.domain.BusinessType.CORPORATION
import com.paycore.master.merchant.domain.BusinessType.INDIVIDUAL
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.time.LocalDate

/**
 * BUSINESS-PROFILE-001 BIZ-1-13(변경 계산 표 §4.5 — TC-12·TC-13, M-2·M-4)·BIZ-1-12 Domain 부분(등록 완료 M-1·`BUSINESS_NOT_DRAFT`)·BIZ-1-10(TC-21)·§9 마스킹 형식(C-2).
 * 변이 (g)(REGISTERED 대표자 추가만 = 일반) → "REGISTERED: adding a representative only is important" RED, (h)(하위 행 짝짓기에서 순서 제외) → "order-only swap" RED.
 */
class BusinessProfileChangeSpec : BehaviorSpec({

    val name = DisplayName("상호")

    fun content(profile: BusinessProfile, type: BusinessType? = INDIVIDUAL, displayName: DisplayName = name) = BusinessContent(type, displayName, profile)

    fun change(before: BusinessProfile, after: BusinessProfile, status: BusinessRegistrationStatus = REGISTERED) =
        BusinessProfileChange.of(status, content(before), content(after))

    fun fields(c: BusinessProfileChange) = c.entries.map { it.field }

    Given("§4.5 change table — what counts as an important change") {
        Then("TC-13 display name and business type changes are important in both states; a migration row's first business type is not") {
            BusinessProfileChange.of(DRAFT, content(individualProfile()), content(individualProfile(), displayName = DisplayName("새 상호"))).important shouldBe true
            BusinessProfileChange.of(REGISTERED, content(individualProfile()), content(individualProfile(), type = CORPORATION)).important shouldBe true
            BusinessProfileChange.of(DRAFT, content(individualProfile(), type = null), content(individualProfile(), type = INDIVIDUAL)).let {
                listOf(it.important, fields(it)) shouldBe listOf(false, listOf("businessType"))
            }
        }

        Then("opening date / corporate number: first input is general, changing or clearing an existing value is important") {
            val empty = individualProfile().copy(registration = registration(openingDate = null))
            change(empty, individualProfile(), DRAFT).important shouldBe false
            change(individualProfile(), individualProfile().copy(registration = registration(openingDate = LocalDate.parse("2025-12-31"))), DRAFT).important shouldBe true
            change(individualProfile(), empty, DRAFT).important shouldBe true
            val corp = BusinessFixtures.corporationProfile()
            val corpEmpty = corp.copy(registration = corp.registration.copy(corporateRegistrationNumber = null))
            BusinessProfileChange.of(DRAFT, content(corpEmpty, CORPORATION), content(corp, CORPORATION)).important shouldBe false
            BusinessProfileChange.of(DRAFT, content(corp, CORPORATION), content(corpEmpty, CORPORATION)).important shouldBe true
        }

        Then("TC-12 general fields (categories, addresses, business phone/email, English name, contacts) are never important") {
            val after = individualProfile().copy(
                registration = registration(phone = "0299999999", email = "new@example.test", englishName = "New Name"),
                categories = listOf(businessType("새업태"), businessItem("새종목")),
                addresses = listOf(BusinessFixtures.registeredAddress("909호")),
                contacts = listOf(contact("새담당", email = "n@example.test")),
            )
            change(individualProfile(), after).let { listOf(it.important, fields(it)) } shouldBe listOf(
                false,
                listOf("registration.englishName", "registration.phoneNumber", "registration.email", "categories", "addresses", "contacts"),
            )
        }

        Then("DRAFT: representatives 0 -> 1 is general (the only exemption); keeping + adding is general; ending a row is important") {
            change(individualProfile().copy(representatives = emptyList()), individualProfile(), DRAFT).important shouldBe false
            val one = individualProfile().copy(representatives = listOf(joint("가공동")))
            change(one, one.copy(representatives = listOf(joint("가공동"), joint("나공동"))), DRAFT).important shouldBe false
            change(one, one.copy(representatives = listOf(joint("다공동"))), DRAFT).important shouldBe true
        }

        Then("M-2 REGISTERED: adding a representative only is important (mutation (g)), so is a type-only change") {
            val one = individualProfile().copy(representatives = listOf(joint("가공동")))
            change(one, one.copy(representatives = listOf(joint("가공동"), joint("나공동")))).important shouldBe true
            change(one, one.copy(representatives = listOf(BusinessRepresentative(PersonName("가공동"), RepresentativeType.EACH)))).important shouldBe true
        }

        Then("M-4 order-only swap of representatives ends both rows and adds both (important); of business types it is general but recorded") {
            val two = individualProfile().copy(representatives = listOf(joint("가공동"), joint("나공동")))
            val swapped = change(two, two.copy(representatives = listOf(joint("나공동"), joint("가공동"))))
            swapped.important shouldBe true
            (swapped.entries.single() as BusinessChangeEntry.Rows).let { listOf(it.ended.size, it.added.size) } shouldBe listOf(2, 2)

            val cats = individualProfile().copy(categories = listOf(businessType("가업태"), businessType("나업태"), businessItem()))
            val catSwap = change(cats, cats.copy(categories = listOf(businessType("나업태"), businessType("가업태"), businessItem())))
            listOf(catSwap.important, fields(catSwap), (catSwap.entries.single() as BusinessChangeEntry.Rows).ended.size) shouldBe listOf(false, listOf("categories"), 2)
        }

        Then("interleaving business types and items differently is not a change (order is per category type — DB display_order per type)") {
            val a = individualProfile().copy(categories = listOf(businessType("가업태"), businessItem("가종목"), businessType("나업태")))
            val b = individualProfile().copy(categories = listOf(businessType("가업태"), businessType("나업태"), businessItem("가종목")))
            change(a, b).entries.shouldBeEmpty()
        }

        Then("history values are masked (C-2 formats): names, phone, email, corporate number, address") {
            val before = BusinessFixtures.canaryProfile()
            val after = before.copy(
                representatives = listOf(sole("이대표")),
                registration = registration(phone = "0255556666", email = "after@example.test"),
                addresses = listOf(BusinessFixtures.registeredAddress()),
                contacts = listOf(contact()),
            )
            val text = change(before, after).entries.toString()
            listOf(BusinessFixtures.CANARY_REPRESENTATIVE, BusinessFixtures.CANARY_EMAIL, BusinessFixtures.CANARY_PHONE, BusinessFixtures.CANARY_DETAIL_ADDRESS, "캔어리")
                .filter { text.contains(it) }.shouldBeEmpty()
            text.contains("캔****") shouldBe true
        }
    }

    Given("§9 BusinessPiiMask formats (C-2)") {
        Then("name, email, phone, corporate number, address") {
            listOf(
                BusinessPiiMask.name("홍길동"), BusinessPiiMask.name("김"), BusinessPiiMask.email("abc@example.com"), BusinessPiiMask.email("a@mail.example.co.kr"),
                BusinessPiiMask.phone("01012345678"), BusinessPiiMask.corporateNumber("1101110000001"), BusinessPiiMask.baseAddress("서울특별시 강남구 테헤란로 1"),
                BusinessPiiMask.baseAddress("세종대로"), BusinessPiiMask.detailAddress("101호"),
            ) shouldBe listOf("홍**", "김*", "a***@e***.com", "a***@m***.kr", "****5678", "110111*******", "서울특별시 강남구 ***", "세종대로 ***", "***")
        }
    }

    Given("§4.4 transitions") {
        Then("TC-21 verification has NOT_CHECKED only and every created business starts there") {
            BusinessVerificationStatus.entries shouldBe listOf(BusinessVerificationStatus.NOT_CHECKED)
            listOf(BusinessFixtures.draftMinimal().business, BusinessFixtures.individualRegistered().business, BusinessFixtures.legacyDraft())
                .map { it.verificationStatus }.toSet() shouldBe setOf(BusinessVerificationStatus.NOT_CHECKED)
        }

        Then("createDraft / createRegistered / legacy register set the two status axes (lifecycle ACTIVE) and version 0") {
            listOf(BusinessFixtures.draftMinimal().business, BusinessFixtures.individualRegistered().business, BusinessFixtures.legacyDraft()).map {
                listOf(it.registrationStatus, it.businessType, it.lifecycle, it.version)
            } shouldBe listOf(
                listOf(DRAFT, INDIVIDUAL, Lifecycle.ACTIVE, 0L), listOf(REGISTERED, INDIVIDUAL, Lifecycle.ACTIVE, 0L), listOf(DRAFT, null, Lifecycle.ACTIVE, 0L),
            )
            BusinessFixtures.individualRegistered().change.entries.map { it.field }.first() shouldBe "displayName"
        }

        Then("requireRegistered rejects DRAFT with BUSINESS_NOT_REGISTERED (called from merchant registration at BIZ-4)") {
            shouldThrow<MasterRuleViolation> { BusinessFixtures.draftMinimal().business.requireRegistered() }.rejection shouldBe MasterRejection.BUSINESS_NOT_REGISTERED
            BusinessFixtures.individualRegistered().business.requireRegistered()
        }

        // BIZ-CRIT-APPROVAL-001 §7.1(D-GOV-016 ③·D-GOV-025 ④): REGISTERED 상호 변경은 2인 — 1인 경로 기대값 `BUSINESS_CHANGE_REASON_REQUIRED` → `BUSINESS_CRITICAL_CHANGE_APPROVAL_REQUIRED`.
        // version·변경 없음 순서 단언은 불변. 사유 있으면 1인 성공(옛 :154-156)은 근거 실행 성공 단언으로 이동(BusinessCriticalChangeSpec "success (moved from …)").
        Then("update: version mismatch, unchanged content and a REGISTERED important change needing approval are rejected in that order") {
            val stored = BusinessFixtures.individualRegistered()
            val b = stored.business
            val none = BusinessCriticalChangeFixtures.NO_HISTORY
            fun violation(block: () -> Unit) = shouldThrow<MasterRuleViolation>(block).rejection
            violation { b.updateProfile(stored.profile, INDIVIDUAL, DisplayName("새 상호"), stored.profile, null, 7, none, t0, operator) } shouldBe MasterRejection.BUSINESS_VERSION_CONFLICT
            violation { b.updateProfile(stored.profile, INDIVIDUAL, b.displayName, stored.profile, null, 0, none, t0, operator) } shouldBe MasterRejection.BUSINESS_PROFILE_UNCHANGED
            violation { b.updateProfile(stored.profile, INDIVIDUAL, DisplayName("새 상호"), stored.profile, null, 0, none, t0, operator) } shouldBe
                MasterRejection.BUSINESS_CRITICAL_CHANGE_APPROVAL_REQUIRED
        }

        Then("M-1 completeRegistration with the stored content succeeds (status change is the change); REGISTERED cannot complete again") {
            val draft = Business.createDraft(MasterFixture.businessId(), INDIVIDUAL, BusinessFixtures.registrationNumber(), name, individualProfile(), t0, operator)
            val done = draft.business.completeRegistration(draft.profile, INDIVIDUAL, name, individualProfile(), null, 0, t0, operator)
            listOf(done.business.registrationStatus, done.business.version, done.change.entries.map { it.field }) shouldBe listOf(REGISTERED, 1L, listOf("registrationStatus"))
            (done.change.entries.first() as BusinessChangeEntry.Value).let { listOf(it.before, it.after) } shouldBe listOf("DRAFT", "REGISTERED")
            shouldThrow<MasterRuleViolation> {
                done.business.completeRegistration(done.profile, INDIVIDUAL, name, individualProfile(), null, 1, t0, operator)
            }.rejection shouldBe MasterRejection.BUSINESS_NOT_DRAFT
        }

        Then("completeRegistration with an important change and no reason is rejected; a DRAFT missing REGISTERED fields is a rule defect") {
            val draft = Business.createDraft(MasterFixture.businessId(), INDIVIDUAL, BusinessFixtures.registrationNumber(), name, individualProfile(), t0, operator)
            shouldThrow<MasterRuleViolation> {
                draft.business.completeRegistration(draft.profile, INDIVIDUAL, DisplayName("바뀐 상호"), individualProfile(), null, 0, t0, operator)
            }.rejection shouldBe MasterRejection.BUSINESS_CHANGE_REASON_REQUIRED
            val minimal = BusinessFixtures.draftMinimal()
            shouldThrow<BusinessProfileRuleViolation> {
                minimal.business.completeRegistration(minimal.profile, INDIVIDUAL, minimal.business.displayName, BusinessProfile.EMPTY, null, 0, t0, operator)
            }
        }

        Then("X-1 a migration row (type NULL) must get a business type on its first update, which is not an important change") {
            val legacy = BusinessFixtures.legacyDraft()
            val edit = legacy.updateProfile(BusinessProfile.EMPTY, INDIVIDUAL, legacy.displayName, BusinessProfile.EMPTY, null, 0, t0, operator)
            listOf(edit.business.businessType, edit.change.important) shouldBe listOf(INDIVIDUAL, false)
        }

        Then("m-4 the transition time never goes back: a clock earlier than the stored updatedAt keeps updatedAt (child rows effective_to >= effective_from)") {
            val stored = BusinessFixtures.individualRegistered()
            val later = stored.business.updateProfile(stored.profile, INDIVIDUAL, DisplayName("나중 상호"), stored.profile, BusinessChangeReason("변경"), 0, t0.plusSeconds(60), operator)
            val earlier = later.business.updateProfile(later.profile, INDIVIDUAL, DisplayName("또 상호"), later.profile, BusinessChangeReason("변경"), 1, t0.plusSeconds(10), operator)
            listOf(later.business.updatedAt, earlier.business.updatedAt, earlier.business.version) shouldBe listOf(t0.plusSeconds(60), t0.plusSeconds(60), 2L)
            // 대조: 시계가 앞으로 가면 그 시각을 쓴다.
            earlier.business.updateProfile(earlier.profile, INDIVIDUAL, DisplayName("세번째 상호"), earlier.profile, BusinessChangeReason("변경"), 2, t0.plusSeconds(90), operator)
                .business.updatedAt shouldBe t0.plusSeconds(90)
        }

        Then("a terminated business cannot be updated (BUSINESS_NOT_ACTIVE)") {
            val closed = Business.reconstitute(
                MasterFixture.businessId(), BusinessFixtures.registrationNumber(), name, INDIVIDUAL, DRAFT, BusinessVerificationStatus.NOT_CHECKED,
                Lifecycle.TERMINATED, t0, operator, t0, operator, 0,
            )
            shouldThrow<MasterRuleViolation> { closed.updateProfile(BusinessProfile.EMPTY, INDIVIDUAL, DisplayName("x"), BusinessProfile.EMPTY, null, 0, t0, operator) }
                .rejection shouldBe MasterRejection.BUSINESS_NOT_ACTIVE
        }

        // BIZ-NORM-002 M-3(BR-BIZ-311, D-GOV-005 ②): 사용중지 사업자 수정은 저장되는 업무 거절(결함 check() 아님). 기대값은 명세 리터럴(이름 문자열 — 구현 상수와 독립).
        Then("M-3 an INACTIVE business update is a business rejection BUSINESS_INACTIVE (not a defect) before version and content checks") {
            val inactive = BusinessFixtures.inactiveRow()
            val profile = individualProfile()
            fun rejection(block: () -> Unit) = shouldThrow<MasterRuleViolation>(block).let { listOf(it.rejection.name, it.reference) }
            // 맞는 version·바뀐 내용·사유 있음 — 그래도 거절.
            rejection { inactive.updateProfile(profile, INDIVIDUAL, DisplayName("새 상호"), profile, BusinessChangeReason("상호 변경"), 3, t0, operator) } shouldBe
                listOf("BUSINESS_INACTIVE", null)
            // 판정 순서: 사용중지가 version 충돌·변경 없음보다 먼저(어느 입력이든 같은 거절 — 재생 결과가 입력에 따라 갈리지 않는다).
            rejection { inactive.updateProfile(profile, INDIVIDUAL, DisplayName("새 상호"), profile, null, 99, t0, operator) } shouldBe listOf("BUSINESS_INACTIVE", null)
            rejection { inactive.updateProfile(profile, INDIVIDUAL, inactive.displayName, profile, null, 3, t0, operator) } shouldBe listOf("BUSINESS_INACTIVE", null)
        }

        Then("M-3 completeRegistration of an INACTIVE business stays BUSINESS_NOT_DRAFT (expected value fixed — unchanged behaviour)") {
            val inactive = BusinessFixtures.inactiveRow()
            shouldThrow<MasterRuleViolation> {
                inactive.completeRegistration(individualProfile(), INDIVIDUAL, inactive.displayName, individualProfile(), null, 3, t0, operator)
            }.rejection shouldBe MasterRejection.BUSINESS_NOT_DRAFT
        }

        Then("M-3 control: a TERMINATED + INACTIVE row is still BUSINESS_NOT_ACTIVE first (lifecycle before registration status)") {
            val closed = Business.reconstitute(
                MasterFixture.businessId(), BusinessFixtures.registrationNumber(), name, INDIVIDUAL, BusinessRegistrationStatus.INACTIVE, BusinessVerificationStatus.NOT_CHECKED,
                Lifecycle.TERMINATED, t0, operator, t0, operator, 0,
            )
            shouldThrow<MasterRuleViolation> { closed.updateProfile(individualProfile(), INDIVIDUAL, DisplayName("x"), individualProfile(), null, 0, t0, operator) }
                .rejection shouldBe MasterRejection.BUSINESS_NOT_ACTIVE
        }

        Then("Business.toString shows the masked registration number only") {
            BusinessFixtures.individualRegistered(BusinessRegistrationNumber(BusinessFixtures.CANARY_NUMBER)).business.toString() shouldNotContain "9024865"
        }
    }
})

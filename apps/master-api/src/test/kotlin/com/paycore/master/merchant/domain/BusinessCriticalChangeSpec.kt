package com.paycore.master.merchant.domain

import com.paycore.master.merchant.domain.BusinessChangeApprovalLevel.BLOCKED
import com.paycore.master.merchant.domain.BusinessChangeApprovalLevel.DUAL
import com.paycore.master.merchant.domain.BusinessChangeApprovalLevel.DUAL_CORRECTION
import com.paycore.master.merchant.domain.BusinessChangeApprovalLevel.SINGLE
import com.paycore.master.merchant.domain.BusinessChangeApprovalLevel.SINGLE_WITH_REASON
import com.paycore.master.merchant.domain.BusinessCriticalChangeFixtures.APPROVER
import com.paycore.master.merchant.domain.BusinessCriticalChangeFixtures.CONTRACT_AND_MERCHANT
import com.paycore.master.merchant.domain.BusinessCriticalChangeFixtures.CONTRACT_ONLY
import com.paycore.master.merchant.domain.BusinessCriticalChangeFixtures.EVIDENCE
import com.paycore.master.merchant.domain.BusinessCriticalChangeFixtures.MERCHANT_ONLY
import com.paycore.master.merchant.domain.BusinessCriticalChangeFixtures.OTHER_OPERATOR
import com.paycore.master.merchant.domain.BusinessCriticalChangeFixtures.REQUESTER
import com.paycore.master.merchant.domain.BusinessCriticalChangeFixtures.StoredBusiness
import com.paycore.master.merchant.domain.BusinessCriticalChangeFixtures.aCriticalChangeProposal
import com.paycore.master.merchant.domain.BusinessCriticalChangeFixtures.aDraftBusinessWithContractAndMerchant
import com.paycore.master.merchant.domain.BusinessCriticalChangeFixtures.aDraftBusinessWithContractHistory
import com.paycore.master.merchant.domain.BusinessCriticalChangeFixtures.aDraftBusinessWithMerchantOnly
import com.paycore.master.merchant.domain.BusinessCriticalChangeFixtures.aDraftBusinessWithoutHistory
import com.paycore.master.merchant.domain.BusinessCriticalChangeFixtures.aRegisteredBusinessWithContractAndMerchant
import com.paycore.master.merchant.domain.BusinessCriticalChangeFixtures.aRegisteredBusinessWithEndedMerchantOnly
import com.paycore.master.merchant.domain.BusinessCriticalChangeFixtures.aRegisteredBusinessWithIntakeOnly
import com.paycore.master.merchant.domain.BusinessCriticalChangeFixtures.aRegisteredBusinessWithTerminatedContract
import com.paycore.master.merchant.domain.BusinessCriticalChangeFixtures.aRegisteredBusinessWithoutHistory
import com.paycore.master.merchant.domain.BusinessCriticalChangeFixtures.approvedFor
import com.paycore.master.merchant.domain.BusinessCriticalChangeFixtures.asStored
import com.paycore.master.merchant.domain.BusinessCriticalChangeFixtures.corporateNumberChange
import com.paycore.master.merchant.domain.BusinessCriticalChangeFixtures.generalChange
import com.paycore.master.merchant.domain.BusinessCriticalChangeFixtures.proposalId
import com.paycore.master.merchant.domain.BusinessCriticalChangeFixtures.representativeChange
import com.paycore.master.merchant.domain.BusinessCriticalChangeFixtures.testDigest
import com.paycore.master.merchant.domain.BusinessFixtures.t0
import com.paycore.master.merchant.domain.BusinessRegistrationStatus.DRAFT
import com.paycore.master.merchant.domain.BusinessRegistrationStatus.INACTIVE
import com.paycore.master.merchant.domain.BusinessRegistrationStatus.REGISTERED
import com.paycore.master.merchant.domain.BusinessType.CORPORATION
import com.paycore.master.merchant.domain.BusinessType.INDIVIDUAL
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * BIZ-CRIT-APPROVAL-001 S1(MASTER Domain — 명세 §1.1 인원 판정표·§3.3·§3.4·§3.6·§7.2 BusinessCriticalChangeSpec). 기대값은 명세 리터럴(거절 이름 문자열 — 구현 상수와 독립).
 * 열: 일반정보만(사업체 전화번호) / 중요 — 상호·대표자(상호) / 기존 값 있는 구분·법인등록번호(법인등록번호 변경). 행: 등록 상태 × 계약·가맹점 이력(없음·계약만·가맹점만·둘 다).
 */
class BusinessCriticalChangeSpec : BehaviorSpec({

    val reason = BusinessChangeReason("변경 신고서 접수")
    val evidence = EvidenceRef(EVIDENCE)

    /** 열 하나의 변경 후 (구분, 상호, profile). */
    fun columns(s: StoredBusiness): Map<String, Triple<BusinessType, DisplayName, BusinessProfile>> {
        val type = s.business.businessType!!
        return linkedMapOf(
            "general" to Triple(type, s.business.displayName, generalChange(s.profile)),
            "critical" to Triple(type, DisplayName("바뀐 상호"), s.profile),
            "subject" to Triple(type, s.business.displayName, corporateNumberChange(s.profile)),
        )
    }

    fun outcome(block: () -> Any): String = try {
        when (val r = block()) {
            is BusinessChangeProposal -> if (r.subjectCorrection) "PROPOSED_CORRECTION" else "PROPOSED"
            else -> "OK"
        }
    } catch (e: MasterRuleViolation) {
        e.rejection.name
    }

    val rows: List<Pair<String, () -> StoredBusiness>> = listOf(
        "DRAFT/none" to ::aDraftBusinessWithoutHistory,
        "DRAFT/contract" to ::aDraftBusinessWithContractHistory,
        "DRAFT/merchant" to ::aDraftBusinessWithMerchantOnly,
        "DRAFT/both" to ::aDraftBusinessWithContractAndMerchant,
        "REGISTERED/none" to ::aRegisteredBusinessWithoutHistory,
        "REGISTERED/contract" to ::aRegisteredBusinessWithTerminatedContract,
        "REGISTERED/merchant" to ::aRegisteredBusinessWithEndedMerchantOnly,
        "REGISTERED/both" to ::aRegisteredBusinessWithContractAndMerchant,
    )

    // 행마다 사업자를 한 번 만들고(사업자마다 ID가 다르다) 세 열을 그 사업자에 적용한다.
    fun tableOf(cell: (StoredBusiness, BusinessType, DisplayName, BusinessProfile) -> String): Map<String, List<String>> =
        rows.associate { (label, make) ->
            val s = make()
            label to columns(s).values.map { (type, name, profile) -> cell(s, type, name, profile) }
        }

    Given("§1.1 judgment table — every DRAFT/REGISTERED cell") {
        Then("pure level: general = SINGLE; critical = SINGLE_WITH_REASON (DRAFT, no history) / DUAL; subject = SINGLE_WITH_REASON / BLOCKED (history) / DUAL_CORRECTION") {
            tableOf { s, type, name, profile ->
                val change = BusinessProfileChange.of(s.business.registrationStatus, BusinessContent(s.business.businessType, s.business.displayName, s.profile),
                    BusinessContent(type, name, profile))
                BusinessChangeApprovalLevel.of(s.business.registrationStatus, change, s.history).name
            } shouldBe mapOf(
                "DRAFT/none" to listOf("SINGLE", "SINGLE_WITH_REASON", "SINGLE_WITH_REASON"),
                "DRAFT/contract" to listOf("SINGLE", "DUAL", "BLOCKED"),
                "DRAFT/merchant" to listOf("SINGLE", "DUAL", "BLOCKED"),
                "DRAFT/both" to listOf("SINGLE", "DUAL", "BLOCKED"),
                "REGISTERED/none" to listOf("SINGLE", "DUAL", "DUAL_CORRECTION"),
                "REGISTERED/contract" to listOf("SINGLE", "DUAL", "BLOCKED"),
                "REGISTERED/merchant" to listOf("SINGLE", "DUAL", "BLOCKED"),
                "REGISTERED/both" to listOf("SINGLE", "DUAL", "BLOCKED"),
            )
        }

        Then("1-person update without a reason: blocked > approval required > reason required; general passes") {
            tableOf { s, type, name, profile ->
                outcome { s.business.updateProfile(s.profile, type, name, profile, null, s.business.version, s.history, t0.plusSeconds(1), REQUESTER) }
            } shouldBe mapOf(
                "DRAFT/none" to listOf("OK", "BUSINESS_CHANGE_REASON_REQUIRED", "BUSINESS_CHANGE_REASON_REQUIRED"),
                "DRAFT/contract" to listOf("OK", "BUSINESS_CRITICAL_CHANGE_APPROVAL_REQUIRED", "BUSINESS_SUBJECT_CHANGE_NOT_ALLOWED"),
                "DRAFT/merchant" to listOf("OK", "BUSINESS_CRITICAL_CHANGE_APPROVAL_REQUIRED", "BUSINESS_SUBJECT_CHANGE_NOT_ALLOWED"),
                "DRAFT/both" to listOf("OK", "BUSINESS_CRITICAL_CHANGE_APPROVAL_REQUIRED", "BUSINESS_SUBJECT_CHANGE_NOT_ALLOWED"),
                "REGISTERED/none" to listOf("OK", "BUSINESS_CRITICAL_CHANGE_APPROVAL_REQUIRED", "BUSINESS_CRITICAL_CHANGE_APPROVAL_REQUIRED"),
                "REGISTERED/contract" to listOf("OK", "BUSINESS_CRITICAL_CHANGE_APPROVAL_REQUIRED", "BUSINESS_SUBJECT_CHANGE_NOT_ALLOWED"),
                "REGISTERED/merchant" to listOf("OK", "BUSINESS_CRITICAL_CHANGE_APPROVAL_REQUIRED", "BUSINESS_SUBJECT_CHANGE_NOT_ALLOWED"),
                "REGISTERED/both" to listOf("OK", "BUSINESS_CRITICAL_CHANGE_APPROVAL_REQUIRED", "BUSINESS_SUBJECT_CHANGE_NOT_ALLOWED"),
            )
        }

        Then("1-person update with a reason: only the 1-person cells pass (a reason never replaces the second person)") {
            tableOf { s, type, name, profile ->
                outcome { s.business.updateProfile(s.profile, type, name, profile, reason, s.business.version, s.history, t0.plusSeconds(1), REQUESTER) }
            } shouldBe mapOf(
                "DRAFT/none" to listOf("OK", "OK", "OK"),
                "DRAFT/contract" to listOf("OK", "BUSINESS_CRITICAL_CHANGE_APPROVAL_REQUIRED", "BUSINESS_SUBJECT_CHANGE_NOT_ALLOWED"),
                "DRAFT/merchant" to listOf("OK", "BUSINESS_CRITICAL_CHANGE_APPROVAL_REQUIRED", "BUSINESS_SUBJECT_CHANGE_NOT_ALLOWED"),
                "DRAFT/both" to listOf("OK", "BUSINESS_CRITICAL_CHANGE_APPROVAL_REQUIRED", "BUSINESS_SUBJECT_CHANGE_NOT_ALLOWED"),
                "REGISTERED/none" to listOf("OK", "BUSINESS_CRITICAL_CHANGE_APPROVAL_REQUIRED", "BUSINESS_CRITICAL_CHANGE_APPROVAL_REQUIRED"),
                "REGISTERED/contract" to listOf("OK", "BUSINESS_CRITICAL_CHANGE_APPROVAL_REQUIRED", "BUSINESS_SUBJECT_CHANGE_NOT_ALLOWED"),
                "REGISTERED/merchant" to listOf("OK", "BUSINESS_CRITICAL_CHANGE_APPROVAL_REQUIRED", "BUSINESS_SUBJECT_CHANGE_NOT_ALLOWED"),
                "REGISTERED/both" to listOf("OK", "BUSINESS_CRITICAL_CHANGE_APPROVAL_REQUIRED", "BUSINESS_SUBJECT_CHANGE_NOT_ALLOWED"),
            )
        }

        Then("proposal path: only 2-person cells make a proposal; 1-person cells are BUSINESS_CHANGE_APPROVAL_NOT_REQUIRED; blocked cells stay blocked") {
            tableOf { s, type, name, profile ->
                outcome {
                    s.business.proposeCriticalChange(s.profile, type, name, profile, reason, evidence, s.business.version, s.history, proposalId(), t0.plusSeconds(1), REQUESTER)
                }
            } shouldBe mapOf(
                "DRAFT/none" to listOf("BUSINESS_CHANGE_APPROVAL_NOT_REQUIRED", "BUSINESS_CHANGE_APPROVAL_NOT_REQUIRED", "BUSINESS_CHANGE_APPROVAL_NOT_REQUIRED"),
                "DRAFT/contract" to listOf("BUSINESS_CHANGE_APPROVAL_NOT_REQUIRED", "PROPOSED", "BUSINESS_SUBJECT_CHANGE_NOT_ALLOWED"),
                "DRAFT/merchant" to listOf("BUSINESS_CHANGE_APPROVAL_NOT_REQUIRED", "PROPOSED", "BUSINESS_SUBJECT_CHANGE_NOT_ALLOWED"),
                "DRAFT/both" to listOf("BUSINESS_CHANGE_APPROVAL_NOT_REQUIRED", "PROPOSED", "BUSINESS_SUBJECT_CHANGE_NOT_ALLOWED"),
                "REGISTERED/none" to listOf("BUSINESS_CHANGE_APPROVAL_NOT_REQUIRED", "PROPOSED", "PROPOSED_CORRECTION"),
                "REGISTERED/contract" to listOf("BUSINESS_CHANGE_APPROVAL_NOT_REQUIRED", "PROPOSED", "BUSINESS_SUBJECT_CHANGE_NOT_ALLOWED"),
                "REGISTERED/merchant" to listOf("BUSINESS_CHANGE_APPROVAL_NOT_REQUIRED", "PROPOSED", "BUSINESS_SUBJECT_CHANGE_NOT_ALLOWED"),
                "REGISTERED/both" to listOf("BUSINESS_CHANGE_APPROVAL_NOT_REQUIRED", "PROPOSED", "BUSINESS_SUBJECT_CHANGE_NOT_ALLOWED"),
            )
        }

        Then("INACTIVE rows reject every column with BUSINESS_INACTIVE and TERMINATED rows with BUSINESS_NOT_ACTIVE, on both paths and with any history") {
            val cases = rows.filter { it.first.startsWith("REGISTERED") }.flatMap { (label, make) ->
                listOf(INACTIVE to Lifecycle.ACTIVE, REGISTERED to Lifecycle.TERMINATED).map { (status, lifecycle) ->
                    val s = make()
                    val b = asStored(s.business, status, lifecycle)
                    "$label→$status/$lifecycle" to columns(s).values.flatMap { (type, name, profile) ->
                        listOf(
                            outcome { b.updateProfile(s.profile, type, name, profile, reason, b.version, s.history, t0, REQUESTER) },
                            outcome { b.proposeCriticalChange(s.profile, type, name, profile, reason, evidence, b.version, s.history, proposalId(), t0, REQUESTER) },
                        )
                    }.toSet()
                }
            }.toMap()
            // NIT-5: 행렬 키마다 단언(어느 행·상태가 틀렸는지 바로 보인다).
            cases shouldBe listOf("none", "contract", "merchant", "both").flatMap { h ->
                listOf("REGISTERED/$h→INACTIVE/ACTIVE" to setOf("BUSINESS_INACTIVE"), "REGISTERED/$h→REGISTERED/TERMINATED" to setOf("BUSINESS_NOT_ACTIVE"))
            }.toMap()
        }
    }

    Given("§7.2 named cases") {
        Then("① DRAFT without history: business type change (CORPORATION → INDIVIDUAL) is a 1-person registration edit with a reason, no evidence") {
            val s = aDraftBusinessWithoutHistory()
            val individual = s.profile.copy(registration = s.profile.registration.copy(corporateRegistrationNumber = null))
            outcome { s.business.updateProfile(s.profile, INDIVIDUAL, s.business.displayName, individual, null, 0, s.history, t0, REQUESTER) } shouldBe
                "BUSINESS_CHANGE_REASON_REQUIRED"
            val edit = s.business.updateProfile(s.profile, INDIVIDUAL, s.business.displayName, individual, reason, 0, s.history, t0, REQUESTER)
            listOf(edit.business.businessType, edit.business.registrationStatus, edit.business.version, edit.change.subjectCorrection) shouldBe
                listOf(INDIVIDUAL, DRAFT, 1L, true)
        }

        Then("② DRAFT with history: business type change is blocked") {
            listOf(aDraftBusinessWithContractHistory(), aDraftBusinessWithMerchantOnly()).map { s ->
                val individual = s.profile.copy(registration = s.profile.registration.copy(corporateRegistrationNumber = null))
                outcome { s.business.updateProfile(s.profile, INDIVIDUAL, s.business.displayName, individual, reason, 0, s.history, t0, REQUESTER) }
            } shouldBe listOf("BUSINESS_SUBJECT_CHANGE_NOT_ALLOWED", "BUSINESS_SUBJECT_CHANGE_NOT_ALLOWED")
        }

        Then("③ REGISTERED with only a terminated contract: business type / corporate number change is blocked on both paths") {
            val s = aRegisteredBusinessWithTerminatedContract()
            val cleared = s.profile.copy(registration = s.profile.registration.copy(corporateRegistrationNumber = null))
            listOf(
                outcome { s.business.proposeCriticalChange(s.profile, INDIVIDUAL, s.business.displayName, cleared, reason, evidence, 0, s.history, proposalId(), t0, REQUESTER) },
                outcome { s.business.updateProfile(s.profile, INDIVIDUAL, s.business.displayName, cleared, reason, 0, s.history, t0, REQUESTER) },
            ) shouldBe listOf("BUSINESS_SUBJECT_CHANGE_NOT_ALLOWED", "BUSINESS_SUBJECT_CHANGE_NOT_ALLOWED")
        }

        Then("④ REGISTERED with intake only (no history): corporate number change is a 2-person correction") {
            val s = aRegisteredBusinessWithIntakeOnly()
            val p = s.business.proposeCriticalChange(s.profile, CORPORATION, s.business.displayName, corporateNumberChange(s.profile), reason, evidence, 0, s.history,
                proposalId(), t0, REQUESTER)
            listOf(p.subjectCorrection, p.importantFields) shouldBe listOf(true, listOf("registration.corporateRegistrationNumber"))
        }

        Then("⑤ DRAFT with an ended merchant only (migrated row): display name change needs 2 persons") {
            val s = aDraftBusinessWithMerchantOnly()
            outcome { s.business.updateProfile(s.profile, CORPORATION, DisplayName("바뀐 상호"), s.profile, reason, 0, s.history, t0, REQUESTER) } shouldBe
                "BUSINESS_CRITICAL_CHANGE_APPROVAL_REQUIRED"
            aCriticalChangeProposal(s).let { listOf(it.subjectCorrection, it.importantFields) } shouldBe listOf(false, listOf("displayName"))
        }

        Then("REGISTERED representative change needs 2 persons (adding only, too — M-2)") {
            val s = aRegisteredBusinessWithoutHistory()
            val added = s.profile.copy(representatives = s.profile.representatives + BusinessFixtures.joint("추가공동"))
            listOf(representativeChange(s.profile), added).map { profile ->
                outcome { s.business.updateProfile(s.profile, CORPORATION, s.business.displayName, profile, reason, 0, s.history, t0, REQUESTER) }
            } shouldBe listOf("BUSINESS_CRITICAL_CHANGE_APPROVAL_REQUIRED", "BUSINESS_CRITICAL_CHANGE_APPROVAL_REQUIRED")
        }

        Then("a mixed save follows the strongest cell (blocked > 2 persons > 1 person + reason > 1 person)") {
            fun level(s: StoredBusiness, name: String, profile: BusinessProfile) = BusinessChangeApprovalLevel.of(
                s.business.registrationStatus,
                BusinessProfileChange.of(s.business.registrationStatus, BusinessContent(CORPORATION, s.business.displayName, s.profile),
                    BusinessContent(CORPORATION, DisplayName(name), profile)),
                s.history,
            )
            val none = aRegisteredBusinessWithoutHistory()
            val hist = aRegisteredBusinessWithTerminatedContract()
            val draftHist = aDraftBusinessWithContractHistory()
            val draftNone = aDraftBusinessWithoutHistory()
            listOf(
                level(none, "섞인 상호", corporateNumberChange(generalChange(none.profile))),
                level(hist, "섞인 상호", corporateNumberChange(hist.profile)),
                level(draftHist, "섞인 상호", generalChange(draftHist.profile)),
                level(draftNone, "섞인 상호", generalChange(draftNone.profile)),
                level(draftNone, draftNone.business.displayName.value, generalChange(draftNone.profile)),
            ) shouldBe listOf(DUAL_CORRECTION, BLOCKED, DUAL, SINGLE_WITH_REASON, SINGLE)
            // 섞인 저장은 통째로: 2인 제안은 일반 항목까지 담는다.
            aCriticalChangeProposal(none, profile = generalChange(none.profile)).changedFields shouldBe listOf("displayName", "registration.phoneNumber")
        }

        Then("first input is general even in a blocked cell (migration NULL type → first value, corporate number first input, opening date first input)") {
            val legacy = BusinessFixtures.legacyDraft()
            legacy.updateProfile(BusinessProfile.EMPTY, INDIVIDUAL, legacy.displayName, BusinessProfile.EMPTY, null, 0, CONTRACT_AND_MERCHANT, t0, REQUESTER)
                .business.businessType shouldBe INDIVIDUAL
            val s = aRegisteredBusinessWithContractAndMerchant()
            // 법인등록번호·개업일이 비어 있던 DRAFT(이력 있음)에 첫 입력.
            val draft = Business.createDraft(MasterFixture.businessId(), CORPORATION, BusinessFixtures.registrationNumber(), DisplayName("빈 법인"),
                s.profile.copy(registration = s.profile.registration.copy(corporateRegistrationNumber = null, openingDate = null)), t0, REQUESTER)
            val edit = draft.business.updateProfile(draft.profile, CORPORATION, draft.business.displayName, s.profile, null, 0, CONTRACT_AND_MERCHANT, t0, REQUESTER)
            listOf(edit.change.important, edit.change.subjectCorrection, edit.business.version) shouldBe listOf(false, false, 1L)
        }

        Then("1-person order: version → unchanged → blocked → approval required → reason → rules") {
            val s = aRegisteredBusinessWithTerminatedContract()
            fun r(name: String, profile: BusinessProfile, version: Long = 0, why: BusinessChangeReason? = null) =
                outcome { s.business.updateProfile(s.profile, CORPORATION, DisplayName(name), profile, why, version, s.history, t0, REQUESTER) }
            val noReps = s.profile.copy(representatives = emptyList())
            listOf(
                r("바뀐 상호", corporateNumberChange(s.profile), version = 9),
                r(s.business.displayName.value, s.profile),
                r("바뀐 상호", corporateNumberChange(s.profile)),
                r("바뀐 상호", noReps),
            ) shouldBe listOf("BUSINESS_VERSION_CONFLICT", "BUSINESS_PROFILE_UNCHANGED", "BUSINESS_SUBJECT_CHANGE_NOT_ALLOWED", "BUSINESS_CRITICAL_CHANGE_APPROVAL_REQUIRED")
            // 규칙 위반(대표자 0명)보다 승인 필요가 먼저다(§3.3). 1인 칸 규칙 위반은 그대로 결함 예외.
            val d = aDraftBusinessWithoutHistory()
            shouldThrow<BusinessProfileRuleViolation> {
                d.business.updateProfile(d.profile, INDIVIDUAL, d.business.displayName, d.profile, reason, 0, d.history, t0, REQUESTER)
            }
        }
    }

    Given("§7.1 completeRegistration (BusinessProfileChangeSpec:173 variants)") {
        Then("DRAFT with contract history or merchant only + display name change → BUSINESS_CRITICAL_CHANGE_APPROVAL_REQUIRED; without history → reason rule unchanged") {
            listOf(aDraftBusinessWithContractHistory(), aDraftBusinessWithMerchantOnly(), aDraftBusinessWithoutHistory()).map { s ->
                outcome { s.business.completeRegistration(s.profile, CORPORATION, DisplayName("바뀐 상호"), s.profile, null, 0, s.history, t0, REQUESTER) }
            } shouldBe listOf("BUSINESS_CRITICAL_CHANGE_APPROVAL_REQUIRED", "BUSINESS_CRITICAL_CHANGE_APPROVAL_REQUIRED", "BUSINESS_CHANGE_REASON_REQUIRED")
        }

        Then("blocked cell mixed into completeRegistration → BUSINESS_SUBJECT_CHANGE_NOT_ALLOWED; no history + subject change + reason registers (registration edit)") {
            val hist = aDraftBusinessWithContractHistory()
            outcome { hist.business.completeRegistration(hist.profile, CORPORATION, hist.business.displayName, corporateNumberChange(hist.profile), reason, 0, hist.history, t0, REQUESTER) } shouldBe
                "BUSINESS_SUBJECT_CHANGE_NOT_ALLOWED"
            val none = aDraftBusinessWithoutHistory()
            val done = none.business.completeRegistration(none.profile, CORPORATION, none.business.displayName, corporateNumberChange(none.profile), reason, 0, none.history, t0, REQUESTER)
            listOf(done.business.registrationStatus, done.change.entries.map { it.field }) shouldBe
                listOf(REGISTERED, listOf("registrationStatus", "registration.corporateRegistrationNumber"))
            // 이력 있음이어도 내용 변경이 없으면 등록 완료(상태 전이만 — M-1).
            hist.business.completeRegistration(hist.profile, CORPORATION, hist.business.displayName, hist.profile, null, 0, hist.history, t0, REQUESTER)
                .business.registrationStatus shouldBe REGISTERED
        }
    }

    Given("§3.4 derived values of the change") {
        Then("importantFields / representativeChanged / subjectCorrection; the important rule itself is unchanged") {
            val s = aRegisteredBusinessWithoutHistory()
            fun change(type: BusinessType?, name: String, profile: BusinessProfile, before: BusinessContent = BusinessContent(CORPORATION, s.business.displayName, s.profile)) =
                BusinessProfileChange.of(REGISTERED, before, BusinessContent(type, DisplayName(name), profile))
            change(CORPORATION, "바뀐 상호", representativeChange(generalChange(s.profile))).let {
                listOf(it.importantFields, it.representativeChanged, it.subjectCorrection, it.important)
            } shouldBe listOf(listOf("displayName", "representatives"), true, false, true)
            change(CORPORATION, s.business.displayName.value, generalChange(s.profile)).let {
                listOf(it.importantFields, it.representativeChanged, it.subjectCorrection, it.important)
            } shouldBe listOf(emptyList<String>(), false, false, false)
            val cleared = s.profile.copy(registration = s.profile.registration.copy(corporateRegistrationNumber = null))
            change(INDIVIDUAL, s.business.displayName.value, cleared).let { listOf(it.importantFields, it.subjectCorrection) } shouldBe
                listOf(listOf("businessType", "registration.corporateRegistrationNumber"), true)
            // 첫 입력(구분 NULL → 값, 법인번호 없음 → 값)은 정정 대상이 아니다.
            change(CORPORATION, s.business.displayName.value, s.profile, before = BusinessContent(null, s.business.displayName, cleared)).let {
                listOf(it.importantFields, it.subjectCorrection, it.important)
            } shouldBe listOf(emptyList<String>(), false, false)
            // DRAFT 대표자 0 → 1(일반)은 대표자 변경이지만 중요 항목이 아니다.
            BusinessProfileChange.of(DRAFT, BusinessContent(INDIVIDUAL, DisplayName("a"), BusinessFixtures.individualProfile().copy(representatives = emptyList())),
                BusinessContent(INDIVIDUAL, DisplayName("a"), BusinessFixtures.individualProfile())).let {
                listOf(it.representativeChanged, it.importantFields, it.important)
            } shouldBe listOf(true, emptyList<String>(), false)
            BusinessProfileChange.created(BusinessContent(INDIVIDUAL, DisplayName("a"), BusinessFixtures.individualProfile())).let {
                listOf(it.importantFields, it.representativeChanged, it.subjectCorrection)
            } shouldBe listOf(emptyList<String>(), false, false)
        }

        Then("withStatusTransition keeps the derived values") {
            val s = aDraftBusinessWithoutHistory()
            BusinessProfileChange.of(DRAFT, BusinessContent(CORPORATION, s.business.displayName, s.profile), BusinessContent(CORPORATION, DisplayName("x"), corporateNumberChange(s.profile)))
                .withStatusTransition(DRAFT, REGISTERED).let { listOf(it.importantFields, it.subjectCorrection) } shouldBe
                listOf(listOf("displayName", "registration.corporateRegistrationNumber"), true)
        }
    }

    Given("§3.2·§3.3 proposal") {
        Then("a proposal keeps the business untouched and carries base version, fields, flags, requester, reason and evidence") {
            val s = aRegisteredBusinessWithoutHistory()
            val p = aCriticalChangeProposal(s, profile = representativeChange(s.profile))
            listOf(p.businessId, p.baseVersion, p.changedFields, p.importantFields, p.representativeChanged, p.subjectCorrection, p.requestedBy, p.requestedAt, p.evidenceRef.value) shouldBe
                listOf(s.business.id, 0L, listOf("displayName", "representatives"), listOf("displayName", "representatives"), true, false, REQUESTER, t0.plusSeconds(10), EVIDENCE)
            listOf(s.business.version, s.business.displayName.value) shouldBe listOf(0L, "법인 상호")
            p.content.displayName.value shouldBe "새 법인 상호"
        }

        Then("proposal order: lifecycle → INACTIVE → version → unchanged → blocked → not required → reason → rules") {
            val s = aRegisteredBusinessWithTerminatedContract()
            fun p(b: Business = s.business, name: String = "바뀐 상호", profile: BusinessProfile = s.profile, version: Long = 0, why: BusinessChangeReason? = reason) =
                outcome { b.proposeCriticalChange(s.profile, CORPORATION, DisplayName(name), profile, why, evidence, version, s.history, proposalId(), t0, REQUESTER) }
            listOf(
                p(b = asStored(s.business, INACTIVE, Lifecycle.TERMINATED), version = 9, why = null),
                p(b = asStored(s.business, INACTIVE), version = 9, why = null),
                p(version = 9, profile = corporateNumberChange(s.profile)),
                p(name = s.business.displayName.value),
                p(profile = corporateNumberChange(s.profile), why = null),
                p(name = s.business.displayName.value, profile = generalChange(s.profile), why = null),
                p(why = null),
            ) shouldBe listOf(
                "BUSINESS_NOT_ACTIVE", "BUSINESS_INACTIVE", "BUSINESS_VERSION_CONFLICT", "BUSINESS_PROFILE_UNCHANGED", "BUSINESS_SUBJECT_CHANGE_NOT_ALLOWED",
                "BUSINESS_CHANGE_APPROVAL_NOT_REQUIRED", "BUSINESS_CHANGE_REASON_REQUIRED",
            )
            // 2인 경로에서 규칙 위반(REGISTERED 대표자 0명)은 결함 예외(400 — 입력 해석이 먼저 막는다).
            shouldThrow<BusinessProfileRuleViolation> { p(profile = s.profile.copy(representatives = emptyList())) }
        }

        Then("content JSON and digest input are canonical, carry businessId + baseVersion, and never the registration number") {
            val s = aRegisteredBusinessWithoutHistory()
            val p = aCriticalChangeProposal(s)
            val again = BusinessChangeProposal.reconstitute(
                p.id, p.businessId, p.baseVersion, p.content, p.changes, p.importantFields, p.representativeChanged, p.subjectCorrection, p.reason, p.evidenceRef,
                p.requestedBy, p.requestedAt,
            )
            listOf(again.contentJson() == p.contentJson(), again.digestInput() == p.digestInput()) shouldBe listOf(true, true)
            p.digestInput() shouldContain "\"businessId\":\"${s.business.id.value}\""
            p.digestInput() shouldContain "\"baseVersion\":0"
            p.digestInput() shouldContain p.contentJson()
            p.contentJson() shouldContain "\"displayName\":\"새 법인 상호\""
            p.contentJson() shouldContain "\"businessType\":\"CORPORATION\""
            p.contentJson() shouldContain BusinessCriticalChangeFixtures.STORED_CORPORATE_NUMBER
            p.contentJson() shouldNotContain s.business.registrationNumber.value
            // 내용·기준 version·대상이 하나라도 다르면 digest 입력이 다르다.
            val other = aCriticalChangeProposal(s, name = DisplayName("다른 상호"))
            val otherBase = BusinessChangeProposal.reconstitute(
                p.id, p.businessId, 1, p.content, p.changes, p.importantFields, p.representativeChanged, p.subjectCorrection, p.reason, p.evidenceRef, p.requestedBy, p.requestedAt,
            )
            setOf(p.digestInput(), other.digestInput(), otherBase.digestInput()).size shouldBe 3
            // 정규형: 업태·종목 입력 순서(유형 사이 끼워 넣기)는 JSON에 영향이 없다(저장 profile 정규화 — BusinessProfile.canonical).
            val c = s.profile.categories
            val interleaved = s.profile.copy(categories = listOf(c[0], c[2], c[1], c[3]))
            aCriticalChangeProposal(s, profile = interleaved).contentJson() shouldBe p.contentJson()
        }

        Then("proposal toString and digest toString carry no business name, personal data, reason or evidence") {
            val p = aCriticalChangeProposal(name = DisplayName("캔어리상호"), profile = BusinessFixtures.corporationProfile().copy(representatives = listOf(BusinessFixtures.joint(BusinessFixtures.CANARY_REPRESENTATIVE))))
            val text = p.toString() + testDigest(p.digestInput()).toString()
            listOf("캔어리", "상호 변경 신고", EVIDENCE, BusinessCriticalChangeFixtures.STORED_CORPORATE_NUMBER).filter { text.contains(it) } shouldBe emptyList()
        }

        Then("proposal invariants: no important field, important ⊄ changed, subject flag without subject field, non-canonical profile are defects") {
            val p = aCriticalChangeProposal()
            fun rebuild(important: List<String> = p.importantFields, subject: Boolean = p.subjectCorrection, content: BusinessContent = p.content) =
                BusinessChangeProposal.reconstitute(p.id, p.businessId, p.baseVersion, content, p.changes, important, p.representativeChanged, subject, p.reason, p.evidenceRef,
                    p.requestedBy, p.requestedAt)
            val shuffled = p.content.copy(profile = p.content.profile.copy(categories = p.content.profile.categories.reversed()))
            listOf(
                runCatching { rebuild(important = emptyList()) }.exceptionOrNull() is IllegalArgumentException,
                runCatching { rebuild(important = listOf("contacts")) }.exceptionOrNull() is IllegalArgumentException,
                runCatching { rebuild(subject = true) }.exceptionOrNull() is IllegalArgumentException,
                runCatching { rebuild(content = shuffled) }.exceptionOrNull() is IllegalArgumentException,
            ) shouldBe listOf(true, true, true, true)
            // MINOR-1: 파생 표시값은 항목 목록과 일치해야 한다(정정 항목이 중요 항목에 있으면 subjectCorrection, 대표자 항목이 있으면 representativeChanged).
            val correction = aCriticalChangeProposal(name = DisplayName("법인 상호"), profile = corporateNumberChange(BusinessFixtures.corporationProfile()))
            val rep = aCriticalChangeProposal(profile = representativeChange(BusinessFixtures.corporationProfile()))
            listOf(correction.subjectCorrection, rep.representativeChanged) shouldBe listOf(true, true)
            fun rebuildOf(x: BusinessChangeProposal, subject: Boolean = x.subjectCorrection, representative: Boolean = x.representativeChanged) =
                runCatching {
                    BusinessChangeProposal.reconstitute(x.id, x.businessId, x.baseVersion, x.content, x.changes, x.importantFields, representative, subject, x.reason, x.evidenceRef,
                        x.requestedBy, x.requestedAt)
                }.exceptionOrNull() is IllegalArgumentException
            listOf(rebuildOf(correction, subject = false), rebuildOf(rep, representative = false), rebuildOf(p, representative = true)) shouldBe listOf(true, true, true)
            shouldThrow<IllegalArgumentException> { BusinessChangeProposalId("bcp_short") }
            shouldThrow<IllegalArgumentException> { BusinessChangeDigest("h1:" + "A".repeat(64)) }
        }
    }

    Given("§3.3 C approved execution (applyApprovedChange)") {
        Then("success (moved from BusinessProfileChangeSpec:154-156): version +1, new name, same status, approver as updatedBy, registration number unchanged") {
            val s = aRegisteredBusinessWithoutHistory()
            val p = aCriticalChangeProposal(s)
            val edit = s.business.applyApprovedChange(s.profile, p, approvedFor(p), testDigest(p.digestInput()), s.history, t0.plusSeconds(60))
            listOf(edit.business.version, edit.business.displayName.value, edit.business.registrationStatus, edit.business.updatedAt, edit.business.updatedBy) shouldBe
                listOf(1L, "새 법인 상호", REGISTERED, t0.plusSeconds(60), APPROVER)
            edit.business.registrationNumber shouldBe s.business.registrationNumber
            edit.change.entries.map { it.field } shouldBe listOf("displayName")
        }

        Then("a mixed proposal applies the general fields together; a DRAFT proposal (history) stays DRAFT") {
            val s = aRegisteredBusinessWithoutHistory()
            val p = aCriticalChangeProposal(s, profile = generalChange(representativeChange(s.profile)))
            val edit = s.business.applyApprovedChange(s.profile, p, approvedFor(p), testDigest(p.digestInput()), s.history, t0.plusSeconds(60))
            listOf(edit.profile.registration.phoneNumber?.value, edit.profile.representatives.map { it.name.value }, edit.change.entries.map { it.field }) shouldBe
                listOf("0299990000", listOf("새공동", "박공동"), listOf("displayName", "registration.phoneNumber", "representatives"))
            val d = aDraftBusinessWithContractHistory()
            val dp = aCriticalChangeProposal(d)
            d.business.applyApprovedChange(d.profile, dp, approvedFor(dp), testDigest(dp.digestInput()), d.history, t0.plusSeconds(60)).business.registrationStatus shouldBe DRAFT
        }

        Then("base version mismatch → BUSINESS_VERSION_CONFLICT (the business changed while pending)") {
            val s = aRegisteredBusinessWithoutHistory()
            val p = aCriticalChangeProposal(s)
            val moved = s.business.updateProfile(s.profile, CORPORATION, s.business.displayName, generalChange(s.profile), null, 0, s.history, t0.plusSeconds(20), REQUESTER)
            outcome { moved.business.applyApprovedChange(moved.profile, p, approvedFor(p), testDigest(p.digestInput()), s.history, t0.plusSeconds(60)) } shouldBe
                "BUSINESS_VERSION_CONFLICT"
        }

        Then("proposal ↔ approved command mismatch (digest, recomputed digest, businessId, baseVersion, proposalId) → PROPOSAL_CONTENT_MISMATCH") {
            val s = aRegisteredBusinessWithoutHistory()
            val p = aCriticalChangeProposal(s)
            val other = aCriticalChangeProposal(s, name = DisplayName("다른 상호"))
            val good = testDigest(p.digestInput())
            fun run(approved: ApprovedBusinessChange, recomputed: BusinessChangeDigest = good) =
                outcome { s.business.applyApprovedChange(s.profile, p, approved, recomputed, s.history, t0.plusSeconds(60)) }
            listOf(
                run(approvedFor(p, digest = testDigest(other.digestInput()))),
                run(approvedFor(p), recomputed = testDigest(other.digestInput())),
                run(approvedFor(p, businessId = MasterFixture.businessId())),
                run(approvedFor(p, baseVersion = 1)),
                run(approvedFor(p, proposalId = proposalId())),
            ).toSet() shouldBe setOf("PROPOSAL_CONTENT_MISMATCH")
            // 다른 사업자에 그 사업자의 제안을 적용하지 않는다.
            val elsewhere = aRegisteredBusinessWithoutHistory()
            outcome { elsewhere.business.applyApprovedChange(elsewhere.profile, p, approvedFor(p), good, elsewhere.history, t0.plusSeconds(60)) } shouldBe
                "PROPOSAL_CONTENT_MISMATCH"
        }

        Then("M-6 proposal requester ≠ approval requester → PROPOSAL_REQUESTER_MISMATCH") {
            val s = aRegisteredBusinessWithoutHistory()
            val p = aCriticalChangeProposal(s, requestedBy = OTHER_OPERATOR)
            outcome { s.business.applyApprovedChange(s.profile, p, approvedFor(p, requestedBy = REQUESTER), testDigest(p.digestInput()), s.history, t0.plusSeconds(60)) } shouldBe
                "PROPOSAL_REQUESTER_MISMATCH"
        }

        Then("execution order: content mismatch → requester mismatch → lifecycle/INACTIVE → version") {
            val s = aRegisteredBusinessWithoutHistory()
            val p = aCriticalChangeProposal(s)
            val closed = asStored(s.business, lifecycle = Lifecycle.TERMINATED)
            val inactive = asStored(s.business, INACTIVE)
            val other = testDigest("other")
            fun run(b: Business, approved: ApprovedBusinessChange, recomputed: BusinessChangeDigest = testDigest(p.digestInput())) =
                outcome { b.applyApprovedChange(s.profile, p, approved, recomputed, s.history, t0.plusSeconds(60)) }
            listOf(
                run(closed, approvedFor(p, requestedBy = OTHER_OPERATOR, digest = other)),
                run(closed, approvedFor(p, requestedBy = OTHER_OPERATOR)),
                run(closed, approvedFor(p)),
                run(inactive, approvedFor(p)),
            ) shouldBe listOf("PROPOSAL_CONTENT_MISMATCH", "PROPOSAL_REQUESTER_MISMATCH", "BUSINESS_NOT_ACTIVE", "BUSINESS_INACTIVE")
        }

        Then("R2-m1 no re-judgment of persons: an approved display name change still executes after contract history appears") {
            val s = aRegisteredBusinessWithoutHistory()
            val p = aCriticalChangeProposal(s)
            s.business.applyApprovedChange(s.profile, p, approvedFor(p), testDigest(p.digestInput()), CONTRACT_AND_MERCHANT, t0.plusSeconds(60)).business.displayName.value shouldBe
                "새 법인 상호"
        }

        Then("a subject correction that became a blocked cell while pending → BUSINESS_SUBJECT_CHANGE_NOT_ALLOWED (mixed with a name change too)") {
            val s = aRegisteredBusinessWithoutHistory()
            val correction = aCriticalChangeProposal(s, name = s.business.displayName, profile = corporateNumberChange(s.profile))
            val mixed = aCriticalChangeProposal(s, profile = corporateNumberChange(s.profile))
            listOf(correction.subjectCorrection, mixed.subjectCorrection) shouldBe listOf(true, true)
            listOf(correction, mixed).map { p ->
                outcome { s.business.applyApprovedChange(s.profile, p, approvedFor(p), testDigest(p.digestInput()), CONTRACT_ONLY, t0.plusSeconds(60)) }
            } shouldBe listOf("BUSINESS_SUBJECT_CHANGE_NOT_ALLOWED", "BUSINESS_SUBJECT_CHANGE_NOT_ALLOWED")
            // 이력이 그대로면 2인 정정이 실행된다.
            s.business.applyApprovedChange(s.profile, correction, approvedFor(correction), testDigest(correction.digestInput()), s.history, t0.plusSeconds(60))
                .profile.registration.corporateRegistrationNumber?.value shouldBe BusinessCriticalChangeFixtures.CORRECTED_CORPORATE_NUMBER
        }

        Then("MINOR-2 a recomputed change summary that differs from the proposal summary is an integrity defect (before the blocked-cell check)") {
            val s = aRegisteredBusinessWithoutHistory()
            val real = aCriticalChangeProposal(s)
            // 제안 요약에 실제로 바뀌지 않는 항목(사업체 전화번호)이 들어 있다 — 불변식은 만족하지만 저장 내용과 맞지 않는다.
            val phantom = real.changes + BusinessChangeEntry.Value("registration.phoneNumber", "****5678", "****0000")
            val forged = BusinessChangeProposal.reconstitute(real.id, real.businessId, real.baseVersion, real.content, phantom, real.importantFields, real.representativeChanged,
                real.subjectCorrection, real.reason, real.evidenceRef, real.requestedBy, real.requestedAt)
            // 이력이 생겨도(차단 판정이 있었다면 업무 거절이 났을 상황이 아니어도) 요약 불일치가 먼저 결함으로 드러난다.
            shouldThrow<IllegalStateException> {
                s.business.applyApprovedChange(s.profile, forged, approvedFor(forged), testDigest(forged.digestInput()), CONTRACT_AND_MERCHANT, t0.plusSeconds(60))
            }
            val subjectReal = aCriticalChangeProposal(s, name = s.business.displayName, profile = corporateNumberChange(s.profile))
            val subjectForged = BusinessChangeProposal.reconstitute(subjectReal.id, subjectReal.businessId, subjectReal.baseVersion, subjectReal.content,
                subjectReal.changes + BusinessChangeEntry.Value("registration.phoneNumber", "a", "b"), subjectReal.importantFields, subjectReal.representativeChanged,
                subjectReal.subjectCorrection, subjectReal.reason, subjectReal.evidenceRef, subjectReal.requestedBy, subjectReal.requestedAt)
            shouldThrow<IllegalStateException> {
                s.business.applyApprovedChange(s.profile, subjectForged, approvedFor(subjectForged), testDigest(subjectForged.digestInput()), CONTRACT_ONLY, t0.plusSeconds(60))
            }
        }

        Then("NIT-6 BusinessContent / proposal content toString carries no personal data (value objects mask themselves)") {
            val canary = BusinessFixtures.canaryProfile()
            val text = BusinessContent(INDIVIDUAL, DisplayName("상호"), canary).toString()
            listOf(BusinessFixtures.CANARY_REPRESENTATIVE, BusinessFixtures.CANARY_EMAIL, BusinessFixtures.CANARY_PHONE, BusinessFixtures.CANARY_DETAIL_ADDRESS)
                .filter { text.contains(it) } shouldBe emptyList()
        }

        Then("a proposal that now sits in a 1-person cell is an integrity defect, not a business rejection") {
            val s = aDraftBusinessWithoutHistory()
            val forged = aCriticalChangeProposal(aDraftBusinessWithContractHistory()).let { real ->
                BusinessChangeProposal.reconstitute(proposalId(), s.business.id, 0, real.content, real.changes, real.importantFields, real.representativeChanged, false, real.reason,
                    real.evidenceRef, REQUESTER, t0)
            }
            shouldThrow<IllegalStateException> {
                s.business.applyApprovedChange(s.profile, forged, approvedFor(forged), testDigest(forged.digestInput()), s.history, t0.plusSeconds(60))
            }
        }
    }
})

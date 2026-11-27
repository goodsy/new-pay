package com.paycore.master.merchant.domain

import com.paycore.master.merchant.domain.BusinessContractFixtures.kst
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.Instant

/**
 * BUSINESS-PROFILE-001 BIZ-4-1(C-27 — D-BIZ-001) + **MERCHANT-FLOW-001 FLOW-3(§4B.8 — 매트릭스 GOV-MER-ELIG-001 §3.1 4행 + §3.2 14행)**: `Merchant.register` 판정 순서
 * `BUSINESS_NOT_ACTIVE` → `BUSINESS_NOT_REGISTERED` → **`BUSINESS_CONTRACT_NOT_EFFECTIVE`**(최신 사업자 계약이 판정 시각에 유효 + 적용 중 버전 범위에 카드 PG 결제 — BR-BIZ-320·327·328)
 * → 코드 예약어. 접수 건은 입력이 아니다(BR-BIZ-319 — 그래서 §3.2의 접수 건 열은 같은 계약 입력으로 같은 판정). 사업자 없음은 Application(잠금).
 * 기대값 변경 없음(§4B.8 "Fixture에 유효 사업자 계약을 명시적으로 추가") — 기존 세 Then은 입력에 유효 계약만 더했다.
 */
class MerchantRegistrationGateSpec : BehaviorSpec({

    val f = BusinessContractFixtures
    val at: Instant = f.t0.plus(Duration.ofMinutes(5))

    fun register(business: Business, contract: BusinessContract? = f.storedEffectiveFor(business.id, MasterFixture.t0), code: String = MerchantCodeFixtures.unique().value, now: Instant = MasterFixture.t0) =
        Merchant.register(MasterFixture.merchantId(), business, contract, DisplayName("Demo Merchant"), MerchantCode(code), now, MasterFixture.operator)

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

    Given("FLOW-3 §3.1 business axis (4 rows) — the business axis is judged before the contract axis") {
        Then("TERMINATED → NOT_ACTIVE, ACTIVE·DRAFT / ACTIVE·INACTIVE → NOT_REGISTERED even with an effective contract, ACTIVE·REGISTERED → the contract axis") {
            listOf(
                stored(Lifecycle.TERMINATED, BusinessRegistrationStatus.REGISTERED) to MasterRejection.BUSINESS_NOT_ACTIVE,
                stored(Lifecycle.ACTIVE, BusinessRegistrationStatus.DRAFT) to MasterRejection.BUSINESS_NOT_REGISTERED,
                stored(Lifecycle.ACTIVE, BusinessRegistrationStatus.INACTIVE) to MasterRejection.BUSINESS_NOT_REGISTERED,
            ).forEach { (business, rejection) ->
                withClue("${business.lifecycle}/${business.registrationStatus}") { shouldThrow<MasterRuleViolation> { register(business) }.rejection shouldBe rejection }
            }
            val registered = stored(Lifecycle.ACTIVE, BusinessRegistrationStatus.REGISTERED)
            shouldThrow<MasterRuleViolation> { register(registered, contract = null) }.rejection shouldBe MasterRejection.BUSINESS_CONTRACT_NOT_EFFECTIVE
            register(registered).businessId shouldBe registered.id
        }
    }

    Given("FLOW-3 §3.2 contract axis (14 rows, business ACTIVE·REGISTERED) — the intake state is not an input (BR-BIZ-319)") {
        Then("each row: refused rows are BUSINESS_CONTRACT_NOT_EFFECTIVE (one code), allowed rows register; the registration basis is the applying version") {
            val business = BusinessFixtures.individualRegistered().business
            val effective = f.anEffectiveBusinessContract(business, start = f.today.minusDays(3), at = kst(f.today.minusDays(3), 9))
            val withScheduled = effective.addVersion(business, 2, f.terms(f.today.plusDays(5)), f.document, null, f.t0, f.operator).contract
            val terminatedFirst = f.aTerminatedContract(business, at = kst(f.today.minusDays(10), 9))
            val renewedNotYet = BusinessContract.create(business, terminatedFirst, 2, f.terms(f.today.plusDays(7)), f.document, null, f.t0, f.operator).contract
            val renewedActive = BusinessContract.create(business, terminatedFirst, 2, f.terms(f.today), f.document, null, f.t0, f.operator).contract
            val rows: List<Triple<String, BusinessContract?, Int?>> = listOf(
                Triple("#1 none / no intake", null, null),
                Triple("#2 none / NEW intake open", null, null),
                Triple("#3 none / NEW intake rejected", null, null),
                Triple("#4 not yet effective", f.aNotYetEffectiveContract(business), null),
                Triple("#5 effective / none or completed", effective, 1),
                Triple("#6 effective / CHANGE intake open", effective, 1),
                Triple("#7 effective / CHANGE intake rejected", effective, 1),
                Triple("#8 effective + scheduled version (applying version decides)", withScheduled, 1),
                // #9 적용 버전 범위에 카드 PG 없음: 빈 범위 금지 + ServiceKind = CARD_PG_PAYMENT 하나라 지금은 만들 수 없다(I-5) — 아래 별도 Then이 그 사실을 단언한다(리뷰 m-5).
                Triple("#10 suspended", f.aSuspendedContract(business), null),
                Triple("#11 terminated", f.aTerminatedContract(business), null),
                Triple("#12 expired", f.anExpiredContract(business), null),
                Triple("#13 renewed after termination, not yet effective", renewedNotYet, null),
                Triple("#14 renewed after termination, effective", renewedActive, 1),
            )
            rows.forEach { (name, contract, version) ->
                withClue(name) {
                    if (version == null) {
                        shouldThrow<MasterRuleViolation> { register(business, contract, now = at) }.rejection shouldBe MasterRejection.BUSINESS_CONTRACT_NOT_EFFECTIVE
                    } else {
                        val merchant = register(business, contract, now = at)
                        val basis = MerchantRegistrationBasis.of(merchant, checkNotNull(contract), at, MasterFixture.operator)
                        listOf(basis.merchantId, basis.contractId, basis.versionNo, basis.recordedAt) shouldBe listOf(merchant.id, contract.id, version, at)
                    }
                }
            }
            // 정지된 계약 + 예약어 코드 → 계약 판정이 먼저(§4B.8 순서: 관문 → 코드 예약어).
            shouldThrow<MasterRuleViolation> { register(business, f.aSuspendedContract(business), code = "adminshop", now = at) }.rejection shouldBe
                MasterRejection.BUSINESS_CONTRACT_NOT_EFFECTIVE
            // 다른 사업자의 계약은 입력이 될 수 없다(결함).
            shouldThrow<IllegalArgumentException> { register(business, f.storedEffectiveFor(MasterFixture.businessId(), MasterFixture.t0)) }
        }

        Then("a contract judged with a clock behind its last event still counts the event (fail-closed — 2a MAJOR-1): a contract terminated after the judgement time is refused") {
            val business = BusinessFixtures.individualRegistered().business
            val terminatedLater = f.anEffectiveBusinessContract(business).terminate(business, 0, f.reason, f.t0.plus(Duration.ofHours(2)), f.operator).contract
            shouldThrow<MasterRuleViolation> { register(business, terminatedLater, now = f.t0) }.rejection shouldBe MasterRejection.BUSINESS_CONTRACT_NOT_EFFECTIVE
        }
    }

    Given("FLOW-3 §3.2 #9 (review m-5): an applying version without CARD_PG_PAYMENT") {
        Then("cannot be constructed today — ServiceKind has only CARD_PG_PAYMENT and a version with an empty scope is refused (I-5); revisit when a service kind is added") {
            ServiceKind.entries shouldBe listOf(ServiceKind.CARD_PG_PAYMENT)
            shouldThrow<IllegalArgumentException> {
                BusinessContractVersion(
                    versionNo = 1, originIntakeSeq = 1, migrated = true, startsAt = Instant.parse("2026-11-21T03:00:00Z"), startDate = null, endDate = null,
                    services = emptySet(), document = null, reason = null, createdAt = Instant.parse("2026-11-21T03:00:00Z"), createdBy = ActorRef("system:migration"),
                )
            }.message shouldBe "a version covers at least one service kind"
        }
    }
})

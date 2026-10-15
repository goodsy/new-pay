package com.paycore.master.merchant.domain

import com.paycore.master.merchant.domain.EntitlementFixture.evidence
import com.paycore.master.merchant.domain.EntitlementFixture.krw
import com.paycore.master.merchant.domain.EntitlementFixture.newEvidence
import com.paycore.master.merchant.domain.EntitlementFixture.operator
import com.paycore.master.merchant.domain.EntitlementFixture.reason
import com.paycore.master.merchant.domain.EntitlementFixture.t0
import com.paycore.master.merchant.domain.EntitlementFixture.usd
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

/**
 * 승인 시작 조건 원본 domain 규칙(MASTER-SNAPSHOT-002 명세 §3): 한도 정책(D-AUTH-006·D-MS2-001·002), 정책 버전 등록부(D-MS2-003), 정책 Assignment
 * (D-MD-018, CSA = MID 발급 CSA), 카드사(매입사) 개통(D-MD-017·D-MS2-004·005, 전이 D-MS-003 준용). 값은 Demo, 기대값은 명세 표에서 직접 적는다.
 */
class AuthorizationTermsSpec : BehaviorSpec({

    fun rejection(block: () -> Unit): MasterRejection = shouldThrow<MasterRuleViolation>(block).rejection

    // t0 = 2026-10-09T01:00Z = 10:00 KST. Seoul 자정(UTC 15:00): 그날 밤과 그다음 밤.
    val midnight1: Instant = Instant.parse("2026-10-09T15:00:00.000Z")
    val midnight2: Instant = Instant.parse("2026-10-10T15:00:00.000Z")
    val demoPerTx = LimitValue.Limited(1_000_000)
    val demoDaily = LimitValue.Limited(5_000_000)

    fun limitId() = TransactionLimitPolicyId("tlp_${MasterFixture.ulid()}")

    Given("an issued merchant with its issuing card CSA and MID") {
        val issued = MasterFixture.issued()
        val account = issued.issuance.account
        val csa = issued.contract.agreements.single().id
        val csaTarget = LimitTarget.csaDefault(issued.merchant, issued.contract, csa)
        val midTarget = LimitTarget.midOverride(issued.merchant, account)

        fun register(target: LimitTarget, period: EffectivePeriod, existing: List<TransactionLimitPolicy> = emptyList(), unit: CurrencyUnit = krw, at: Instant = t0) =
            TransactionLimitPolicy.register(limitId(), target, unit, demoPerTx, demoDaily, period, existing, reason, at, operator)

        Then("a CSA default from the next Seoul midnight is recorded with explicit limits; an explicit UNLIMITED is accepted") {
            val p = register(csaTarget, EffectivePeriod(midnight1))
            listOf(p.scope, p.perTransaction, p.daily, p.period) shouldBe listOf(LimitScope.CsaDefault(csa), demoPerTx, demoDaily, EffectivePeriod(midnight1))
            TransactionLimitPolicy.register(limitId(), midTarget, krw, LimitValue.Unlimited, LimitValue.Unlimited, EffectivePeriod(midnight1), emptyList(), reason, t0, operator)
                .daily shouldBe LimitValue.Unlimited
        }

        Then("boundaries off Asia/Seoul midnight are refused (D-MS2-002), and so is a start before the command time") {
            mapOf(
                "start at 10:00 KST" to rejection { register(csaTarget, EffectivePeriod(t0)) },
                "start 1 ms after midnight" to rejection { register(csaTarget, EffectivePeriod(midnight1.plusMillis(1))) },
                "end at UTC midnight" to rejection { register(csaTarget, EffectivePeriod(midnight1, Instant.parse("2026-10-11T00:00:00.000Z"))) },
                "retroactive midnight" to rejection { register(csaTarget, EffectivePeriod(midnight1), at = midnight1.plusSeconds(1)) },
            ) shouldBe mapOf(
                "start at 10:00 KST" to MasterRejection.LIMIT_POLICY_BOUNDARY_NOT_SEOUL_MIDNIGHT,
                "start 1 ms after midnight" to MasterRejection.LIMIT_POLICY_BOUNDARY_NOT_SEOUL_MIDNIGHT,
                "end at UTC midnight" to MasterRejection.LIMIT_POLICY_BOUNDARY_NOT_SEOUL_MIDNIGHT,
                "retroactive midnight" to MasterRejection.LIMIT_POLICY_RETROACTIVE,
            )
        }

        Then("the same level, target and currency cannot overlap (D-MS2-001); adjacent periods, another currency, and CSA default vs MID override can") {
            val first = register(csaTarget, EffectivePeriod(midnight1, midnight2))
            shouldThrow<MasterRuleViolation> { register(csaTarget, EffectivePeriod(midnight1), listOf(first)) }
                .let { it.rejection to it.reference } shouldBe (MasterRejection.LIMIT_POLICY_OVERLAP to first.id.value)
            register(csaTarget, EffectivePeriod(midnight2), listOf(first)).period.from shouldBe midnight2
            register(csaTarget, EffectivePeriod(midnight1), listOf(first), unit = usd).currency shouldBe usd.code
            register(midTarget, EffectivePeriod(midnight1), listOf(first)).scope shouldBe LimitScope.MidOverride(account.id)
        }

        Then("an open policy is ended once at a Seoul midnight not in the past; a replacement switches without a gap and keeps scope and currency") {
            val open = register(csaTarget, EffectivePeriod(midnight1))
            rejection { open.end(midnight1.plusSeconds(3600), t0, operator) } shouldBe MasterRejection.LIMIT_POLICY_BOUNDARY_NOT_SEOUL_MIDNIGHT
            rejection { open.end(midnight1, midnight1.plusSeconds(1), operator) } shouldBe MasterRejection.LIMIT_POLICY_RETROACTIVE
            val replacement = TransactionLimitPolicy.replace(
                open, limitId(), csaTarget, krw, LimitValue.Limited(2_000_000), demoDaily, midnight2, listOf(open), reason, t0, operator,
            )
            listOf(open.period, replacement.period, replacement.perTransaction) shouldBe
                listOf(EffectivePeriod(midnight1, midnight2), EffectivePeriod(midnight2), LimitValue.Limited(2_000_000))
            rejection { open.end(midnight2, t0, operator) } shouldBe MasterRejection.LIMIT_POLICY_ALREADY_ENDED
        }

        Then("the target must belong to the merchant: a CSA absent from the contract is refused; negative limits cannot be expressed") {
            rejection { LimitTarget.csaDefault(issued.merchant, issued.contract, MasterFixture.agreementId()) } shouldBe MasterRejection.AGREEMENT_NOT_FOUND
            val other = MasterFixture.issued()
            rejection { LimitTarget.csaDefault(issued.merchant, other.contract, other.contract.agreements.single().id) } shouldBe
                MasterRejection.CONTRACT_MERCHANT_MISMATCH
            shouldThrow<IllegalArgumentException> { LimitValue.Limited(-1) }
        }
    }

    Given("the policy version registry (identification only, D-MS2-003)") {
        fun register(code: String, existing: List<PolicyVersion>, kind: PolicyKind = PolicyKind.FEE_SCHEDULE, hash: String? = null) =
            PolicyVersion.register(PolicyVersionId("pv_${MasterFixture.ulid()}"), kind, PolicyCode(code), existing, PolicyContentStatus.DEMO, hash, reason, t0, operator)

        Then("version numbers start at 1 and increase per (kind, code); a malformed content hash is refused") {
            val v1 = register("FEE-DEMO", emptyList())
            val v2 = register("FEE-DEMO", listOf(v1))
            listOf(v1.versionNo, v2.versionNo, register("STL-DEMO", emptyList(), PolicyKind.SETTLEMENT_POLICY).versionNo) shouldBe listOf(1, 2, 1)
            rejection { register("FEE-DEMO", emptyList(), hash = "ABC") } shouldBe MasterRejection.POLICY_CONTENT_HASH_INVALID
            register("FEE-DEMO", emptyList(), hash = "a".repeat(64)).contentHash shouldBe "a".repeat(64)
        }
    }

    Given("policy assignments of an issued MID (D-MD-018)") {
        val issued = MasterFixture.issued()
        val account = issued.issuance.account
        val csa = issued.contract.agreements.single().id
        val fee = PolicyVersion.register(PolicyVersionId("pv_${MasterFixture.ulid()}"), PolicyKind.FEE_SCHEDULE, PolicyCode("FEE-DEMO"), emptyList(), PolicyContentStatus.DEMO, null, reason, t0, operator)
        val settlement = PolicyVersion.register(PolicyVersionId("pv_${MasterFixture.ulid()}"), PolicyKind.SETTLEMENT_POLICY, PolicyCode("STL-DEMO"), emptyList(), PolicyContentStatus.DEMO, null, reason, t0, operator)

        fun assign(version: PolicyVersion, period: EffectivePeriod, existing: List<PolicyAssignment> = emptyList(), agreement: ContractServiceAgreementId = csa, unit: CurrencyUnit = krw) =
            PolicyAssignment.assign(PolicyAssignmentId("pas_${MasterFixture.ulid()}"), account, agreement, unit, version, period, existing, reason, t0, operator)

        Then("the CSA must be the MID's issuing CSA; any instant may be a boundary but not before the command time") {
            rejection { assign(fee, EffectivePeriod(t0), agreement = MasterFixture.agreementId()) } shouldBe MasterRejection.POLICY_ASSIGNMENT_AGREEMENT_MISMATCH
            rejection { assign(fee, EffectivePeriod(t0.minusMillis(1))) } shouldBe MasterRejection.POLICY_ASSIGNMENT_RETROACTIVE
            assign(fee, EffectivePeriod(t0.plusSeconds(7))).agreementId shouldBe csa
        }

        Then("one assignment per (currency, kind) at a time; fee and settlement are independent; a replacement must keep the kind") {
            val feeKrw = assign(fee, EffectivePeriod(t0))
            rejection { assign(fee, EffectivePeriod(t0.plusSeconds(60)), listOf(feeKrw)) } shouldBe MasterRejection.POLICY_ASSIGNMENT_OVERLAP
            assign(settlement, EffectivePeriod(t0), listOf(feeKrw)).kind shouldBe PolicyKind.SETTLEMENT_POLICY
            assign(fee, EffectivePeriod(t0), listOf(feeKrw), unit = usd).currency shouldBe usd.code
            rejection {
                PolicyAssignment.replace(feeKrw, PolicyAssignmentId("pas_${MasterFixture.ulid()}"), account, settlement, t0.plusSeconds(60), listOf(feeKrw), krw, reason, t0, operator)
            } shouldBe MasterRejection.POLICY_KIND_MISMATCH
            val next = PolicyAssignment.replace(feeKrw, PolicyAssignmentId("pas_${MasterFixture.ulid()}"), account, fee, t0.plusSeconds(60), listOf(feeKrw), krw, reason, t0, operator)
            listOf(feeKrw.period.to, next.period.from) shouldBe listOf(t0.plusSeconds(60), t0.plusSeconds(60))
            rejection { feeKrw.end(t0.plusSeconds(90), t0, operator) } shouldBe MasterRejection.POLICY_ASSIGNMENT_ALREADY_ENDED
        }
    }

    Given("card route activations (D-MD-017, D-MS2-004 — transitions as D-MS-003)") {
        val registered = EntitlementFixture.registered()
        val account = registered.account
        val acquirer = AcquirerCode("SIMCARD01")

        fun register(existing: List<CardRouteActivation> = emptyList(), method: PaymentMethodActivation? = registered.activation, code: AcquirerCode = acquirer) =
            CardRouteActivation.register(
                CardRouteActivationId("cra_${MasterFixture.ulid()}"), account, method, code, RouteRef("SIM-VAN-01"), InstitutionMerchantRef("SIMMERCH0001"),
                existing, t0, operator,
            )

        Then("a route needs a non-terminated method activation, and one non-terminated route per (method, acquirer); another acquirer is independent") {
            rejection { register(method = null) } shouldBe MasterRejection.CARD_ROUTE_METHOD_NOT_ACTIVATED
            val (first, transition) = register()
            listOf(first.status, transition.from, transition.to) shouldBe listOf(ActivationStatus.UNDER_REVIEW, null, ActivationStatus.UNDER_REVIEW)
            shouldThrow<MasterRuleViolation> { register(listOf(first)) }.let { it.rejection to it.reference } shouldBe
                (MasterRejection.CARD_ROUTE_DUPLICATE to first.id.value)
            register(listOf(first), code = AcquirerCode("OTHERCARD")).first.acquirerCode shouldBe AcquirerCode("OTHERCARD")
            first.terminate(reason, t0, operator)
            register(listOf(first)).first.status shouldBe ActivationStatus.UNDER_REVIEW
        }

        Then("approve needs evidence; suspend → resume needs evidence never used before; terminate is irreversible") {
            val (route, _) = register()
            rejection { route.suspend(reason, t0, operator) } shouldBe MasterRejection.CARD_ROUTE_TRANSITION_NOT_ALLOWED
            route.approve(account, evidence, t0, operator).to shouldBe ActivationStatus.ACTIVE
            route.suspend(reason, t0, operator).to shouldBe ActivationStatus.SUSPENDED
            rejection { route.resume(account, evidence, listOf(evidence), t0, operator) } shouldBe MasterRejection.CARD_ROUTE_EVIDENCE_NOT_NEW
            route.resume(account, newEvidence, listOf(evidence), t0, operator).to shouldBe ActivationStatus.ACTIVE
            route.terminate(reason, t0, operator).to shouldBe ActivationStatus.TERMINATED
            rejection { route.terminate(reason, t0, operator) } shouldBe MasterRejection.CARD_ROUTE_TRANSITION_NOT_ALLOWED
            rejection { route.approve(account, newEvidence, t0, operator) } shouldBe MasterRejection.CARD_ROUTE_TRANSITION_NOT_ALLOWED
        }

        Then("the institution merchant reference is internal-identifier grade: never in toString (D-MS2-005)") {
            val (route, _) = register()
            route.toString().contains("SIMMERCH0001") shouldBe false
            InstitutionMerchantRef("SIMMERCH0001").toString().contains("SIMMERCH0001") shouldBe false
            shouldThrow<IllegalArgumentException> { InstitutionMerchantRef("has space") }
            shouldThrow<IllegalArgumentException> { AcquirerCode("lower") }
        }
    }

    Given("Asia/Seoul midnight") {
        Then("is UTC 15:00:00.000 (KST has no daylight saving); other instants and sub-millisecond values are not boundaries") {
            listOf(
                SeoulMidnight.isBoundary(Instant.parse("2026-10-09T15:00:00Z")),
                SeoulMidnight.isBoundary(Instant.parse("2026-10-09T00:00:00Z")),
                SeoulMidnight.isBoundary(Instant.parse("2026-10-09T15:00:00.000000001Z")),
            ) shouldBe listOf(true, false, false)
        }
    }
})

package com.paycore.master.merchant.domain

import com.paycore.master.merchant.domain.EntitlementFixture.demoLease
import com.paycore.master.merchant.domain.EntitlementFixture.evidence
import com.paycore.master.merchant.domain.EntitlementFixture.krw
import com.paycore.master.merchant.domain.EntitlementFixture.operator
import com.paycore.master.merchant.domain.EntitlementFixture.reason
import com.paycore.master.merchant.domain.EntitlementFixture.t0
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.Instant

/**
 * Snapshot 조립의 승인 시작 조건(MASTER-SNAPSHOT-002 명세 §4.1): 그 MID 발급 CSA의 CSA 기본 + 그 MID Override만, Assignment는 정책 버전 식별과 함께,
 * 카드사 개통은 종료 아닌 것만 싣는다. 판정은 하지 않는다(해석은 PAYMENT). 값은 Demo.
 */
class AuthorizationTermsAssemblySpec : BehaviorSpec({

    val midnight1: Instant = Instant.parse("2026-10-09T15:00:00.000Z")

    Given("an assembled source with limits of the issuing CSA and of another CSA, two assignments and an active plus a terminated route") {
        val active = EntitlementFixture.active()
        val issued = active.registered.issued
        val account = active.account
        val csa = issued.contract.agreements.single().id
        // 같은 Merchant의 다른 계약 CSA 기본 정책(저장 상태 복원 — 이 MID의 발급 CSA가 아니므로 이 MID Snapshot에 실리면 안 된다).
        val otherCsa = ContractServiceAgreementId("csa_${MasterFixture.ulid()}")
        val csaDefault = TransactionLimitPolicy.register(
            TransactionLimitPolicyId("tlp_${MasterFixture.ulid()}"), LimitTarget.csaDefault(issued.merchant, issued.contract, csa), krw,
            LimitValue.Limited(1_000_000), LimitValue.Limited(5_000_000), EffectivePeriod(midnight1), emptyList(), reason, t0, operator,
        )
        val otherDefault = TransactionLimitPolicy.reconstitute(
            TransactionLimitPolicyId("tlp_${MasterFixture.ulid()}"), issued.merchant.id, LimitScope.CsaDefault(otherCsa), krw.code, LimitValue.Unlimited,
            LimitValue.Unlimited, EffectivePeriod(midnight1), reason, t0, operator, null, null,
        )
        val override = TransactionLimitPolicy.register(
            TransactionLimitPolicyId("tlp_${MasterFixture.ulid()}"), LimitTarget.midOverride(issued.merchant, account), krw,
            LimitValue.Limited(300_000), LimitValue.Unlimited, EffectivePeriod(midnight1), emptyList(), reason, t0, operator,
        )
        val feeVersion = PolicyVersion.register(PolicyVersionId("pv_${MasterFixture.ulid()}"), PolicyKind.FEE_SCHEDULE, PolicyCode("FEE-DEMO"), emptyList(), PolicyContentStatus.DEMO, null, reason, t0, operator)
        val fee = PolicyAssignment.assign(PolicyAssignmentId("pas_${MasterFixture.ulid()}"), account, csa, krw, feeVersion, EffectivePeriod(t0), emptyList(), reason, t0, operator)
        val (route, _) = CardRouteActivation.register(
            CardRouteActivationId("cra_${MasterFixture.ulid()}"), account, active.activation, AcquirerCode("SIMCARD01"), RouteRef("SIM-VAN-01"),
            InstitutionMerchantRef("SIMMERCH0001"), emptyList(), t0, operator,
        )
        route.approve(account, evidence, t0, operator)
        val (terminated, _) = CardRouteActivation.register(
            CardRouteActivationId("cra_${MasterFixture.ulid()}"), account, active.activation, AcquirerCode("OLDCARD"), RouteRef("SIM-VAN-01"),
            InstitutionMerchantRef("SIMMERCH0002"), listOf(route), t0, operator,
        )
        terminated.terminate(reason, t0, operator)
        val base = EntitlementFixture.source(active)
        val source = base.copy(
            accounts = base.accounts.map {
                it.copy(
                    limitPolicies = listOf(csaDefault, otherDefault, override), policyAssignments = listOf(fee), policyVersions = listOf(feeVersion),
                    cardRoutes = listOf(route, terminated),
                )
            },
        )
        val snapshot = EntitlementSnapshotAssembler.assemble(source, t0, 0, demoLease)
        val state = snapshot.accounts.single()

        Then("only the issuing CSA default and this MID's override are carried, with their explicit values") {
            state.limitPolicies.map { it.policyId to it.scope }.toSet() shouldBe
                setOf(csaDefault.id to LimitScopeKind.CSA_DEFAULT, override.id to LimitScopeKind.MID_OVERRIDE)
            state.limitPolicies.single { it.scope == LimitScopeKind.MID_OVERRIDE }.let { listOf(it.agreementId, it.perTransaction, it.daily) } shouldBe
                listOf(null, LimitValue.Limited(300_000), LimitValue.Unlimited)
        }

        Then("the assignment carries its policy version identity, and only the non-terminated route is carried") {
            state.policyAssignments.single().let { listOf(it.policyVersionId, it.policyCode, it.versionNo, it.contentStatus) } shouldBe
                listOf(feeVersion.id, PolicyCode("FEE-DEMO"), 1, PolicyContentStatus.DEMO)
            state.cardRoutes.map { it.acquirerCode to it.status } shouldBe listOf(AcquirerCode("SIMCARD01") to ActivationStatus.ACTIVE)
        }

        Then("the canonical content carries the v2 fields; reassembling the same source at a later lease gives the same content hash") {
            snapshot.canonicalContent shouldContain "\"limitPolicies\":["
            snapshot.canonicalContent shouldContain "\"amountMinor\":null,\"kind\":\"UNLIMITED\""
            EntitlementSnapshotAssembler.assemble(source, t0.plusSeconds(30), 1, demoLease).contentHash shouldBe snapshot.contentHash
        }
    }
})

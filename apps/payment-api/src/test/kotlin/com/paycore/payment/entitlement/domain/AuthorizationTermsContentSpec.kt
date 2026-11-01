package com.paycore.payment.entitlement.domain

import com.paycore.payment.entitlement.domain.SnapshotFixture.INSTITUTION_REF
import com.paycore.payment.entitlement.domain.SnapshotFixture.assignment
import com.paycore.payment.entitlement.domain.SnapshotFixture.csaDefault
import com.paycore.payment.entitlement.domain.SnapshotFixture.entitled
import com.paycore.payment.entitlement.domain.SnapshotFixture.entitledWithTerms
import com.paycore.payment.entitlement.domain.SnapshotFixture.ids
import com.paycore.payment.entitlement.domain.SnapshotFixture.incoming
import com.paycore.payment.entitlement.domain.SnapshotFixture.mapTerms
import com.paycore.payment.entitlement.domain.SnapshotFixture.midOverride
import com.paycore.payment.entitlement.domain.SnapshotFixture.route
import com.paycore.payment.entitlement.domain.SnapshotFixture.seoulMidnight1
import com.paycore.payment.entitlement.domain.SnapshotFixture.seoulMidnight2
import com.paycore.payment.entitlement.domain.SnapshotFixture.t0
import com.paycore.payment.entitlement.domain.SnapshotFixture.termsIds
import com.paycore.payment.entitlement.domain.SnapshotFixture.ulid
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * schemaVersion 2 내용(MASTER-SNAPSHOT-002 명세 §4.1, §5.1): v2 필드는 v2에만 있고(v1 내용에 있으면·v2에 없으면 계약 위반), 수신 구조 불변식(같은 수준
 * 한도 중첩·CSA 불일치·같은 종류 Assignment 중첩·카드사 개통 중복·종료 개통)은 MASTER를 믿지 않고 다시 확인해 거절한다(4xx·저장 없음 → MASTER 격리).
 */
class AuthorizationTermsContentSpec : FunSpec({

    test("a v2 content round-trips through its canonical JSON with the same hash, and its hash differs from the v1 content of the same rights") {
        val ids = ids()
        val v2 = entitledWithTerms(ids)
        val parsed = EntitlementContent.fromJson(MiniJson.parse(v2.canonical), 2, allowLeaseFields = false)
        listOf(parsed.canonical, parsed.contentHash, parsed.schemaVersion) shouldBe listOf(v2.canonical, v2.contentHash, 2)
        v2.contentHash shouldNotBe entitled(ids).contentHash
    }

    test("v2 fields in a v1 content, missing v2 fields in a v2 content, or an unsupported version are contract violations") {
        val ids = ids()
        val v1 = entitled(ids).canonical
        val v2 = entitledWithTerms(ids).canonical
        val withoutRoutes = v2.replace(Regex(",\"cardRoutes\":\\[[^\\]]*\\]"), "")
        withoutRoutes shouldNotBe v2
        listOf<() -> Any>(
            { EntitlementContent.fromJson(MiniJson.parse(v2), 1, allowLeaseFields = false) },
            { EntitlementContent.fromJson(MiniJson.parse(v1), 2, allowLeaseFields = false) },
            { EntitlementContent.fromJson(MiniJson.parse(withoutRoutes), 2, allowLeaseFields = false) },
            { EntitlementContent.fromJson(MiniJson.parse(v1), 3, allowLeaseFields = false) },
            { entitled(ids).copy(schemaVersion = 2) },
            { entitledWithTerms(ids).copy(schemaVersion = 1) },
        ).forEach { shouldThrow<SnapshotContractViolation> { it() } }
    }

    test("an incoming snapshot whose envelope schema version differs from its content version is rejected") {
        val content = entitledWithTerms()
        shouldThrow<SnapshotContractViolation> { incoming(content).copy(schemaVersion = 1) }
        incoming(content).isSupported shouldBe true
    }

    test("an unlimited value carries no amount and a limited value a non-negative amount; an unknown limit kind is rejected") {
        val v2 = entitledWithTerms().canonical
        val first = "{\"amountMinor\":1000000,\"kind\":\"LIMITED\"}"
        listOf(
            v2.replaceFirst(first, "{\"amountMinor\":1000000,\"kind\":\"UNLIMITED\"}"),
            v2.replaceFirst(first, "{\"amountMinor\":-1,\"kind\":\"LIMITED\"}"),
            v2.replaceFirst(first, "{\"amountMinor\":1000000,\"kind\":\"SOMETIMES\"}"),
            v2.replaceFirst(first, "{\"amountMinor\":null,\"kind\":\"LIMITED\"}"),
        ).forEach { variant ->
            variant shouldNotBe v2
            shouldThrow<SnapshotContractViolation> { EntitlementContent.fromJson(MiniJson.parse(variant), 2, allowLeaseFields = false) }
        }
    }

    test("receive-time structural invariants reject ambiguity before any decision (MASTER is not trusted)") {
        val ids = ids()
        val terms = termsIds()
        val base = entitledWithTerms(ids, terms)
        mapOf<String, () -> Any>(
            "overlapping CSA defaults" to { base.mapTerms { it.copy(limitPolicies = it.limitPolicies + csaDefault(ids, "tlp_${ulid()}", seoulMidnight1)) } },
            "overlapping MID overrides" to {
                base.mapTerms { it.copy(limitPolicies = it.limitPolicies + midOverride("tlp_${ulid()}", seoulMidnight1) + midOverride("tlp_${ulid()}", seoulMidnight2)) }
            },
            "CSA default of another agreement" to {
                base.mapTerms { it.copy(limitPolicies = listOf(csaDefault(ids.copy(agreementId = "csa_${ulid()}"), "tlp_${ulid()}"))) }
            },
            "assignment of another agreement" to {
                base.mapTerms { it.copy(policyAssignments = it.policyAssignments.map { a -> a.copy(agreementId = "csa_${ulid()}") }) }
            },
            "overlapping fee assignments" to {
                base.mapTerms { it.copy(policyAssignments = it.policyAssignments + assignment(ids, "pas_${ulid()}", PolicyKind.FEE_SCHEDULE, "pv_${ulid()}", seoulMidnight1)) }
            },
            "two routes for the same acquirer" to { base.mapTerms { it.copy(cardRoutes = it.cardRoutes + route("cra_${ulid()}", ActivationStatus.SUSPENDED)) } },
            "a terminated route" to { base.mapTerms { it.copy(cardRoutes = listOf(route(terms.route, ActivationStatus.TERMINATED))) } },
            "duplicate policy ids" to { base.mapTerms { it.copy(limitPolicies = it.limitPolicies + midOverride(terms.csaDefault, seoulMidnight1)) } },
        ).forEach { (name, build) ->
            val error = runCatching { build() }.exceptionOrNull()
            (name to (error is SnapshotContractViolation)) shouldBe (name to true)
        }
    }

    // INST-CODE-001 IC-4(D-INST-CODE-001): 카드사 개통의 매입사·Route 코드는 기관 식별 코드 공통 형식 ^[A-Z0-9]{1,32}$ — 수신(wire)·구성 모두 계약 위반.
    test("a card route whose acquirer or route code is not the common institution code format is a contract violation") {
        val terms = termsIds()
        val base = entitledWithTerms(ids(), terms)
        mapOf<String, () -> Any>(
            "hyphenated acquirer" to { base.mapTerms { it.copy(cardRoutes = listOf(route(terms.route, acquirer = "SIMACQ-A"))) } },
            "underscore acquirer" to { base.mapTerms { it.copy(cardRoutes = listOf(route(terms.route, acquirer = "SIM_ACQ_A"))) } },
            "lower-case acquirer" to { base.mapTerms { it.copy(cardRoutes = listOf(route(terms.route, acquirer = "simacqa"))) } },
            "acquirer over 32" to { base.mapTerms { it.copy(cardRoutes = listOf(route(terms.route, acquirer = "A".repeat(33)))) } },
            "hyphenated route" to { base.mapTerms { it.copy(cardRoutes = listOf(route(terms.route).copy(routeRef = "SIM-VAN-01"))) } },
            "underscore route" to { base.mapTerms { it.copy(cardRoutes = listOf(route(terms.route).copy(routeRef = "SIM_VAN_01"))) } },
            "dotted route over the old grammar" to { base.mapTerms { it.copy(cardRoutes = listOf(route(terms.route).copy(routeRef = "sim.van:01"))) } },
        ).forEach { (name, build) ->
            val error = runCatching { build() }.exceptionOrNull()
            (name to (error is SnapshotContractViolation)) shouldBe (name to true)
        }
        val wire = base.canonical
        listOf("\"acquirerCode\":\"SIMCARD01\"" to "\"acquirerCode\":\"SIM-CARD-01\"", "\"routeRef\":\"SIMVAN01\"" to "\"routeRef\":\"SIM_VAN_01\"").forEach { (from, to) ->
            val variant = wire.replace(from, to)
            variant shouldNotBe wire
            shouldThrow<SnapshotContractViolation> { EntitlementContent.fromJson(MiniJson.parse(variant), 2, allowLeaseFields = false) }
        }
        base.mapTerms { it.copy(cardRoutes = listOf(route(terms.route, acquirer = "A".repeat(32)))) }.schemaVersion shouldBe 2
    }

    test("same-level non-overlapping periods and a CSA default overlapping a MID override are accepted (Override wins at decision time, D-MS2-001)") {
        val ids = ids()
        val content = entitledWithTerms(ids).mapTerms {
            it.copy(
                limitPolicies = listOf(
                    csaDefault(ids, "tlp_${ulid()}", t0, seoulMidnight1),
                    csaDefault(ids, "tlp_${ulid()}", seoulMidnight1),
                    midOverride("tlp_${ulid()}", seoulMidnight2),
                ),
            )
        }
        content.accounts.single().terms!!.limitPolicies.size shouldBe 3
    }

    test("the institution merchant reference never appears in toString of the route entry or the content (D-MS2-005)") {
        val content = entitledWithTerms()
        content.accounts.single().terms!!.cardRoutes.single().toString().contains(INSTITUTION_REF) shouldBe false
        content.toString().contains(INSTITUTION_REF) shouldBe false
        content.canonical.contains(INSTITUTION_REF) shouldBe true
    }
})

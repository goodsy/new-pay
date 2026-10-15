package com.paycore.payment.entitlement.domain

import com.paycore.payment.entitlement.domain.SnapshotFixture.ACQUIRER
import com.paycore.payment.entitlement.domain.SnapshotFixture.assignment
import com.paycore.payment.entitlement.domain.SnapshotFixture.csaDefault
import com.paycore.payment.entitlement.domain.SnapshotFixture.entitled
import com.paycore.payment.entitlement.domain.SnapshotFixture.entitledWithTerms
import com.paycore.payment.entitlement.domain.SnapshotFixture.headOf
import com.paycore.payment.entitlement.domain.SnapshotFixture.ids
import com.paycore.payment.entitlement.domain.SnapshotFixture.leaseAt
import com.paycore.payment.entitlement.domain.SnapshotFixture.mapAccount
import com.paycore.payment.entitlement.domain.SnapshotFixture.mapTerms
import com.paycore.payment.entitlement.domain.SnapshotFixture.midOverride
import com.paycore.payment.entitlement.domain.SnapshotFixture.route
import com.paycore.payment.entitlement.domain.SnapshotFixture.seoulMidnight1
import com.paycore.payment.entitlement.domain.SnapshotFixture.seoulMidnight2
import com.paycore.payment.entitlement.domain.SnapshotFixture.skew
import com.paycore.payment.entitlement.domain.SnapshotFixture.t0
import com.paycore.payment.entitlement.domain.SnapshotFixture.termsIds
import com.paycore.payment.entitlement.domain.SnapshotFixture.termsQuestion
import com.paycore.payment.entitlement.domain.SnapshotFixture.ulid
import com.paycore.payment.entitlement.domain.SnapshotFixture.unsupported
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Duration
import java.time.Instant

/**
 * 승인 시작 허용 판정 결정표(MASTER-SNAPSHOT-002 명세 §6.2, D-AUTH-006, D-MD-017·018, D-MS2-001·004). 정상 Fixture(승인 시작 가능 v2)에서 조건을
 * 하나씩 바꿔 앞 단계부터 멈추는지 본다. 기대값은 명세 표에서 직접 적는다(구현 식 복제 없음). 한도·정책 값은 Demo.
 */
class AuthorizationTermsEvaluatorSpec : BehaviorSpec({

    val t = t0.plusSeconds(10)
    val longLease = leaseAt(t0, 0, Duration.ofDays(3))

    fun decide(content: EntitlementContent, ids: SnapshotFixture.Ids, at: Instant = t, now: Instant = at, lease: Lease = leaseAt(), head: EntitlementHead? = headOf(content, lease), acquirer: String = ACQUIRER, currency: String = "KRW") =
        AuthorizationTermsEvaluator.evaluate(head, content, termsQuestion(ids, acquirer, currency), at, now, skew)

    Given("a v2 snapshot where authorization can start (entitled + CSA default limit + fee/settlement assignments + ACTIVE card route)") {
        val ids = ids()
        val terms = termsIds()
        val content = entitledWithTerms(ids, terms)

        Then("Admitted with the entitlement version, the route, both assignments, the CSA default limit and validUntil = leaseUntil − skew") {
            val admitted = decide(content, ids).shouldBeInstanceOf<AuthorizationTermsDecision.Admitted>()
            listOf(
                admitted.entitlementVersion, admitted.route.activationId, admitted.feeSchedule.assignmentId, admitted.settlementPolicy.assignmentId,
                (admitted.limit as LimitPolicyChoice.Resolved).policy.policyId, admitted.validUntil, admitted.evaluatedAt,
            ) shouldBe listOf(
                "es1:${ids.merchantId}:1", terms.route, terms.feeAssignment, terms.settlementAssignment, terms.csaDefault, t0.plusSeconds(60).minus(skew), t,
            )
        }

        Then("A0 comes first: missing/blocked/expired snapshot → Unavailable; a v1 head → TERMS_NOT_PROPAGATED even when the rights would be refused") {
            val v1 = entitled(ids)
            val v1Refusing = entitled(ids).copy(sites = v1.sites.map { it.copy(reviewStatus = SiteReviewStatus.PENDING) })
            mapOf(
                "no head" to decide(content, ids, head = null),
                "blocked" to decide(content, ids, head = SnapshotApplicationRule.decide(headOf(content), unsupported(ids.merchantId, 9)).newHead),
                "lease expired" to decide(content, ids, at = t0.plusSeconds(60).minus(skew)),
                "v1 head" to decide(v1, ids),
                "v1 head refusing" to decide(v1Refusing, ids),
            ) shouldBe mapOf(
                "no head" to AuthorizationTermsDecision.Unavailable(TermsUnavailableReason.SNAPSHOT_MISSING),
                "blocked" to AuthorizationTermsDecision.Unavailable(TermsUnavailableReason.VERSION_UNSUPPORTED),
                "lease expired" to AuthorizationTermsDecision.Unavailable(TermsUnavailableReason.SNAPSHOT_EXPIRED),
                "v1 head" to AuthorizationTermsDecision.Unavailable(TermsUnavailableReason.TERMS_NOT_PROPAGATED),
                "v1 head refusing" to AuthorizationTermsDecision.Unavailable(TermsUnavailableReason.TERMS_NOT_PROPAGATED),
            )
        }

        Then("A1 re-evaluates the rights (creation-time eligibility does not stand in): currency, site review, unknown site") {
            val pendingReview = content.copy(sites = content.sites.map { it.copy(reviewStatus = SiteReviewStatus.PENDING) })
            val suspendedActivation = content.mapAccount { a -> a.copy(activations = a.activations.map { it.copy(status = ActivationStatus.SUSPENDED) }) }
            mapOf(
                "USD" to decide(content, ids, currency = "USD"),
                "site review" to decide(pendingReview, ids),
                "method suspended" to decide(suspendedActivation, ids),
                "unknown site" to AuthorizationTermsEvaluator.evaluate(headOf(content), content, termsQuestion(ids).copy(siteId = "site_${ulid()}"), t, t, skew),
            ) shouldBe mapOf(
                "USD" to AuthorizationTermsDecision.Refused(TermsRefusalReason.CURRENCY_NOT_ENABLED, RefusalDetail.CURRENCY),
                "site review" to AuthorizationTermsDecision.Refused(TermsRefusalReason.METHOD_NOT_ENABLED, RefusalDetail.SITE_REVIEW),
                "method suspended" to AuthorizationTermsDecision.Refused(TermsRefusalReason.METHOD_NOT_ENABLED, RefusalDetail.ACTIVATION),
                "unknown site" to AuthorizationTermsDecision.NotFoundOrForbidden(RefusalDetail.SITE_UNKNOWN),
            )
        }

        Then("A2: an acquirer without a card route, or with a route that is not ACTIVE, is CARD_COMPANY_NOT_ENABLED — before policy assignments are looked at") {
            val noAssignments = content.mapTerms { it.copy(policyAssignments = emptyList()) }
            val refused = AuthorizationTermsDecision.Refused(TermsRefusalReason.CARD_COMPANY_NOT_ENABLED, null)
            listOf(ActivationStatus.UNDER_REVIEW, ActivationStatus.SUSPENDED).map { s ->
                decide(content.mapTerms { it.copy(cardRoutes = listOf(route(terms.route, s))) }, ids)
            } + decide(content, ids, acquirer = "OTHERCARD") + decide(noAssignments, ids, acquirer = "OTHERCARD") shouldBe List(4) { refused }
        }

        Then("A3: each of fee and settlement needs exactly one assignment effective at t for the issuing CSA and currency (D-MD-018)") {
            val feeOnly = content.mapTerms { it.copy(policyAssignments = it.policyAssignments.filter { a -> a.kind == PolicyKind.FEE_SCHEDULE }) }
            val settlementEnded = content.mapTerms {
                it.copy(policyAssignments = it.policyAssignments.map { a -> if (a.kind == PolicyKind.SETTLEMENT_POLICY) a.copy(period = Interval(t0, t)) else a })
            }
            val feeLater = content.mapTerms {
                it.copy(policyAssignments = it.policyAssignments.map { a -> if (a.kind == PolicyKind.FEE_SCHEDULE) a.copy(period = Interval(seoulMidnight1, null)) else a })
            }
            mapOf("fee only" to decide(feeOnly, ids), "settlement ended at t" to decide(settlementEnded, ids), "fee starts later" to decide(feeLater, ids)) shouldBe
                mapOf(
                    "fee only" to AuthorizationTermsDecision.Unavailable(TermsUnavailableReason.POLICY_ASSIGNMENT_MISSING),
                    "settlement ended at t" to AuthorizationTermsDecision.Unavailable(TermsUnavailableReason.POLICY_ASSIGNMENT_MISSING),
                    "fee starts later" to AuthorizationTermsDecision.Unavailable(TermsUnavailableReason.POLICY_ASSIGNMENT_MISSING),
                )
        }

        Then("A4: no limit policy effective at t is Missing (passed on — AuthorizationStart refuses it), not an Admitted with a default") {
            val noLimits = content.mapTerms { it.copy(limitPolicies = emptyList()) }
            decide(noLimits, ids).shouldBeInstanceOf<AuthorizationTermsDecision.Admitted>().limit shouldBe LimitPolicyChoice.Missing
        }
    }

    Given("a CSA default limit until the first Seoul midnight and a MID override from it (D-MS2-001 Override wins, whole policy)") {
        val ids = ids()
        val defaultId = "tlp_${ulid()}"
        val overrideId = "tlp_${ulid()}"
        val content = entitledWithTerms(ids).mapTerms {
            it.copy(limitPolicies = listOf(csaDefault(ids, defaultId, t0), midOverride(overrideId, seoulMidnight1, seoulMidnight2)))
        }

        fun limitAt(at: Instant) = (decide(content, ids, at = at, lease = longLease).shouldBeInstanceOf<AuthorizationTermsDecision.Admitted>())

        Then("before the override starts the CSA default applies and validUntil is cut at the override start (it changes the resolution)") {
            val admitted = limitAt(t)
            listOf((admitted.limit as LimitPolicyChoice.Resolved).policy.policyId, admitted.validUntil) shouldBe listOf(defaultId, seoulMidnight1)
        }

        Then("while the override is effective it replaces the default entirely; after it ends the default applies again") {
            val during = limitAt(seoulMidnight1)
            val resolved = (during.limit as LimitPolicyChoice.Resolved).policy
            listOf(resolved.policyId, resolved.perTransaction, resolved.daily, during.validUntil) shouldBe
                listOf(overrideId, LimitBoundEntry.Limited(300_000), LimitBoundEntry.Unlimited, seoulMidnight2)
            ((limitAt(seoulMidnight2).limit) as LimitPolicyChoice.Resolved).policy.policyId shouldBe defaultId
        }

        Then("a decision delayed by lock waits past validUntil is SNAPSHOT_EXPIRED (t allowed and now must agree)") {
            decide(content, ids, at = seoulMidnight1.minusMillis(1), now = seoulMidnight1, lease = longLease) shouldBe
                AuthorizationTermsDecision.Unavailable(TermsUnavailableReason.SNAPSHOT_EXPIRED)
        }
    }

    Given("a CSA default that ends at a Seoul midnight with no successor") {
        val ids = ids()
        val content = entitledWithTerms(ids).mapTerms { it.copy(limitPolicies = listOf(csaDefault(ids, "tlp_${ulid()}", t0, seoulMidnight1))) }

        Then("before the end it resolves with validUntil = the end; from the end the limit is Missing (fail-closed, no inherited default)") {
            val before = decide(content, ids, lease = longLease).shouldBeInstanceOf<AuthorizationTermsDecision.Admitted>()
            before.validUntil shouldBe seoulMidnight1
            decide(content, ids, at = seoulMidnight1, lease = longLease).shouldBeInstanceOf<AuthorizationTermsDecision.Admitted>().limit shouldBe
                LimitPolicyChoice.Missing
        }

        Then("an assignment of another currency does not count, and an assignment ending inside the lease cuts validUntil") {
            val shortAssignment = content.mapTerms {
                it.copy(policyAssignments = it.policyAssignments.map { a -> if (a.kind == PolicyKind.FEE_SCHEDULE) a.copy(period = Interval(t0, t.plusSeconds(5))) else a })
            }
            decide(shortAssignment, ids).shouldBeInstanceOf<AuthorizationTermsDecision.Admitted>().validUntil shouldBe t.plusSeconds(5)
            val usdOnly = content.mapTerms { it.copy(policyAssignments = it.policyAssignments.map { a -> a.copy(currency = "USD") } + assignment(ids, "pas_${ulid()}", PolicyKind.SETTLEMENT_POLICY, "pv_${ulid()}")) }
            decide(usdOnly, ids) shouldBe AuthorizationTermsDecision.Unavailable(TermsUnavailableReason.POLICY_ASSIGNMENT_MISSING)
        }
    }
})

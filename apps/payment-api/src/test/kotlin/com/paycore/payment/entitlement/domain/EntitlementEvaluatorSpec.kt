package com.paycore.payment.entitlement.domain

import com.paycore.payment.entitlement.domain.SnapshotFixture.entitled
import com.paycore.payment.entitlement.domain.SnapshotFixture.headOf
import com.paycore.payment.entitlement.domain.SnapshotFixture.ids
import com.paycore.payment.entitlement.domain.SnapshotFixture.mapAccount
import com.paycore.payment.entitlement.domain.SnapshotFixture.question
import com.paycore.payment.entitlement.domain.SnapshotFixture.skew
import com.paycore.payment.entitlement.domain.SnapshotFixture.t0
import com.paycore.payment.entitlement.domain.SnapshotFixture.unsupported
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant

/**
 * 결제 생성 판정 결정표(명세 §6, D-MD-016~019, D-MS-001·002). 정상 Fixture(결제 가능)에서 조건 하나씩 바꿔 앞 단계부터 멈추는지 본다.
 * 시각 경계(n-5): 명령 없이 바뀌는 구간 경계는 재발행 없이 판정을 바꾸고, Allowed의 validUntil은 그 경계(또는 lease − skew)이며 평가 시각보다
 * 항상 뒤다(R12 — validUntil == evaluatedAt인 Allowed는 없다).
 */
class EntitlementEvaluatorSpec : BehaviorSpec({

    val t = t0.plusSeconds(10)

    fun decide(content: EntitlementContent, q: EligibilityQuestion, at: Instant = t, now: Instant = at, head: EntitlementHead? = headOf(content)) =
        EntitlementEvaluator.evaluate(head, content, q, at, now, skew)

    Given("an entitled merchant snapshot (approved site, effective PRIMARY, contract window, CARD ACTIVE + site confirmation, KRW)") {
        val ids = ids()
        val content = entitled(ids)

        Then("the decision is Allowed with version es1:<merchant>:<revision>, the KRW unit and validUntil = leaseUntil − skew") {
            decide(content, question(ids)) shouldBe EntitlementDecision.Allowed(
                "es1:${ids.merchantId}:1", "KRW", 0, t0.plusSeconds(60).minus(skew),
            )
        }

        Then("Unavailable comes first: no head / empty head → SNAPSHOT_MISSING, blocked head → VERSION_UNSUPPORTED, MID absent → SNAPSHOT_MISSING") {
            mapOf(
                "no head" to decide(content, question(ids), head = null),
                "empty head" to decide(content, question(ids), head = EntitlementHead.empty(ids.merchantId)),
                "blocked" to decide(content, question(ids), head = SnapshotApplicationRule.decide(headOf(content), unsupported(ids.merchantId, 9)).newHead),
                "mid absent" to decide(content, question(ids).copy(mid = "mid_${SnapshotFixture.ulid()}")),
            ) shouldBe mapOf(
                "no head" to EntitlementDecision.Unavailable(UnavailableReason.SNAPSHOT_MISSING),
                "empty head" to EntitlementDecision.Unavailable(UnavailableReason.SNAPSHOT_MISSING),
                "blocked" to EntitlementDecision.Unavailable(UnavailableReason.VERSION_UNSUPPORTED),
                "mid absent" to EntitlementDecision.Unavailable(UnavailableReason.SNAPSHOT_MISSING),
            )
        }

        Then("relationship failures are NotFoundOrForbidden: unknown/other merchant site, terminated site, site without assignment, suspended assignment, no PRIMARY") {
            val suspendedAssignment = content.mapAccount { a -> a.copy(assignments = a.assignments.map { it.copy(status = AssignmentStatus.SUSPENDED) }) }
            val additionalOnly = content.mapAccount { a -> a.copy(assignments = a.assignments.map { it.copy(role = AssignmentRole.ADDITIONAL) }) }
            mapOf(
                "unknown site" to decide(content, question(ids, site = "site_${SnapshotFixture.ulid()}")),
                "terminated site" to decide(content.copy(sites = content.sites.map { it.copy(lifecycle = Lifecycle.TERMINATED) }), question(ids)),
                "no assignment" to decide(content, question(ids, site = ids.otherSiteId)),
                "suspended assignment" to decide(suspendedAssignment, question(ids)),
                "additional only" to decide(additionalOnly, question(ids)),
            ).mapValues { (it.value as EntitlementDecision.NotFoundOrForbidden).detail } shouldBe mapOf(
                "unknown site" to RefusalDetail.SITE_UNKNOWN,
                "terminated site" to RefusalDetail.SITE_TERMINATED,
                "no assignment" to RefusalDetail.ASSIGNMENT_NOT_EFFECTIVE,
                "suspended assignment" to RefusalDetail.ASSIGNMENT_NOT_EFFECTIVE,
                "additional only" to RefusalDetail.PRIMARY_NOT_EFFECTIVE,
            )
        }

        Then("operation stops are MERCHANT_SUSPENDED with the internal scope (Business / Merchant / MID — terminated or blocked, D-MD-019, D-MS-002(c))") {
            mapOf(
                "business terminated" to content.copy(party = content.party.copy(businessLifecycle = Lifecycle.TERMINATED)),
                "merchant blocked" to content.copy(party = content.party.copy(merchantBlocked = true)),
                "mid terminated" to content.mapAccount { it.copy(lifecycle = Lifecycle.TERMINATED) },
                "mid blocked" to content.mapAccount { it.copy(blocked = true) },
            ).mapValues { (_, c) -> decide(c, question(ids)).shouldBeInstanceOf<EntitlementDecision.Refused>().let { it.reason to it.suspendedScope } } shouldBe
                mapOf(
                    "business terminated" to (RefusalReason.MERCHANT_SUSPENDED to SuspendedScope.BUSINESS),
                    "merchant blocked" to (RefusalReason.MERCHANT_SUSPENDED to SuspendedScope.MERCHANT),
                    "mid terminated" to (RefusalReason.MERCHANT_SUSPENDED to SuspendedScope.MID),
                    "mid blocked" to (RefusalReason.MERCHANT_SUSPENDED to SuspendedScope.MID),
                )
        }

        Then("contract, review, activation, confirmation and currency refusals map to the fixed reasons in that order") {
            val noWindow = content.mapAccount { it.copy(contractWindows = emptyList()) }
            val pendingReview = content.copy(sites = content.sites.map { it.copy(reviewStatus = SiteReviewStatus.PENDING) })
            val underReview = content.mapAccount { it.copy(activations = listOf(ActivationEntry("CARD", ActivationStatus.UNDER_REVIEW))) }
            val noConfirmation = content.mapAccount { it.copy(siteConfirmations = emptyList()) }
            mapOf(
                "scope outside / window" to decide(noWindow, question(ids)),
                "site review pending" to decide(pendingReview, question(ids)),
                "activation under review" to decide(underReview, question(ids)),
                "no site confirmation" to decide(noConfirmation, question(ids)),
                "USD not enabled" to decide(content, question(ids, currency = "USD")),
                "both window and review missing" to decide(noWindow.copy(sites = pendingReview.sites), question(ids)),
            ).mapValues { (_, d) -> (d as EntitlementDecision.Refused).let { it.reason to it.detail } } shouldBe mapOf(
                "scope outside / window" to (RefusalReason.CONTRACT_NOT_EFFECTIVE to RefusalDetail.CONTRACT_WINDOW),
                "site review pending" to (RefusalReason.METHOD_NOT_ENABLED to RefusalDetail.SITE_REVIEW),
                "activation under review" to (RefusalReason.METHOD_NOT_ENABLED to RefusalDetail.ACTIVATION),
                "no site confirmation" to (RefusalReason.METHOD_NOT_ENABLED to RefusalDetail.SITE_CONFIRMATION),
                "USD not enabled" to (RefusalReason.CURRENCY_NOT_ENABLED to RefusalDetail.CURRENCY),
                "both window and review missing" to (RefusalReason.CONTRACT_NOT_EFFECTIVE to RefusalDetail.CONTRACT_WINDOW),
            )
        }
    }

    Given("lease boundaries (R3, R12)") {
        val ids = ids()
        val content = entitled(ids)
        val limit = t0.plusSeconds(60).minus(skew)

        Then("at exactly leaseUntil − skew the snapshot is expired; 1 ms before it is Allowed with validUntil strictly after the evaluation time") {
            decide(content, question(ids), at = limit) shouldBe EntitlementDecision.Unavailable(UnavailableReason.SNAPSHOT_EXPIRED)
            decide(content, question(ids), at = limit.minusMillis(1)).shouldBeInstanceOf<EntitlementDecision.Allowed>().validUntil shouldBe limit
        }

        Then("when now has passed the lease limit, expiry is reported before any relationship refusal (review n-1)") {
            decide(content, question(ids, site = "site_${SnapshotFixture.ulid()}"), at = limit.minusMillis(5), now = limit) shouldBe
                EntitlementDecision.Unavailable(UnavailableReason.SNAPSHOT_EXPIRED)
        }

        Then("an evaluation delayed by a lock wait (now ≥ validUntil) is SNAPSHOT_EXPIRED even though it was allowed at t") {
            decide(content, question(ids), at = limit.minusMillis(5), now = limit) shouldBe EntitlementDecision.Unavailable(UnavailableReason.SNAPSHOT_EXPIRED)
        }
    }

    Given("time boundaries inside the lease change the decision without a new snapshot (n-5)") {
        val ids = ids()
        val t1 = t0.plusSeconds(20)
        val t2 = t0.plusSeconds(40)

        Then("a future contract window start: refused before, allowed from the boundary") {
            val future = entitled(ids).mapAccount { a -> a.copy(contractWindows = a.contractWindows.map { it.copy(period = Interval(t1, null)) }) }
            decide(future, question(ids), at = t1.minusMillis(1)).shouldBeInstanceOf<EntitlementDecision.Refused>().reason shouldBe RefusalReason.CONTRACT_NOT_EFFECTIVE
            decide(future, question(ids), at = t1).shouldBeInstanceOf<EntitlementDecision.Allowed>()
        }

        Then("a scheduled assignment end: allowed until it with validUntil = the end, NotFoundOrForbidden from it") {
            val ending = entitled(ids).mapAccount { a -> a.copy(assignments = a.assignments.map { it.copy(period = Interval(t0, t2)) }) }
            decide(ending, question(ids), at = t1).shouldBeInstanceOf<EntitlementDecision.Allowed>().validUntil shouldBe t2
            decide(ending, question(ids), at = t2).shouldBeInstanceOf<EntitlementDecision.NotFoundOrForbidden>()
        }

        Then("a currency period end bounds validUntil and refuses CURRENCY_NOT_ENABLED from the end") {
            val ending = entitled(ids).mapAccount { a -> a.copy(currencies = listOf(CurrencyEntry("KRW", 0, Interval(t0, t1)))) }
            decide(ending, question(ids), at = t1.minusMillis(1)).shouldBeInstanceOf<EntitlementDecision.Allowed>().validUntil shouldBe t1
            decide(ending, question(ids), at = t1).shouldBeInstanceOf<EntitlementDecision.Refused>().reason shouldBe RefusalReason.CURRENCY_NOT_ENABLED
        }

        Then("the earliest used boundary wins (assignment end before contract end)") {
            val both = entitled(ids).mapAccount { a ->
                a.copy(
                    assignments = a.assignments.map { it.copy(period = Interval(t0, t1)) },
                    contractWindows = a.contractWindows.map { it.copy(period = Interval(t0, t2)) },
                )
            }
            decide(both, question(ids), at = t0.plusSeconds(1)).shouldBeInstanceOf<EntitlementDecision.Allowed>().validUntil shouldBe t1
        }
    }
})

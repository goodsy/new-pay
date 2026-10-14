package com.paycore.payment.entitlement.domain

import com.paycore.payment.entitlement.domain.SnapshotFixture.entitled
import com.paycore.payment.entitlement.domain.SnapshotFixture.headOf
import com.paycore.payment.entitlement.domain.SnapshotFixture.ids
import com.paycore.payment.entitlement.domain.SnapshotFixture.incoming
import com.paycore.payment.entitlement.domain.SnapshotFixture.leaseAt
import com.paycore.payment.entitlement.domain.SnapshotFixture.mapAccount
import com.paycore.payment.entitlement.domain.SnapshotFixture.t0
import com.paycore.payment.entitlement.domain.SnapshotFixture.unsupported
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Snapshot 적용 규칙 결정표(명세 §3.5, 허용 §4.2 R2: 늦게 도착한 과거 허용으로 차단을 되돌릴 수 없다).
 */
class SnapshotApplicationRuleSpec : BehaviorSpec({

    Given("an empty head (nothing applied yet)") {
        Then("the first supported snapshot is APPLIED with its content and lease") {
            val content = entitled()
            val decision = SnapshotApplicationRule.decide(EntitlementHead.empty(content.merchantId), incoming(content))
            decision.outcome shouldBe InboxOutcome.APPLIED
            decision.appliedContent shouldBe content
            decision.newHead!!.let { Triple(it.revision, it.contentHash, it.lease) } shouldBe Triple(1L, content.contentHash, leaseAt())
        }

        Then("an unsupported schema version blocks the head (fail-closed instead of using nothing or old rights)") {
            val decision = SnapshotApplicationRule.decide(EntitlementHead.empty("mer_X"), unsupported("mer_X", 3))
            decision.outcome shouldBe InboxOutcome.UNSUPPORTED
            decision.newHead!!.let { it.blocked to it.blockedRevision } shouldBe (HeadBlock.VERSION_UNSUPPORTED to 3L)
        }
    }

    Given("a head at revision 2") {
        val ids = ids()
        val r2 = entitled(ids, revision = 2)
        val head = headOf(r2)

        Then("revision 3 replaces it; revision 1 (a late past permission) is STALE and changes nothing (R2)") {
            SnapshotApplicationRule.decide(head, incoming(entitled(ids, revision = 3))).let { it.outcome to it.newHead?.revision } shouldBe
                (InboxOutcome.APPLIED to 3L)
            val late = SnapshotApplicationRule.decide(head, incoming(entitled(ids, revision = 1)))
            late.outcome shouldBe InboxOutcome.STALE
            late.newHead.shouldBeNull()
            late.appliedContent.shouldBeNull()
        }

        Then("the same revision with the same hash extends a longer lease (LEASE_EXTENDED) and ignores a shorter or equal one (STALE)") {
            val longer = SnapshotApplicationRule.decide(head, incoming(r2, leaseAt(t0.plusSeconds(30), seq = 1)))
            longer.outcome shouldBe InboxOutcome.LEASE_EXTENDED
            val extended = checkNotNull(longer.newHead)
            (extended.lease?.leaseUntil to extended.revision) shouldBe (t0.plusSeconds(90) to 2L)
            SnapshotApplicationRule.decide(head, incoming(r2, leaseAt())).outcome shouldBe InboxOutcome.STALE
        }

        Then("the same revision with different content is INTEGRITY_CONFLICT and blocks the head (missed revision increment)") {
            val tampered = r2.mapAccount { it.copy(activations = listOf(ActivationEntry("CARD", ActivationStatus.SUSPENDED))) }
            val decision = SnapshotApplicationRule.decide(head, incoming(tampered))
            decision.outcome shouldBe InboxOutcome.INTEGRITY_CONFLICT
            decision.newHead!!.let { Triple(it.revision, it.blocked, it.blockedRevision) } shouldBe Triple(2L, HeadBlock.INTEGRITY_CONFLICT, 2L)
        }

        Then("an unsupported newer version blocks; an unsupported older one is only recorded") {
            SnapshotApplicationRule.decide(head, unsupported(ids.merchantId, 5)).newHead!!.blockedRevision shouldBe 5L
            SnapshotApplicationRule.decide(head, unsupported(ids.merchantId, 1)).let { it.outcome to it.newHead } shouldBe (InboxOutcome.UNSUPPORTED to null)
        }
    }

    Given("a head blocked by an unsupported revision 5") {
        val ids = ids()
        val blocked = checkNotNull(SnapshotApplicationRule.decide(headOf(entitled(ids, revision = 2)), unsupported(ids.merchantId, 5)).newHead)

        Then("a supported revision 3 or 4 (older than the unreadable one) is STALE and the head stays blocked") {
            listOf(3L, 4L).map { SnapshotApplicationRule.decide(blocked, incoming(entitled(ids, revision = it))).outcome } shouldBe
                listOf(InboxOutcome.STALE, InboxOutcome.STALE)
        }

        Then("reprocessing revision 5 as a supported snapshot (after upgrade) or a newer revision applies and clears the block") {
            SnapshotApplicationRule.decide(blocked, incoming(entitled(ids, revision = 5))).newHead!!.let { it.revision to it.blocked } shouldBe (5L to null)
            SnapshotApplicationRule.decide(blocked, incoming(entitled(ids, revision = 6))).newHead!!.let { it.revision to it.blocked } shouldBe (6L to null)
        }
    }

    Given("a head blocked by an integrity conflict at revision 2") {
        val ids = ids()
        val r2 = entitled(ids, revision = 2)
        val tampered = r2.mapAccount { it.copy(blocked = true) }
        val blocked = checkNotNull(SnapshotApplicationRule.decide(headOf(r2), incoming(tampered)).newHead)

        Then("neither version of revision 2 unblocks it; only a newer revision does") {
            listOf(r2, tampered).map { SnapshotApplicationRule.decide(blocked, incoming(it, leaseAt(t0.plusSeconds(30), 1))).outcome } shouldBe
                listOf(InboxOutcome.STALE, InboxOutcome.STALE)
            SnapshotApplicationRule.decide(blocked, incoming(entitled(ids, revision = 3))).newHead!!.blocked.shouldBeNull()
        }
    }
})

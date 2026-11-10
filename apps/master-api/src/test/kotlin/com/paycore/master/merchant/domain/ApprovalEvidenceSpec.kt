package com.paycore.master.merchant.domain

import com.paycore.master.merchant.domain.ApprovalEvidenceFixtures.APPROVED_AT
import com.paycore.master.merchant.domain.ApprovalEvidenceFixtures.DEMO_WINDOW
import com.paycore.master.merchant.domain.ApprovalEvidenceFixtures.EXECUTE_BEFORE
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.time.Duration
import java.time.Instant

/**
 * MERCHANT-OPS-002 §8.2 B2-2-1 / §9.3: 승인 근거 Domain 판정 — 1 요청자 = 승인자, 2 실행 창(기한 ≤ 승인 시각·최대 창 초과·ADMIN 시계 앞섬 초과), 순서 1 → 2,
 * 4 잠금 뒤 시각 ≥ 기한(배타 끝). 경계는 명시 값(±1ms).
 */
class ApprovalEvidenceSpec : BehaviorSpec({

    fun violation(block: () -> Unit) = shouldThrow<MasterRuleViolation>(block).rejection
    val now: Instant = Instant.parse("2026-10-09T02:00:00.000Z")

    Given("judgment 1 — the requester and the approver") {
        Then("the same canonical actor is APPROVAL_SAME_ACTOR; distinct actors are admitted (control)") {
            violation { ApprovalEvidenceFixtures.sameActor().admit(now, DEMO_WINDOW) } shouldBe MasterRejection.APPROVAL_SAME_ACTOR
            ApprovalEvidenceFixtures.distinctActors().admit(now, DEMO_WINDOW)
        }

        Then("order 1 → 2: the same actor with an invalid window is reported as APPROVAL_SAME_ACTOR") {
            val both = ApprovalEvidenceFixtures.distinctActors(approvedBy = ApprovalEvidenceFixtures.REQUESTER, executeBefore = APPROVED_AT)
            violation { both.admit(now, DEMO_WINDOW) } shouldBe MasterRejection.APPROVAL_SAME_ACTOR
        }
    }

    Given("judgment 2 — the execution window (executeBefore exclusive, Demo window PT24H, skew PT1M)") {
        Then("executeBefore = approvedAt and executeBefore before approvedAt are APPROVAL_WINDOW_INVALID") {
            violation { ApprovalEvidenceFixtures.distinctActors(executeBefore = APPROVED_AT).admit(now, DEMO_WINDOW) } shouldBe MasterRejection.APPROVAL_WINDOW_INVALID
            violation { ApprovalEvidenceFixtures.distinctActors(executeBefore = APPROVED_AT.minusMillis(1)).admit(now, DEMO_WINDOW) } shouldBe
                MasterRejection.APPROVAL_WINDOW_INVALID
        }

        Then("exactly the maximum window is admitted; one millisecond more is APPROVAL_WINDOW_INVALID") {
            ApprovalEvidenceFixtures.distinctActors(executeBefore = APPROVED_AT.plus(Duration.ofHours(24))).admit(now, DEMO_WINDOW)
            violation { ApprovalEvidenceFixtures.windowExceeded().admit(now, DEMO_WINDOW) } shouldBe MasterRejection.APPROVAL_WINDOW_INVALID
        }

        Then("approvedAt = now + skew is admitted; one millisecond later is APPROVAL_WINDOW_INVALID (ADMIN clock ahead)") {
            val edge = now.plus(Duration.ofMinutes(1))
            ApprovalEvidenceFixtures.distinctActors(approvedAt = edge, executeBefore = edge.plusSeconds(60)).admit(now, DEMO_WINDOW)
            violation {
                ApprovalEvidenceFixtures.distinctActors(approvedAt = edge.plusMillis(1), executeBefore = edge.plusSeconds(60)).admit(now, DEMO_WINDOW)
            } shouldBe MasterRejection.APPROVAL_WINDOW_INVALID
        }
    }

    Given("judgment 4 — expiry at the time read after the business locks (m-4)") {
        Then("lockedNow = executeBefore is APPROVAL_EXPIRED (exclusive end); one millisecond before is allowed") {
            val evidence = ApprovalEvidenceFixtures.distinctActors()
            violation { evidence.requireNotExpired(EXECUTE_BEFORE) } shouldBe MasterRejection.APPROVAL_EXPIRED
            evidence.requireNotExpired(EXECUTE_BEFORE.minusMillis(1))
            violation { ApprovalEvidenceFixtures.expiredAt(now).requireNotExpired(now) } shouldBe MasterRejection.APPROVAL_EXPIRED
        }

        Then("isDueAt follows the same exclusive end (deadline confirmation §9.4)") {
            val evidence = ApprovalEvidenceFixtures.distinctActors()
            evidence.isDueAt(EXECUTE_BEFORE.minusMillis(1)) shouldBe false
            evidence.isDueAt(EXECUTE_BEFORE) shouldBe true
        }
    }

    Given("formats and non-exposure") {
        Then("approvalId, canonical actors (no lower case ULID), millisecond instants and the mop1 fingerprint are enforced") {
            shouldThrow<IllegalArgumentException> { ApprovalEvidenceFixtures.distinctActors(approvalId = "apr_short") }
            shouldThrow<IllegalArgumentException> { ApprovalEvidenceFixtures.distinctActors(requestedBy = ActorRef(ApprovalEvidenceFixtures.REQUESTER.value.lowercase())) }
            shouldThrow<IllegalArgumentException> { ApprovalEvidenceFixtures.distinctActors(approvedBy = ActorRef("system:demo-seed")) }
            shouldThrow<IllegalArgumentException> { ApprovalEvidenceFixtures.distinctActors(approvedAt = APPROVED_AT.plusNanos(1)) }
            shouldThrow<IllegalArgumentException> { ApprovalEvidenceFixtures.distinctActors(fingerprint = "mop1:" + "A".repeat(64)) }
            shouldThrow<IllegalArgumentException> { ApprovalWindow(Duration.ZERO, Duration.ofMinutes(1)) }
            shouldThrow<IllegalArgumentException> { ApprovalWindow(Duration.ofHours(1), Duration.ofSeconds(-1)) }
        }

        Then("toString shows only the approval id (no actors, evidence reference or fingerprint)") {
            val evidence = ApprovalEvidenceFixtures.distinctActors(fingerprint = "mop1:" + "ab".repeat(32))
            val text = evidence.toString()
            text shouldNotContain ApprovalEvidenceFixtures.REQUESTER.value
            text shouldNotContain ApprovalEvidenceFixtures.APPROVER.value
            text shouldNotContain ApprovalEvidenceFixtures.EVIDENCE_REF
            text shouldNotContain "abab"
        }
    }
})

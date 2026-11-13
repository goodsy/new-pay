package com.paycore.master.merchant.domain

import com.paycore.master.merchant.domain.EntitlementFixture.evidence
import com.paycore.master.merchant.domain.EntitlementFixture.newEvidence
import com.paycore.master.merchant.domain.EntitlementFixture.operator
import com.paycore.master.merchant.domain.EntitlementFixture.reason
import com.paycore.master.merchant.domain.EntitlementFixture.t0
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * Site 적용 확인(D-MD-016)·철회(D-MS-003)와 Site 심사 상태(D-MS-002 (a)).
 */
class SiteConfirmationAndReviewSpec : BehaviorSpec({

    fun rejection(block: () -> Unit): MasterRejection = shouldThrow<MasterRuleViolation>(block).rejection

    Given("an ACTIVE CARD activation with its PRIMARY confirmation") {
        Then("an ADDITIONAL site with an open assignment can be confirmed with evidence; a second valid confirmation of the same site is refused") {
            val a = EntitlementFixture.active()
            val issued = a.registered.issued
            val extra = MasterFixture.site(issued.merchant)
            val additional = SiteAssignment.start(MasterFixture.assignmentId(), a.account, extra, SiteAssignmentRole.ADDITIONAL, EffectivePeriod(t0), t0, operator)
            val assignments = listOf(a.registered.primary, additional)
            val c = SiteConfirmationRules.confirm(
                EntitlementFixture.confirmationId(), a.account, extra, a.activation, assignments, listOf(a.confirmation), newEvidence, t0, operator,
            )
            c.merchantSiteId shouldBe extra.id
            shouldThrow<MasterRuleViolation> {
                SiteConfirmationRules.confirm(
                    EntitlementFixture.confirmationId(), a.account, extra, a.activation, assignments, listOf(a.confirmation, c), newEvidence, t0, operator,
                )
            }.let { it.rejection to it.reference } shouldBe (MasterRejection.SITE_CONFIRMATION_DUPLICATE to c.id.value)
        }

        Then("a site without an open assignment, another merchant's site and a terminated activation are refused") {
            val a = EntitlementFixture.active()
            val issued = a.registered.issued
            val unassigned = MasterFixture.site(issued.merchant)
            val other = MasterFixture.site(MasterFixture.merchant(issued.business))
            val assignments = listOf(a.registered.primary)
            mapOf(
                "unassigned" to rejection {
                    SiteConfirmationRules.confirm(EntitlementFixture.confirmationId(), a.account, unassigned, a.activation, assignments, emptyList(), evidence, t0, operator)
                },
                "other merchant" to rejection {
                    SiteConfirmationRules.confirm(EntitlementFixture.confirmationId(), a.account, other, a.activation, assignments, emptyList(), evidence, t0, operator)
                },
            ) shouldBe mapOf("unassigned" to MasterRejection.ASSIGNMENT_NOT_OPEN, "other merchant" to MasterRejection.SITE_OF_OTHER_MERCHANT)

            a.activation.terminate(reason, t0, operator)
            rejection {
                SiteConfirmationRules.confirm(EntitlementFixture.confirmationId(), a.account, issued.site, a.activation, assignments, emptyList(), evidence, t0, operator)
            } shouldBe MasterRejection.ACTIVATION_TERMINATED
        }

        Then("revocation is recorded once; afterwards the site can be confirmed again with a new confirmation row") {
            val a = EntitlementFixture.active()
            val issued = a.registered.issued
            a.confirmation.revoke(reason, newEvidence, t0, operator).evidenceRef shouldBe newEvidence
            a.confirmation.isValid shouldBe false
            rejection { a.confirmation.revoke(reason, newEvidence, t0, operator) } shouldBe MasterRejection.SITE_CONFIRMATION_ALREADY_REVOKED
            val again = SiteConfirmationRules.confirm(
                EntitlementFixture.confirmationId(), a.account, issued.site, a.activation, listOf(a.registered.primary), listOf(a.confirmation),
                evidence, t0, operator,
            )
            (again.id == a.confirmation.id) shouldBe false
            again.isValid shouldBe true
        }

        Then("a suspended activation keeps its confirmations and can still receive new ones (only TERMINATED stops confirmation)") {
            val a = EntitlementFixture.active()
            a.activation.suspend(reason, t0, operator)
            a.confirmation.isValid shouldBe true
            val issued = a.registered.issued
            val extra = MasterFixture.site(issued.merchant)
            val additional = SiteAssignment.start(MasterFixture.assignmentId(), a.account, extra, SiteAssignmentRole.ADDITIONAL, EffectivePeriod(t0), t0, operator)
            SiteConfirmationRules.confirm(
                EntitlementFixture.confirmationId(), a.account, extra, a.activation, listOf(a.registered.primary, additional), listOf(a.confirmation),
                evidence, t0, operator,
            ).isValid shouldBe true
        }
    }

    Given("an UNDER_REVIEW activation (review M-1: the PRIMARY confirmation is recorded only by approval)") {
        Then("confirming the PRIMARY site before approval is refused; an ADDITIONAL site may be confirmed") {
            val r = EntitlementFixture.registered()
            rejection {
                SiteConfirmationRules.confirm(
                    EntitlementFixture.confirmationId(), r.account, r.issued.site, r.activation, listOf(r.primary), emptyList(), evidence, t0, operator,
                )
            } shouldBe MasterRejection.PRIMARY_CONFIRMED_BY_APPROVAL
            val extra = MasterFixture.site(r.issued.merchant)
            val additional = SiteAssignment.start(MasterFixture.assignmentId(), r.account, extra, SiteAssignmentRole.ADDITIONAL, EffectivePeriod(t0), t0, operator)
            SiteConfirmationRules.confirm(
                EntitlementFixture.confirmationId(), r.account, extra, r.activation, listOf(r.primary, additional), emptyList(), evidence, t0, operator,
            ).seq shouldBe 1
        }

        Then("after approval and a revocation, the re-confirmation of the same site takes the next sequence") {
            val a = EntitlementFixture.active()
            a.confirmation.seq shouldBe 1
            a.confirmation.revoke(reason, newEvidence, t0, operator)
            SiteConfirmationRules.confirm(
                EntitlementFixture.confirmationId(), a.account, a.registered.issued.site, a.activation, listOf(a.registered.primary), listOf(a.confirmation),
                evidence, t0, operator,
            ).seq shouldBe 2
        }
    }

    Given("a new merchant site (PG review)") {
        val site = MasterFixture.site(MasterFixture.merchant())

        fun decision(history: List<SiteReviewDecision>, status: SiteReviewStatus) =
            SiteReviewRules.decide(site, history, status, reason, null, t0, operator)

        Then("it starts PENDING; PENDING → APPROVED → SUSPENDED → APPROVED is allowed and the current status is the last decision") {
            SiteReviewRules.currentStatus(emptyList()) shouldBe SiteReviewStatus.PENDING
            val h1 = listOf(decision(emptyList(), SiteReviewStatus.APPROVED))
            val h2 = h1 + decision(h1, SiteReviewStatus.SUSPENDED)
            val h3 = h2 + decision(h2, SiteReviewStatus.APPROVED)
            h3.map { it.seq } shouldBe listOf(1, 2, 3)
            SiteReviewRules.currentStatus(h3) shouldBe SiteReviewStatus.APPROVED
        }

        Then("REJECTED is terminal, same-state and PENDING decisions are refused (fail-closed transition set)") {
            val rejected = listOf(decision(emptyList(), SiteReviewStatus.REJECTED))
            mapOf(
                "after rejected" to rejection { decision(rejected, SiteReviewStatus.APPROVED) },
                "pending to suspended" to rejection { decision(emptyList(), SiteReviewStatus.SUSPENDED) },
                "approved again" to rejection { decision(listOf(decision(emptyList(), SiteReviewStatus.APPROVED)), SiteReviewStatus.APPROVED) },
            ) shouldBe mapOf(
                "after rejected" to MasterRejection.SITE_REVIEW_TRANSITION_NOT_ALLOWED,
                "pending to suspended" to MasterRejection.SITE_REVIEW_TRANSITION_NOT_ALLOWED,
                "approved again" to MasterRejection.SITE_REVIEW_TRANSITION_NOT_ALLOWED,
            )
            shouldThrow<IllegalArgumentException> { SiteReviewDecision(site.id, site.merchantId, 1, SiteReviewStatus.PENDING, reason, null, t0, operator) }
        }
    }

    Given("D-MOPS-009 ③ evidence-path site review approval: the site registrant cannot approve (MERCHANT-OPS-002 §9.27 B3-1F-1)") {
        val approver = ApprovalEvidenceFixtures.APPROVER
        val other = ApprovalEvidenceFixtures.OTHER

        fun approveWithEvidence(site: MerchantSite, history: List<SiteReviewDecision>, by: ActorRef = approver) =
            SiteReviewRules.approveWithEvidence(site, history, reason, evidence, t0, by)

        fun inactive(createdBy: ActorRef): MerchantSite = SiteReviewFixtures.pending(createdBy).let {
            MerchantSite.reconstitute(it.id, it.merchantId, it.channel, Lifecycle.TERMINATED, it.createdAt, it.createdBy)
        }

        Then("site creator = approver → APPROVER_IS_SITE_REGISTRANT from PENDING") {
            val site = SiteReviewFixtures.pending(createdBy = approver)
            rejection { approveWithEvidence(site, emptyList()) } shouldBe MasterRejection.APPROVER_IS_SITE_REGISTRANT
        }

        Then("D-MOPS-010 ② (user-confirmed): the SUSPENDED → APPROVED re-approval by the original site creator is APPROVER_IS_SITE_REGISTRANT too") {
            val site = SiteReviewFixtures.pending(createdBy = approver)
            val history = SiteReviewFixtures.suspendedHistory(site, suspendedBy = other)
            SiteReviewRules.currentStatus(history) shouldBe SiteReviewStatus.SUSPENDED
            rejection { approveWithEvidence(site, history) } shouldBe MasterRejection.APPROVER_IS_SITE_REGISTRANT
        }

        Then("D-MOPS-010 ②: the actor who suspended (not the creator) may re-approve — the suspender is not barred") {
            val site = SiteReviewFixtures.pending(createdBy = other)
            val history = SiteReviewFixtures.suspendedHistory(site, suspendedBy = approver)
            approveWithEvidence(site, history).let { listOf(it.seq, it.decision, it.decidedBy, it.evidenceRef) } shouldBe
                listOf(3, SiteReviewStatus.APPROVED, approver, evidence)
        }

        Then("another approver → APPROVED seq +1 with the evidence") {
            val site = SiteReviewFixtures.pending(createdBy = other)
            approveWithEvidence(site, emptyList()).let { listOf(it.seq, it.decision, it.decidedBy, it.evidenceRef) } shouldBe
                listOf(1, SiteReviewStatus.APPROVED, approver, evidence)
        }

        Then("order: registrant before SITE_REVIEW_TRANSITION_NOT_ALLOWED (REJECTED site) and before SITE_NOT_ACTIVE (inactive site)") {
            val creatorsSite = SiteReviewFixtures.pending(createdBy = approver)
            val othersSite = SiteReviewFixtures.pending(createdBy = other)
            fun rejected(site: MerchantSite) = listOf(SiteReviewRules.decide(site, emptyList(), SiteReviewStatus.REJECTED, reason, null, t0, operator))
            mapOf(
                "rejected, creator" to rejection { approveWithEvidence(creatorsSite, rejected(creatorsSite)) },
                "rejected, other" to rejection { approveWithEvidence(othersSite, rejected(othersSite)) },
                "inactive, creator" to rejection { approveWithEvidence(inactive(approver), emptyList()) },
                "inactive, other" to rejection { approveWithEvidence(inactive(other), emptyList()) },
            ) shouldBe mapOf(
                "rejected, creator" to MasterRejection.APPROVER_IS_SITE_REGISTRANT,
                "rejected, other" to MasterRejection.SITE_REVIEW_TRANSITION_NOT_ALLOWED,
                "inactive, creator" to MasterRejection.APPROVER_IS_SITE_REGISTRANT,
                "inactive, other" to MasterRejection.SITE_NOT_ACTIVE,
            )
        }

        Then("decide(APPROVED) seed path and decide(REJECTED) 1-person path have no registrant check — the creator may reject (C-31, §9.27)") {
            val seeded = SiteReviewFixtures.pending(createdBy = approver)
            SiteReviewRules.decide(seeded, emptyList(), SiteReviewStatus.APPROVED, reason, null, t0, approver).decision shouldBe SiteReviewStatus.APPROVED
            val rejectedByCreator = SiteReviewFixtures.pending(createdBy = approver)
            SiteReviewRules.decide(rejectedByCreator, emptyList(), SiteReviewStatus.REJECTED, reason, null, t0, approver).decision shouldBe SiteReviewStatus.REJECTED
        }
    }
})

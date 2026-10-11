package com.paycore.master.merchant.domain

import com.paycore.master.merchant.domain.MasterFixture.operator
import com.paycore.master.merchant.domain.MasterFixture.t0
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

/**
 * MID–Site 연결 규칙: 같은 Merchant(구조 §8), 소급 시작 금지·같은 MID–Site 기간 중첩 금지·재연결은 새 행·미래 종료 예약 허용(D-MD-005),
 * 기간이 겹치는 PRIMARY는 하나(D-MD-007). 이번 범위의 명령은 최초 PRIMARY만 만들지만 규칙은 domain에서 전부 강제한다
 * (교체·추가 명령은 MASTER-LIFECYCLE-001 / Q-MD-08).
 */
class SiteAssignmentRulesSpec : BehaviorSpec({

    val day = 86_400L

    Given("an issued MID with its PRIMARY on site A from t0 (open-ended)") {
        val issued = MasterFixture.issued()
        val account = issued.issuance.account
        val primary = issued.issuance.primaryAssignment
        val siteB = MasterFixture.site(issued.merchant)

        fun start(site: MerchantSite, role: SiteAssignmentRole, period: EffectivePeriod, now: Instant = t0) =
            SiteAssignment.start(MasterFixture.assignmentId(), account, site, role, period, now, operator)

        fun violation(block: () -> Unit) = shouldThrow<MasterRuleViolation>(block).rejection

        Then("a second PRIMARY overlapping the current one is rejected (at most one effective PRIMARY)") {
            violation {
                SiteAssignmentRules.requireCanStart(listOf(primary), start(siteB, SiteAssignmentRole.PRIMARY, EffectivePeriod(t0.plusSeconds(day))))
            } shouldBe MasterRejection.PRIMARY_ALREADY_EFFECTIVE
        }

        Then("an ADDITIONAL link to another site of the same Merchant passes the period rules (sharing approval is Q-MD-08, not decided here)") {
            SiteAssignmentRules.requireCanStart(listOf(primary), start(siteB, SiteAssignmentRole.ADDITIONAL, EffectivePeriod(t0)))
        }

        Then("a second link of the same MID–Site overlapping the open one is rejected") {
            violation {
                SiteAssignmentRules.requireCanStart(
                    listOf(primary), start(issued.site, SiteAssignmentRole.ADDITIONAL, EffectivePeriod(t0.plusSeconds(day))),
                )
            } shouldBe MasterRejection.ASSIGNMENT_PERIOD_OVERLAP
        }

        Then("a link that would start before the command instant is rejected (no retroactive start)") {
            violation { start(siteB, SiteAssignmentRole.ADDITIONAL, EffectivePeriod(t0.minusMillis(1))) } shouldBe
                MasterRejection.ASSIGNMENT_RETROACTIVE
        }

        Then("a site of another Merchant is rejected even inside the same Business") {
            val sibling = MasterFixture.merchant(issued.business)
            violation { start(MasterFixture.site(sibling), SiteAssignmentRole.ADDITIONAL, EffectivePeriod(t0)) } shouldBe
                MasterRejection.SITE_OF_OTHER_MERCHANT
        }
    }

    Given("links with a scheduled end (future end scheduling is allowed)") {
        val issued = MasterFixture.issued()
        val account = issued.issuance.account
        val siteB = MasterFixture.site(issued.merchant)
        val siteC = MasterFixture.site(issued.merchant)
        val endsAt = t0.plusSeconds(10 * day)

        val scheduledPrimary = SiteAssignment.start(
            MasterFixture.assignmentId(), account, siteB, SiteAssignmentRole.PRIMARY, EffectivePeriod(t0, endsAt), t0, operator,
        )

        Then("reconnecting the same MID–Site after the scheduled end is a new non-overlapping row and is accepted") {
            SiteAssignmentRules.requireCanStart(
                listOf(scheduledPrimary),
                SiteAssignment.start(MasterFixture.assignmentId(), account, siteB, SiteAssignmentRole.ADDITIONAL, EffectivePeriod(endsAt), t0, operator),
            )
        }

        Then("a replacement PRIMARY starting exactly at the scheduled end does not overlap and is accepted (no automatic promotion is implied)") {
            SiteAssignmentRules.requireCanStart(
                listOf(scheduledPrimary),
                SiteAssignment.start(MasterFixture.assignmentId(), account, siteC, SiteAssignmentRole.PRIMARY, EffectivePeriod(endsAt), t0, operator),
            )
        }

        Then("a replacement PRIMARY starting one millisecond before the scheduled end overlaps and is rejected") {
            shouldThrow<MasterRuleViolation> {
                SiteAssignmentRules.requireCanStart(
                    listOf(scheduledPrimary),
                    SiteAssignment.start(
                        MasterFixture.assignmentId(), account, siteC, SiteAssignmentRole.PRIMARY, EffectivePeriod(endsAt.minusMillis(1)), t0, operator,
                    ),
                )
            }.rejection shouldBe MasterRejection.PRIMARY_ALREADY_EFFECTIVE
        }

        Then("only the candidate MID's links may be compared (a programming error otherwise)") {
            val other = MasterFixture.issued()
            shouldThrow<IllegalArgumentException> {
                SiteAssignmentRules.requireCanStart(listOf(other.issuance.primaryAssignment), scheduledPrimary)
            }
        }
    }

    Given("a payment method activation registration (minimal storage, Q-MD-09 states undecided)") {
        Then("it is recorded for an ACTIVE MID with the method and the actor") {
            val issued = MasterFixture.issued()
            val activation = PaymentMethodActivation.register(
                MasterFixture.activationId(), issued.issuance.account, PaymentMethod.CARD, t0, operator,
            )
            (activation.merchantAccountId to activation.paymentMethod) shouldBe (issued.issuance.account.id to PaymentMethod.CARD)
        }
    }
})

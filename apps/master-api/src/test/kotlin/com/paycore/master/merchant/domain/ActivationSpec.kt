package com.paycore.master.merchant.domain

import com.paycore.master.merchant.domain.EntitlementFixture.evidence
import com.paycore.master.merchant.domain.EntitlementFixture.newEvidence
import com.paycore.master.merchant.domain.EntitlementFixture.operator
import com.paycore.master.merchant.domain.EntitlementFixture.reason
import com.paycore.master.merchant.domain.EntitlementFixture.t0
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * 개통 상태 전이(D-MD-017, D-MS-003): UNDER_REVIEW→ACTIVE(근거 필수, PRIMARY 확인 동시), UNDER_REVIEW→TERMINATED, ACTIVE→SUSPENDED(사유),
 * SUSPENDED→ACTIVE(새 근거), ACTIVE·SUSPENDED→TERMINATED 비가역. 권리를 넓히는 전이는 MID ACTIVE 필요.
 */
class ActivationSpec : BehaviorSpec({

    fun rejection(block: () -> Unit): MasterRejection = shouldThrow<MasterRuleViolation>(block).rejection

    Given("a newly registered CARD activation") {
        Then("it is UNDER_REVIEW with a registration transition from nothing") {
            val issued = MasterFixture.issued()
            val (activation, transition) = PaymentMethodActivation.register(
                MasterFixture.activationId(), issued.issuance.account, PaymentMethod.CARD, t0, operator,
            )
            activation.status shouldBe ActivationStatus.UNDER_REVIEW
            activation.evidenceRef.shouldBeNull()
            transition shouldBe ActivationTransition(null, ActivationStatus.UNDER_REVIEW, null, null, t0, operator)
        }

        Then("approval with institution evidence makes it ACTIVE and records the effective PRIMARY site confirmation in the same step") {
            val r = EntitlementFixture.registered()
            val result = ActivationApproval.approve(r.activation, r.account, listOf(r.primary), emptyList(), EntitlementFixture.confirmationId(), evidence, t0, operator)
            r.activation.status shouldBe ActivationStatus.ACTIVE
            r.activation.evidenceRef shouldBe evidence
            result.transition.from shouldBe ActivationStatus.UNDER_REVIEW
            val confirmation = checkNotNull(result.primaryConfirmation)
            mapOf(
                "site" to confirmation.merchantSiteId, "mid" to confirmation.merchantAccountId, "activation" to confirmation.activationId,
                "evidence" to confirmation.evidenceRef, "valid" to confirmation.isValid,
            ) shouldBe mapOf(
                "site" to r.primary.merchantSiteId, "mid" to r.account.id, "activation" to r.activation.id, "evidence" to evidence, "valid" to true,
            )
        }

        Then("approval without an effective PRIMARY (only a future one) records no confirmation — payment stays blocked (fail-closed)") {
            val r = EntitlementFixture.registered()
            val futurePrimary = SiteAssignment.start(
                MasterFixture.assignmentId(), r.account, r.issued.site, SiteAssignmentRole.PRIMARY, EffectivePeriod(t0.plusSeconds(3600)), t0, operator,
            )
            val future = ActivationApproval.approve(r.activation, r.account, listOf(futurePrimary), emptyList(), EntitlementFixture.confirmationId(), evidence, t0, operator)
            future.primaryConfirmation.shouldBeNull()

            val r2 = EntitlementFixture.registered()
            val none = ActivationApproval.approve(r2.activation, r2.account, emptyList(), emptyList(), EntitlementFixture.confirmationId(), evidence, t0, operator)
            none.primaryConfirmation.shouldBeNull()
            r2.activation.status shouldBe ActivationStatus.ACTIVE
        }

        Then("it can be terminated directly (review rejection) and then nothing else is allowed") {
            val r = EntitlementFixture.registered()
            r.activation.terminate(reason, t0, operator).to shouldBe ActivationStatus.TERMINATED
            mapOf(
                "approve" to rejection { ActivationApproval.approve(r.activation, r.account, listOf(r.primary), emptyList(), EntitlementFixture.confirmationId(), evidence, t0, operator) },
                "resume" to rejection { r.activation.resume(r.account, newEvidence, emptyList(), t0, operator) },
                "suspend" to rejection { r.activation.suspend(reason, t0, operator) },
                "terminate" to rejection { r.activation.terminate(reason, t0, operator) },
            ) shouldBe mapOf(
                "approve" to MasterRejection.ACTIVATION_TRANSITION_NOT_ALLOWED,
                "resume" to MasterRejection.ACTIVATION_TRANSITION_NOT_ALLOWED,
                "suspend" to MasterRejection.ACTIVATION_TRANSITION_NOT_ALLOWED,
                "terminate" to MasterRejection.ACTIVATION_TRANSITION_NOT_ALLOWED,
            )
        }

        Then("suspend and resume are not allowed before approval") {
            val r = EntitlementFixture.registered()
            rejection { r.activation.suspend(reason, t0, operator) } shouldBe MasterRejection.ACTIVATION_TRANSITION_NOT_ALLOWED
            rejection { r.activation.resume(r.account, newEvidence, emptyList(), t0, operator) } shouldBe MasterRejection.ACTIVATION_TRANSITION_NOT_ALLOWED
        }
    }

    Given("an ACTIVE activation") {
        Then("it is suspended with a reason and resumed only with a NEW institution evidence reference") {
            val a = EntitlementFixture.active()
            a.activation.suspend(reason, t0, operator).to shouldBe ActivationStatus.SUSPENDED
            rejection { a.activation.resume(a.account, evidence, emptyList(), t0, operator) } shouldBe MasterRejection.ACTIVATION_EVIDENCE_NOT_NEW
            a.activation.status shouldBe ActivationStatus.SUSPENDED
            a.activation.resume(a.account, newEvidence, emptyList(), t0, operator).to shouldBe ActivationStatus.ACTIVE
            a.activation.evidenceRef shouldBe newEvidence
        }

        Then("approving twice is not allowed and termination from ACTIVE or SUSPENDED is irreversible") {
            val a = EntitlementFixture.active()
            rejection {
                ActivationApproval.approve(a.activation, a.account, emptyList(), emptyList(), EntitlementFixture.confirmationId(), newEvidence, t0, operator)
            } shouldBe MasterRejection.ACTIVATION_TRANSITION_NOT_ALLOWED
            a.activation.terminate(reason, t0, operator).from shouldBe ActivationStatus.ACTIVE

            val b = EntitlementFixture.active()
            b.activation.suspend(reason, t0, operator)
            b.activation.terminate(reason, t0, operator).from shouldBe ActivationStatus.SUSPENDED
            rejection { b.activation.resume(b.account, newEvidence, emptyList(), t0, operator) } shouldBe MasterRejection.ACTIVATION_TRANSITION_NOT_ALLOWED
        }
    }

    Given("an activation that went through suspend/resume (review m-2: 'new' evidence = never used by this activation)") {
        Then("resuming with an evidence used earlier — though not the current one — is refused") {
            val a = EntitlementFixture.active()
            a.activation.suspend(reason, t0, operator)
            a.activation.resume(a.account, newEvidence, listOf(evidence), t0, operator)
            a.activation.suspend(reason, t0, operator)
            rejection { a.activation.resume(a.account, evidence, listOf(evidence, newEvidence), t0, operator) } shouldBe
                MasterRejection.ACTIVATION_EVIDENCE_NOT_NEW
            a.activation.status shouldBe ActivationStatus.SUSPENDED
        }
    }

    Given("an approval while a valid PRIMARY confirmation already exists (review M-1)") {
        Then("no second valid confirmation is created for the PRIMARY site") {
            val r = EntitlementFixture.registered()
            val existing = SiteActivationConfirmation.reconstitute(
                EntitlementFixture.confirmationId(), r.account.id, r.primary.merchantSiteId, r.account.merchantId, PaymentMethod.CARD, r.activation.id,
                evidence, t0, operator, 1, null,
            )
            val result = ActivationApproval.approve(r.activation, r.account, listOf(r.primary), listOf(existing), EntitlementFixture.confirmationId(), evidence, t0, operator)
            result.primaryConfirmation.shouldBeNull()
            r.activation.status shouldBe ActivationStatus.ACTIVE
        }
    }

    Given("a terminated MID (stored state)") {
        Then("rights-widening transitions are refused but rights-reducing ones are still allowed") {
            val a = EntitlementFixture.active()
            val terminated = MerchantAccount.reconstitute(
                a.account.id, a.account.merchantId, a.account.businessId, a.account.issuingBasis, Lifecycle.TERMINATED, t0, operator,
            )
            a.activation.suspend(reason, t0, operator)
            rejection { a.activation.resume(terminated, newEvidence, emptyList(), t0, operator) } shouldBe MasterRejection.MERCHANT_ACCOUNT_NOT_ACTIVE
            a.activation.terminate(reason, t0, operator).to shouldBe ActivationStatus.TERMINATED

            rejection {
                PaymentMethodActivation.register(MasterFixture.activationId(), terminated, PaymentMethod.CARD, t0, operator)
            } shouldBe MasterRejection.MERCHANT_ACCOUNT_NOT_ACTIVE
        }
    }

    Given("stored activation state") {
        Then("an ACTIVE activation without evidence cannot be reconstituted") {
            val a = EntitlementFixture.active()
            shouldThrow<IllegalArgumentException> {
                PaymentMethodActivation.reconstitute(
                    a.activation.id, a.account.id, PaymentMethod.CARD, t0, operator, ActivationStatus.ACTIVE, null, t0,
                )
            }
        }
    }
})

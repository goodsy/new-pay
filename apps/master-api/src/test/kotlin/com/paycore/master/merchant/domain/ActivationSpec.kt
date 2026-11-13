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

    Given("D-MOPS-009 ③ evidence-path activation approval: the registrant cannot approve (MERCHANT-OPS-002 §9.27 B3-1F-1, C-49 order)") {
        val approver = ApprovalEvidenceFixtures.APPROVER
        val other = ApprovalEvidenceFixtures.OTHER

        fun approveWithEvidence(
            r: EntitlementFixture.Registered,
            account: MerchantAccount = r.account,
            by: ActorRef = approver,
            primary: MerchantSiteId = r.primary.merchantSiteId,
        ) = ActivationApproval.approveWithEvidence(r.activation, account, listOf(r.primary), emptyList(), EntitlementFixture.confirmationId(), evidence, t0, by, primary)

        fun terminated(account: MerchantAccount) = MerchantAccount.reconstitute(
            account.id, account.merchantId, account.businessId, account.issuingBasis, Lifecycle.TERMINATED, t0, operator,
        )

        Then("registrant = approver → APPROVER_IS_ACTIVATION_REGISTRANT; the activation stays UNDER_REVIEW without evidence") {
            val r = ActivationFixtures.underReview(registeredBy = approver)
            rejection { approveWithEvidence(r) } shouldBe MasterRejection.APPROVER_IS_ACTIVATION_REGISTRANT
            r.activation.status shouldBe ActivationStatus.UNDER_REVIEW
            r.activation.evidenceRef.shouldBeNull()
            r.activation.statusChangedAt shouldBe t0
        }

        Then("another approver → ACTIVE with one PRIMARY confirmation (existing expectation)") {
            val r = ActivationFixtures.underReview(registeredBy = other)
            val result = approveWithEvidence(r)
            r.activation.status shouldBe ActivationStatus.ACTIVE
            listOf(result.transition.by, checkNotNull(result.primaryConfirmation).merchantSiteId) shouldBe listOf(approver, r.primary.merchantSiteId)
        }

        Then("order: a foreign MID is a defect (require) and an inactive MID is MERCHANT_ACCOUNT_NOT_ACTIVE — both before the registrant check") {
            val r = ActivationFixtures.underReview(registeredBy = approver)
            shouldThrow<IllegalArgumentException> { approveWithEvidence(r, account = MasterFixture.issued().issuance.account) }
            rejection { approveWithEvidence(r, account = terminated(r.account)) } shouldBe MasterRejection.MERCHANT_ACCOUNT_NOT_ACTIVE
            r.activation.status shouldBe ActivationStatus.UNDER_REVIEW
        }

        Then("order (B3-1F review NIT-1): on the activation itself, a foreign MID fails requireSameAccount before the registrant check (the assignments require above fires first)") {
            // ActivationApproval의 assignments require가 위 사례를 먼저 막으므로, 개통 Aggregate 진입 메서드를 직접 불러 같은 MID 검사 → 등록자 검사 순서를 따로 확인한다.
            val r = ActivationFixtures.underReview(registeredBy = approver)
            val foreign = MasterFixture.issued().issuance.account
            shouldThrow<IllegalArgumentException> { r.activation.approveWithEvidence(foreign, evidence, t0, approver) }.message shouldBe "activation belongs to another MID"
            // 대조군: 같은 MID면 같은 호출이 등록자 거절에 닿는다(위 예외가 등록자 검사 전이라는 근거).
            rejection { r.activation.approveWithEvidence(r.account, evidence, t0, approver) } shouldBe MasterRejection.APPROVER_IS_ACTIVATION_REGISTRANT
            r.activation.status shouldBe ActivationStatus.UNDER_REVIEW
        }

        Then("order: an ACTIVE activation approved by its registrant is APPROVER_IS_ACTIVATION_REGISTRANT (registrant before transition); another approver gets ACTIVATION_TRANSITION_NOT_ALLOWED") {
            val r = ActivationFixtures.underReview(registeredBy = approver)
            ActivationApproval.approve(r.activation, r.account, listOf(r.primary), emptyList(), EntitlementFixture.confirmationId(), evidence, t0, other)
            r.activation.status shouldBe ActivationStatus.ACTIVE
            mapOf(
                "registrant" to rejection { approveWithEvidence(r) },
                "other" to rejection { approveWithEvidence(r, by = other) },
            ) shouldBe mapOf(
                "registrant" to MasterRejection.APPROVER_IS_ACTIVATION_REGISTRANT,
                "other" to MasterRejection.ACTIVATION_TRANSITION_NOT_ALLOWED,
            )
        }

        Then("order: PRIMARY_SITE_MISMATCH (C-45) only after the registrant and the transition pass") {
            val elsewhere = MasterFixture.siteId()
            val byRegistrant = ActivationFixtures.underReview(registeredBy = approver)
            val byOther = ActivationFixtures.underReview(registeredBy = other)
            val alreadyActive = ActivationFixtures.underReview(registeredBy = other).also {
                ActivationApproval.approve(it.activation, it.account, listOf(it.primary), emptyList(), EntitlementFixture.confirmationId(), evidence, t0, other)
            }
            mapOf(
                "registrant" to rejection { approveWithEvidence(byRegistrant, primary = elsewhere) },
                "active" to rejection { approveWithEvidence(alreadyActive, primary = elsewhere) },
                "other" to rejection { approveWithEvidence(byOther, primary = elsewhere) },
            ) shouldBe mapOf(
                "registrant" to MasterRejection.APPROVER_IS_ACTIVATION_REGISTRANT,
                "active" to MasterRejection.ACTIVATION_TRANSITION_NOT_ALLOWED,
                "other" to MasterRejection.PRIMARY_SITE_MISMATCH,
            )
            byRegistrant.activation.status shouldBe ActivationStatus.UNDER_REVIEW
        }

        Then("seed path ActivationApproval.approve accepts the same actor for registration and approval (C-31 — unchanged, no registrant check)") {
            val r = ActivationFixtures.underReview(registeredBy = approver)
            val result = ActivationApproval.approve(r.activation, r.account, listOf(r.primary), emptyList(), EntitlementFixture.confirmationId(), evidence, t0, approver)
            result.transition.to shouldBe ActivationStatus.ACTIVE
            r.activation.registeredBy shouldBe result.transition.by
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

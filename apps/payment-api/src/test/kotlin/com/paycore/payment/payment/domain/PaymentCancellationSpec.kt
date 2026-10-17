package com.paycore.payment.payment.domain

import com.paycore.payment.payment.domain.CancellationFixture.cancelObservation
import com.paycore.payment.payment.domain.MoneyFixture.won
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * 취소 operation(CANCEL-CONTRACT-001 §4.6·T3): 상태 OPEN → (UNKNOWN →) COMPLETED / FAILED, 취소 Attempt dispatch 전이(승인 AttemptState 의미 재사용,
 * CC-11 dispatch Attempt 1개), 관찰 append-only(같은 키·같은 기관 내용 1회, 같은 키·다른 내용은 상충 증거), 취소 요청 업무 키(D-CANCEL-002),
 * 운영자 보정 개시자(D-CANCEL-005). 결과 판정표(상태·예약·사건)는 T5 CancellationResolutionSpec.
 */
class PaymentCancellationSpec : BehaviorSpec({

    fun started(amount: Long = 3_000L) = CancellationFixture.paymentWithReservedReturn(amount.won())

    Given("a cancellation just started (TX1)") {
        Then("it is OPEN with one PREPARED attempt sent on the original route under the cancel operation") {
            val c = started()
            c.cancellation.status shouldBe CancellationStatus.OPEN
            c.cancellation.isInFlight() shouldBe true
            val attempt = c.cancellation.attempts.single()
            attempt.state shouldBe AttemptState.PREPARED
            attempt.mayHaveBeenSent() shouldBe false
            val original = AuthorizationFixture.providerRequest
            attempt.providerRequest.institution shouldBe original.institution
            attempt.providerRequest.environment shouldBe original.environment
            attempt.providerRequest.institutionAccount shouldBe original.institutionAccount
            attempt.providerRequest.operation shouldBe "CANCEL"
        }
        Then("it refers to the original authorization transaction and copies the original policy and entitlement versions (CX-11, D-CANCEL-008)") {
            val c = started()
            c.cancellation.originalTransactionId shouldBe checkNotNull(c.auth.authorization.transaction).id
            c.cancellation.policyAssignmentVersion shouldBe AuthorizationFixture.admission.policyAssignmentVersion
            c.cancellation.entitlementVersion shouldBe AuthorizationFixture.admission.entitlementVersion
            c.cancellation.paymentId shouldBe c.auth.payment.id
            c.cancellation.orderKey shouldBe c.auth.payment.orderKey
        }
    }

    Given("cancel attempt dispatch states") {
        Then("PREPARED → DISPATCHING once; dispatching again is rejected") {
            val c = started()
            c.cancellation.beginDispatch(c.attemptId)
            c.cancellation.attempt(c.attemptId).state shouldBe AttemptState.DISPATCHING
            c.cancellation.attempt(c.attemptId).mayHaveBeenSent() shouldBe true
            shouldThrow<InvalidCancellationTransitionException> { c.cancellation.beginDispatch(c.attemptId) }
        }
        Then("gate refusal / recovery fencing abandons only PREPARED; connect failure only DISPATCHING") {
            val a = started()
            a.cancellation.attempt(a.attemptId).abandon(a.cancellation.id, AbandonCause.GATE_REFUSED)
            a.cancellation.attempt(a.attemptId).state shouldBe AttemptState.ABANDONED_BEFORE_DISPATCH
            val b = started()
            shouldThrow<InvalidCancellationTransitionException> { b.cancellation.attempt(b.attemptId).abandon(b.cancellation.id, AbandonCause.CONNECT_FAILED) }
            b.cancellation.beginDispatch(b.attemptId)
            shouldThrow<InvalidCancellationTransitionException> { b.cancellation.attempt(b.attemptId).abandon(b.cancellation.id, AbandonCause.FENCED_BY_RECOVERY) }
            b.cancellation.attempt(b.attemptId).abandon(b.cancellation.id, AbandonCause.CONNECT_FAILED)
            b.cancellation.attempt(b.attemptId).abandonCause shouldBe AbandonCause.CONNECT_FAILED
        }
        Then("an outcome becomes unknown only after dispatch; an unknown attempt has no new attempt") {
            val c = started()
            shouldThrow<InvalidCancellationTransitionException> { c.cancellation.attempt(c.attemptId).markOutcomeUnknown(c.cancellation.id, UnknownCause.TIMEOUT) }
            c.cancellation.beginDispatch(c.attemptId)
            c.cancellation.attempt(c.attemptId).markOutcomeUnknown(c.cancellation.id, UnknownCause.TIMEOUT)
            c.cancellation.markUnknown()
            c.cancellation.status shouldBe CancellationStatus.UNKNOWN
            c.cancellation.isInFlight() shouldBe true
            c.cancellation.attempts.size shouldBe 1
        }
        Then("an unknown attempt id is a defect") {
            val c = started()
            shouldThrow<IllegalArgumentException> { c.cancellation.attempt(CancellationFixture.cancelAttemptId(99)) }
        }
    }

    Given("institution cancel observations (append-only)") {
        Then("a new key is appended; the same key with the same institution content is a duplicate even if receipt time and hash differ") {
            val c = started()
            c.cancellation.beginDispatch(c.attemptId)
            val first = cancelObservation(c, InstitutionOutcome.APPROVED)
            c.cancellation.recordObservation(first) shouldBe ObservationRecord.NEW
            c.cancellation.recordObservation(first.copy(observedAt = first.observedAt.plusSeconds(5), evidenceHash = "sha256:other")) shouldBe
                ObservationRecord.DUPLICATE
            c.cancellation.observations shouldBe listOf(first)
        }
        Then("the same key with different content keeps the first and records the conflicting one as evidence") {
            val c = started()
            c.cancellation.beginDispatch(c.attemptId)
            val first = cancelObservation(c, InstitutionOutcome.APPROVED)
            c.cancellation.recordObservation(first)
            val conflicting = cancelObservation(c, InstitutionOutcome.DECLINED)
            c.cancellation.recordObservation(conflicting) shouldBe ObservationRecord.KEY_CONFLICT
            c.cancellation.recordObservation(conflicting) shouldBe ObservationRecord.DUPLICATE
            c.cancellation.observations shouldBe listOf(first)
            c.cancellation.keyConflictingObservations shouldBe listOf(conflicting)
        }
        Then("approved terms exist exactly for APPROVED observations") {
            val c = started()
            shouldThrow<IllegalArgumentException> { cancelObservation(c, InstitutionOutcome.DECLINED, terms = CancellationFixture.confirmedTerms(3_000L.won())) }
            shouldThrow<IllegalArgumentException> { cancelObservation(c, InstitutionOutcome.APPROVED, terms = null) }
        }
        Then("only the requested money on the original transaction matches the request") {
            val c = started()
            c.cancellation.matchesRequest(CancellationFixture.confirmedTerms(3_000L.won())) shouldBe true
            c.cancellation.matchesRequest(CancellationFixture.confirmedTerms(2_000L.won())) shouldBe false
            c.cancellation.matchesRequest(CancellationFixture.confirmedTerms(3_000L.won()).copy(originalProviderTransactionId = "PTX-OTHER")) shouldBe false
        }
    }

    Given("completion and failure") {
        Then("a completed cancellation holds exactly one CANCEL transaction and is terminal") {
            val c = CancellationFixture.completed(started())
            c.cancellation.status shouldBe CancellationStatus.COMPLETED
            c.cancellation.isInFlight() shouldBe false
            val tx = checkNotNull(c.cancellation.transaction)
            tx.type shouldBe PaymentTransactionType.CANCEL
            tx.money shouldBe 3_000L.won()
            tx.originalTransactionId shouldBe c.cancellation.originalTransactionId
            shouldThrow<InvalidCancellationTransitionException> { c.cancellation.fail(TerminationEvidence.INSTITUTION_CONFIRMED_DECLINE) }
            shouldThrow<InvalidCancellationTransitionException> { c.cancellation.markUnknown() }
        }
        Then("a transaction of another cancellation or with other money is rejected") {
            val c = started()
            val done = CancellationFixture.completed(started(), n = 1)
            val foreign = checkNotNull(done.cancellation.transaction).copy(cancellationId = CancellationFixture.cancellationId(7))
            shouldThrow<IllegalArgumentException> { c.cancellation.complete(foreign) }
            val otherMoney = checkNotNull(done.cancellation.transaction).copy(terms = CancellationFixture.confirmedTerms(2_000L.won()))
            shouldThrow<IllegalArgumentException> { c.cancellation.complete(otherMoney) }
            c.cancellation.status shouldBe CancellationStatus.OPEN
        }
        Then("failure needs sufficient evidence: timeout, NOT_FOUND or lease expiry never fail a cancellation") {
            listOf(TerminationEvidence.TIMEOUT, TerminationEvidence.INQUIRY_NOT_FOUND, TerminationEvidence.LEASE_EXPIRED,
                TerminationEvidence.NOT_TRANSMITTED_AND_SESSION_DISCARDED).forEach { e ->
                val c = started()
                shouldThrow<IllegalArgumentException> { c.cancellation.fail(e) }
                c.cancellation.status shouldBe CancellationStatus.OPEN
            }
            val d = CancellationFixture.declined(started())
            d.cancellation.status shouldBe CancellationStatus.FAILED
            d.cancellation.failureEvidence shouldBe TerminationEvidence.INSTITUTION_CONFIRMED_DECLINE
        }
    }

    Given("the cancel request business key (D-CANCEL-002 merchant, CC-12 operator — permanent, not tied to the 30-day idempotency retention)") {
        Then("same merchantCancelId + same money = same request; same id + other money = conflict; another id = different request") {
            val c = started()
            val key = CancelRequestKey.Merchant(CancellationFixture.merchantCancelId(1))
            c.cancellation.compareRequest(c.auth.payment.id, key, 3_000L.won()) shouldBe CancelRequestMatch.SAME_REQUEST
            c.cancellation.compareRequest(c.auth.payment.id, key, 3_001L.won()) shouldBe CancelRequestMatch.CONFLICTING_CONTENT
            c.cancellation.compareRequest(c.auth.payment.id, CancelRequestKey.Merchant(CancellationFixture.merchantCancelId(2)), 3_000L.won()) shouldBe
                CancelRequestMatch.DIFFERENT_REQUEST
        }
        Then("after the cancellation FAILED the same id still means the same request (old result returned; a retry needs a new id)") {
            val d = CancellationFixture.declined(started())
            d.cancellation.compareRequest(d.auth.payment.id, CancelRequestKey.Merchant(CancellationFixture.merchantCancelId(1)), 3_000L.won()) shouldBe CancelRequestMatch.SAME_REQUEST
        }
        Then("merchantCancelId follows the D-CPC-002 format, compared as given (no trim, case-sensitive)") {
            MerchantCancelId("Abc-1_.:") shouldBe MerchantCancelId("Abc-1_.:")
            (MerchantCancelId("abc") == MerchantCancelId("ABC")) shouldBe false
            listOf("", " abc", "abc ", "한글", "a".repeat(65), "a/b").forEach { bad ->
                shouldThrow<IllegalArgumentException> { MerchantCancelId(bad) }
            }
        }
        Then("an operator cancellation is keyed by its two-person approval id: same approval + same money = same, other money = conflict") {
            val s = AuthorizationFixture.authorized()
            val op = CancellationFixture.operator(s, 3_000L.won(), null)
            val c = CancellationFixture.started(s, null, 3_000L.won(), initiator = op)
            c.cancellation.compareRequest(c.auth.payment.id, CancelRequestKey.Operator(CancellationFixture.approvalId(1)), 3_000L.won()) shouldBe CancelRequestMatch.SAME_REQUEST
            c.cancellation.compareRequest(c.auth.payment.id, CancelRequestKey.Operator(CancellationFixture.approvalId(1)), 2_000L.won()) shouldBe CancelRequestMatch.CONFLICTING_CONTENT
            c.cancellation.compareRequest(c.auth.payment.id, CancelRequestKey.Operator(CancellationFixture.approvalId(2)), 3_000L.won()) shouldBe CancelRequestMatch.DIFFERENT_REQUEST
            c.cancellation.compareRequest(c.auth.payment.id, CancelRequestKey.Merchant(CancellationFixture.merchantCancelId(1)), 3_000L.won()) shouldBe CancelRequestMatch.DIFFERENT_REQUEST
        }
    }

    Given("a business key looked up for another payment (review m-B — fail-closed)") {
        Then("the same merchant or operator key with a different payment id is CONFLICTING_CONTENT, never the same request") {
            val s = AuthorizationFixture.authorized()
            val op = CancellationFixture.started(s, null, 3_000L.won(), initiator = CancellationFixture.operator(s, 3_000L.won(), null))
            val merchant = started()
            val otherPayment = OrderFixture.paymentId(2)
            op.cancellation.compareRequest(otherPayment, CancelRequestKey.Operator(CancellationFixture.approvalId(1)), 3_000L.won()) shouldBe CancelRequestMatch.CONFLICTING_CONTENT
            merchant.cancellation.compareRequest(otherPayment, CancelRequestKey.Merchant(CancellationFixture.merchantCancelId(1)), 3_000L.won()) shouldBe
                CancelRequestMatch.CONFLICTING_CONTENT
        }
    }

    Given("the cancel provider request operation (review n-3)") {
        Then("a cancellation is only sent under the cancel operation") {
            val s = AuthorizationFixture.authorized()
            shouldThrow<IllegalArgumentException> {
                CancelStart.start(CancellationFixture.command(s, null, 3_000L.won(), providerRequest = CancellationFixture.cancelRequest().copy(operation = "AUTHORIZE")))
            }
            PaymentCancellation.CANCEL_OPERATION shouldBe "CANCEL"
        }
    }

    Given("an operator initiator opened directly (bypassing CancelStart)") {
        Then("PaymentCancellation.open itself refuses an approval with requester == approver (defence in depth)") {
            val s = AuthorizationFixture.authorized()
            val bad = CancellationFixture.operator(s, 3_000L.won(), null, approvedBy = OperatorId("demo-op-requester"))
            shouldThrow<IllegalArgumentException> {
                PaymentCancellation.open(
                    CancellationFixture.cancellationId(), s.payment, s.authorization, 3_000L.won(), CancelScope.PARTIAL, bad,
                    CancellationFixture.cancelAttemptId(), CancellationFixture.cancelRequest(), CancellationFixture.at,
                )
            }
        }
    }
})

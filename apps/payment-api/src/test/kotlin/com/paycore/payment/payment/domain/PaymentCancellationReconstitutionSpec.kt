package com.paycore.payment.payment.domain

import com.paycore.payment.payment.domain.CancellationFixture.applyCancel
import com.paycore.payment.payment.domain.CancellationFixture.cancelObservation
import com.paycore.payment.payment.domain.MoneyFixture.won
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * 취소 operation 영속 복원(CANCEL-001 명세 F-1, CC §14.1 nit 4). 공식 전이로 만든 상태는 같은 값으로 되돌아오고(왕복), 공식 전이로 만들 수 없는 조합은
 * 거부한다(조용히 고치지 않는다). 기대 조합은 명세 §3.2 F-1·§8.1 표에서 독립 기술한다.
 */
class PaymentCancellationReconstitutionSpec : BehaviorSpec({

    data class Parts(
        val status: CancellationStatus,
        val attempts: List<CancelAttempt>,
        val observations: List<CancelObservation>,
        val conflicts: List<CancelObservation>,
        val transaction: CancelTransaction?,
        val failureEvidence: TerminationEvidence?,
        val incidentCount: Int,
        val initiator: CancelInitiator,
    )

    fun PaymentCancellation.parts() = Parts(status, attempts, observations, keyConflictingObservations, transaction, failureEvidence, incidentCount, initiator)

    fun restore(c: PaymentCancellation, p: Parts = c.parts()): PaymentCancellation = PaymentCancellation.reconstitute(
        id = c.id, paymentId = c.paymentId, authorizationId = c.authorizationId, originalTransactionId = c.originalTransactionId, orderKey = c.orderKey,
        money = c.money, scope = c.scope, initiator = p.initiator, originalProviderTransactionId = c.originalProviderTransactionId,
        policyAssignmentVersion = c.policyAssignmentVersion, entitlementVersion = c.entitlementVersion, capabilityVersion = c.capabilityVersion, openedAt = c.openedAt,
        status = p.status, attempts = p.attempts, observations = p.observations, keyConflictingObservations = p.conflicts, transaction = p.transaction,
        failureEvidence = p.failureEvidence, incidentCount = p.incidentCount,
    )

    fun same(a: PaymentCancellation, b: PaymentCancellation) {
        b.parts() shouldBe a.parts()
        b.attempts.map { listOf(it.id, it.state, it.abandonCause, it.unknownCause, it.routeProfileVersion, it.providerRequest, it.preparedAt) } shouldBe
            a.attempts.map { listOf(it.id, it.state, it.abandonCause, it.unknownCause, it.routeProfileVersion, it.providerRequest, it.preparedAt) }
        listOf(b.money, b.scope, b.capabilityVersion, b.policyAssignmentVersion, b.incidentRecorded) shouldBe
            listOf(a.money, a.scope, a.capabilityVersion, a.policyAssignmentVersion, a.incidentRecorded)
    }

    fun started(amount: Long = 3_000L) = CancellationFixture.paymentWithReservedReturn(amount.won())

    fun dispatched(amount: Long = 3_000L) = started(amount).also { it.cancellation.beginDispatch(it.attemptId, CancellationFixture.ROUTE_PROFILE_VERSION) }

    fun attempt(state: AttemptState, version: String? = null, abandon: AbandonCause? = null, unknown: UnknownCause? = null) =
        CancelAttempt(CancellationFixture.cancelAttemptId(), CancellationFixture.cancelRequest(), CancellationFixture.at, state, abandon, unknown, version)

    Given("states made by official transitions") {
        Then("OPEN/PREPARED, OPEN/DISPATCHING (pinned route profile version), UNKNOWN, COMPLETED, FAILED by decline and by gate refusal round-trip unchanged") {
            val open = started()
            val disp = dispatched()
            val unknown = dispatched().also { CancellationResolution.markOutcomeUnknown(it.cancellation, it.balance, it.attemptId, UnknownCause.TIMEOUT) }
            val completed = CancellationFixture.completed(started())
            val declined = CancellationFixture.declined(started())
            val refused = started().also { CancellationResolution.abandonBeforeDispatch(it.cancellation, it.balance, it.attemptId, AbandonCause.GATE_REFUSED) }
            listOf(open, disp, unknown, completed, declined, refused).forEach { s -> same(s.cancellation, restore(s.cancellation)) }
        }
        Then("an operator cancellation, key-conflict evidence and incident counts round-trip (D-CANCEL1-002 incident_count persisted)") {
            val s = AuthorizationFixture.authorized()
            val op = CancellationFixture.started(s, null, 3_000L.won(), initiator = CancellationFixture.operator(s, 3_000L.won(), null))
            same(op.cancellation, restore(op.cancellation))
            val late = CancellationFixture.lateCancelSuccessAfterDecline(3_000L.won())
            // D-CANCEL1-005: 다른 기관 거래 식별자의 늦은 성공이라 두 번째 사건이다(같은 거래면 같은 사건).
            applyCancel(late, cancelObservation(late, InstitutionOutcome.APPROVED, source = ObservationSource.RECONCILIATION, resultId = "rec-1",
                terms = CancellationFixture.confirmedTerms(3_000L.won(), n = 9)))
            late.cancellation.incidentCount shouldBe 2
            same(late.cancellation, restore(late.cancellation))
            val conflict = dispatched()
            applyCancel(conflict, cancelObservation(conflict, InstitutionOutcome.DECLINED))
            applyCancel(conflict, cancelObservation(conflict, InstitutionOutcome.APPROVED))
            conflict.cancellation.keyConflictingObservations.size shouldBe 1
            same(conflict.cancellation, restore(conflict.cancellation))
        }
        Then("an in-flight cancel whose never-sent attempt got a definitive result is UNKNOWN with a PREPARED attempt only together with an incident") {
            val p = started()
            applyCancel(p, cancelObservation(p, InstitutionOutcome.APPROVED))
            p.cancellation.status shouldBe CancellationStatus.UNKNOWN
            p.cancellation.attempts.single().state shouldBe AttemptState.PREPARED
            same(p.cancellation, restore(p.cancellation))
            shouldThrow<IllegalArgumentException> { restore(p.cancellation, p.cancellation.parts().copy(incidentCount = 0)) }
        }
    }

    Given("rows that official transitions cannot produce (rejected, never repaired)") {
        Then("status and transaction / failure evidence must pair") {
            val done = CancellationFixture.completed(started()).cancellation
            shouldThrow<IllegalArgumentException> { restore(done, done.parts().copy(transaction = null)) }
            val open = started().cancellation
            shouldThrow<IllegalArgumentException> { restore(open, open.parts().copy(transaction = done.transaction)) }
            val failed = CancellationFixture.declined(started()).cancellation
            shouldThrow<IllegalArgumentException> { restore(failed, failed.parts().copy(failureEvidence = null)) }
            shouldThrow<IllegalArgumentException> { restore(failed, failed.parts().copy(failureEvidence = TerminationEvidence.TIMEOUT)) }
            shouldThrow<IllegalArgumentException> { restore(open, open.parts().copy(failureEvidence = TerminationEvidence.INSTITUTION_CONFIRMED_DECLINE)) }
        }
        // mid-review #1 n-2: 기관 거절·무효과 근거는 그것을 말한 관찰이 있어야 한다.
        Then("n-2 a DECLINE needs a DECLINED observation and a NO_EFFECT needs a NO_EFFECT_CONFIRMED observation") {
            val declined = CancellationFixture.declined(started()).cancellation
            shouldThrow<IllegalArgumentException> { restore(declined, declined.parts().copy(observations = emptyList())) }
            shouldThrow<IllegalArgumentException> {
                restore(declined, declined.parts().copy(failureEvidence = TerminationEvidence.INSTITUTION_CONFIRMED_NO_EFFECT))
            }
            val noEffect = dispatched().also {
                CancellationResolution.markOutcomeUnknown(it.cancellation, it.balance, it.attemptId, UnknownCause.TIMEOUT)
                applyCancel(it, cancelObservation(it, InstitutionOutcome.NO_EFFECT_CONFIRMED, source = ObservationSource.INQUIRY, resultId = "inq-ne"))
            }.cancellation
            same(noEffect, restore(noEffect))
            shouldThrow<IllegalArgumentException> { restore(noEffect, noEffect.parts().copy(failureEvidence = TerminationEvidence.INSTITUTION_CONFIRMED_DECLINE)) }
        }
        Then("exactly one attempt (CC-11) whose state fits the cancellation status") {
            val open = started().cancellation
            shouldThrow<IllegalArgumentException> { restore(open, open.parts().copy(attempts = emptyList())) }
            shouldThrow<IllegalArgumentException> { restore(open, open.parts().copy(attempts = open.attempts + open.attempts)) }
            shouldThrow<IllegalArgumentException> { restore(open, open.parts().copy(attempts = listOf(attempt(AttemptState.OUTCOME_UNKNOWN, "v1", unknown = UnknownCause.TIMEOUT)))) }
            val unknown = dispatched().also { CancellationResolution.markOutcomeUnknown(it.cancellation, it.balance, it.attemptId, UnknownCause.TIMEOUT) }.cancellation
            shouldThrow<IllegalArgumentException> { restore(unknown, unknown.parts().copy(attempts = listOf(attempt(AttemptState.DISPATCHING, "v1")))) }
            val done = CancellationFixture.completed(started()).cancellation
            shouldThrow<IllegalArgumentException> { restore(done, done.parts().copy(attempts = listOf(attempt(AttemptState.OUTCOME_UNKNOWN, "v1", unknown = UnknownCause.TIMEOUT)))) }
            val refused = started().also { CancellationResolution.abandonBeforeDispatch(it.cancellation, it.balance, it.attemptId, AbandonCause.GATE_REFUSED) }.cancellation
            // 전송 전 실패 근거는 포기한 Attempt에서만, 기관 거절 근거는 결과를 받은 Attempt에서만 나온다.
            shouldThrow<IllegalArgumentException> { restore(refused, refused.parts().copy(failureEvidence = TerminationEvidence.INSTITUTION_CONFIRMED_DECLINE)) }
        }
        Then("route profile version exists exactly for attempts that were dispatched (D-CANCEL1-004) and abandon/unknown causes pair with their states") {
            val open = started().cancellation
            shouldThrow<IllegalArgumentException> { attempt(AttemptState.PREPARED, "v1") }
            shouldThrow<IllegalArgumentException> { restore(open, open.parts().copy(attempts = listOf(attempt(AttemptState.DISPATCHING, null)))) }
            shouldThrow<IllegalArgumentException> { attempt(AttemptState.DISPATCHING, "") }
            shouldThrow<IllegalArgumentException> { attempt(AttemptState.DISPATCHING, "has space") }
            shouldThrow<IllegalArgumentException> { attempt(AttemptState.DISPATCHING, "v".repeat(65)) }
            val refused = started().also { CancellationResolution.abandonBeforeDispatch(it.cancellation, it.balance, it.attemptId, AbandonCause.GATE_REFUSED) }.cancellation
            shouldThrow<IllegalArgumentException> {
                restore(refused, refused.parts().copy(attempts = listOf(attempt(AttemptState.ABANDONED_BEFORE_DISPATCH, "v1", abandon = AbandonCause.GATE_REFUSED))))
            }
            shouldThrow<IllegalArgumentException> { restore(refused, refused.parts().copy(attempts = listOf(attempt(AttemptState.ABANDONED_BEFORE_DISPATCH, null)))) }
            val unknown = dispatched().also { CancellationResolution.markOutcomeUnknown(it.cancellation, it.balance, it.attemptId, UnknownCause.TIMEOUT) }.cancellation
            shouldThrow<IllegalArgumentException> { restore(unknown, unknown.parts().copy(attempts = listOf(attempt(AttemptState.OUTCOME_UNKNOWN, "v1", unknown = null)))) }
        }
        Then("observations: keys unique, owned by the attempt, conflict evidence needs a different first observation with the same key") {
            val c = dispatched()
            val first = cancelObservation(c, InstitutionOutcome.NOT_FOUND, source = ObservationSource.INQUIRY, resultId = "inq-1")
            applyCancel(c, first)
            val parts = c.cancellation.parts()
            shouldThrow<IllegalArgumentException> { restore(c.cancellation, parts.copy(observations = listOf(first, first))) }
            shouldThrow<IllegalArgumentException> {
                restore(c.cancellation, parts.copy(observations = listOf(first.copy(attemptId = CancellationFixture.cancelAttemptId(9)))))
            }
            shouldThrow<IllegalArgumentException> { restore(c.cancellation, parts.copy(conflicts = listOf(first))) }
            shouldThrow<IllegalArgumentException> {
                restore(c.cancellation, parts.copy(conflicts = listOf(cancelObservation(c, InstitutionOutcome.DECLINED, source = ObservationSource.INQUIRY, resultId = "inq-other"))))
            }
        }
        Then("the transaction belongs to this cancellation, keeps the original reference and scope, matches the request and its source observation") {
            val done = CancellationFixture.completed(started()).cancellation
            val tx = checkNotNull(done.transaction)
            listOf(
                tx.copy(cancellationId = CancellationFixture.cancellationId(9)),
                tx.copy(originalTransactionId = PaymentTransactionId("demo-ptx-other")),
                tx.copy(scope = CancelScope.FULL),
                tx.copy(terms = CancellationFixture.confirmedTerms(2_000L.won())),
                tx.copy(sourceObservation = tx.sourceObservation.copy(sourceResultId = "nowhere")),
            ).forEach { bad -> shouldThrow<IllegalArgumentException> { restore(done, done.parts().copy(transaction = bad)) } }
        }
        Then("an operator row with requester = approver or a negative incident count is rejected") {
            val s = AuthorizationFixture.authorized()
            val op = CancellationFixture.started(s, null, 3_000L.won(), initiator = CancellationFixture.operator(s, 3_000L.won(), null)).cancellation
            val bad = (op.initiator as CancelInitiator.Operator).copy(approvedBy = OperatorId("demo-op-requester"))
            shouldThrow<IllegalArgumentException> { restore(op, op.parts().copy(initiator = bad)) }
            shouldThrow<IllegalArgumentException> { restore(op, op.parts().copy(incidentCount = -1)) }
            // mid-review #1 m-3: DB 열(500·200)을 넘는 운영자 사유·근거는 저장될 수 없는 행이다.
            val operator: CancelInitiator.Operator = op.initiator
            shouldThrow<IllegalArgumentException> { restore(op, op.parts().copy(initiator = operator.copy(reason = "가".repeat(501)))) }
            shouldThrow<IllegalArgumentException> {
                restore(op, op.parts().copy(initiator = operator.copy(purpose = OperatorCancelPurpose.IncidentOrReconCorrection("c".repeat(201)))))
            }
            // 사건이 없는 행에서 사건 수만 있는 경우는 진행 상태와 무관하게 복원한다(사건은 종료 뒤에도 기록될 수 있다 — CR-11·12).
            same(op, restore(op))
        }
    }
})

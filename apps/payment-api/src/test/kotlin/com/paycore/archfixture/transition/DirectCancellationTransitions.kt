package com.paycore.archfixture.transition

import com.paycore.payment.payment.domain.AbandonCause
import com.paycore.payment.payment.domain.CancelAttempt
import com.paycore.payment.payment.domain.CancelObservation
import com.paycore.payment.payment.domain.CancelTransaction
import com.paycore.payment.payment.domain.PaymentCancellation
import com.paycore.payment.payment.domain.TerminationEvidence
import com.paycore.payment.payment.domain.UnknownCause

/**
 * ArchUnit 위반 fixture(시험 전용, 실행하지 않는다): domain 밖 main 코드가 취소 내부 전이를 부르는 우회. 대상: PaymentCancellation.recordObservation·
 * markUnknown·complete·fail·recordIncident, CancelAttempt.beginDispatch·abandon·markOutcomeUnknown·resolve.
 */
class DirectCancellationTransitions {
    fun bypass(cancellation: PaymentCancellation, attempt: CancelAttempt, observation: CancelObservation, transaction: CancelTransaction) {
        cancellation.recordObservation(observation)
        cancellation.markUnknown()
        cancellation.complete(transaction)
        cancellation.fail(TerminationEvidence.INSTITUTION_CONFIRMED_DECLINE)
        cancellation.recordIncident()
        attempt.beginDispatch(cancellation.id)
        attempt.abandon(cancellation.id, AbandonCause.GATE_REFUSED)
        attempt.markOutcomeUnknown(cancellation.id, UnknownCause.TIMEOUT)
        attempt.resolve(cancellation.id)
    }
}

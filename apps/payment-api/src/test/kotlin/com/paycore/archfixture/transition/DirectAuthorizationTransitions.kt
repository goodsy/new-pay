package com.paycore.archfixture.transition

import com.paycore.payment.payment.domain.AbandonCause
import com.paycore.payment.payment.domain.AuthorizationAttempt
import com.paycore.payment.payment.domain.InstitutionObservation
import com.paycore.payment.payment.domain.PaymentAuthorization
import com.paycore.payment.payment.domain.PaymentTransaction
import com.paycore.payment.payment.domain.TerminationEvidence
import com.paycore.payment.payment.domain.UnknownCause

/**
 * ArchUnit 위반 fixture(시험 전용, 실행하지 않는다): 같은 Gradle 모듈의 domain 밖 코드는 Kotlin `internal` 전이를 부를 수 있다 — 그 우회를
 * 규칙이 잡는지 본다. 대상: PaymentAuthorization.recordObservation·markUnknown·authorize·fail, AuthorizationAttempt.beginDispatch·abandon·
 * markOutcomeUnknown·resolve.
 */
class DirectAuthorizationTransitions {
    fun bypass(authorization: PaymentAuthorization, attempt: AuthorizationAttempt, observation: InstitutionObservation, transaction: PaymentTransaction) {
        authorization.recordObservation(observation)
        authorization.markUnknown()
        authorization.authorize(transaction)
        authorization.fail(TerminationEvidence.INSTITUTION_CONFIRMED_DECLINE)
        attempt.beginDispatch(authorization.id)
        attempt.abandon(authorization.id, AbandonCause.GATE_REFUSED)
        attempt.markOutcomeUnknown(authorization.id, UnknownCause.TIMEOUT)
        attempt.resolve(authorization.id)
    }
}

package com.paycore.archfixture.cancelstart

import com.paycore.payment.payment.domain.PaymentCancellation

/** ArchUnit 위반 fixture(시험 전용, 실행하지 않는다): persistence adapter 밖에서 취소 operation을 복원한다. 대상 = PaymentCancellation.Companion.reconstitute(CANCEL-001 F-1). */
class DirectCancellationReconstitute {
    fun bypass(source: PaymentCancellation): PaymentCancellation =
        PaymentCancellation.reconstitute(
            source.id, source.paymentId, source.authorizationId, source.originalTransactionId, source.orderKey, source.money, source.scope, source.initiator,
            source.originalProviderTransactionId, source.policyAssignmentVersion, source.entitlementVersion, source.capabilityVersion, source.openedAt,
            source.status, source.attempts, source.observations, source.keyConflictingObservations, source.transaction, source.failureEvidence, source.incidentCount,
        )
}

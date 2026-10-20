package com.paycore.payment.payment.adapter.persistence

import com.paycore.payment.payment.domain.AbandonCause
import com.paycore.payment.payment.domain.AttemptState
import com.paycore.payment.payment.domain.AuthorizationId
import com.paycore.payment.payment.domain.CancelAttempt
import com.paycore.payment.payment.domain.CancelAttemptId
import com.paycore.payment.payment.domain.CancelConfirmedTerms
import com.paycore.payment.payment.domain.CancelInitiator
import com.paycore.payment.payment.domain.CancelObservation
import com.paycore.payment.payment.domain.CancelObservationKey
import com.paycore.payment.payment.domain.CancelScope
import com.paycore.payment.payment.domain.CancelTransaction
import com.paycore.payment.payment.domain.CancellationId
import com.paycore.payment.payment.domain.CancellationStatus
import com.paycore.payment.payment.domain.CurrencyCode
import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.InstitutionOutcome
import com.paycore.payment.payment.domain.MerchantCancelId
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.ObservationSource
import com.paycore.payment.payment.domain.OperatorApprovalId
import com.paycore.payment.payment.domain.OperatorCancelPurpose
import com.paycore.payment.payment.domain.OperatorId
import com.paycore.payment.payment.domain.PaymentCancellation
import com.paycore.payment.payment.domain.PaymentId
import com.paycore.payment.payment.domain.PaymentTransactionId
import com.paycore.payment.payment.domain.ProviderRequestRef
import com.paycore.payment.payment.domain.TerminationEvidence
import com.paycore.payment.payment.domain.UnknownCause

/**
 * `PaymentCancellation` ↔ 행 변환(CANCEL-001 명세 §8.4). 복원은 `PaymentCancellation.reconstitute`(존재할 수 없는 조합 거부 — F-1)만 쓴다. 관찰 행 variant 0 = 첫 관찰,
 * n ≥ 1 = 같은 키의 n번째 상충 증거. 두 목록은 저장 순서(id)로 되돌린다(domain 기록 순서).
 */
internal object PaymentCancellationPersistenceMapper {

    const val INITIATOR_MERCHANT = "MERCHANT"
    const val INITIATOR_OPERATOR = "OPERATOR"
    const val PURPOSE_INCIDENT_OR_RECON = "INCIDENT_OR_RECON_CORRECTION"
    const val PURPOSE_MERCHANT_TERMINATED = "MERCHANT_TERMINATED_OBLIGATION"
    const val RECORDED_BY_RESOLUTION = "RESOLUTION"

    fun newRow(c: PaymentCancellation): JpaPaymentCancellationEntity {
        val order = PaymentPersistenceMapper.orderKeyColumns(c.orderKey)
        val merchant = c.initiator as? CancelInitiator.Merchant
        val operator = c.initiator as? CancelInitiator.Operator
        return JpaPaymentCancellationEntity.newRow(
            cancellationId = c.id.value, paymentId = c.paymentId.value, authorizationId = c.authorizationId.value, originalTransactionId = c.originalTransactionId.value,
            environment = order.environment, merchantAccountId = order.merchantAccountId, merchantOrderId = order.merchantOrderId, amountMinor = c.money.amountMinor,
            currency = c.money.currency.value, scope = c.scope.name, status = c.status.name, failureEvidence = c.failureEvidence?.name, incidentCount = c.incidentCount,
            initiatorKind = if (operator != null) INITIATOR_OPERATOR else INITIATOR_MERCHANT, merchantCancelId = merchant?.merchantCancelId?.value,
            approvalId = operator?.approvalId?.value, requestedBy = operator?.requestedBy?.value, approvedBy = operator?.approvedBy?.value, operatorReason = operator?.reason,
            purposeKind = operator?.purpose?.let(::purposeKind), purposeRef = operator?.purpose?.reference, approvedTargetFingerprint = operator?.approvedTargetFingerprint,
            originalProviderTransactionId = c.originalProviderTransactionId, policyAssignmentVersion = c.policyAssignmentVersion, entitlementVersion = c.entitlementVersion,
            cancelCapabilityVersion = c.capabilityVersion, openedAt = c.openedAt,
        )
    }

    fun newAttemptRow(cancellationId: CancellationId, a: CancelAttempt): JpaCancelAttemptEntity {
        val r = a.providerRequest
        return JpaCancelAttemptEntity.newRow(
            attemptId = a.id.value, cancellationId = cancellationId.value, institution = r.institution, environment = r.environment.name,
            institutionAccount = r.institutionAccount, operation = r.operation, requestId = r.requestId, state = a.state.name, abandonCause = a.abandonCause?.name,
            unknownCause = a.unknownCause?.name, routeProfileVersion = a.routeProfileVersion, preparedAt = a.preparedAt,
        )
    }

    /** domain 관찰 목록을 (관찰, variant) 행으로 펼친다: 첫 관찰 0, 같은 키의 상충 증거는 그 키 안에서 1, 2, … (기록 순서). */
    fun observationRows(c: PaymentCancellation): List<Pair<CancelObservation, Int>> {
        val counts = mutableMapOf<CancelObservationKey, Int>()
        return c.observations.map { it to 0 } + c.keyConflictingObservations.map { o -> o to counts.merge(o.key, 1, Int::plus)!! }
    }

    fun newObservationRow(cancellationId: CancellationId, o: CancelObservation, variant: Int): JpaCancelObservationEntity {
        val t = o.confirmed
        return JpaCancelObservationEntity.newRow(
            cancellationId = cancellationId.value, attemptId = o.attemptId.value, source = o.source.name, sourceResultId = o.sourceResultId, variant = variant,
            outcome = o.outcome.name, cancelledAmountMinor = t?.money?.amountMinor, cancelledCurrency = t?.money?.currency?.value,
            originalProviderTransactionId = t?.originalProviderTransactionId, providerTransactionId = t?.providerTransactionId, cancelApprovalNumber = t?.cancelApprovalNumber,
            institutionTransactionDate = t?.institutionTransactionDate, institutionBusinessDate = t?.institutionBusinessDate, evidenceHash = o.evidenceHash, observedAt = o.observedAt,
        )
    }

    fun newTransactionRow(t: CancelTransaction): JpaPaymentTransactionEntity = JpaPaymentTransactionEntity.newCancelRow(
        transactionId = t.id.value, paymentId = t.paymentId.value, authorizationId = t.authorizationId.value, cancellationId = t.cancellationId.value,
        originalTransactionId = t.originalTransactionId.value, cancelScope = t.scope.name, amountMinor = t.terms.money.amountMinor, currency = t.terms.money.currency.value,
        cancelApprovalNumber = t.terms.cancelApprovalNumber, providerTransactionId = t.terms.providerTransactionId,
        originalProviderTransactionId = t.terms.originalProviderTransactionId, institutionTransactionDate = t.terms.institutionTransactionDate,
        institutionBusinessDate = t.terms.institutionBusinessDate, sourceAttemptId = t.sourceObservation.attemptId.value, sourceKind = t.sourceObservation.source.name,
        sourceResultId = t.sourceObservation.sourceResultId, recordedBy = RECORDED_BY_RESOLUTION, confirmedAt = t.confirmedAt,
    )

    fun toAttempt(row: JpaCancelAttemptEntity): CancelAttempt = CancelAttempt(
        CancelAttemptId(row.attemptId),
        ProviderRequestRef(row.institution, Environment.valueOf(row.environment), row.institutionAccount, row.operation, row.requestId),
        row.preparedAt,
        AttemptState.valueOf(row.state),
        row.abandonCause?.let(AbandonCause::valueOf),
        row.unknownCause?.let(UnknownCause::valueOf),
        row.routeProfileVersion,
    )

    fun toObservation(row: JpaCancelObservationEntity): CancelObservation = CancelObservation(
        attemptId = CancelAttemptId(row.attemptId),
        source = ObservationSource.valueOf(row.source),
        sourceResultId = row.sourceResultId,
        outcome = InstitutionOutcome.valueOf(row.outcome),
        confirmed = row.cancelledAmountMinor?.let { amount ->
            CancelConfirmedTerms(
                Money(amount, CurrencyCode(checkNotNull(row.cancelledCurrency))), checkNotNull(row.originalProviderTransactionId), checkNotNull(row.providerTransactionId),
                row.cancelApprovalNumber, checkNotNull(row.institutionTransactionDate), row.institutionBusinessDate,
            )
        },
        evidenceHash = row.evidenceHash,
        observedAt = row.observedAt,
    )

    fun toTransaction(row: JpaPaymentTransactionEntity): CancelTransaction {
        check(row.type == "CANCEL") { "transaction ${row.transactionId} is not a cancel transaction" }
        return CancelTransaction(
            id = PaymentTransactionId(row.transactionId),
            paymentId = PaymentId(row.paymentId),
            authorizationId = AuthorizationId(row.authorizationId),
            cancellationId = CancellationId(checkNotNull(row.cancellationId)),
            originalTransactionId = PaymentTransactionId(checkNotNull(row.originalTransactionId)),
            scope = CancelScope.valueOf(checkNotNull(row.cancelScope)),
            terms = CancelConfirmedTerms(
                Money(row.amountMinor, CurrencyCode(row.currency)), checkNotNull(row.originalProviderTransactionId), row.providerTransactionId, row.approvalNumber,
                row.institutionTransactionDate, row.institutionBusinessDate,
            ),
            sourceObservation = CancelObservationKey(CancelAttemptId(row.sourceAttemptId), ObservationSource.valueOf(row.sourceKind), row.sourceResultId),
            confirmedAt = row.confirmedAt,
        )
    }

    fun toInitiator(row: JpaPaymentCancellationEntity): CancelInitiator = when (row.initiatorKind) {
        INITIATOR_MERCHANT -> CancelInitiator.Merchant(MerchantCancelId(checkNotNull(row.merchantCancelId)))
        INITIATOR_OPERATOR -> CancelInitiator.Operator(
            requestedBy = OperatorId(checkNotNull(row.requestedBy)),
            approvedBy = OperatorId(checkNotNull(row.approvedBy)),
            approvalId = OperatorApprovalId(checkNotNull(row.approvalId)),
            reason = checkNotNull(row.operatorReason),
            purpose = when (row.purposeKind) {
                PURPOSE_INCIDENT_OR_RECON -> OperatorCancelPurpose.IncidentOrReconCorrection(checkNotNull(row.purposeRef))
                PURPOSE_MERCHANT_TERMINATED -> OperatorCancelPurpose.MerchantTerminatedObligation(checkNotNull(row.purposeRef))
                else -> error("unknown operator cancel purpose of ${row.cancellationId}")
            },
            approvedTargetFingerprint = checkNotNull(row.approvedTargetFingerprint),
        )
        else -> error("unknown cancel initiator of ${row.cancellationId}")
    }

    fun toCancellation(
        row: JpaPaymentCancellationEntity,
        attempts: List<JpaCancelAttemptEntity>,
        observations: List<JpaCancelObservationEntity>,
        transaction: JpaPaymentTransactionEntity?,
    ): PaymentCancellation = PaymentCancellation.reconstitute(
        id = CancellationId(row.cancellationId),
        paymentId = PaymentId(row.paymentId),
        authorizationId = AuthorizationId(row.authorizationId),
        originalTransactionId = PaymentTransactionId(row.originalTransactionId),
        orderKey = PaymentPersistenceMapper.orderKeyOf(row.environment, row.merchantAccountId, row.merchantOrderId),
        money = Money(row.amountMinor, CurrencyCode(row.currency)),
        scope = CancelScope.valueOf(row.scope),
        initiator = toInitiator(row),
        originalProviderTransactionId = row.originalProviderTransactionId,
        policyAssignmentVersion = row.policyAssignmentVersion,
        entitlementVersion = row.entitlementVersion,
        capabilityVersion = row.cancelCapabilityVersion,
        openedAt = row.openedAt,
        status = CancellationStatus.valueOf(row.status),
        attempts = attempts.map(::toAttempt),
        observations = observations.filter { it.variant == 0 }.map(::toObservation),
        keyConflictingObservations = observations.filter { it.variant > 0 }.map(::toObservation),
        transaction = transaction?.let(::toTransaction),
        failureEvidence = row.failureEvidence?.let(TerminationEvidence::valueOf),
        incidentCount = row.incidentCount,
    )

    private fun purposeKind(p: OperatorCancelPurpose): String = when (p) {
        is OperatorCancelPurpose.IncidentOrReconCorrection -> PURPOSE_INCIDENT_OR_RECON
        is OperatorCancelPurpose.MerchantTerminatedObligation -> PURPOSE_MERCHANT_TERMINATED
    }
}

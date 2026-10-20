package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.provided.CancelFailureCategory
import com.paycore.payment.payment.application.required.CancellationIncidentDetectedV1Payload
import com.paycore.payment.payment.application.required.CancellationOutcomeUnresolvedV1Payload
import com.paycore.payment.payment.application.required.EscalationReason
import com.paycore.payment.payment.application.provided.EventId
import com.paycore.payment.payment.application.required.OutboxEvent
import com.paycore.payment.payment.application.required.OutboxPayload
import com.paycore.payment.payment.application.required.PaymentCancelFailedV1Payload
import com.paycore.payment.payment.application.required.PaymentCancelledV1Payload
import com.paycore.payment.payment.domain.AuthorizationTransaction
import com.paycore.payment.payment.domain.CancelAttemptId
import com.paycore.payment.payment.domain.CancelInitiator
import com.paycore.payment.payment.domain.CancelObservation
import com.paycore.payment.payment.domain.CancelResolutionOutcome
import com.paycore.payment.payment.domain.IncidentHold
import com.paycore.payment.payment.domain.PaymentCancellation
import com.paycore.payment.payment.domain.PaymentPlan
import com.paycore.payment.payment.domain.TerminationEvidence
import java.time.Instant

/**
 * 취소 통합 이벤트 v1(CANCEL-001 §4.10). aggregateType `PaymentCancellation`, aggregateVersion = 그 TX에서 갱신한 취소 JPA version. effect_key가 업무 효과당 하나라 같은 효과의 이벤트가
 * 두 번 생기지 않는다(UNIQUE): 확정 `PaymentCancelled:{transactionId}`(CN-13), 무효과 `PaymentCancelFailed:{cancellationId}`, 사건 `…:{cancellationId}:{incidentSeq}`(D-CANCEL1-002),
 * 이관 `…:{attemptId}`. payload에 카드번호·Secret·운영자 개인정보(사유 문구)·기관 계약계정을 넣지 않는다.
 */
internal object CancellationEvents {
    private const val PRODUCER = "payment-api/payment"
    private const val AGGREGATE_TYPE = "PaymentCancellation"
    private const val SCHEMA_VERSION = 1

    @Suppress("LongParameterList")
    fun cancelled(
        c: PaymentCancellation, completed: CancelResolutionOutcome.Completed, original: AuthorizationTransaction, merchantSiteId: String, version: Long, eventId: EventId,
        causation: EventCausation,
    ): OutboxEvent {
        val t = completed.transaction
        val o = c.orderKey
        return envelope(
            "PaymentCancelled", "FINANCIAL_FACT", c, version, t.confirmedAt, eventId, causation, "PaymentCancelled:${t.id.value}",
            PaymentCancelledV1Payload(
                environment = o.environment, merchantAccountId = o.merchantAccountId.value, merchantSiteId = merchantSiteId, merchantOrderId = o.merchantOrderId.value,
                paymentId = c.paymentId.value, authorizationId = c.authorizationId.value, originalTransactionId = c.originalTransactionId.value, cancellationId = c.id.value,
                transactionId = t.id.value, amountMinor = t.terms.money.amountMinor, currency = t.terms.money.currency.value, scope = t.scope.name,
                authorizedAmountMinor = completed.authorized.amountMinor, returnSequence = completed.completion.sequence, cumulativeReturnedMinor = completed.completion.cumulativeMinor,
                returnStatusAfter = completed.statusAfter.name, cancelApprovalNumber = t.terms.cancelApprovalNumber, providerTransactionId = t.terms.providerTransactionId,
                originalProviderTransactionId = t.terms.originalProviderTransactionId, institutionTransactionDate = t.terms.institutionTransactionDate,
                institutionBusinessDate = t.terms.institutionBusinessDate, cancelledAt = t.confirmedAt, policyAssignmentVersion = c.policyAssignmentVersion,
                entitlementVersion = c.entitlementVersion, initiatorKind = initiatorKind(c.initiator),
                originalPlanKind = if (original.terms.plan is PaymentPlan.Installment) "INSTALLMENT" else "LUMP_SUM",
                originalInstallmentMonths = (original.terms.plan as? PaymentPlan.Installment)?.months, originalCardType = original.terms.cardType.name,
            ),
        )
    }

    fun failed(c: PaymentCancellation, version: Long, at: Instant, eventId: EventId, causation: EventCausation): OutboxEvent {
        val evidence = checkNotNull(c.failureEvidence) { "a FAILED cancellation has its evidence" }
        val o = c.orderKey
        return envelope(
            "PaymentCancelFailed", "INTEGRATION_NON_FINANCIAL", c, version, at, eventId, causation, "PaymentCancelFailed:${c.id.value}",
            PaymentCancelFailedV1Payload(
                environment = o.environment, merchantAccountId = o.merchantAccountId.value, merchantOrderId = o.merchantOrderId.value, paymentId = c.paymentId.value,
                cancellationId = c.id.value, amountMinor = c.money.amountMinor, currency = c.money.currency.value, failureEvidence = evidence.name,
                failureCategory = failureCategory(evidence).name, failedAt = at,
            ),
        )
    }

    @Suppress("LongParameterList")
    fun incident(
        c: PaymentCancellation, incident: CancelResolutionOutcome.Incident, hold: IncidentHold?, opening: CancelObservation?, excess: Boolean, excessMinor: Long, version: Long, at: Instant,
        eventId: EventId, causation: EventCausation,
    ): OutboxEvent {
        val o = c.orderKey
        return envelope(
            "CancellationIncidentDetected", "RISK_INCIDENT", c, version, at, eventId, causation, "CancellationIncidentDetected:${c.id.value}:${incident.incidentSeq}",
            CancellationIncidentDetectedV1Payload(
                environment = o.environment, merchantAccountId = o.merchantAccountId.value, merchantOrderId = o.merchantOrderId.value, paymentId = c.paymentId.value,
                cancellationId = c.id.value, incidentSeq = incident.incidentSeq, reason = incident.reason.name, riskHint = if (excess) "CRITICAL" else "HIGH", excessAmountMinor = excessMinor,
                holdOpened = incident.holdOpened, holdOccurrence = hold?.occurrence, observationSource = opening?.source?.name, observationResultId = opening?.sourceResultId,
                observedOutcome = opening?.outcome?.name, observedAmountMinor = opening?.confirmed?.money?.amountMinor, observedCurrency = opening?.confirmed?.money?.currency?.value,
                observedProviderTransactionId = opening?.confirmed?.providerTransactionId, detectedAt = at,
            ),
        )
    }

    @Suppress("LongParameterList")
    fun unresolved(
        c: PaymentCancellation, attemptId: CancelAttemptId, inquiryCount: Int, unknownSince: Instant, configVersion: String, reason: EscalationReason, version: Long, at: Instant,
        eventId: EventId, causation: EventCausation,
    ): OutboxEvent {
        val o = c.orderKey
        return envelope(
            "CancellationOutcomeUnresolved", "OPERATIONAL_RISK", c, version, at, eventId, causation, "CancellationOutcomeUnresolved:${attemptId.value}",
            CancellationOutcomeUnresolvedV1Payload(
                environment = o.environment, merchantAccountId = o.merchantAccountId.value, merchantOrderId = o.merchantOrderId.value, paymentId = c.paymentId.value,
                cancellationId = c.id.value, attemptId = attemptId.value, requestId = c.attempt(attemptId).providerRequest.requestId, inquiryCount = inquiryCount,
                unknownSince = unknownSince, escalatedAt = at, configVersion = configVersion, escalationReason = reason,
            ),
        )
    }

    fun failureCategory(evidence: TerminationEvidence): CancelFailureCategory = when (evidence) {
        TerminationEvidence.INSTITUTION_CONFIRMED_DECLINE -> CancelFailureCategory.DECLINED
        TerminationEvidence.PRE_TRANSMISSION_FAILURE_CONFIRMED -> CancelFailureCategory.NOT_SENT
        TerminationEvidence.INSTITUTION_CONFIRMED_NO_EFFECT -> CancelFailureCategory.NO_EFFECT
        else -> error("a cancellation fails only with sufficient evidence")
    }

    private fun initiatorKind(i: CancelInitiator) = when (i) {
        is CancelInitiator.Merchant -> "MERCHANT"
        is CancelInitiator.Operator -> "OPERATOR"
    }

    @Suppress("LongParameterList")
    private fun envelope(
        type: String, category: String, c: PaymentCancellation, version: Long, at: Instant, eventId: EventId, causation: EventCausation, effectKey: String, payload: OutboxPayload,
    ) = OutboxEvent(
        eventId = eventId, eventType = type, schemaVersion = SCHEMA_VERSION, eventCategory = category, producer = PRODUCER, aggregateType = AGGREGATE_TYPE,
        aggregateId = c.id.value, aggregateVersion = version, occurredAt = at, correlationId = causation.correlationId, causationId = causation.causationId,
        effectKey = effectKey, payload = payload,
    )
}

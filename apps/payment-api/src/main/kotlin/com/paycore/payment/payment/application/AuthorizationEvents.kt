package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.provided.EventId
import com.paycore.payment.payment.application.provided.FailureCategory
import com.paycore.payment.payment.application.required.AuthorizationIncidentDetectedV1Payload
import com.paycore.payment.payment.application.required.AuthorizationOutcomeUnresolvedV1Payload
import com.paycore.payment.payment.application.required.EscalationReason
import com.paycore.payment.payment.application.required.OutboxEvent
import com.paycore.payment.payment.application.required.PaymentAuthorizationFailedV1Payload
import com.paycore.payment.payment.application.required.PaymentAuthorizedV1Payload
import com.paycore.payment.payment.domain.AttemptId
import com.paycore.payment.payment.domain.AuthorizationTransaction
import com.paycore.payment.payment.domain.IncidentHold
import com.paycore.payment.payment.domain.InstitutionObservation
import com.paycore.payment.payment.domain.PaymentAuthorization
import com.paycore.payment.payment.domain.PaymentPlan
import com.paycore.payment.payment.domain.TerminationEvidence
import com.paycore.payment.payment.domain.VerifiedAuthentication
import java.time.Instant

/** 이벤트 인과 값(envelope): confirm 경로는 requestId·Attempt, 회복은 회차 ID·Attempt. */
data class EventCausation(val correlationId: String, val causationId: String) {
    init {
        require(correlationId.length in 1..MAX_CORRELATION && causationId.length in 1..MAX_CAUSATION) { "causation values exceed the envelope columns" }
    }

    private companion object {
        const val MAX_CORRELATION = 128
        const val MAX_CAUSATION = 64
    }
}

/**
 * 승인 통합 이벤트 v1(AUTH-001 §4.10, AC1 §11). aggregateType `PaymentAuthorization`, aggregateVersion = 그 TX에서 갱신한 Authorization JPA version. effect_key가 업무 효과당
 * 하나라 같은 효과의 이벤트가 두 번 생기지 않는다(UNIQUE). payload는 명시적 통합 계약이며 카드번호·Secret·인증 증거 원문·기관 계약계정(D-MS2-005)을 넣지 않는다.
 */
internal object AuthorizationEvents {
    private const val PRODUCER = "payment-api/payment"
    private const val AGGREGATE_TYPE = "PaymentAuthorization"
    private const val SCHEMA_VERSION = 1

    fun authorized(
        authorization: PaymentAuthorization, transaction: AuthorizationTransaction, merchantSiteId: String, evidence: VerifiedAuthentication, version: Long,
        eventId: EventId, causation: EventCausation,
    ): OutboxEvent {
        val t = transaction.terms
        val o = authorization.orderKey
        return envelope(
            "PaymentAuthorized", "FINANCIAL_FACT", authorization, version, transaction.confirmedAt, eventId, causation, "PaymentAuthorized:${transaction.id.value}",
            PaymentAuthorizedV1Payload(
                environment = o.environment, merchantAccountId = o.merchantAccountId.value, merchantSiteId = merchantSiteId, merchantOrderId = o.merchantOrderId.value,
                paymentId = authorization.paymentId.value, authorizationId = authorization.id.value, transactionId = transaction.id.value,
                amountMinor = t.money.amountMinor, currency = t.money.currency.value, planKind = planKind(t.plan), installmentMonths = (t.plan as? PaymentPlan.Installment)?.months,
                cardType = t.cardType.name, issuerCode = evidence.issuerCode, acquirerCode = evidence.acquirerCode, approvalNumber = t.approvalNumber,
                providerTransactionId = t.providerTransactionId, institutionTransactionDate = t.institutionTransactionDate, institutionBusinessDate = t.institutionBusinessDate,
                approvedAt = transaction.confirmedAt, policyAssignmentVersion = authorization.admission.policyAssignmentVersion,
                entitlementVersion = authorization.admission.entitlementVersion,
            ),
        )
    }

    fun failed(authorization: PaymentAuthorization, version: Long, at: Instant, eventId: EventId, causation: EventCausation): OutboxEvent {
        val evidence = checkNotNull(authorization.failureEvidence) { "a FAILED authorization has its evidence" }
        val o = authorization.orderKey
        return envelope(
            "PaymentAuthorizationFailed", "INTEGRATION_NON_FINANCIAL", authorization, version, at, eventId, causation, "PaymentAuthorizationFailed:${authorization.id.value}",
            PaymentAuthorizationFailedV1Payload(
                environment = o.environment, merchantAccountId = o.merchantAccountId.value, merchantOrderId = o.merchantOrderId.value, paymentId = authorization.paymentId.value,
                authorizationId = authorization.id.value, failureEvidence = evidence.name, failureCategory = failureCategory(evidence).name, failedAt = at,
            ),
        )
    }

    fun incident(
        authorization: PaymentAuthorization, hold: IncidentHold, opening: InstitutionObservation?, version: Long, at: Instant, eventId: EventId, causation: EventCausation,
    ): OutboxEvent {
        val o = authorization.orderKey
        return envelope(
            "AuthorizationIncidentDetected", "RISK_INCIDENT", authorization, version, at, eventId, causation,
            "AuthorizationIncidentDetected:${hold.paymentId.value}:${hold.reason.name}:${hold.occurrence}",
            AuthorizationIncidentDetectedV1Payload(
                environment = o.environment, merchantAccountId = o.merchantAccountId.value, merchantOrderId = o.merchantOrderId.value, paymentId = hold.paymentId.value,
                authorizationId = authorization.id.value, reason = hold.reason.name, occurrence = hold.occurrence, observationSource = opening?.source?.name,
                observationResultId = opening?.sourceResultId, observedOutcome = opening?.outcome?.name, observedAmountMinor = opening?.approved?.money?.amountMinor,
                observedCurrency = opening?.approved?.money?.currency?.value, observedApprovalNumber = opening?.approved?.approvalNumber, detectedAt = at,
            ),
        )
    }

    @Suppress("LongParameterList")
    fun unresolved(
        authorization: PaymentAuthorization, attemptId: AttemptId, inquiryCount: Int, unknownSince: Instant, configVersion: String, reason: EscalationReason,
        version: Long, at: Instant, eventId: EventId, causation: EventCausation,
    ): OutboxEvent {
        val o = authorization.orderKey
        return envelope(
            "AuthorizationOutcomeUnresolved", "OPERATIONAL_RISK", authorization, version, at, eventId, causation, "AuthorizationOutcomeUnresolved:${attemptId.value}",
            AuthorizationOutcomeUnresolvedV1Payload(
                environment = o.environment, merchantAccountId = o.merchantAccountId.value, merchantOrderId = o.merchantOrderId.value, paymentId = authorization.paymentId.value,
                authorizationId = authorization.id.value, attemptId = attemptId.value, requestId = authorization.attempt(attemptId).providerRequest.requestId,
                inquiryCount = inquiryCount, unknownSince = unknownSince, escalatedAt = at, configVersion = configVersion, escalationReason = reason,
            ),
        )
    }

    fun failureCategory(evidence: TerminationEvidence): FailureCategory = when (evidence) {
        TerminationEvidence.INSTITUTION_CONFIRMED_DECLINE -> FailureCategory.DECLINED
        TerminationEvidence.PRE_TRANSMISSION_FAILURE_CONFIRMED -> FailureCategory.NOT_SENT
        TerminationEvidence.INSTITUTION_CONFIRMED_NO_EFFECT -> FailureCategory.NO_EFFECT
        else -> error("an authorization fails only with sufficient evidence")
    }

    private fun planKind(plan: PaymentPlan) = when (plan) {
        PaymentPlan.LumpSum -> "LUMP_SUM"
        is PaymentPlan.Installment -> "INSTALLMENT"
    }

    @Suppress("LongParameterList")
    private fun envelope(
        type: String, category: String, authorization: PaymentAuthorization, version: Long, at: Instant, eventId: EventId, causation: EventCausation, effectKey: String,
        payload: com.paycore.payment.payment.application.required.OutboxPayload,
    ) = OutboxEvent(
        eventId = eventId, eventType = type, schemaVersion = SCHEMA_VERSION, eventCategory = category, producer = PRODUCER, aggregateType = AGGREGATE_TYPE,
        aggregateId = authorization.id.value, aggregateVersion = version, occurredAt = at, correlationId = causation.correlationId, causationId = causation.causationId,
        effectKey = effectKey, payload = payload,
    )
}

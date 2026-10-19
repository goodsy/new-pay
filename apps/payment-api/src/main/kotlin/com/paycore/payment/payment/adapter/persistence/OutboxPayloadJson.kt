package com.paycore.payment.payment.adapter.persistence

import com.paycore.payment.payment.application.required.AuthorizationIncidentDetectedV1Payload
import com.paycore.payment.payment.application.required.AuthorizationOutcomeUnresolvedV1Payload
import com.paycore.payment.payment.application.required.OutboxPayload
import com.paycore.payment.payment.application.required.PaymentAuthorizationFailedV1Payload
import com.paycore.payment.payment.application.required.PaymentAuthorizedV1Payload
import com.paycore.payment.payment.application.required.PaymentExpiredV1Payload
import com.paycore.payment.payment.application.required.PaymentRequestedV1Payload
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Outbox payload 직렬화(통합 계약 형식은 adapter 책임). 필드 이름·형식은 계약(CP-CONTRACT-001 §10)이 기준이며 Kotlin 프로퍼티 이름을
 * 그대로 노출하지 않는다(예: amountMinor → `amount`). 금액은 JSON 정수(부동소수점 아님), 시각은 UTC ms ISO-8601(`…T01:02:03.456Z`).
 */
internal object OutboxPayloadJson {
    private val mapper: JsonMapper = JsonMapper.builder().build()
    private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    fun serialize(payload: OutboxPayload): String = when (payload) {
        is PaymentRequestedV1Payload -> mapper.writeValueAsString(
            linkedMapOf(
                "environment" to payload.environment.name,
                "merchantAccountId" to payload.merchantAccountId,
                "merchantSiteId" to payload.merchantSiteId,
                "merchantOrderId" to payload.merchantOrderId,
                "paymentId" to payload.paymentId,
                "amount" to payload.amountMinor,
                "currency" to payload.currency,
                "paymentMethod" to payload.paymentMethod,
                "slotGeneration" to payload.slotGeneration,
                "orderIntentFingerprint" to payload.orderIntentFingerprint,
                "lifecycle" to payload.lifecycle,
                "requestedAt" to format(payload.requestedAt),
            ),
        )
        is PaymentExpiredV1Payload -> mapper.writeValueAsString(
            linkedMapOf(
                "environment" to payload.environment.name,
                "merchantAccountId" to payload.merchantAccountId,
                "merchantSiteId" to payload.merchantSiteId,
                "merchantOrderId" to payload.merchantOrderId,
                "paymentId" to payload.paymentId,
                "amount" to payload.amountMinor,
                "currency" to payload.currency,
                "paymentMethod" to payload.paymentMethod,
                "slotGeneration" to payload.slotGeneration,
                "lifecycle" to payload.lifecycle,
                "terminationEvidence" to payload.terminationEvidence,
                "checkoutExpiresAt" to format(payload.checkoutExpiresAt),
                "checkoutValidityVersion" to payload.checkoutValidityVersion,
                "expiredAt" to format(payload.expiredAt),
            ),
        )
        is PaymentAuthorizedV1Payload, is PaymentAuthorizationFailedV1Payload, is AuthorizationIncidentDetectedV1Payload, is AuthorizationOutcomeUnresolvedV1Payload ->
            authorization(payload)
    }

    private fun format(instant: Instant): String = TIMESTAMP.format(instant)

    private fun authorization(payload: OutboxPayload): String = when (payload) {
        is PaymentAuthorizedV1Payload -> mapper.writeValueAsString(
            linkedMapOf(
                "environment" to payload.environment.name, "merchantAccountId" to payload.merchantAccountId, "merchantSiteId" to payload.merchantSiteId,
                "merchantOrderId" to payload.merchantOrderId, "paymentId" to payload.paymentId, "authorizationId" to payload.authorizationId,
                "transactionId" to payload.transactionId, "amount" to payload.amountMinor, "currency" to payload.currency,
                "plan" to linkedMapOf<String, Any?>("kind" to payload.planKind).also { m -> payload.installmentMonths?.let { m["months"] = it } },
                "cardType" to payload.cardType, "issuerCode" to payload.issuerCode, "acquirerCode" to payload.acquirerCode, "approvalNumber" to payload.approvalNumber,
                "providerTransactionId" to payload.providerTransactionId, "institutionTransactionDate" to payload.institutionTransactionDate.toString(),
                "institutionBusinessDate" to payload.institutionBusinessDate?.toString(), "approvedAt" to format(payload.approvedAt),
                "policyAssignmentVersion" to payload.policyAssignmentVersion, "entitlementVersion" to payload.entitlementVersion,
            ),
        )
        is PaymentAuthorizationFailedV1Payload -> mapper.writeValueAsString(
            linkedMapOf(
                "environment" to payload.environment.name, "merchantAccountId" to payload.merchantAccountId, "merchantOrderId" to payload.merchantOrderId,
                "paymentId" to payload.paymentId, "authorizationId" to payload.authorizationId, "failureEvidence" to payload.failureEvidence,
                "failureCategory" to payload.failureCategory, "failedAt" to format(payload.failedAt),
            ),
        )
        is AuthorizationIncidentDetectedV1Payload -> mapper.writeValueAsString(
            linkedMapOf(
                "environment" to payload.environment.name, "merchantAccountId" to payload.merchantAccountId, "merchantOrderId" to payload.merchantOrderId,
                "paymentId" to payload.paymentId, "authorizationId" to payload.authorizationId, "reason" to payload.reason, "occurrence" to payload.occurrence,
                "observationSource" to payload.observationSource, "observationResultId" to payload.observationResultId, "observedOutcome" to payload.observedOutcome,
                "observedAmount" to payload.observedAmountMinor, "observedCurrency" to payload.observedCurrency, "observedApprovalNumber" to payload.observedApprovalNumber,
                "detectedAt" to format(payload.detectedAt),
            ),
        )
        is AuthorizationOutcomeUnresolvedV1Payload -> mapper.writeValueAsString(
            linkedMapOf(
                "environment" to payload.environment.name, "merchantAccountId" to payload.merchantAccountId, "merchantOrderId" to payload.merchantOrderId,
                "paymentId" to payload.paymentId, "authorizationId" to payload.authorizationId, "attemptId" to payload.attemptId, "requestId" to payload.requestId,
                "inquiryCount" to payload.inquiryCount, "unknownSince" to format(payload.unknownSince), "escalatedAt" to format(payload.escalatedAt),
                "configVersion" to payload.configVersion, "escalationReason" to payload.escalationReason.name,
            ),
        )
        else -> error("not an authorization payload")
    }
}

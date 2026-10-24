package com.paycore.payment.payment.adapter.persistence

import com.paycore.payment.payment.application.required.MerchantNotificationFacts
import com.paycore.payment.payment.application.required.MerchantWebhookEnvelope
import com.paycore.payment.payment.domain.PaymentStatus
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 가맹점 Webhook 본문 v1 직렬화(WEBHOOK-001 §4.2.0 rev 0.2). 필드 이름·순서는 계약이며 GET 응답의 엄격한 부분집합이다(m-1 — 시험 T-C2b). 금액은 JSON 정수, 시각은 UTC ms `Z`.
 * 같은 봉투는 항상 같은 바이트다(순서 고정 맵) — fan-out이 한 번 만든 바이트를 저장하고 재시도는 그 바이트를 그대로 보낸다(WH-08). 없는 값은 키를 생략한다(GET 관례).
 */
internal object MerchantWebhookBodyJson {
    private val mapper: JsonMapper = JsonMapper.builder().build()
    private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    fun serialize(e: MerchantWebhookEnvelope): ByteArray {
        val facts = e.facts
        val payment = linkedMapOf<String, Any?>(
            "paymentId" to e.paymentId.value,
            "merchantOrderId" to e.merchantOrderId.value,
            "amount" to e.payment.amountMinor,
            "currency" to e.payment.currency,
            "status" to publicStatus(e.payment.status),
        )
        if (facts is MerchantNotificationFacts.AuthorizationFailed) payment["failureCategory"] = facts.failureCategory
        val out = linkedMapOf<String, Any?>(
            "eventId" to e.eventId.value,
            "type" to e.type.wireName,
            "schemaVersion" to e.schemaVersion,
            "environment" to e.environment.name,
            "merchantAccountId" to e.merchantAccountId.value,
            "createdAt" to format(e.createdAt),
            "sequence" to e.sequence,
            "payment" to payment,
        )
        when (facts) {
            is MerchantNotificationFacts.Authorized -> out["authorization"] = linkedMapOf<String, Any?>(
                "authorizationId" to facts.authorizationId,
                "status" to "AUTHORIZED",
                "approvedAmount" to facts.approvedAmountMinor,
                "currency" to facts.currency,
                "plan" to linkedMapOf<String, Any?>("kind" to facts.planKind).also { m -> facts.installmentMonths?.let { m["months"] = it } },
                "cardType" to facts.cardType,
                "approvalNumber" to facts.approvalNumber,
                "approvedAt" to format(facts.approvedAt),
            )
            is MerchantNotificationFacts.AuthorizationFailed -> out["authorization"] = linkedMapOf<String, Any?>(
                "authorizationId" to facts.authorizationId,
                "status" to "FAILED",
                "failureCategory" to facts.failureCategory,
            )
            MerchantNotificationFacts.Expired -> Unit
            is MerchantNotificationFacts.Cancelled -> {
                out["cancellation"] = cancellation(facts.cancellationId, facts.initiator, facts.merchantCancelId, "COMPLETED", facts.amountMinor, facts.currency).also { c ->
                    c["cancelTransactionId"] = facts.cancelTransactionId
                    facts.cancelApprovalNumber?.let { c["cancelApprovalNumber"] = it }
                    c["cancelledAt"] = format(facts.cancelledAt)
                }
                out["returnSummary"] = linkedMapOf<String, Any?>(
                    "authorizedAmount" to facts.authorizedAmountMinor,
                    "cancelledAmount" to facts.cancelledAmountMinor,
                    "returnStatus" to facts.returnStatus,
                )
            }
            is MerchantNotificationFacts.CancelFailed -> out["cancellation"] =
                cancellation(facts.cancellationId, facts.initiator, facts.merchantCancelId, "FAILED", facts.amountMinor, facts.currency).also { c ->
                    c["failureCategory"] = facts.failureCategory
                }
        }
        return mapper.writeValueAsBytes(out)
    }

    private fun cancellation(id: String, initiator: String, merchantCancelId: String?, status: String, amount: Long, currency: String) =
        linkedMapOf<String, Any?>("cancellationId" to id, "initiator" to initiator).also { c ->
            if (initiator == "MERCHANT") c["merchantCancelId"] = merchantCancelId
            c["status"] = status
            c["amount"] = amount
            c["currency"] = currency
        }

    /** 통지 대상 Payment의 공개 상태(GET 어휘와 같다 — 분류가 통지한 경우 이 셋뿐). */
    private fun publicStatus(status: PaymentStatus): String = when (status) {
        PaymentStatus.AUTHORIZED, PaymentStatus.FAILED, PaymentStatus.EXPIRED -> status.name
        else -> error("a notified payment is AUTHORIZED, FAILED or EXPIRED")
    }

    private fun format(instant: Instant): String = TIMESTAMP.format(instant)
}

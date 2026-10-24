package com.paycore.payment.payment.adapter.persistence

import com.paycore.payment.payment.application.required.MerchantNotificationFacts
import com.paycore.payment.payment.application.required.MerchantWebhookEnvelope
import com.paycore.payment.payment.application.required.NotifiedPayment
import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.MerchantAccountId
import com.paycore.payment.payment.domain.MerchantNotificationType
import com.paycore.payment.payment.domain.MerchantOrderId
import com.paycore.payment.payment.domain.MerchantWebhookEventId
import com.paycore.payment.payment.domain.PaymentId
import com.paycore.payment.payment.domain.PaymentStatus
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

/**
 * 가맹점 Webhook 본문 계약 v1(WEBHOOK-001 §4.2.0 rev 0.2 — T-C2 본문 바이트, T-C2b GET ⊇ Webhook). 기대 바이트는 명세 표에서 손으로 적는다.
 * GET 키 집합은 `GetPaymentController`(CP-QUERY-001 §4.4)의 실제 출력 함수 `body`·`authorization`·`returnSummary`·`cancellation`이 만드는 키 전부다(API 수준 대조는 S5 T-V1).
 */
class MerchantWebhookBodyJsonSpec : BehaviorSpec({

    val createdAt = Instant.parse("2026-10-21T01:00:00Z")
    val mapper = JsonMapper.builder().build()

    fun envelope(type: MerchantNotificationType, status: PaymentStatus, facts: MerchantNotificationFacts, sequence: Int = 1) = MerchantWebhookEnvelope(
        MerchantWebhookEventId("whe_01JB0000000000000000000001"), type, Environment.TEST, MerchantAccountId("mid_demo_1"), createdAt, sequence,
        PaymentId("pay_1"), MerchantOrderId("order-1"), NotifiedPayment(status, 10_000, "KRW"), facts,
    )

    val head = """{"eventId":"whe_01JB0000000000000000000001","type":"%s","schemaVersion":1,"environment":"TEST","merchantAccountId":"mid_demo_1",""" +
        """"createdAt":"2026-10-21T01:00:00.000Z","sequence":%d,"payment":{"paymentId":"pay_1","merchantOrderId":"order-1","amount":10000,"currency":"KRW","status":"%s""""

    val authorized = envelope(
        MerchantNotificationType.PAYMENT_AUTHORIZED, PaymentStatus.AUTHORIZED,
        MerchantNotificationFacts.Authorized("pau_1", 10_000, "KRW", "LUMP_SUM", null, "CREDIT", "12345678", Instant.parse("2026-10-21T00:59:55.123Z")),
    )
    val installment = envelope(
        MerchantNotificationType.PAYMENT_AUTHORIZED, PaymentStatus.AUTHORIZED,
        MerchantNotificationFacts.Authorized("pau_1", 60_000, "KRW", "INSTALLMENT", 3, "CREDIT", "12345678", Instant.parse("2026-10-21T00:59:55Z")),
    )
    val failed = envelope(MerchantNotificationType.PAYMENT_FAILED, PaymentStatus.FAILED, MerchantNotificationFacts.AuthorizationFailed("pau_1", "DECLINED"))
    val expired = envelope(MerchantNotificationType.PAYMENT_EXPIRED, PaymentStatus.EXPIRED, MerchantNotificationFacts.Expired)
    val cancelled = envelope(
        MerchantNotificationType.PAYMENT_CANCELLED, PaymentStatus.AUTHORIZED,
        MerchantNotificationFacts.Cancelled("pcn_1", "MERCHANT", "c-1", 3_000, "KRW", "ptx_c1", "C0001", Instant.parse("2026-10-21T00:59:58Z"), 10_000, 3_000, "PARTIALLY_CANCELLED"),
        sequence = 2,
    )
    val operatorCancelled = envelope(
        MerchantNotificationType.PAYMENT_CANCELLED, PaymentStatus.AUTHORIZED,
        MerchantNotificationFacts.Cancelled("pcn_2", "OPERATOR", null, 7_000, "KRW", "ptx_c2", null, Instant.parse("2026-10-21T00:59:59Z"), 10_000, 10_000, "FULLY_CANCELLED"),
        sequence = 3,
    )
    val cancelFailed = envelope(
        MerchantNotificationType.PAYMENT_CANCEL_FAILED, PaymentStatus.AUTHORIZED, MerchantNotificationFacts.CancelFailed("pcn_3", "OPERATOR", null, 1_000, "KRW", "NO_EFFECT"),
        sequence = 4,
    )

    fun text(e: MerchantWebhookEnvelope) = String(MerchantWebhookBodyJson.serialize(e), Charsets.UTF_8)

    Given("each event type (exact bytes — T-C2)") {
        Then("payment.authorized") {
            text(authorized) shouldBe head.format("payment.authorized", 1, "AUTHORIZED") +
                """},"authorization":{"authorizationId":"pau_1","status":"AUTHORIZED","approvedAmount":10000,"currency":"KRW","plan":{"kind":"LUMP_SUM"},""" +
                """"cardType":"CREDIT","approvalNumber":"12345678","approvedAt":"2026-10-21T00:59:55.123Z"}}"""
        }
        Then("payment.authorized with an installment plan carries the months") {
            text(installment).contains(""""plan":{"kind":"INSTALLMENT","months":3}""") shouldBe true
        }
        Then("payment.failed (no failure time — m-1)") {
            text(failed) shouldBe head.format("payment.failed", 1, "FAILED") +
                ""","failureCategory":"DECLINED"},"authorization":{"authorizationId":"pau_1","status":"FAILED","failureCategory":"DECLINED"}}"""
        }
        Then("payment.expired (status only — no expiry time)") {
            text(expired) shouldBe head.format("payment.expired", 1, "EXPIRED") + "}}"
        }
        Then("payment.cancelled by the merchant") {
            text(cancelled) shouldBe head.format("payment.cancelled", 2, "AUTHORIZED") +
                """},"cancellation":{"cancellationId":"pcn_1","initiator":"MERCHANT","merchantCancelId":"c-1","status":"COMPLETED","amount":3000,"currency":"KRW",""" +
                """"cancelTransactionId":"ptx_c1","cancelApprovalNumber":"C0001","cancelledAt":"2026-10-21T00:59:58.000Z"},""" +
                """"returnSummary":{"authorizedAmount":10000,"cancelledAmount":3000,"returnStatus":"PARTIALLY_CANCELLED"}}"""
        }
        Then("payment.cancelled by an operator: initiator only, no merchantCancelId (D-WH-001), absent approval number omitted") {
            text(operatorCancelled) shouldBe head.format("payment.cancelled", 3, "AUTHORIZED") +
                """},"cancellation":{"cancellationId":"pcn_2","initiator":"OPERATOR","status":"COMPLETED","amount":7000,"currency":"KRW",""" +
                """"cancelTransactionId":"ptx_c2","cancelledAt":"2026-10-21T00:59:59.000Z"},""" +
                """"returnSummary":{"authorizedAmount":10000,"cancelledAmount":10000,"returnStatus":"FULLY_CANCELLED"}}"""
        }
        Then("payment.cancel_failed (no failure time)") {
            text(cancelFailed) shouldBe head.format("payment.cancel_failed", 4, "AUTHORIZED") +
                """},"cancellation":{"cancellationId":"pcn_3","initiator":"OPERATOR","status":"FAILED","amount":1000,"currency":"KRW","failureCategory":"NO_EFFECT"}}"""
        }
        Then("serialization is deterministic (same envelope = same bytes — retries resend identical bytes, WH-08)") {
            listOf(authorized, failed, expired, cancelled, operatorCancelled, cancelFailed).forEach {
                MerchantWebhookBodyJson.serialize(it).contentEquals(MerchantWebhookBodyJson.serialize(it)) shouldBe true
            }
        }
    }

    Given("the GET response field sets (GetPaymentController body/authorization/returnSummary/cancellation — T-C2b)") {
        val getTop = setOf("paymentId", "merchantOrderId", "amount", "currency", "paymentCreatedAt", "status", "failureCategory", "onHold", "authorization", "returnSummary", "cancellations", "notice")
        val getAuthorization = setOf("authorizationId", "status", "approvedAmount", "currency", "plan", "cardType", "approvalNumber", "approvedAt", "failureCategory")
        val getPlan = setOf("kind", "months")
        val getReturnSummary = setOf("authorizedAmount", "cancelledAmount", "cancelInProgressAmount", "cancellableAmount", "returnStatus")
        val getCancellation = setOf(
            "cancellationId", "initiator", "merchantCancelId", "status", "amount", "currency", "requestedAt", "cancelTransactionId", "cancelApprovalNumber", "cancelledAt", "failureCategory",
        )
        val envelopeKeys = setOf("eventId", "type", "schemaVersion", "environment", "merchantAccountId", "createdAt", "sequence", "payment", "authorization", "cancellation", "returnSummary")

        fun keys(n: JsonNode?): Set<String> = n?.propertyNames()?.toSet() ?: emptySet()

        listOf(authorized, installment, failed, expired, cancelled, operatorCancelled, cancelFailed).forEach { e ->
            Then("${e.type.wireName}/${e.facts::class.simpleName}: every webhook data key is a GET key") {
                val root = mapper.readTree(MerchantWebhookBodyJson.serialize(e))
                envelopeKeys shouldContainAll keys(root)
                getTop shouldContainAll keys(root.get("payment"))
                getAuthorization shouldContainAll keys(root.get("authorization"))
                getPlan shouldContainAll keys(root.get("authorization")?.get("plan"))
                getReturnSummary shouldContainAll keys(root.get("returnSummary"))
                getCancellation shouldContainAll keys(root.get("cancellation"))
            }
        }
        Then("no time other than GET's own times and the envelope's creation time appears (no failedAt/expiredAt — m-1)") {
            listOf(failed, expired, cancelFailed).forEach { e ->
                val t = text(e)
                listOf("failedAt", "expiredAt", "requestedAt", "occurredAt").forEach { t.contains("\"$it\"") shouldBe false }
            }
        }
    }
})

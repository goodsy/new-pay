package com.paycore.payment.payment.adapter.persistence

import com.paycore.payment.payment.application.required.MerchantNotificationFacts
import com.paycore.payment.payment.application.required.UnreadableNotificationSourceException
import com.paycore.payment.payment.domain.Environment
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.DateTimeException
import java.time.Instant

/**
 * Outbox payload(`OutboxPayloadJson`이 쓴 v1 형식) → 가맹점 Webhook 사실(WEBHOOK-001 §4.2.0 rev 0.2, F-8·H-2·n-5). 공개 본문에 필요한 값만 꺼낸다 — 기관 PTX·기관 일자·
 * issuer/acquirer·정책 버전·`failureEvidence` 원 이름은 읽지 않는다. 취소 개시자·가맹점 취소 ID는 불변 `payment_cancellation` 행([CancellationRow])이 권위다.
 * 형식 오류·필드 누락·형 불일치는 추측하지 않고 [UnreadableNotificationSourceException](fan-out이 그 행을 비종결 실패로 둔다).
 */
internal object OutboxNotificationPayloads {
    private val mapper: JsonMapper = JsonMapper.builder().build()

    data class Identity(val environment: Environment, val merchantAccountId: String, val paymentId: String, val merchantOrderId: String, val cancellationId: String?)

    /** 불변 취소 행에서 읽은 개시자(`initiator_kind`)와 가맹점 취소 ID. */
    data class CancellationRow(val initiatorKind: String, val merchantCancelId: String?)

    fun identity(eventType: String, payload: String): Identity = read(payload) { n ->
        Identity(
            environment = Environment.valueOf(n.text("environment")),
            merchantAccountId = n.text("merchantAccountId"),
            paymentId = n.text("paymentId"),
            merchantOrderId = n.text("merchantOrderId"),
            cancellationId = if (eventType == "PaymentCancelled" || eventType == "PaymentCancelFailed") n.text("cancellationId") else null,
        )
    }

    fun facts(eventType: String, payload: String, cancellation: CancellationRow?): MerchantNotificationFacts {
        val parsed = read(payload) { n ->
            when (eventType) {
                "PaymentAuthorized" -> {
                    val plan = n.obj("plan")
                    val kind = plan.text("kind")
                    Parsed.Ready(
                        MerchantNotificationFacts.Authorized(
                            authorizationId = n.text("authorizationId"), approvedAmountMinor = n.integer("amount"), currency = n.text("currency"), planKind = kind,
                            installmentMonths = if (plan.has("months")) plan.integer("months").toInt() else null, cardType = n.text("cardType"),
                            approvalNumber = n.text("approvalNumber"), approvedAt = n.instant("approvedAt"),
                        ),
                    )
                }
                "PaymentAuthorizationFailed" -> Parsed.Ready(MerchantNotificationFacts.AuthorizationFailed(n.text("authorizationId"), n.text("failureCategory")))
                "PaymentExpired" -> {
                    n.instant("expiredAt") // 형식 확인만(본문에는 넣지 않는다 — m-1)
                    Parsed.Ready(MerchantNotificationFacts.Expired)
                }
                "PaymentCancelled" -> Parsed.NeedsRow { row ->
                    if (n.text("initiatorKind") != row.initiatorKind) throw UnreadableNotificationSourceException("INITIATOR_MISMATCH")
                    MerchantNotificationFacts.Cancelled(
                        cancellationId = n.text("cancellationId"), initiator = row.initiatorKind, merchantCancelId = row.merchantCancelId,
                        amountMinor = n.integer("amount"), currency = n.text("currency"),
                        // n-5: PayCore 거래 ID(payload transactionId). providerTransactionId(기관 PTX)는 쓰지 않는다.
                        cancelTransactionId = n.text("transactionId"), cancelApprovalNumber = n.optionalText("cancelApprovalNumber"), cancelledAt = n.instant("cancelledAt"),
                        authorizedAmountMinor = n.integer("authorizedAmount"), cancelledAmountMinor = n.integer("cumulativeReturnedAmount"), returnStatus = n.text("returnStatusAfter"),
                    )
                }
                "PaymentCancelFailed" -> Parsed.NeedsRow { row ->
                    MerchantNotificationFacts.CancelFailed(
                        cancellationId = n.text("cancellationId"), initiator = row.initiatorKind, merchantCancelId = row.merchantCancelId,
                        amountMinor = n.integer("amount"), currency = n.text("currency"), failureCategory = n.text("failureCategory"),
                    )
                }
                else -> throw UnreadableNotificationSourceException("NOT_CATALOGUED")
            }
        }
        return when (parsed) {
            is Parsed.Ready -> parsed.facts
            is Parsed.NeedsRow -> {
                val row = cancellation ?: throw UnreadableNotificationSourceException("CANCELLATION_ROW_MISSING")
                validated { parsed.build(row) }
            }
        }
    }

    private sealed interface Parsed {
        data class Ready(val facts: MerchantNotificationFacts) : Parsed

        class NeedsRow(val build: (CancellationRow) -> MerchantNotificationFacts) : Parsed
    }

    private fun <T> read(payload: String, block: (JsonNode) -> T): T {
        val root = try {
            mapper.readTree(payload)
        } catch (e: JacksonException) {
            throw UnreadableNotificationSourceException("PAYLOAD_UNREADABLE")
        }
        if (root == null || !root.isObject) throw UnreadableNotificationSourceException("PAYLOAD_UNREADABLE")
        return validated { block(root) }
    }

    /** 사실 값의 불변식 위반(예: 운영자 취소에 가맹점 취소 ID)은 PAYLOAD_INVALID — 추측하지 않는다. */
    private fun <T> validated(block: () -> T): T = try {
        block()
    } catch (e: UnreadableNotificationSourceException) {
        throw e
    } catch (e: MissingField) {
        throw UnreadableNotificationSourceException("PAYLOAD_UNREADABLE")
    } catch (e: IllegalArgumentException) {
        throw UnreadableNotificationSourceException("PAYLOAD_INVALID")
    }

    private class MissingField : RuntimeException("missing or mistyped payload field")

    private fun JsonNode.field(name: String): JsonNode = get(name)?.takeUnless { it.isNull } ?: throw MissingField()

    private fun JsonNode.text(name: String): String = field(name).takeIf { it.isString }?.stringValue() ?: throw MissingField()

    private fun JsonNode.optionalText(name: String): String? = get(name)?.takeUnless { it.isNull }?.let { if (it.isString) it.stringValue() else throw MissingField() }

    private fun JsonNode.integer(name: String): Long = field(name).takeIf { it.isIntegralNumber }?.longValue() ?: throw MissingField()

    private fun JsonNode.obj(name: String): JsonNode = field(name).takeIf { it.isObject } ?: throw MissingField()

    private fun JsonNode.instant(name: String): Instant = try {
        Instant.parse(text(name))
    } catch (e: DateTimeException) {
        throw MissingField()
    }
}

package com.paycore.payment.payment.adapter.persistence

import com.paycore.payment.payment.application.required.MerchantNotificationFacts
import com.paycore.payment.payment.application.required.PaymentAuthorizationFailedV1Payload
import com.paycore.payment.payment.application.required.PaymentAuthorizedV1Payload
import com.paycore.payment.payment.application.required.PaymentCancelFailedV1Payload
import com.paycore.payment.payment.application.required.PaymentCancelledV1Payload
import com.paycore.payment.payment.application.required.PaymentExpiredV1Payload
import com.paycore.payment.payment.application.required.UnreadableNotificationSourceException
import com.paycore.payment.payment.domain.Environment
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.time.LocalDate

/**
 * Outbox payload → 가맹점 사실 해석(WEBHOOK-001 §4.2.0 rev 0.2, F-8·H-2·n-5). 입력은 **운영 직렬화기**(`OutboxPayloadJson`)가 만든 바이트 그대로다(생산자·해석기 어긋남 방지).
 * 취소의 개시자·가맹점 취소 ID는 불변 `payment_cancellation` 행 값이 권위다(PaymentCancelFailed payload에는 개시자가 없다 — H-2).
 */
class OutboxNotificationPayloadsSpec : BehaviorSpec({

    val at = Instant.parse("2026-10-21T00:59:55.123Z")
    val authorizedPayload = OutboxPayloadJson.serialize(
        PaymentAuthorizedV1Payload(
            Environment.TEST, "mid_demo_1", "site_1", "order-1", "pay_1", "pau_1", "ptx_a1", 10_000, "KRW", "INSTALLMENT", 3, "CREDIT", "ISS-A", "ACQ-A", "12345678",
            "PTX-PROVIDER-A", LocalDate.parse("2026-10-21"), null, at, "pa-v1", "ent-7",
        ),
    )
    val cancelledPayload = OutboxPayloadJson.serialize(
        PaymentCancelledV1Payload(
            Environment.TEST, "mid_demo_1", "site_1", "order-1", "pay_1", "pau_1", "ptx_a1", "pcn_1", "ptx_c1", 3_000, "KRW", "PARTIAL", 10_000, 1, 3_000, "PARTIALLY_CANCELLED",
            "C0001", "PTX-PROVIDER-C", "PTX-PROVIDER-A", LocalDate.parse("2026-10-21"), null, at, "pa-v1", "ent-7", "MERCHANT", "LUMP_SUM", null, "CREDIT",
        ),
    )
    val cancelFailedPayload = OutboxPayloadJson.serialize(
        PaymentCancelFailedV1Payload(Environment.TEST, "mid_demo_1", "order-1", "pay_1", "pcn_2", 1_000, "KRW", "INSTITUTION_CONFIRMED_DECLINE", "DECLINED", at),
    )

    Given("the identity part of a catalogued payload") {
        Then("environment, MID, payment, order and (for cancellations) cancellation id are read") {
            OutboxNotificationPayloads.identity("PaymentCancelled", cancelledPayload) shouldBe
                OutboxNotificationPayloads.Identity(Environment.TEST, "mid_demo_1", "pay_1", "order-1", "pcn_1")
            OutboxNotificationPayloads.identity("PaymentAuthorized", authorizedPayload).cancellationId shouldBe null
        }
    }

    Given("PaymentAuthorized") {
        Then("the GET authorization terms are taken; provider ids, institution dates and versions are not carried") {
            OutboxNotificationPayloads.facts("PaymentAuthorized", authorizedPayload, null) shouldBe
                MerchantNotificationFacts.Authorized("pau_1", 10_000, "KRW", "INSTALLMENT", 3, "CREDIT", "12345678", at)
        }
    }

    Given("PaymentAuthorizationFailed and PaymentExpired") {
        Then("only the public failure category / nothing are taken") {
            val failedPayload = OutboxPayloadJson.serialize(
                PaymentAuthorizationFailedV1Payload(Environment.TEST, "mid_demo_1", "order-1", "pay_1", "pau_1", "INSTITUTION_CONFIRMED_DECLINE", "DECLINED", at),
            )
            OutboxNotificationPayloads.facts("PaymentAuthorizationFailed", failedPayload, null) shouldBe MerchantNotificationFacts.AuthorizationFailed("pau_1", "DECLINED")
            val expiredPayload = OutboxPayloadJson.serialize(
                PaymentExpiredV1Payload(
                    Environment.TEST, "mid_demo_1", "site_1", "order-1", "pay_1", 10_000, "KRW", "CARD", 1, "EXPIRED", "NOT_TRANSMITTED_AND_SESSION_DISCARDED", at, "v1", at,
                ),
            )
            OutboxNotificationPayloads.facts("PaymentExpired", expiredPayload, null) shouldBe MerchantNotificationFacts.Expired
        }
    }

    Given("PaymentCancelled") {
        Then("cancelTransactionId is PayCore's transactionId, never the provider PTX (n-5); initiator and merchantCancelId come from the cancellation row") {
            val facts = OutboxNotificationPayloads.facts("PaymentCancelled", cancelledPayload, OutboxNotificationPayloads.CancellationRow("MERCHANT", "c-1"))
            facts shouldBe MerchantNotificationFacts.Cancelled("pcn_1", "MERCHANT", "c-1", 3_000, "KRW", "ptx_c1", "C0001", at, 10_000, 3_000, "PARTIALLY_CANCELLED")
            (facts as MerchantNotificationFacts.Cancelled).cancelTransactionId shouldBe "ptx_c1"
        }
        Then("a payload initiator that disagrees with the immutable row is unreadable (fail-closed)") {
            shouldThrow<UnreadableNotificationSourceException> {
                OutboxNotificationPayloads.facts("PaymentCancelled", cancelledPayload, OutboxNotificationPayloads.CancellationRow("OPERATOR", null))
            }.code shouldBe "INITIATOR_MISMATCH"
        }
    }

    Given("PaymentCancelFailed (no initiator in the payload — H-2)") {
        Then("the initiator is read from the cancellation row: operator → no merchantCancelId, merchant → its id") {
            OutboxNotificationPayloads.facts("PaymentCancelFailed", cancelFailedPayload, OutboxNotificationPayloads.CancellationRow("OPERATOR", null)) shouldBe
                MerchantNotificationFacts.CancelFailed("pcn_2", "OPERATOR", null, 1_000, "KRW", "DECLINED")
            OutboxNotificationPayloads.facts("PaymentCancelFailed", cancelFailedPayload, OutboxNotificationPayloads.CancellationRow("MERCHANT", "c-9")) shouldBe
                MerchantNotificationFacts.CancelFailed("pcn_2", "MERCHANT", "c-9", 1_000, "KRW", "DECLINED")
        }
        Then("a missing cancellation row is unreadable") {
            shouldThrow<UnreadableNotificationSourceException> { OutboxNotificationPayloads.facts("PaymentCancelFailed", cancelFailedPayload, null) }.code shouldBe "CANCELLATION_ROW_MISSING"
        }
        Then("an inconsistent row (operator with a merchant cancel id) is unreadable") {
            shouldThrow<UnreadableNotificationSourceException> {
                OutboxNotificationPayloads.facts("PaymentCancelFailed", cancelFailedPayload, OutboxNotificationPayloads.CancellationRow("OPERATOR", "c-1"))
            }.code shouldBe "PAYLOAD_INVALID"
        }
    }

    Given("malformed payloads") {
        Then("broken JSON, a missing field or a wrong type is unreadable (never a guessed value)") {
            listOf("{", "{}", authorizedPayload.replace("\"amount\":10000", "\"amount\":\"10000\""), authorizedPayload.replace("\"approvedAt\":", "\"approvedAtX\":")).forEach { p ->
                shouldThrow<UnreadableNotificationSourceException> { OutboxNotificationPayloads.facts("PaymentAuthorized", p, null) }.code shouldBe "PAYLOAD_UNREADABLE"
            }
            shouldThrow<UnreadableNotificationSourceException> { OutboxNotificationPayloads.identity("PaymentAuthorized", "{}") }.code shouldBe "PAYLOAD_UNREADABLE"
        }
    }
})

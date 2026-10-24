package com.paycore.payment.payment.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * 가맹점 Webhook 분류 표(WEBHOOK-001 명세 §4.1, D-WH-001·002, 도출 D-CPQ-002 (ii)·D-CANCEL-013). 기대값은 명세 표에서 직접 적는다(코드에서 복사하지 않음).
 * Payment 상태 6종 × Outbox 이벤트 종류 전 행. 이벤트가 커밋된 뒤 판정하므로 "그 이벤트가 생길 수 없는 상태"는 결함(anomaly)이다.
 */
class MerchantNotificationPolicySpec : BehaviorSpec({

    val notify = MerchantNotificationDecision::Notify
    val p1Approval = MerchantNotificationDecision.Suppress(SuppressionReason.LATE_APPROVAL_ON_TERMINATED_PAYMENT)
    val p1Cancel = MerchantNotificationDecision.Suppress(SuppressionReason.CANCELLATION_ON_TERMINATED_PAYMENT)
    val anomaly: MerchantNotificationDecision? = null

    // 행: (Outbox 이벤트, Payment 상태) → 기대(null = anomaly 예외)
    val table: Map<String, Map<PaymentStatus, MerchantNotificationDecision?>> = mapOf(
        "PaymentAuthorized" to mapOf(
            PaymentStatus.REQUESTED to anomaly, PaymentStatus.AUTHORIZING to anomaly, PaymentStatus.UNKNOWN to anomaly,
            PaymentStatus.AUTHORIZED to notify(MerchantNotificationType.PAYMENT_AUTHORIZED),
            PaymentStatus.FAILED to p1Approval, PaymentStatus.EXPIRED to p1Approval,
        ),
        "PaymentAuthorizationFailed" to mapOf(
            PaymentStatus.REQUESTED to anomaly, PaymentStatus.AUTHORIZING to anomaly, PaymentStatus.UNKNOWN to anomaly,
            PaymentStatus.AUTHORIZED to anomaly, PaymentStatus.FAILED to notify(MerchantNotificationType.PAYMENT_FAILED), PaymentStatus.EXPIRED to anomaly,
        ),
        "PaymentExpired" to mapOf(
            PaymentStatus.REQUESTED to anomaly, PaymentStatus.AUTHORIZING to anomaly, PaymentStatus.UNKNOWN to anomaly,
            PaymentStatus.AUTHORIZED to anomaly, PaymentStatus.FAILED to anomaly, PaymentStatus.EXPIRED to notify(MerchantNotificationType.PAYMENT_EXPIRED),
        ),
        "PaymentCancelled" to mapOf(
            PaymentStatus.REQUESTED to anomaly, PaymentStatus.AUTHORIZING to anomaly, PaymentStatus.UNKNOWN to anomaly,
            PaymentStatus.AUTHORIZED to notify(MerchantNotificationType.PAYMENT_CANCELLED),
            PaymentStatus.FAILED to p1Cancel, PaymentStatus.EXPIRED to p1Cancel,
        ),
        "PaymentCancelFailed" to mapOf(
            PaymentStatus.REQUESTED to anomaly, PaymentStatus.AUTHORIZING to anomaly, PaymentStatus.UNKNOWN to anomaly,
            PaymentStatus.AUTHORIZED to notify(MerchantNotificationType.PAYMENT_CANCEL_FAILED),
            PaymentStatus.FAILED to p1Cancel, PaymentStatus.EXPIRED to p1Cancel,
        ),
    )

    Given("the five catalogued Outbox events") {
        table.forEach { (eventType, row) ->
            row.forEach { (status, expected) ->
                Then("$eventType with payment $status -> ${expected ?: "anomaly"}") {
                    if (expected == null) {
                        val e = shouldThrow<MerchantNotificationAnomalyException> { MerchantNotificationPolicy.classify(eventType, status) }
                        e.eventType shouldBe eventType
                        e.paymentStatus shouldBe status
                    } else {
                        MerchantNotificationPolicy.classify(eventType, status) shouldBe expected
                    }
                }
            }
        }
        Then("the table covers every payment status for every catalogued event") {
            table.values.forEach { it.keys shouldBe PaymentStatus.entries.toSet() }
            table.keys shouldBe MerchantNotificationPolicy.CATALOGUED_EVENT_TYPES
        }
    }

    Given("Outbox events that are not merchant notifications (internal risk/operational, creation, unknown)") {
        listOf(
            "PaymentRequested", "AuthorizationIncidentDetected", "AuthorizationOutcomeUnresolved", "CancellationIncidentDetected",
            "CancellationOutcomeUnresolved", "SomeFutureEvent", "paymentauthorized",
        ).forEach { type ->
            Then("$type is not applicable for every payment status (no payment status needed)") {
                PaymentStatus.entries.forEach { MerchantNotificationPolicy.classify(type, it) shouldBe MerchantNotificationDecision.NotApplicable }
                MerchantNotificationPolicy.classify(type, null) shouldBe MerchantNotificationDecision.NotApplicable
            }
        }
    }

    Given("a catalogued event whose payment status could not be read") {
        Then("it is an anomaly (never a silent skip)") {
            MerchantNotificationPolicy.CATALOGUED_EVENT_TYPES.forEach { type ->
                shouldThrow<MerchantNotificationAnomalyException> { MerchantNotificationPolicy.classify(type, null) }.paymentStatus shouldBe null
            }
        }
    }

    Given("the public wire names (contract v1)") {
        Then("they are exactly the five names in spec §4.1") {
            MerchantNotificationType.entries.map { it.wireName } shouldBe
                listOf("payment.authorized", "payment.failed", "payment.expired", "payment.cancelled", "payment.cancel_failed")
        }
    }
})

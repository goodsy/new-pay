package com.paycore.payment.payment.application.required

import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.MerchantAccountId
import com.paycore.payment.payment.domain.MerchantNotificationType
import com.paycore.payment.payment.domain.MerchantOrderId
import com.paycore.payment.payment.domain.MerchantWebhookEventId
import com.paycore.payment.payment.domain.PaymentId
import java.time.Instant

/**
 * 가맹점 Webhook 봉투(WEBHOOK-001 §4.2.0 rev 0.2). [createdAt]은 가맹점 이벤트를 만든 시각(fan-out — 업무 시각 아님, WH-21). [sequence]는 그 Payment의 가맹점 이벤트 연속 순번.
 * 직렬화(바이트 고정)는 persistence adapter 책임이다(WH-08).
 * [merchantCode](MERCHANT-CODE-001 §6, C-36(MC-4)): 그 MID 가맹점 코드 — 출력 전용 **가산 필드**, 코드 없는 MID는 null → 키 생략. schemaVersion은 1 그대로다
 * (WEBHOOK-001 §4.2.1 "추가 필드는 가산적, 의미 변경은 새 버전" — V21 CHECK `schema_version = 1`). 이미 저장된 이벤트의 바이트는 그대로 재전송한다(WH-08).
 */
data class MerchantWebhookEnvelope(
    val eventId: MerchantWebhookEventId,
    val type: MerchantNotificationType,
    val environment: Environment,
    val merchantAccountId: MerchantAccountId,
    val createdAt: Instant,
    val sequence: Int,
    val paymentId: PaymentId,
    val merchantOrderId: MerchantOrderId,
    val payment: NotifiedPayment,
    val facts: MerchantNotificationFacts,
    val merchantCode: String? = null,
) {
    val schemaVersion: Int get() = SCHEMA_VERSION

    init {
        require(sequence >= 1) { "sequence starts at 1" }
        require(MerchantNotificationFacts.matches(type, facts)) { "facts do not match the notification type" }
    }

    companion object {
        const val SCHEMA_VERSION = 1
    }
}

/** 전달 계획: 그 (환경, MID)에 endpoint가 있었는가(없으면 이벤트만 기록 — LIVE는 항상 NO_ENDPOINT, D-WH-004·WH-12). */
enum class MerchantWebhookDeliveryPlan { DELIVER, NO_ENDPOINT }

data class MerchantWebhookEventRecord(
    val envelope: MerchantWebhookEnvelope,
    val sourceEventId: String,
    val deliveryPlan: MerchantWebhookDeliveryPlan,
    val recordedAt: Instant,
)

/** 가맹점 이벤트 저장 Port(insert-only, MANDATORY TX). 같은 Outbox 이벤트로 두 번 만들면 [PersistenceFailure.UniqueConflict]. */
interface MerchantWebhookEventStore {
    fun append(record: MerchantWebhookEventRecord)
}

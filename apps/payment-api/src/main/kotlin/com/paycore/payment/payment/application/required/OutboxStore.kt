package com.paycore.payment.payment.application.required

import com.paycore.payment.payment.application.provided.EventId
import com.paycore.payment.payment.domain.Environment
import java.time.Instant

/**
 * 통합 이벤트 Outbox 기록(CP-CONTRACT-001 §10 envelope). 업무 변경과 같은 PAYMENT 로컬 TX로 append한다. 전달(Relay)·발행 표시는
 * 이 Port 밖(별도 작업)이다. [effectKey]는 업무 효과당 하나(UNIQUE)라 같은 Payment의 생성 이벤트가 두 번 생기지 않는다.
 */
data class OutboxEvent(
    val eventId: EventId,
    val eventType: String,
    val schemaVersion: Int,
    val eventCategory: String,
    val producer: String,
    val aggregateType: String,
    val aggregateId: String,
    val aggregateVersion: Long,
    val occurredAt: Instant,
    val correlationId: String,
    val causationId: String,
    val effectKey: String,
    val payload: OutboxPayload,
)

/** 이벤트 payload(명시적 통합 계약 — Domain Entity 직렬화가 아니다). 직렬화 형식은 adapter 책임이다. */
sealed interface OutboxPayload

/**
 * PaymentRequested v1 payload(D-CPC-003). paymentKey(결제창 지목 키)·orderName·카드정보·Secret·개인정보는 넣지 않는다.
 * 금액은 통화 minor unit 정수, lifecycle은 항상 REQUESTED. 금융 사실이 아니다(eventCategory INTEGRATION_NON_FINANCIAL).
 */
data class PaymentRequestedV1Payload(
    val environment: Environment,
    val merchantAccountId: String,
    val merchantSiteId: String,
    val merchantOrderId: String,
    val paymentId: String,
    val amountMinor: Long,
    val currency: String,
    val paymentMethod: String,
    val slotGeneration: Long,
    val orderIntentFingerprint: String,
    val lifecycle: String,
    val requestedAt: Instant,
) : OutboxPayload

interface OutboxStore {
    /** event_id·effect_key 충돌은 [PersistenceFailure.UniqueConflict]. TX 없이 호출하면 거부한다. */
    fun append(event: OutboxEvent)
}

package com.paycore.financial.paymentfact.application.required

import com.paycore.financial.paymentfact.domain.PaymentFactInboxOutcome
import java.time.Instant

/** 저장된 Inbox 기록(재전송 판정에 필요한 값만). */
data class StoredPaymentFactInbox(val eventId: String, val effectKey: String, val outcome: PaymentFactInboxOutcome, val payloadSha256: String)

/** 새 Inbox 기록. [paymentId]·[transactionId]는 지원 사실일 때만(UNSUPPORTED면 null). */
data class PaymentFactInboxRecord(
    val eventId: String,
    val effectKey: String,
    val eventType: String,
    val schemaVersion: Int,
    val producer: String,
    val aggregateType: String,
    val aggregateId: String,
    val aggregateVersion: Long,
    val occurredAt: Instant,
    val outcome: PaymentFactInboxOutcome,
    val paymentId: String?,
    val transactionId: String?,
    val payload: String,
    val payloadSha256: String,
    val signingKeyId: String,
    val receivedAt: Instant,
)

/**
 * 결제 사실 Inbox 저장 Port(CONVENTIONS §14 Store ③ 프로토콜 기록 — FINANCIAL이 소유한 Aggregate가 아니다). 호출자 TX 필수. append-only(조회·INSERT만).
 * event_id·effect_key UNIQUE 충돌은 [PaymentFactPersistenceFailure.UniqueConflict].
 */
interface PaymentFactInboxStore {
    fun findByEventId(eventId: String): StoredPaymentFactInbox?

    fun findByEffectKey(effectKey: String): StoredPaymentFactInbox?

    fun insert(record: PaymentFactInboxRecord)
}

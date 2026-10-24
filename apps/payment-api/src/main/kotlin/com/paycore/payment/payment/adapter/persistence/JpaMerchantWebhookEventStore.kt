package com.paycore.payment.payment.adapter.persistence

import com.paycore.payment.payment.adapter.persistence.PersistenceFailures.translate
import com.paycore.payment.payment.application.required.MerchantWebhookEventReader
import com.paycore.payment.payment.application.required.MerchantWebhookEventRecord
import com.paycore.payment.payment.application.required.MerchantWebhookEventStore
import com.paycore.payment.payment.application.required.StoredMerchantWebhookEvent
import com.paycore.payment.payment.domain.MerchantWebhookEventId
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.util.HexFormat

/**
 * [MerchantWebhookEventStore]·[MerchantWebhookEventReader] JPA 구현(WEBHOOK-001 V21). 쓰기는 MANDATORY TX: 봉투를 v1 바이트로 한 번 직렬화해([MerchantWebhookBodyJson]) 그 바이트와 SHA-256(소문자 hex)을
 * 함께 INSERT한다. 같은 Outbox 이벤트로 두 번 → 1062 `uk_merchant_webhook_event_source` → UniqueConflict. 읽기([find])는 전송 직전 자기 읽기 전용 TX(저장 바이트 그대로 — 전송 adapter가 sha를 재확인한다, n-4).
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaMerchantWebhookEventStore(private val entityManager: EntityManager) : MerchantWebhookEventStore, MerchantWebhookEventReader {

    override fun append(record: MerchantWebhookEventRecord): Unit = translate(ENTITY) {
        val e = record.envelope
        val body = MerchantWebhookBodyJson.serialize(e)
        entityManager.persist(
            JpaMerchantWebhookEventEntity.newRow(
                webhookEventId = e.eventId.value, sourceEventId = record.sourceEventId, environment = e.environment.name, merchantAccountId = e.merchantAccountId.value,
                paymentId = e.paymentId.value, paymentSequence = e.sequence, eventType = e.type.wireName, schemaVersion = e.schemaVersion, body = body,
                bodySha256 = sha256Hex(body), deliveryPlan = record.deliveryPlan.name, createdAt = record.recordedAt,
            ),
        )
        entityManager.flush()
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRED)
    override fun find(id: MerchantWebhookEventId): StoredMerchantWebhookEvent? = translate(ENTITY) {
        entityManager.find(JpaMerchantWebhookEventEntity::class.java, id.value)?.let { StoredMerchantWebhookEvent(id, it.eventType, it.body.copyOf(), it.bodySha256) }
    }

    internal companion object {
        const val ENTITY = "merchant_webhook_event"

        fun sha256Hex(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}

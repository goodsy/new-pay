package com.paycore.financial.paymentfact.adapter.persistence

import com.paycore.financial.paymentfact.adapter.persistence.PaymentFactPersistenceFailures.translate
import com.paycore.financial.paymentfact.application.required.PaymentFactInboxRecord
import com.paycore.financial.paymentfact.application.required.PaymentFactInboxStore
import com.paycore.financial.paymentfact.application.required.StoredPaymentFactInbox
import com.paycore.financial.paymentfact.domain.PaymentFactInboxOutcome
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * [PaymentFactInboxStore] 구현(JPA). 호출자 TX 필수(MANDATORY), 쓰기는 persist + 즉시 flush(UNIQUE 충돌을 이 Port 호출 안에서 [translate]로 분류 — commit 때 처음
 * 드러나 결과 불명확으로 바뀌지 않게). 조회는 JPQL(잠금 없음 — 같은 Payment는 직렬화 행 잠금 아래에서 부르고, 다른 Payment 경합은 UNIQUE가 막는다).
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaPaymentFactInboxStore(
    private val inbox: PaymentFactInboxJpaRepository,
    private val entityManager: EntityManager,
) : PaymentFactInboxStore {

    override fun findByEventId(eventId: String): StoredPaymentFactInbox? = translate(ENTITY) { inbox.findByEvent(eventId)?.let(::stored) }

    override fun findByEffectKey(effectKey: String): StoredPaymentFactInbox? = translate(ENTITY) { inbox.findByEffect(effectKey)?.let(::stored) }

    override fun insert(record: PaymentFactInboxRecord): Unit = translate(ENTITY) {
        entityManager.persist(
            JpaPaymentFactInboxEntity.newRow(
                record.eventId, record.effectKey, record.eventType, record.schemaVersion, record.producer, record.aggregateType, record.aggregateId,
                record.aggregateVersion, record.occurredAt, record.outcome.name, record.paymentId, record.transactionId, record.payload, record.payloadSha256,
                record.signingKeyId, record.receivedAt,
            ),
        )
        entityManager.flush()
    }

    private fun stored(row: JpaPaymentFactInboxEntity) =
        StoredPaymentFactInbox(row.eventId, row.effectKey, PaymentFactInboxOutcome.valueOf(row.outcome), row.payloadSha256)

    private companion object {
        const val ENTITY = "payment_fact_inbox"
    }
}

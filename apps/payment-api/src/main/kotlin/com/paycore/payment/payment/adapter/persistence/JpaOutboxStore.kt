package com.paycore.payment.payment.adapter.persistence

import com.paycore.payment.payment.adapter.persistence.PersistenceFailures.translate
import com.paycore.payment.payment.application.required.OutboxEvent
import com.paycore.payment.payment.application.required.OutboxStore
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaOutboxStore(private val entityManager: EntityManager) : OutboxStore {

    override fun append(event: OutboxEvent): Unit = translate(ENTITY) {
        entityManager.persist(
            JpaOutboxEventEntity.pending(
                eventId = event.eventId.value,
                effectKey = event.effectKey,
                eventType = event.eventType,
                schemaVersion = event.schemaVersion,
                eventCategory = event.eventCategory,
                producer = event.producer,
                aggregateType = event.aggregateType,
                aggregateId = event.aggregateId,
                aggregateVersion = event.aggregateVersion,
                occurredAt = event.occurredAt,
                correlationId = event.correlationId,
                causationId = event.causationId,
                payload = OutboxPayloadJson.serialize(event.payload),
                createdAt = event.occurredAt,
            ),
        )
        entityManager.flush()
    }

    private companion object {
        const val ENTITY = "outbox_event"
    }
}

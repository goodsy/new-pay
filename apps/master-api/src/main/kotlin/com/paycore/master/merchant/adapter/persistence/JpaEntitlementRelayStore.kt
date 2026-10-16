package com.paycore.master.merchant.adapter.persistence

import com.paycore.master.merchant.adapter.persistence.PersistenceFailures.translate
import com.paycore.master.merchant.application.EntitlementSnapshotPublisher
import com.paycore.master.merchant.application.required.EntitlementRelayStore
import com.paycore.master.merchant.application.required.RelayEnvelope
import com.paycore.master.merchant.application.required.RelayMarkResult
import com.paycore.master.merchant.application.required.SnapshotAck
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Relay 저장(명세 §4.7). claim: `SELECT id … FOR UPDATE SKIP LOCKED`(native — JPA 표준 잠금 API에 없는 조합)로 이 TX가 행을 잠근 뒤 그 행만
 * Entity로 읽어 claim을 표시한다(잠금 뒤 읽으므로 영속성 컨텍스트가 최신 값). 결과 반영은 행을 다시 잠가(`FOR UPDATE`) 상태를 확인한 뒤 바꾼다.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaEntitlementRelayStore(
    private val outbox: OutboxEventJpaRepository,
    private val entityManager: EntityManager,
) : EntitlementRelayStore {

    override fun claim(owner: String, now: Instant, claimUntil: Instant, limit: Int): List<RelayEnvelope> = translate(ENTITY) {
        val ids = outbox.lockClaimable(EntitlementSnapshotPublisher.EVENT_CATEGORY, now, limit)
        if (ids.isEmpty()) return@translate emptyList()
        val rows = outbox.findAllByIds(ids)
        rows.forEach {
            it.claimOwner = owner
            it.claimUntil = claimUntil
        }
        entityManager.flush()
        rows.map {
            RelayEnvelope(
                eventId = it.eventId, eventType = it.eventType, schemaVersion = it.schemaVersion, producer = it.producer,
                aggregateId = it.aggregateId, aggregateVersion = it.aggregateVersion, occurredAt = it.occurredAt, attemptCount = it.attemptCount,
                payloadJson = it.payload,
            )
        }
    }

    override fun markPublished(eventId: String, owner: String, ack: SnapshotAck): RelayMarkResult = translate(ENTITY) {
        val row = outbox.lockByEventId(eventId) ?: error("outbox event must exist")
        // ACK = PAYMENT가 내구 커밋했다는 사실이다. claim이 다른 인스턴스로 넘어갔어도 전달은 끝났으므로 PUBLISHED로 둔다(다른 쪽은 DUPLICATE를 받는다).
        entityManager.persist(JpaSnapshotAckEntity.newRow(eventId, ack.consumer, ack.outcome, ack.appliedRevision, ack.ackedAt, ack.originalOutcome))
        val result = when (row.publishStatus) {
            JpaOutboxEventEntity.PUBLISHED -> RelayMarkResult.ALREADY_PUBLISHED
            JpaOutboxEventEntity.PENDING -> {
                row.publishStatus = JpaOutboxEventEntity.PUBLISHED
                row.publishedAt = ack.ackedAt
                row.claimOwner = null
                row.claimUntil = null
                RelayMarkResult.MARKED
            }
            else -> RelayMarkResult.CLAIM_LOST
        }
        entityManager.flush()
        result
    }

    override fun markRetry(eventId: String, owner: String, nextAttemptAt: Instant, failureCode: String): RelayMarkResult = translate(ENTITY) {
        markClaimed(eventId, owner) { row ->
            row.attemptCount += 1
            row.nextAttemptAt = nextAttemptAt
            row.lastFailureCode = failureCode.take(FAILURE_CODE_LENGTH)
        }
    }

    override fun markQuarantined(eventId: String, owner: String, failureCode: String): RelayMarkResult = translate(ENTITY) {
        markClaimed(eventId, owner) { row ->
            row.publishStatus = JpaOutboxEventEntity.QUARANTINED
            row.attemptCount += 1
            row.lastFailureCode = failureCode.take(FAILURE_CODE_LENGTH)
        }
    }

    /** 이 소유자의 claim이 유지된 PENDING 행만 바꾼다(claim이 넘어갔으면 덮어쓰지 않는다). claim은 해제한다. */
    private fun markClaimed(eventId: String, owner: String, change: (JpaOutboxEventEntity) -> Unit): RelayMarkResult {
        val row = outbox.lockByEventId(eventId) ?: error("outbox event must exist")
        if (row.publishStatus == JpaOutboxEventEntity.PUBLISHED) return RelayMarkResult.ALREADY_PUBLISHED
        if (row.publishStatus != JpaOutboxEventEntity.PENDING || row.claimOwner != owner) return RelayMarkResult.CLAIM_LOST
        change(row)
        row.claimOwner = null
        row.claimUntil = null
        entityManager.flush()
        return RelayMarkResult.MARKED
    }

    private companion object {
        const val ENTITY = "outbox_event"
        const val FAILURE_CODE_LENGTH = 48
    }
}

package com.paycore.payment.payment.adapter.persistence

import com.paycore.payment.payment.adapter.persistence.PersistenceFailures.translate
import com.paycore.payment.payment.application.required.FinancialFactAck
import com.paycore.payment.payment.application.required.FinancialFactRelayEnvelope
import com.paycore.payment.payment.application.required.FinancialFactRelayMark
import com.paycore.payment.payment.application.required.FinancialFactRelayStore
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * [FinancialFactRelayStore] 구현(FIN-FACT-RELAY-001 §4.4). Outbox는 **잠가 읽지 않는다**(업무 TX의 Outbox INSERT가 Relay를 기다리지 않는다 — OutboxLockReadScanSpec).
 * Outbox 쓰기는 ACK TX의 PK UPDATE 하나(publish_status·published_at — D-FFR-001, runtime 열 단위 권한 V34)뿐이다:
 * - discover: native `INSERT … SELECT … ON DUPLICATE KEY UPDATE`. READ COMMITTED에서 SELECT 쪽(outbox_event)은 잠금 없는 consistent read다(MySQL InnoDB, ROW binlog).
 *   동시 발견은 PK(outbox_id)가 한 행으로 수렴시킨다.
 * - claim: native `SELECT … FOR UPDATE SKIP LOCKED`(JPA 표준 잠금 API에 없는 조합)로 전달 행을 이 TX가 잠근 뒤, 그 행만 Entity로 읽어 claim을 표시한다(잠금 뒤 읽으므로
 *   영속성 컨텍스트가 최신 값). Outbox 열은 그 다음 비잠금 native SELECT로 읽는다(JSON 열은 `CAST(… AS CHAR)` — MySQL 정규화 문자열, 재전송마다 같은 값).
 * - mark: `find(…, PESSIMISTIC_WRITE)`로 전달 행만 잠가 상태를 확인한 뒤 변경 감지로 바꾼다(`@Version` 증가). ACK 기록은 `@Immutable` persist.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaFinancialFactRelayStore(private val entityManager: EntityManager) : FinancialFactRelayStore {

    override fun discover(now: Instant, limit: Int): Int = translate(RELAY) {
        entityManager.createNativeQuery(DISCOVER_SQL).setParameter("now", now).setParameter("limit", limit).executeUpdate()
    }

    override fun claim(owner: String, now: Instant, claimUntil: Instant, limit: Int): List<FinancialFactRelayEnvelope> = translate(RELAY) {
        @Suppress("UNCHECKED_CAST")
        val ids = (entityManager.createNativeQuery(CLAIM_SQL).setParameter("now", now).setParameter("limit", limit).resultList as List<Number>).map { it.toLong() }
        if (ids.isEmpty()) return@translate emptyList()
        val rows = ids.map { id -> checkNotNull(entityManager.find(JpaFinancialFactRelayEntity::class.java, id)) { "claimed relay row must exist" } }
        rows.forEach {
            it.claimOwner = owner
            it.claimUntil = claimUntil
            it.updatedAt = now
        }
        entityManager.flush()
        val attempts = rows.associate { it.outboxId to it.attempts }
        @Suppress("UNCHECKED_CAST")
        val outbox = entityManager.createNativeQuery(OUTBOX_SQL).setParameter("ids", ids).resultList as List<Array<Any?>>
        outbox.map { o ->
            val id = (o[0] as Number).toLong()
            FinancialFactRelayEnvelope(
                outboxId = id, eventId = o[1] as String, effectKey = o[2] as String, eventType = o[3] as String, schemaVersion = (o[4] as Number).toInt(),
                eventCategory = o[5] as String, producer = o[6] as String, aggregateType = o[7] as String, aggregateId = o[8] as String,
                aggregateVersion = (o[9] as Number).toLong(), occurredAt = Instant.parse(o[10] as String), attempts = attempts.getValue(id), payloadJson = o[11] as String,
            )
        }
    }

    override fun markDelivered(outboxId: Long, owner: String, ack: FinancialFactAck): FinancialFactRelayMark = translate(RELAY) {
        val row = lock(outboxId)
        // ACK = FINANCIAL이 내구 커밋했다는 사실이다. claim이 다른 인스턴스로 넘어갔어도 전달은 끝났으므로 DELIVERED로 둔다(다른 쪽은 DUPLICATE를 받는다).
        entityManager.persist(JpaFinancialFactRelayAckEntity.newRow(outboxId, row.eventId, ack.consumer, ack.outcome, ack.originalOutcome, owner, ack.ackedAt))
        val result = if (row.status == JpaFinancialFactRelayEntity.PENDING) {
            markOutboxPublished(outboxId, ack.ackedAt)
            row.status = JpaFinancialFactRelayEntity.DELIVERED
            row.ackOutcome = ack.firstOutcome
            row.deliveredAt = ack.ackedAt
            row.attempts += 1
            row.claimOwner = null
            row.claimUntil = null
            row.updatedAt = ack.ackedAt
            FinancialFactRelayMark.MARKED
        } else {
            FinancialFactRelayMark.ALREADY_FINAL
        }
        entityManager.flush()
        result
    }

    override fun markRetry(outboxId: Long, owner: String, nextAttemptAt: Instant, failureCode: String, at: Instant): FinancialFactRelayMark = translate(RELAY) {
        markClaimed(outboxId, owner, at) { row ->
            row.attempts += 1
            row.nextAttemptAt = nextAttemptAt
            row.lastFailureCode = failureCode
        }
    }

    override fun markQuarantined(outboxId: Long, owner: String, failureCode: String, at: Instant): FinancialFactRelayMark = translate(RELAY) {
        markClaimed(outboxId, owner, at) { row ->
            row.status = JpaFinancialFactRelayEntity.QUARANTINED
            row.quarantinedAt = at
            row.attempts += 1
            row.lastFailureCode = failureCode
        }
    }

    override fun releaseClaims(outboxIds: List<Long>, owner: String, at: Instant): Int = translate(RELAY) {
        var released = 0
        outboxIds.forEach { id ->
            val row = lock(id)
            if (row.status == JpaFinancialFactRelayEntity.PENDING && row.claimOwner == owner) {
                row.claimOwner = null
                row.claimUntil = null
                row.updatedAt = at
                released++
            }
        }
        entityManager.flush()
        released
    }

    /**
     * D-FFR-001: 전달 완료를 Outbox에도 표시한다(같은 TX — 발견 질의가 미전달 행만 보게). PENDING인 FINANCIAL_FACT 행만 바꾼다(가맹점 알림 열·payload는 그대로 —
     * 권한도 그 두 열뿐). 이미 PUBLISHED면 0행(멱등).
     */
    private fun markOutboxPublished(outboxId: Long, at: Instant) {
        entityManager.createNativeQuery(PUBLISH_SQL).setParameter("at", at).setParameter("id", outboxId).executeUpdate()
    }

    /** 이 소유자의 claim이 유지된 PENDING 행만 바꾼다(claim이 넘어갔으면 덮어쓰지 않는다). claim은 해제한다. */
    private fun markClaimed(outboxId: Long, owner: String, at: Instant, change: (JpaFinancialFactRelayEntity) -> Unit): FinancialFactRelayMark {
        val row = lock(outboxId)
        if (row.status != JpaFinancialFactRelayEntity.PENDING) return FinancialFactRelayMark.ALREADY_FINAL
        if (row.claimOwner != owner) return FinancialFactRelayMark.CLAIM_LOST
        change(row)
        row.claimOwner = null
        row.claimUntil = null
        row.updatedAt = at
        entityManager.flush()
        return FinancialFactRelayMark.MARKED
    }

    private fun lock(outboxId: Long): JpaFinancialFactRelayEntity =
        checkNotNull(entityManager.find(JpaFinancialFactRelayEntity::class.java, outboxId, LockModeType.PESSIMISTIC_WRITE)) { "relay row must exist" }

    private companion object {
        const val RELAY = "financial_fact_relay"

        /**
         * 미전달(publish_status = PENDING) FINANCIAL_FACT Outbox 행 중 아직 전달 행이 없는 것(색인 ix_outbox_event_financial_pending, Outbox id 순). 전달된 행은 PUBLISHED라
         * 보지 않으므로 비용은 미전달 행(PENDING·QUARANTINED 전달 행 포함) 수에 비례한다(D-FFR-001).
         */
        val DISCOVER_SQL = """
            INSERT INTO financial_fact_relay (outbox_id, event_id, status, attempts, next_attempt_at, created_at, updated_at, version)
            SELECT o.id, o.event_id, 'PENDING', 0, :now, :now, :now, 0
            FROM outbox_event o
            WHERE o.event_category = 'FINANCIAL_FACT' AND o.publish_status = 'PENDING'
              AND NOT EXISTS (SELECT 1 FROM financial_fact_relay r WHERE r.outbox_id = o.id)
            ORDER BY o.id
            LIMIT :limit
            ON DUPLICATE KEY UPDATE version = financial_fact_relay.version
        """.trimIndent()

        /** 전달 대상 잠금(색인 ix_financial_fact_relay_claim(status, outbox_id) — 정렬 = 색인 순서, 검토 m-3). 다른 인스턴스가 잠근 행은 건너뛴다. */
        val CLAIM_SQL = """
            SELECT r.outbox_id FROM financial_fact_relay r
            WHERE r.status = 'PENDING' AND r.next_attempt_at <= :now AND (r.claim_until IS NULL OR r.claim_until <= :now)
            ORDER BY r.outbox_id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
        """.trimIndent()

        const val PUBLISH_SQL =
            "UPDATE outbox_event SET publish_status = 'PUBLISHED', published_at = :at WHERE id = :id AND event_category = 'FINANCIAL_FACT' AND publish_status = 'PENDING'"

        /** 봉투 재료(비잠금 읽기). 시각은 DATE_FORMAT 문자열(JVM 시간대 무관 — 관례 §9.1). */
        val OUTBOX_SQL = """
            SELECT id, event_id, effect_key, event_type, schema_version, event_category, producer, aggregate_type, aggregate_id, aggregate_version,
                   DATE_FORMAT(occurred_at, '%Y-%m-%dT%H:%i:%s.%fZ'), CAST(payload AS CHAR)
            FROM outbox_event WHERE id IN (:ids) ORDER BY id
        """.trimIndent()
    }
}

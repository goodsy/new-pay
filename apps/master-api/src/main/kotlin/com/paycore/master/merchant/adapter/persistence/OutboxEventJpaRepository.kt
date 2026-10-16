package com.paycore.master.merchant.adapter.persistence

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface OutboxEventJpaRepository : JpaRepository<JpaOutboxEventEntity, Long> {
    /**
     * Relay claim 후보(명세 §4.7): ENTITLEMENT_SNAPSHOT·PENDING·재시도 시각 도래·claim 없음/만료, 우선순위 높은 것부터 id 순. 다른 Relay가 잠근
     * 행은 건너뛴다(`FOR UPDATE SKIP LOCKED` — JPA 표준 잠금 API에 없는 조합이라 native). 같은 TX에서 claim을 표시한다.
     */
    @Query(
        nativeQuery = true,
        value = """
            SELECT id FROM outbox_event
            WHERE event_category = :category AND publish_status = 'PENDING' AND next_attempt_at <= :now
              AND (claim_until IS NULL OR claim_until < :now)
            ORDER BY priority DESC, id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
        """,
    )
    fun lockClaimable(@Param("category") category: String, @Param("now") now: Instant, @Param("limit") limit: Int): List<Long>

    @Query("select o from JpaOutboxEventEntity o where o.id in :ids order by o.priority desc, o.id")
    fun findAllByIds(@Param("ids") ids: Collection<Long>): List<JpaOutboxEventEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from JpaOutboxEventEntity o where o.eventId = :eventId")
    fun lockByEventId(@Param("eventId") eventId: String): JpaOutboxEventEntity?
}

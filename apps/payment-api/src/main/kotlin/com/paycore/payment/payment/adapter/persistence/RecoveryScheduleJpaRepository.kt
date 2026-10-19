package com.paycore.payment.payment.adapter.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

/** 회복 일정 Spring Data repository(adapter.persistence 전용). 후보 선점은 native `FOR UPDATE SKIP LOCKED`(다른 Worker가 잡은 행은 건너뛴다 — 다중 인스턴스 직렬화, ADR-001: Redis lock 없음). */
interface RecoveryScheduleJpaRepository : JpaRepository<JpaRecoveryScheduleEntity, String> {
    @Query(
        nativeQuery = true,
        value = "SELECT attempt_id FROM authorization_recovery_schedule WHERE closed_at IS NULL AND next_check_at <= :now " +
            "ORDER BY next_check_at, attempt_id LIMIT :limit FOR UPDATE SKIP LOCKED",
    )
    fun claimDueIds(@Param("now") now: Instant, @Param("limit") limit: Int): List<String>
}

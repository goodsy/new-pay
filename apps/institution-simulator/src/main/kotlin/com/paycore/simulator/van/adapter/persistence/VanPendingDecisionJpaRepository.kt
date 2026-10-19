package com.paycore.simulator.van.adapter.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

/** PENDING 예약 Spring Data repository(adapter.persistence 전용). 확정 반영은 조건부 UPDATE 한 문장(경합 시 0행). */
interface VanPendingDecisionJpaRepository : JpaRepository<JpaVanPendingDecisionEntity, String> {
    fun findByClientIdAndEnvironmentAndInstitutionAccountAndOperationAndRequestId(
        clientId: String, environment: String, institutionAccount: String, operation: String, requestId: String,
    ): JpaVanPendingDecisionEntity?

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "update JpaVanPendingDecisionEntity p set p.state = 'RESOLVED', p.resolvedEntryId = :entryId, p.updatedAt = :at " +
            "where p.pendingId = :pendingId and p.state = 'PENDING'",
    )
    fun markResolved(@Param("pendingId") pendingId: String, @Param("entryId") entryId: String, @Param("at") at: Instant): Int
}

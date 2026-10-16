package com.paycore.payment.payment.adapter.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface IncidentHoldJpaRepository : JpaRepository<JpaIncidentHoldEntity, Long> {
    @Query(
        "select h from JpaIncidentHoldEntity h where h.environment = :environment and h.merchantAccountId = :merchantAccountId " +
            "and h.merchantOrderId = :merchantOrderId order by h.id",
    )
    fun findByOrder(
        @Param("environment") environment: String,
        @Param("merchantAccountId") merchantAccountId: String,
        @Param("merchantOrderId") merchantOrderId: String,
    ): List<JpaIncidentHoldEntity>
}

package com.paycore.master.merchant.adapter.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ActivationEventJpaRepository : JpaRepository<JpaActivationEventEntity, ActivationEventKey> {
    @Query("select coalesce(max(e.key.seq), 0) from JpaActivationEventEntity e where e.key.activationId = :activationId")
    fun maxSeq(@Param("activationId") activationId: String): Int

    @Query("select e from JpaActivationEventEntity e where e.key.activationId = :activationId order by e.key.seq")
    fun findByActivation(@Param("activationId") activationId: String): List<JpaActivationEventEntity>
}

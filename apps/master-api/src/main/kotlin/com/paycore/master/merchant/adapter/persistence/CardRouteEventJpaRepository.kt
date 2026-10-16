package com.paycore.master.merchant.adapter.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface CardRouteEventJpaRepository : JpaRepository<JpaCardRouteEventEntity, CardRouteEventKey> {
    @Query("select coalesce(max(e.key.seq), 0) from JpaCardRouteEventEntity e where e.key.activationId = :activationId")
    fun maxSeq(@Param("activationId") activationId: String): Int

    @Query("select e from JpaCardRouteEventEntity e where e.key.activationId = :activationId order by e.key.seq")
    fun findByActivation(@Param("activationId") activationId: String): List<JpaCardRouteEventEntity>
}

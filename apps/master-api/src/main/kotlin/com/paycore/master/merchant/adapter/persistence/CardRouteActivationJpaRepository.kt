package com.paycore.master.merchant.adapter.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface CardRouteActivationJpaRepository : JpaRepository<JpaCardRouteActivationEntity, String> {
    @Query("select r from JpaCardRouteActivationEntity r where r.merchantAccountId = :accountId order by r.registeredAt, r.activationId")
    fun findByAccount(@Param("accountId") accountId: String): List<JpaCardRouteActivationEntity>
}

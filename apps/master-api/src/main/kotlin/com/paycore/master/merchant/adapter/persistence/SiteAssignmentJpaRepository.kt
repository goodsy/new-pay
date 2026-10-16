package com.paycore.master.merchant.adapter.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface SiteAssignmentJpaRepository : JpaRepository<JpaSiteAssignmentEntity, String> {
    @Query("select a from JpaSiteAssignmentEntity a where a.merchantAccountId = :accountId order by a.effectiveFrom, a.assignmentId")
    fun findByAccount(@Param("accountId") accountId: String): List<JpaSiteAssignmentEntity>

    @Query("select a from JpaSiteAssignmentEntity a where a.merchantId = :merchantId order by a.effectiveFrom, a.assignmentId")
    fun findByMerchant(@Param("merchantId") merchantId: String): List<JpaSiteAssignmentEntity>
}

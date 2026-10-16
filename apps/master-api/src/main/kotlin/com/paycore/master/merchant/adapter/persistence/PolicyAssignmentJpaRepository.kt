package com.paycore.master.merchant.adapter.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface PolicyAssignmentJpaRepository : JpaRepository<JpaPolicyAssignmentEntity, String> {
    @Query("select a from JpaPolicyAssignmentEntity a where a.merchantAccountId = :accountId order by a.effectiveFrom, a.assignmentId")
    fun findByAccount(@Param("accountId") accountId: String): List<JpaPolicyAssignmentEntity>
}

package com.paycore.payment.payment.adapter.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/** CANCEL-001 D-CANCEL1-006 Spring Data repository(관례 §14). */
interface OperatorCancelRejectionJpaRepository : JpaRepository<JpaOperatorCancelRejectionEntity, Long> {
    @Query("select r from JpaOperatorCancelRejectionEntity r where r.approvalId = :approvalId order by r.id")
    fun findByApproval(@Param("approvalId") approvalId: String): List<JpaOperatorCancelRejectionEntity>
}

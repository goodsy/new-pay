package com.paycore.payment.payment.adapter.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/** ADMIN-PAYMENT-OPS-001 결정 기록 Spring Data repository(관례 §14). 쓰기는 Store의 `persist`만(save 금지). */
interface OperatorApprovalDecisionJpaRepository : JpaRepository<JpaOperatorApprovalDecisionEntity, Long> {
    @Query("select d from JpaOperatorApprovalDecisionEntity d where d.approvalId = :approvalId")
    fun findByApproval(@Param("approvalId") approvalId: String): JpaOperatorApprovalDecisionEntity?
}

package com.paycore.payment.payment.adapter.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface PaymentTerminationEvidenceJpaRepository : JpaRepository<JpaPaymentTerminationEvidenceEntity, TerminationEvidenceKey> {
    @Query("select e from JpaPaymentTerminationEvidenceEntity e where e.key.paymentId = :paymentId order by e.key.seq")
    fun findByPaymentIdOrdered(@Param("paymentId") paymentId: String): List<JpaPaymentTerminationEvidenceEntity>
}

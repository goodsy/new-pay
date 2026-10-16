package com.paycore.master.merchant.adapter.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ContractRevisionApprovalJpaRepository : JpaRepository<JpaContractRevisionApprovalEntity, RevisionKey> {
    @Query("select a from JpaContractRevisionApprovalEntity a where a.key.contractId = :contractId")
    fun findByContract(@Param("contractId") contractId: String): List<JpaContractRevisionApprovalEntity>
}

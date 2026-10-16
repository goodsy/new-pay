package com.paycore.master.merchant.adapter.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ContractRevisionJpaRepository : JpaRepository<JpaContractRevisionEntity, RevisionKey> {
    @Query("select r from JpaContractRevisionEntity r where r.key.contractId = :contractId order by r.key.revisionNo")
    fun findByContract(@Param("contractId") contractId: String): List<JpaContractRevisionEntity>
}

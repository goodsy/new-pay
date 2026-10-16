package com.paycore.master.merchant.adapter.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ContractRevisionScopeSiteJpaRepository : JpaRepository<JpaContractRevisionScopeSiteEntity, ScopeSiteKey> {
    @Query("select s from JpaContractRevisionScopeSiteEntity s where s.key.contractId = :contractId")
    fun findByContract(@Param("contractId") contractId: String): List<JpaContractRevisionScopeSiteEntity>
}

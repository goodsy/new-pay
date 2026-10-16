package com.paycore.master.merchant.adapter.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ContractServiceAgreementJpaRepository : JpaRepository<JpaContractServiceAgreementEntity, String> {
    @Query("select a from JpaContractServiceAgreementEntity a where a.contractId = :contractId order by a.addedAt, a.agreementId")
    fun findByContract(@Param("contractId") contractId: String): List<JpaContractServiceAgreementEntity>
}

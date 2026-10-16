package com.paycore.master.merchant.adapter.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface TransactionLimitPolicyJpaRepository : JpaRepository<JpaTransactionLimitPolicyEntity, String> {
    @Query("select p from JpaTransactionLimitPolicyEntity p where p.merchantId = :merchantId order by p.effectiveFrom, p.policyId")
    fun findByMerchant(@Param("merchantId") merchantId: String): List<JpaTransactionLimitPolicyEntity>
}

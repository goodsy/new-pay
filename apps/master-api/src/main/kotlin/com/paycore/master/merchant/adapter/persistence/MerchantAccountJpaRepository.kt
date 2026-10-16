package com.paycore.master.merchant.adapter.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface MerchantAccountJpaRepository : JpaRepository<JpaMerchantAccountEntity, String> {
    @Query("select count(a) from JpaMerchantAccountEntity a where a.merchantId = :merchantId")
    fun countByMerchant(@Param("merchantId") merchantId: String): Long

    @Query("select a from JpaMerchantAccountEntity a where a.merchantId = :merchantId order by a.merchantAccountId")
    fun findByMerchant(@Param("merchantId") merchantId: String): List<JpaMerchantAccountEntity>
}

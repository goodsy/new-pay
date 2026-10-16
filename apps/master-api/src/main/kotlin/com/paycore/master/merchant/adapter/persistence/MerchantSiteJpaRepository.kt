package com.paycore.master.merchant.adapter.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface MerchantSiteJpaRepository : JpaRepository<JpaMerchantSiteEntity, String> {
    @Query(
        "select s.merchantSiteId from JpaMerchantSiteEntity s where s.channelType = :channelType " +
            "and s.channelIdentifier = :channelIdentifier and s.lifecycle = 'ACTIVE'",
    )
    fun findActiveByChannel(@Param("channelType") channelType: String, @Param("channelIdentifier") channelIdentifier: String): String?

    @Query("select s from JpaMerchantSiteEntity s where s.merchantId = :merchantId order by s.merchantSiteId")
    fun findByMerchant(@Param("merchantId") merchantId: String): List<JpaMerchantSiteEntity>
}

package com.paycore.master.merchant.adapter.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface SiteReviewDecisionJpaRepository : JpaRepository<JpaSiteReviewDecisionEntity, SiteReviewKey> {
    @Query("select d from JpaSiteReviewDecisionEntity d where d.key.merchantSiteId = :siteId order by d.key.seq")
    fun findBySite(@Param("siteId") siteId: String): List<JpaSiteReviewDecisionEntity>

    @Query("select d from JpaSiteReviewDecisionEntity d where d.merchantId = :merchantId order by d.key.merchantSiteId, d.key.seq")
    fun findByMerchant(@Param("merchantId") merchantId: String): List<JpaSiteReviewDecisionEntity>
}

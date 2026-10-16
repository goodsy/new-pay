package com.paycore.master.merchant.adapter.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface CurrencyEnablementJpaRepository : JpaRepository<JpaCurrencyEnablementEntity, String> {
    @Query("select e from JpaCurrencyEnablementEntity e where e.merchantAccountId = :accountId order by e.currencyCode, e.effectiveFrom")
    fun findByAccount(@Param("accountId") accountId: String): List<JpaCurrencyEnablementEntity>
}

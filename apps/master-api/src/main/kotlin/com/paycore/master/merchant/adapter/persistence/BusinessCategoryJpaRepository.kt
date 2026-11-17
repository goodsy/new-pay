package com.paycore.master.merchant.adapter.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/** 업태·종목 현재 행(유형 → 유형 안 순서). */
interface BusinessCategoryJpaRepository : JpaRepository<JpaBusinessCategoryEntity, Long> {
    @Query(
        "select c from JpaBusinessCategoryEntity c where c.registrationId = :registrationId and c.effectiveTo is null " +
            "order by case c.categoryType when 'BUSINESS_TYPE' then 0 else 1 end, c.displayOrder",
    )
    fun findCurrent(@Param("registrationId") registrationId: Long): List<JpaBusinessCategoryEntity>
}
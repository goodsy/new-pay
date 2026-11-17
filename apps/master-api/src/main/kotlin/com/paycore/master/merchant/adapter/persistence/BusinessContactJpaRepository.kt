package com.paycore.master.merchant.adapter.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/** 등록 담당자 현재 행(순서). */
interface BusinessContactJpaRepository : JpaRepository<JpaBusinessContactEntity, Long> {
    @Query("select c from JpaBusinessContactEntity c where c.businessId = :businessId and c.effectiveTo is null order by c.displayOrder")
    fun findCurrent(@Param("businessId") businessId: String): List<JpaBusinessContactEntity>
}
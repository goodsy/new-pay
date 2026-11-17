package com.paycore.master.merchant.adapter.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/** 대표자 현재 행(effective_to IS NULL, 순서). 과거 행은 조회 Reader(jOOQ — BIZ-2)가 읽는다. */
interface BusinessRepresentativeJpaRepository : JpaRepository<JpaBusinessRepresentativeEntity, Long> {
    @Query("select r from JpaBusinessRepresentativeEntity r where r.businessId = :businessId and r.effectiveTo is null order by r.displayOrder")
    fun findCurrent(@Param("businessId") businessId: String): List<JpaBusinessRepresentativeEntity>
}
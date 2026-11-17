package com.paycore.master.merchant.adapter.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/** 주소 현재 행(REGISTERED → ACTUAL). */
interface BusinessAddressJpaRepository : JpaRepository<JpaBusinessAddressEntity, Long> {
    @Query(
        "select a from JpaBusinessAddressEntity a where a.registrationId = :registrationId and a.effectiveTo is null " +
            "order by case a.addressType when 'REGISTERED' then 0 else 1 end",
    )
    fun findCurrent(@Param("registrationId") registrationId: Long): List<JpaBusinessAddressEntity>
}
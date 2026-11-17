package com.paycore.master.merchant.adapter.persistence

import org.springframework.data.jpa.repository.JpaRepository

/** 사업자 등록정보(1:1) Spring Data(adapter.persistence 전용 — application은 [JpaBusinessRepository] Port 구현만 안다). */
interface BusinessRegistrationJpaRepository : JpaRepository<JpaBusinessRegistrationEntity, Long> {
    fun findByBusinessId(businessId: String): JpaBusinessRegistrationEntity?
}
package com.paycore.simulator.van.adapter.persistence

import org.springframework.data.jpa.repository.JpaRepository

/** 가상 VAN 조회 기록 Spring Data repository(adapter.persistence 전용). */
interface VanInquiryLogJpaRepository : JpaRepository<JpaVanInquiryLogEntity, Long> {
    fun countByClientIdAndEnvironmentAndInstitutionAccountAndOriginalRequestId(clientId: String, environment: String, institutionAccount: String, originalRequestId: String): Long
}

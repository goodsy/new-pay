package com.paycore.simulator.authentication.adapter.persistence

import org.springframework.data.jpa.repository.JpaRepository

/** 결과 조회 기록 Spring Data repository(adapter.persistence 전용). */
interface LookupLogJpaRepository : JpaRepository<JpaLookupLogEntity, Long> {
    fun countByResultRef(resultRef: String): Long
}

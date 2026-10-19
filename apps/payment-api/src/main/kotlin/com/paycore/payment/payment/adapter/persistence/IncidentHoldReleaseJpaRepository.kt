package com.paycore.payment.payment.adapter.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/** incident_hold_release 읽기 전용 조회(AUTH-001 — 쓰기 경로는 RECON-CONTRACT-001). */
interface IncidentHoldReleaseJpaRepository : JpaRepository<JpaIncidentHoldReleaseEntity, Long> {
    @Query("select r from JpaIncidentHoldReleaseEntity r where r.incidentHoldId in :holdIds order by r.id")
    fun findByHoldIds(@Param("holdIds") holdIds: Collection<Long>): List<JpaIncidentHoldReleaseEntity>
}

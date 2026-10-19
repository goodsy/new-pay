package com.paycore.payment.payment.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.Hibernate
import org.hibernate.annotations.Immutable
import java.time.Instant

/**
 * incident_hold_release 행(append-only 해제 기록 — D-AUTH-002·011). 이 작업에서는 **읽기만** 한다: 해제 쓰기 경로(2인 승인·재대사 MATCH 확인 Use Case)는 RECON-CONTRACT-001이며
 * 그 작업이 INSERT 권한 migration을 함께 추가한다(관례 §9.2). 그래서 새 행을 만드는 factory가 없다.
 */
@Entity
@Immutable
@Table(name = "incident_hold_release")
class JpaIncidentHoldReleaseEntity protected constructor() {
    @field:Id
    @field:Column(name = "id", nullable = false, updatable = false)
    var id: Long? = null
        protected set

    @field:Column(name = "incident_hold_id", nullable = false, updatable = false)
    var incidentHoldId: Long = 0
        protected set

    @field:Column(name = "requested_by", nullable = false, updatable = false, length = 64)
    lateinit var requestedBy: String
        protected set

    @field:Column(name = "approved_by", nullable = false, updatable = false, length = 64)
    lateinit var approvedBy: String
        protected set

    @field:Column(name = "reconciliation_match_ref", nullable = false, updatable = false, length = 128)
    lateinit var reconciliationMatchRef: String
        protected set

    @field:Column(name = "finding", nullable = false, updatable = false, length = 32)
    lateinit var finding: String
        protected set

    @field:Column(name = "correction_ref", updatable = false, length = 128)
    var correctionRef: String? = null
        protected set

    @field:Column(name = "reason", nullable = false, updatable = false, length = 500)
    lateinit var reason: String
        protected set

    @field:Column(name = "released_at", nullable = false, updatable = false)
    lateinit var releasedAt: Instant
        protected set

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
        return incidentHoldId == (other as JpaIncidentHoldReleaseEntity).incidentHoldId
    }

    override fun hashCode(): Int = incidentHoldId.hashCode()

    override fun toString(): String = "JpaIncidentHoldReleaseEntity(incidentHoldId=$incidentHoldId, finding=$finding)"
}

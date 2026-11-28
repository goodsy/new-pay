package com.paycore.master.merchant.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.Hibernate
import org.hibernate.annotations.Immutable
import java.time.Instant

/**
 * `business_change_evidence` 행(BIZ-CRIT-APPROVAL-001 §3.5·R3-m2 — 1인 경로 선택 근거 원문, **INSERT 전용**). JPA 선택은 [JpaBusinessChangeProposalEntity]와 같다
 * (`@Immutable`·persist만·`@Version`·연관 매핑 없음 — 이력 행은 ID 값으로만 가리킨다, runtime 권한 SELECT·INSERT만).
 */
@Entity
@Immutable
@Table(name = "business_change_evidence")
class JpaBusinessChangeEvidenceEntity protected constructor() {
    @field:Id
    @field:Column(name = "change_history_id", nullable = false, updatable = false)
    var changeHistoryId: Long = 0
        protected set

    @field:Column(name = "business_id", nullable = false, updatable = false, length = 64)
    lateinit var businessId: String
        protected set

    @field:Column(name = "admin_request_id", nullable = false, updatable = false, length = 64)
    lateinit var adminRequestId: String
        protected set

    @field:Column(name = "evidence_ref", nullable = false, updatable = false, length = 128)
    lateinit var evidenceRef: String
        protected set

    @field:Column(name = "recorded_by", nullable = false, updatable = false, length = 64)
    lateinit var recordedBy: String
        protected set

    @field:Column(name = "recorded_at", nullable = false, updatable = false)
    lateinit var recordedAt: Instant
        protected set

    override fun equals(other: Any?): Boolean =
        this === other || (other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && changeHistoryId == (other as JpaBusinessChangeEvidenceEntity).changeHistoryId)

    override fun hashCode(): Int = changeHistoryId.hashCode()

    override fun toString(): String = "JpaBusinessChangeEvidenceEntity(changeHistoryId=$changeHistoryId, businessId=$businessId)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(changeHistoryId: Long, businessId: String, adminRequestId: String, evidenceRef: String, recordedBy: String, recordedAt: Instant) =
            JpaBusinessChangeEvidenceEntity().also {
                it.changeHistoryId = changeHistoryId
                it.businessId = businessId
                it.adminRequestId = adminRequestId
                it.evidenceRef = evidenceRef
                it.recordedBy = recordedBy
                it.recordedAt = recordedAt
            }
    }
}

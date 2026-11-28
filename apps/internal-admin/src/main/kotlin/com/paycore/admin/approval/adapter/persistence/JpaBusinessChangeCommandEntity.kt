package com.paycore.admin.approval.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.Hibernate
import org.hibernate.annotations.Immutable
import java.time.Instant

/**
 * `admin_business_change_command` 행(BIZ-CRIT-APPROVAL-001 §3.1 — INSERT 전용). JPA 선택(역할 → 예 → 이유 → 주의): 승인 행의 생성 불변 부속 기록이라 `@Immutable` + 모든 열 `updatable = false`,
 * persist만(예: 요청 TX가 승인 행 뒤에 한 번 INSERT, TX1·회복은 PK로 읽기만). 승인 Aggregate에 연관 매핑하지 않는다(승인 load마다 따라오지 않게 — 종류별 Store가 따로 읽는다).
 * 주의: 항목 이름은 쉼표 연결 문자열 한 열(정규형 — 자식 Entity 없음), 상호·개인정보 열은 없다(M-5). DB 권한도 SELECT·INSERT만(V18 — 위반 1142).
 */
@Entity
@Immutable
@Table(name = "admin_business_change_command")
class JpaBusinessChangeCommandEntity protected constructor() {
    @field:Id
    @field:Column(name = "approval_id", nullable = false, updatable = false, length = 30)
    lateinit var approvalId: String
        protected set

    @field:Column(name = "business_id", nullable = false, updatable = false, length = 30)
    lateinit var businessId: String
        protected set

    @field:Column(name = "base_version", nullable = false, updatable = false)
    var baseVersion: Long = 0
        protected set

    @field:Column(name = "proposal_id", nullable = false, updatable = false, length = 30)
    lateinit var proposalId: String
        protected set

    @field:Column(name = "content_digest", nullable = false, updatable = false, length = 67)
    lateinit var contentDigest: String
        protected set

    @field:Column(name = "changed_fields", nullable = false, updatable = false, length = 512)
    lateinit var changedFields: String
        protected set

    @field:Column(name = "representative_changed", nullable = false, updatable = false)
    var representativeChanged: Boolean = false
        protected set

    @field:Column(name = "subject_correction", nullable = false, updatable = false)
    var subjectCorrection: Boolean = false
        protected set

    @field:Column(name = "evidence_ref", nullable = false, updatable = false, length = 128)
    lateinit var evidenceRef: String
        protected set

    @field:Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: Instant
        protected set

    override fun equals(other: Any?): Boolean =
        this === other || (other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && approvalId == (other as JpaBusinessChangeCommandEntity).approvalId)

    override fun hashCode(): Int = approvalId.hashCode()

    override fun toString(): String = "JpaBusinessChangeCommandEntity(approvalId=$approvalId, businessId=$businessId)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            approvalId: String, businessId: String, baseVersion: Long, proposalId: String, contentDigest: String, changedFields: String, representativeChanged: Boolean,
            subjectCorrection: Boolean, evidenceRef: String, createdAt: Instant,
        ) = JpaBusinessChangeCommandEntity().also {
            it.approvalId = approvalId
            it.businessId = businessId
            it.baseVersion = baseVersion
            it.proposalId = proposalId
            it.contentDigest = contentDigest
            it.changedFields = changedFields
            it.representativeChanged = representativeChanged
            it.subjectCorrection = subjectCorrection
            it.evidenceRef = evidenceRef
            it.createdAt = createdAt
        }
    }
}

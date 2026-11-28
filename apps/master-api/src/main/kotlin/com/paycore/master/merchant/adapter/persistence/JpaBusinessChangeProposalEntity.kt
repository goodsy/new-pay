package com.paycore.master.merchant.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.Hibernate
import org.hibernate.annotations.Immutable
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

/**
 * `business_change_proposal` 행(BIZ-CRIT-APPROVAL-001 §3.1·§3.2 — **INSERT 전용**). JPA 선택 설명(역할 → 예 → 이유 → 주의 — 명세 §3.1 [구현 관례]):
 * - 역할: 제안 한 건을 담는 INSERT 전용 Entity다. 순수 Domain 값 [com.paycore.master.merchant.domain.BusinessChangeProposal]과 분리하고 변환은 [BusinessChangeProposalMapper]가 한다.
 * - 예: 제안 TX는 persist + flush만, 승인 실행 TX는 제안을 ID로 읽기만 한다. 사업자 변경은 기존 `JpaBusinessRepository.update`가 한다.
 * - 이유: 제안을 사업자 Aggregate 안에 두면 사업자 load마다 제안 목록이 따라오고 대기 중 제안이 사업자 version과 섞인다 — 별도 표·별도 Store로 둔다. 하위 행(대표자 등)은 JSON 열 하나
 *   (정규 내용 — content)로 두어 자식 Entity를 만들지 않는다(원문 한 덩어리 — 일부 행만 바뀌는 일이 없다).
 * - 주의: `@Immutable` + 모든 열 `updatable = false`라 변경 감지 UPDATE가 나가지 않는다. DB 권한도 SELECT·INSERT만(V27 — 위반은 1142). `@Version`·연관 매핑 없음.
 *   JSON 열은 MySQL이 키 순서를 다시 저장하므로 **digest는 이 열의 바이트가 아니라 Domain 정규 JSON으로 다시 계산**한다.
 */
@Entity
@Immutable
@Table(name = "business_change_proposal")
class JpaBusinessChangeProposalEntity protected constructor() {
    @field:Id
    @field:Column(name = "proposal_id", nullable = false, updatable = false, length = 30)
    lateinit var proposalId: String
        protected set

    @field:Column(name = "business_id", nullable = false, updatable = false, length = 64)
    lateinit var businessId: String
        protected set

    @field:Column(name = "base_version", nullable = false, updatable = false)
    var baseVersion: Long = 0
        protected set

    @field:JdbcTypeCode(SqlTypes.JSON)
    @field:Column(name = "content", nullable = false, updatable = false)
    lateinit var content: String
        protected set

    @field:Column(name = "content_digest", nullable = false, updatable = false, length = 67)
    lateinit var contentDigest: String
        protected set

    @field:JdbcTypeCode(SqlTypes.JSON)
    @field:Column(name = "changes", nullable = false, updatable = false)
    lateinit var changes: String
        protected set

    @field:JdbcTypeCode(SqlTypes.JSON)
    @field:Column(name = "important_fields", nullable = false, updatable = false)
    lateinit var importantFields: String
        protected set

    @field:Column(name = "representative_changed", nullable = false, updatable = false)
    var representativeChanged: Boolean = false
        protected set

    @field:Column(name = "subject_correction", nullable = false, updatable = false)
    var subjectCorrection: Boolean = false
        protected set

    @field:Column(name = "reason", nullable = false, updatable = false, length = 200)
    lateinit var reason: String
        protected set

    @field:Column(name = "evidence_ref", nullable = false, updatable = false, length = 128)
    lateinit var evidenceRef: String
        protected set

    @field:Column(name = "requested_by", nullable = false, updatable = false, length = 64)
    lateinit var requestedBy: String
        protected set

    @field:Column(name = "requested_at", nullable = false, updatable = false)
    lateinit var requestedAt: Instant
        protected set

    @field:Column(name = "request_record_id", nullable = false, updatable = false, length = 64)
    lateinit var requestRecordId: String
        protected set

    override fun equals(other: Any?): Boolean =
        this === other || (other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && proposalId == (other as JpaBusinessChangeProposalEntity).proposalId)

    override fun hashCode(): Int = proposalId.hashCode()

    override fun toString(): String = "JpaBusinessChangeProposalEntity(proposalId=$proposalId, businessId=$businessId, baseVersion=$baseVersion)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            proposalId: String, businessId: String, baseVersion: Long, content: String, contentDigest: String, changes: String, importantFields: String,
            representativeChanged: Boolean, subjectCorrection: Boolean, reason: String, evidenceRef: String, requestedBy: String, requestedAt: Instant, requestRecordId: String,
        ) = JpaBusinessChangeProposalEntity().also {
            it.proposalId = proposalId
            it.businessId = businessId
            it.baseVersion = baseVersion
            it.content = content
            it.contentDigest = contentDigest
            it.changes = changes
            it.importantFields = importantFields
            it.representativeChanged = representativeChanged
            it.subjectCorrection = subjectCorrection
            it.reason = reason
            it.evidenceRef = evidenceRef
            it.requestedBy = requestedBy
            it.requestedAt = requestedAt
            it.requestRecordId = requestRecordId
        }
    }
}

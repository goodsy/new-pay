package com.paycore.master.merchant.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.Hibernate
import org.hibernate.annotations.Immutable
import java.time.Instant

/**
 * admin_approval_evidence 행(MERCHANT-OPS-002 §9.3 V9 — 승인 근거, append-only). 순수 Domain `ApprovalEvidence`와 분리된 영속 모양이다(Mapper는 [JpaApprovalEvidenceStore]).
 *
 * JPA 선택(§9.9): `@Immutable` + 모든 열 `updatable = false` — `persist`만 하고 변경 감지·UPDATE 경로가 없다(runtime 권한도 SELECT·INSERT만). equals/hashCode는 PK(approvalId)만,
 * toString에 지문·행위자·근거 참조를 넣지 않는다(로그 노출 방지 — data class 금지).
 */
@Entity
@Immutable
@Table(name = "admin_approval_evidence")
class JpaApprovalEvidenceEntity protected constructor() {
    @field:Id
    @field:Column(name = "approval_id", nullable = false, updatable = false, length = 30)
    lateinit var approvalId: String
        protected set

    @field:Column(name = "operation", nullable = false, updatable = false, length = 48)
    lateinit var operation: String
        protected set

    @field:Column(name = "request_record_id", nullable = false, updatable = false, length = 64)
    lateinit var requestRecordId: String
        protected set

    @field:Column(name = "requested_by", nullable = false, updatable = false, length = 64)
    lateinit var requestedBy: String
        protected set

    @field:Column(name = "approved_by", nullable = false, updatable = false, length = 64)
    lateinit var approvedBy: String
        protected set

    @field:Column(name = "approved_at", nullable = false, updatable = false)
    lateinit var approvedAt: Instant
        protected set

    @field:Column(name = "execute_before", nullable = false, updatable = false)
    lateinit var executeBefore: Instant
        protected set

    @field:Column(name = "evidence_ref", nullable = false, updatable = false, length = 128)
    lateinit var evidenceRef: String
        protected set

    @field:Column(name = "approval_fingerprint", nullable = false, updatable = false, columnDefinition = "char(69)")
    lateinit var approvalFingerprint: String
        protected set

    @field:Column(name = "outcome", nullable = false, updatable = false, length = 16)
    lateinit var outcome: String
        protected set

    @field:Column(name = "result_code", updatable = false, length = 48)
    var resultCode: String? = null
        protected set

    @field:Column(name = "result_reference", updatable = false, length = 64)
    var resultReference: String? = null
        protected set

    @field:Column(name = "decided_via", nullable = false, updatable = false, length = 16)
    lateinit var decidedVia: String
        protected set

    @field:Column(name = "recorded_at", nullable = false, updatable = false)
    lateinit var recordedAt: Instant
        protected set

    override fun equals(other: Any?): Boolean =
        this === other || (other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && approvalId == (other as JpaApprovalEvidenceEntity).approvalId)

    override fun hashCode(): Int = approvalId.hashCode()

    override fun toString(): String = "JpaApprovalEvidenceEntity(approvalId=$approvalId, operation=$operation, outcome=$outcome)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            approvalId: String,
            operation: String,
            requestRecordId: String,
            requestedBy: String,
            approvedBy: String,
            approvedAt: Instant,
            executeBefore: Instant,
            evidenceRef: String,
            approvalFingerprint: String,
            outcome: String,
            resultCode: String?,
            resultReference: String?,
            decidedVia: String,
            recordedAt: Instant,
        ) = JpaApprovalEvidenceEntity().also {
            it.approvalId = approvalId
            it.operation = operation
            it.requestRecordId = requestRecordId
            it.requestedBy = requestedBy
            it.approvedBy = approvedBy
            it.approvedAt = approvedAt
            it.executeBefore = executeBefore
            it.evidenceRef = evidenceRef
            it.approvalFingerprint = approvalFingerprint
            it.outcome = outcome
            it.resultCode = resultCode
            it.resultReference = resultReference
            it.decidedVia = decidedVia
            it.recordedAt = recordedAt
        }
    }
}

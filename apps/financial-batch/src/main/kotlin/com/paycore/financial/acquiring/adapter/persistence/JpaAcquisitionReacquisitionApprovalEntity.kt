package com.paycore.financial.acquiring.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import java.time.Instant

/**
 * acquisition_reacquisition_approval 행(재매입 2인 승인 기록 — D-ACQ-011, append-only, `@Immutable`). runtime 권한은 이번 작업에서 SELECT만(V13 — 입력 경로 ACQ-ADMIN-001 전
 * INSERT 1142). 요청자 ≠ 승인자·generation + 1·원 레코드 참조는 Domain 규칙과 DB CHECK가 함께 지킨다.
 */
@Entity
@Immutable
@Table(name = "acquisition_reacquisition_approval")
class JpaAcquisitionReacquisitionApprovalEntity protected constructor() {
    @field:Id
    @field:Column(name = "approval_id", nullable = false, updatable = false, length = 64)
    lateinit var approvalId: String
        protected set

    @field:Column(name = "file_id", nullable = false, updatable = false, length = 64)
    lateinit var fileId: String
        protected set

    @field:Column(name = "seq", nullable = false, updatable = false)
    var seq: Int = 0
        protected set

    @field:Column(name = "transaction_id", nullable = false, updatable = false, length = 64)
    lateinit var transactionId: String
        protected set

    @field:Column(name = "record_type", nullable = false, updatable = false, length = 8)
    lateinit var recordType: String
        protected set

    @field:Column(name = "target_generation", nullable = false, updatable = false)
    var targetGeneration: Int = 0
        protected set

    @field:Column(name = "new_generation", nullable = false, updatable = false)
    var newGeneration: Int = 0
        protected set

    @field:Column(name = "target_status", nullable = false, updatable = false, length = 16)
    lateinit var targetStatus: String
        protected set

    @field:Column(name = "reacquisition_of", nullable = false, updatable = false, length = 64)
    lateinit var reacquisitionOf: String
        protected set

    @field:Column(name = "requested_by", nullable = false, updatable = false, length = 64)
    lateinit var requestedBy: String
        protected set

    @field:Column(name = "approved_by", nullable = false, updatable = false, length = 64)
    lateinit var approvedBy: String
        protected set

    @field:Column(name = "reason_code", nullable = false, updatable = false, length = 32)
    lateinit var reasonCode: String
        protected set

    @field:Column(name = "evidence_ref", nullable = false, updatable = false, length = 128)
    lateinit var evidenceRef: String
        protected set

    @field:Column(name = "channel", nullable = false, updatable = false, length = 16)
    lateinit var channel: String
        protected set

    @field:Column(name = "recorded_at", nullable = false, updatable = false)
    lateinit var recordedAt: Instant
        protected set

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)

    override fun toString(): String = "JpaAcquisitionReacquisitionApprovalEntity(approvalId=$approvalId, fileId=$fileId, seq=$seq)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            approvalId: String, fileId: String, seq: Int, transactionId: String, recordType: String, targetGeneration: Int, newGeneration: Int, targetStatus: String,
            reacquisitionOf: String, requestedBy: String, approvedBy: String, reasonCode: String, evidenceRef: String, channel: String, recordedAt: Instant,
        ) = JpaAcquisitionReacquisitionApprovalEntity().also {
            it.approvalId = approvalId
            it.fileId = fileId
            it.seq = seq
            it.transactionId = transactionId
            it.recordType = recordType
            it.targetGeneration = targetGeneration
            it.newGeneration = newGeneration
            it.targetStatus = targetStatus
            it.reacquisitionOf = reacquisitionOf
            it.requestedBy = requestedBy
            it.approvedBy = approvedBy
            it.reasonCode = reasonCode
            it.evidenceRef = evidenceRef
            it.channel = channel
            it.recordedAt = recordedAt
        }
    }
}

package com.paycore.financial.acquiring.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import java.time.Instant

/**
 * acquisition_ops_decision 행(ADMIN 조작 승인 ID별 최초 확정 결정 — ACQ-ADMIN-001, append-only, `@Immutable`, `persist`만).
 *
 * JPA 선택(이번 코드에 연결): 이 기록은 바뀌지 않으므로 `@Immutable` + 모든 열 `updatable=false`다 — 영속성 컨텍스트가 변경 감지로 UPDATE를 만들 일이 없다. 같은 승인 ID
 * 두 번째 INSERT는 flush 때 UNIQUE(approval_id) 1062가 나므로 Store가 쓰기 끝에서 `flush()`해 그 자리에서 분류한다(커밋 때 늦게 터지면 같은 TX의 업무 효과와 함께 rollback
 * 되기는 하지만 재시도 분류가 어긋난다). `recorded_at`은 DB가 INSERT 시각으로 채운다(insertable=false — 대사 watermark 기준, B5).
 */
@Entity
@Immutable
@Table(name = "acquisition_ops_decision")
class JpaAcquisitionOpsDecisionEntity protected constructor() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "seq", nullable = false, updatable = false)
    var seq: Long? = null
        protected set

    @field:Column(name = "approval_id", nullable = false, updatable = false, length = 64)
    lateinit var approvalId: String
        protected set

    @field:Column(name = "kind", nullable = false, updatable = false, length = 24)
    lateinit var kind: String
        protected set

    @field:Column(name = "decision", nullable = false, updatable = false, length = 16)
    lateinit var decision: String
        protected set

    @field:Column(name = "rejection_code", updatable = false, length = 48)
    var rejectionCode: String? = null
        protected set

    @field:Column(name = "target_ref", nullable = false, updatable = false, length = 64)
    lateinit var targetRef: String
        protected set

    @field:Column(name = "result_file_id", updatable = false, length = 64)
    var resultFileId: String? = null
        protected set

    @field:Column(name = "content_hash", nullable = false, updatable = false, columnDefinition = "char(69)")
    lateinit var contentHash: String
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

    @field:Column(name = "decided_at", nullable = false, updatable = false)
    lateinit var decidedAt: Instant
        protected set

    @field:Column(name = "recorded_at", insertable = false, updatable = false)
    var recordedAt: Instant? = null
        protected set

    @field:Column(name = "origin", nullable = false, updatable = false, length = 24)
    lateinit var origin: String
        protected set

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)

    override fun toString(): String = "JpaAcquisitionOpsDecisionEntity(approvalId=$approvalId, decision=$decision)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            approvalId: String, kind: String, decision: String, rejectionCode: String?, targetRef: String, resultFileId: String?, contentHash: String,
            requestedBy: String, approvedBy: String, approvedAt: Instant, executeBefore: Instant, decidedAt: Instant, origin: String,
        ) = JpaAcquisitionOpsDecisionEntity().also {
            it.approvalId = approvalId
            it.kind = kind
            it.decision = decision
            it.rejectionCode = rejectionCode
            it.targetRef = targetRef
            it.resultFileId = resultFileId
            it.contentHash = contentHash
            it.requestedBy = requestedBy
            it.approvedBy = approvedBy
            it.approvedAt = approvedAt
            it.executeBefore = executeBefore
            it.decidedAt = decidedAt
            it.origin = origin
        }
    }
}

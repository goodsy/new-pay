package com.paycore.admin.approval.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.Hibernate
import java.time.Instant

/**
 * admin_approval_request 행(행 모양만 — 규칙은 domain `ApprovalRequest`). 생성 불변 열은 `updatable = false`(열 권한: 결정 열·version만 UPDATE — R-12 대조 시험).
 * 생성 열 `pending_key`는 매핑하지 않는다(DB가 상태로 계산, UNIQUE로 중복 PENDING 차단).
 */
@Entity
@Table(name = "admin_approval_request")
class JpaApprovalRequestEntity protected constructor() {
    @field:Id
    @field:Column(name = "approval_id", nullable = false, updatable = false, length = 30)
    lateinit var approvalId: String
        protected set

    @field:Column(name = "kind", nullable = false, updatable = false, length = 40)
    lateinit var kind: String
        protected set

    @field:Column(name = "target_actor_id", nullable = false, updatable = false, length = 30)
    lateinit var targetActorId: String
        protected set

    @field:Column(name = "target_security_version", nullable = false, updatable = false)
    var targetSecurityVersion: Long = 0
        protected set

    @field:Column(name = "target_snapshot", nullable = false, updatable = false, columnDefinition = "json")
    lateinit var targetSnapshot: String
        protected set

    @field:Column(name = "granted_role", updatable = false, length = 16)
    var grantedRole: String? = null
        protected set

    @field:Column(name = "cause_approval_id", updatable = false, length = 30)
    var causeApprovalId: String? = null
        protected set

    @field:Column(name = "requested_by", nullable = false, updatable = false, length = 30)
    lateinit var requestedBy: String
        protected set

    @field:Column(name = "requested_by_display_name", nullable = false, updatable = false, length = 100)
    lateinit var requestedByDisplayName: String
        protected set

    @field:Column(name = "requested_at", nullable = false, updatable = false)
    lateinit var requestedAt: Instant
        protected set

    @field:Column(name = "expires_at", nullable = false, updatable = false)
    lateinit var expiresAt: Instant
        protected set

    @field:Column(name = "status", nullable = false, length = 20)
    lateinit var status: String

    @field:Column(name = "decided_by", length = 30)
    var decidedBy: String? = null

    @field:Column(name = "decided_by_display_name", length = 100)
    var decidedByDisplayName: String? = null

    @field:Column(name = "decided_at")
    var decidedAt: Instant? = null

    @field:Column(name = "result_code", length = 48)
    var resultCode: String? = null

    @field:Version
    @field:Column(name = "version", nullable = false)
    var version: Long? = null
        protected set

    override fun equals(other: Any?): Boolean =
        this === other || (other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && approvalId == (other as JpaApprovalRequestEntity).approvalId)

    override fun hashCode(): Int = approvalId.hashCode()

    override fun toString(): String = "JpaApprovalRequestEntity(approvalId=$approvalId, kind=$kind, status=$status)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            approvalId: String, kind: String, targetActorId: String, targetSecurityVersion: Long, targetSnapshot: String, grantedRole: String?,
            causeApprovalId: String?, requestedBy: String, requestedByDisplayName: String, requestedAt: Instant, expiresAt: Instant,
        ) = JpaApprovalRequestEntity().also {
            it.approvalId = approvalId
            it.kind = kind
            it.targetActorId = targetActorId
            it.targetSecurityVersion = targetSecurityVersion
            it.targetSnapshot = targetSnapshot
            it.grantedRole = grantedRole
            it.causeApprovalId = causeApprovalId
            it.requestedBy = requestedBy
            it.requestedByDisplayName = requestedByDisplayName
            it.requestedAt = requestedAt
            it.expiresAt = expiresAt
        }
    }
}

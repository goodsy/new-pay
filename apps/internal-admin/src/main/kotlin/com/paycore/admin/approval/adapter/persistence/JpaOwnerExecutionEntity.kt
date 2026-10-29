package com.paycore.admin.approval.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.Hibernate
import java.time.Instant

/**
 * admin_owner_execution 행(ADMIN-PAYMENT-OPS-001 §4.11 — 행 모양만, 규칙은 domain `OwnerExecution`).
 * - 불변 열(`updatable = false` + 열 권한 없음 1143): 저장 본문 `request_body`(MEDIUMBLOB — 재전송은 이 바이트 그대로), 지문, 승인 시각, 실행 기한, 실행자.
 * - 변경 열(= V6 열 단위 UPDATE 목록 — `AdminPersistenceMappingSpec` 대조): 시도 수·다음/마지막 시도·마지막 응답·마지막 실패·무결성 플래그·재전송 중단·소유 결과·version.
 * - `@Version`: 회복 선점(`SKIP LOCKED`, S6) 뒤 두 번째 방어. 대상 버전(balanceRevision)과 무관.
 * equals/hashCode는 ID만, toString에 본문이 없다.
 */
@Entity
@Table(name = "admin_owner_execution")
class JpaOwnerExecutionEntity protected constructor() {
    @field:Id
    @field:Column(name = "approval_id", nullable = false, updatable = false, length = 30)
    lateinit var approvalId: String
        protected set

    @field:Column(name = "request_body", nullable = false, updatable = false, columnDefinition = "mediumblob")
    lateinit var requestBody: ByteArray
        protected set

    @field:Column(name = "fingerprint", nullable = false, updatable = false, length = 69)
    lateinit var fingerprint: String
        protected set

    @field:Column(name = "approved_at", nullable = false, updatable = false)
    lateinit var approvedAt: Instant
        protected set

    @field:Column(name = "execute_before", nullable = false, updatable = false)
    lateinit var executeBefore: Instant
        protected set

    @field:Column(name = "executor_actor_id", nullable = false, updatable = false, length = 30)
    lateinit var executorActorId: String
        protected set

    @field:Column(name = "attempt_count", nullable = false)
    var attemptCount: Int = 0

    @field:Column(name = "next_attempt_at", nullable = false)
    lateinit var nextAttemptAt: Instant

    @field:Column(name = "last_attempt_at")
    var lastAttemptAt: Instant? = null

    @field:Column(name = "last_response_at")
    var lastResponseAt: Instant? = null

    @field:Column(name = "last_failure", length = 48)
    var lastFailure: String? = null

    @field:Column(name = "integrity_flag", length = 48)
    var integrityFlag: String? = null

    @field:Column(name = "resend_halted_reason", length = 48)
    var resendHaltedReason: String? = null

    @field:Column(name = "owner_result_code", length = 48)
    var ownerResultCode: String? = null

    @field:Column(name = "owner_reference", length = 64)
    var ownerReference: String? = null

    @field:Version
    @field:Column(name = "version", nullable = false)
    var version: Long? = null
        protected set

    override fun equals(other: Any?): Boolean =
        this === other || (other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && approvalId == (other as JpaOwnerExecutionEntity).approvalId)

    override fun hashCode(): Int = approvalId.hashCode()

    override fun toString(): String = "JpaOwnerExecutionEntity(approvalId=$approvalId, attemptCount=$attemptCount)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(approvalId: String, requestBody: ByteArray, fingerprint: String, approvedAt: Instant, executeBefore: Instant, executorActorId: String) =
            JpaOwnerExecutionEntity().also {
                it.approvalId = approvalId
                it.requestBody = requestBody
                it.fingerprint = fingerprint
                it.approvedAt = approvedAt
                it.executeBefore = executeBefore
                it.executorActorId = executorActorId
            }
    }
}

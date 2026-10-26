package com.paycore.admin.approval.domain

import java.time.Duration
import java.time.Instant

/**
 * 2인 승인 요청 Aggregate(§4.7 — 불변 객체). 생성 불변 값: 종류·대상·대상 버전·스냅샷·부여 역할·원인 승인·요청자·요청/만료 시각(DB 열 권한으로도 UPDATE 불가 — 1143).
 * 결정 값: 상태·결정자·결정 시각·결과 code. 판정표(§4.7.2): PENDING만 결정되고 종료 상태는 모든 명령에 [ApprovalDecision.AlreadyDecided].
 * ADMIN 소유 종류는 `EXECUTED`·`INVALIDATED`·`OWNER_REJECTED`·`REJECTED`·`WITHDRAWN`·`EXPIRED`로만 끝난다(소유 서비스 전용 상태로 가는 전이가 없다).
 */
class ApprovalRequest private constructor(
    val id: String,
    val kind: ApprovalKind,
    val targetActorId: String,
    val targetSecurityVersion: Long,
    val targetSnapshot: String,
    val grantedRole: String?,
    val causeApprovalId: String?,
    val requestedBy: String,
    val requestedByDisplayName: String,
    val requestedAt: Instant,
    val expiresAt: Instant,
    val status: ApprovalStatus,
    val decidedBy: String?,
    val decidedByDisplayName: String?,
    val decidedAt: Instant?,
    val resultCode: String?,
) {
    init {
        require(ID.matches(id)) { "approval id format" }
        require(kind.needsGrantedRole == (grantedRole != null)) { "a granted role exists exactly for ROLE_GRANT" }
        require(kind.needsCause == (causeApprovalId != null)) { "a cause approval exists exactly for ROLE_REACTIVATE" }
        require(causeApprovalId == null || ApprovalCause.isValid(causeApprovalId)) { "cause format (approval id or recovery episode)" }
        require(requestedBy != targetActorId) { "a requester cannot target themselves (D-ADMIN1-004)" }
        require(expiresAt.isAfter(requestedAt)) { "an approval expires after it is requested" }
        require(status !in ApprovalStatus.OWNER_SERVICE_ONLY) { "ADMIN-owned kinds never use owner-service states" }
        require((status == ApprovalStatus.PENDING) == (decidedAt == null)) { "only decided requests have a decision time" }
        require(resultCode == null || CODE.matches(resultCode)) { "result code format" }
    }

    /** 승인 판정(§4.7.3 2단계 — 잠근 행·TX 시각): PENDING·만료 전·승인자 ≠ 요청자·승인자 ≠ 대상. */
    fun decideApproval(approver: String, now: Instant): ApprovalDecision = decide(approver, now)

    /** 거절 판정(§4.7.8): 승인과 같은 조건. */
    fun decideRejection(approver: String, now: Instant): ApprovalDecision = decide(approver, now)

    private fun decide(approver: String, now: Instant): ApprovalDecision = when {
        status.terminal -> ApprovalDecision.AlreadyDecided
        !now.isBefore(expiresAt) -> ApprovalDecision.Expired(close(ApprovalStatus.EXPIRED, null, null, now, null))
        approver == requestedBy -> ApprovalDecision.Refused(SELF_APPROVAL)
        approver == targetActorId -> ApprovalDecision.Refused(APPROVER_IS_TARGET)
        else -> ApprovalDecision.Proceed
    }

    /** 철회 판정(§4.7.8 — 요청자 본인만, 안전 방향이라 TOTP 없음). 만료 시각이 지났으면 만료로. */
    fun decideWithdrawal(requester: String, now: Instant): ApprovalDecision = when {
        status.terminal -> ApprovalDecision.AlreadyDecided
        requester != requestedBy -> ApprovalDecision.Refused(NOT_REQUESTER)
        !now.isBefore(expiresAt) -> ApprovalDecision.Expired(close(ApprovalStatus.EXPIRED, null, null, now, null))
        else -> ApprovalDecision.Proceed
    }

    fun executed(by: String, byName: String, now: Instant): ApprovalRequest = close(ApprovalStatus.EXECUTED, by, byName, now, null)

    fun invalidated(by: String?, byName: String?, code: String, now: Instant): ApprovalRequest = close(ApprovalStatus.INVALIDATED, by, byName, now, code)

    fun ownerRejected(by: String, byName: String, code: String, now: Instant): ApprovalRequest = close(ApprovalStatus.OWNER_REJECTED, by, byName, now, code)

    fun rejected(by: String, byName: String, now: Instant): ApprovalRequest = close(ApprovalStatus.REJECTED, by, byName, now, null)

    fun withdrawn(by: String, byName: String, now: Instant): ApprovalRequest = close(ApprovalStatus.WITHDRAWN, by, byName, now, null)

    /** 만료 정리(화면용 job — 권위 판정은 승인 TX의 [decideApproval]): PENDING이고 만료 시각이 지났으면 EXPIRED, 아니면 null. */
    fun expireIfDue(now: Instant): ApprovalRequest? =
        if (status == ApprovalStatus.PENDING && !now.isBefore(expiresAt)) close(ApprovalStatus.EXPIRED, null, null, now, null) else null

    private fun close(next: ApprovalStatus, by: String?, byName: String?, now: Instant, code: String?): ApprovalRequest {
        check(status == ApprovalStatus.PENDING) { "only a PENDING request can be decided" }
        require(next.terminal) { "a decision ends in a terminal state" }
        return ApprovalRequest(
            id, kind, targetActorId, targetSecurityVersion, targetSnapshot, grantedRole, causeApprovalId, requestedBy, requestedByDisplayName, requestedAt,
            expiresAt, next, by, byName, now, code,
        )
    }

    override fun equals(other: Any?): Boolean = other is ApprovalRequest && other.id == id

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "ApprovalRequest(id=$id, kind=$kind, status=$status)"

    companion object {
        const val SELF_APPROVAL = "SELF_APPROVAL"
        const val APPROVER_IS_TARGET = "APPROVER_IS_TARGET"
        const val NOT_REQUESTER = "NOT_REQUESTER"
        private val ID = Regex("^apr_[0-9A-HJKMNP-TV-Z]{26}$")
        private val CODE = Regex("^[A-Z][A-Z0-9_]{0,47}$")

        @Suppress("LongParameterList")
        fun create(
            id: String,
            kind: ApprovalKind,
            targetActorId: String,
            targetSecurityVersion: Long,
            targetSnapshot: String,
            grantedRole: String?,
            causeApprovalId: String?,
            requestedBy: String,
            requestedByDisplayName: String,
            requestedAt: Instant,
            validity: Duration,
        ) = ApprovalRequest(
            id, kind, targetActorId, targetSecurityVersion, targetSnapshot, grantedRole, causeApprovalId, requestedBy, requestedByDisplayName, requestedAt,
            requestedAt.plus(validity), ApprovalStatus.PENDING, null, null, null, null,
        )

        @Suppress("LongParameterList")
        fun reconstitute(
            id: String, kind: ApprovalKind, targetActorId: String, targetSecurityVersion: Long, targetSnapshot: String, grantedRole: String?,
            causeApprovalId: String?, requestedBy: String, requestedByDisplayName: String, requestedAt: Instant, expiresAt: Instant, status: ApprovalStatus,
            decidedBy: String?, decidedByDisplayName: String?, decidedAt: Instant?, resultCode: String?,
        ) = ApprovalRequest(
            id, kind, targetActorId, targetSecurityVersion, targetSnapshot, grantedRole, causeApprovalId, requestedBy, requestedByDisplayName, requestedAt,
            expiresAt, status, decidedBy, decidedByDisplayName, decidedAt, resultCode,
        )
    }
}

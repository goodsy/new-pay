package com.paycore.admin.approval.domain

import java.time.Duration
import java.time.Instant

/**
 * 2인 승인 요청 Aggregate(ADMIN-BOOT-001 §4.7, ADMIN-PAYMENT-OPS-001 §4.9 — 불변 객체). 생성 불변 값: 종류·대상([target] — 종류·식별자·대상 버전)·스냅샷·부여 역할·
 * 원인 승인·요청자·요청/만료 시각(DB 열 권한으로도 UPDATE 불가 — 1143). 결정 값: 상태·결정자·결정 시각·결과 code.
 *
 * 판정표: PENDING만 결정되고 그 밖(EXECUTING·EXECUTION_UNKNOWN 포함)은 모든 명령에 [ApprovalDecision.AlreadyDecided].
 * - ADMIN 소유 종류(단일 TX): PENDING → `EXECUTED`·`INVALIDATED`·`OWNER_REJECTED`·`REJECTED`·`WITHDRAWN`·`EXPIRED`. 소유 서비스 전용 상태로 가는 전이가 없다.
 * - 소유 서비스 종류(2-TX): PENDING → [beginExecution] → `EXECUTING` → ([executionUnknown] → `EXECUTION_UNKNOWN`) → [executed]·[ownerRejected](종료).
 *   PENDING에서는 거절·철회·만료·승인 TX1의 무효(`INVALIDATED`)만. EXECUTING 이후 거절·철회·만료·무효 불가. 결정자·결정 시각은 승인(beginExecution) 값을 유지한다.
 * - M-6 fail-closed: 단일 TX 종료 전이 `executed(by…)`·`ownerRejected(by…)`와 계정 접근자 [targetActorId]·[targetSecurityVersion]은 ADMIN 소유·계정 대상만 —
 *   기존 단일 TX 승인 경로가 소유 서비스 승인을 계정 명령 실행기로 보내는 일이 없게 한다.
 */
class ApprovalRequest private constructor(
    val id: String,
    val kind: ApprovalKind,
    val target: ApprovalTarget,
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
        require(kind.targetKind == target.kind) { "the target kind matches the approval kind" }
        require(kind.needsGrantedRole == (grantedRole != null)) { "a granted role exists exactly for ROLE_GRANT" }
        require(kind.needsCause == (causeApprovalId != null)) { "a cause approval exists exactly for ROLE_REACTIVATE" }
        require(causeApprovalId == null || ApprovalCause.isValid(causeApprovalId)) { "cause format (approval id or recovery episode)" }
        require(target !is ApprovalTarget.AdminAccount || requestedBy != target.actorId) { "a requester cannot target themselves (D-ADMIN1-004)" }
        require(expiresAt.isAfter(requestedAt)) { "an approval expires after it is requested" }
        require(status !in ApprovalStatus.OWNER_SERVICE_ONLY || kind.executionModel == ApprovalExecutionModel.OWNER_SERVICE) {
            "ADMIN-owned kinds never use owner-service states"
        }
        require((status == ApprovalStatus.PENDING) == (decidedAt == null)) { "only decided requests have a decision time" }
        require(status !in ApprovalStatus.OWNER_SERVICE_ONLY || decidedBy != null) { "an executing request records its approver" }
        // S4 리뷰 L-3: 실행·소유 거절 종료 행은 결정자(실행자 = 승인자)가 있고, 소유 거절은 결과 code가 있다.
        require(status !in TERMINAL_WITH_DECIDER || decidedBy != null) { "an executed or owner-rejected request records its decider" }
        require(status != ApprovalStatus.OWNER_REJECTED || resultCode != null) { "an owner rejection records its code" }
        require(resultCode == null || CODE.matches(resultCode)) { "result code format" }
    }

    /** 계정 대상의 actorId — ADMIN 소유 단일 TX 경로 전용(M-6). Payment 대상이면 [IllegalStateException](fail-closed). */
    val targetActorId: String
        get() = (target as? ApprovalTarget.AdminAccount)?.actorId ?: throw IllegalStateException(NOT_ACCOUNT_TARGET)

    /** 계정 대상의 요청 시점 security_version — ADMIN 소유 단일 TX 경로 전용(M-6). Payment 대상이면 [IllegalStateException]. */
    val targetSecurityVersion: Long
        get() = (target as? ApprovalTarget.AdminAccount)?.securityVersion ?: throw IllegalStateException(NOT_ACCOUNT_TARGET)

    /** 승인 판정(§4.7.3 2단계 — 잠근 행·TX 시각): PENDING·만료 전·승인자 ≠ 요청자·(계정 대상이면) 승인자 ≠ 대상. */
    fun decideApproval(approver: String, now: Instant): ApprovalDecision = decide(approver, now)

    /** 거절 판정(§4.7.8): 승인과 같은 조건. */
    fun decideRejection(approver: String, now: Instant): ApprovalDecision = decide(approver, now)

    private fun decide(approver: String, now: Instant): ApprovalDecision = when {
        status != ApprovalStatus.PENDING -> ApprovalDecision.AlreadyDecided
        !now.isBefore(expiresAt) -> ApprovalDecision.Expired(close(ApprovalStatus.EXPIRED, null, null, now, null))
        approver == requestedBy -> ApprovalDecision.Refused(SELF_APPROVAL)
        target is ApprovalTarget.AdminAccount && approver == target.actorId -> ApprovalDecision.Refused(APPROVER_IS_TARGET)
        else -> ApprovalDecision.Proceed
    }

    /** 철회 판정(§4.7.8 — 요청자 본인만, 안전 방향이라 TOTP 없음). 만료 시각이 지났으면 만료로. */
    fun decideWithdrawal(requester: String, now: Instant): ApprovalDecision = when {
        status != ApprovalStatus.PENDING -> ApprovalDecision.AlreadyDecided
        requester != requestedBy -> ApprovalDecision.Refused(NOT_REQUESTER)
        !now.isBefore(expiresAt) -> ApprovalDecision.Expired(close(ApprovalStatus.EXPIRED, null, null, now, null))
        else -> ApprovalDecision.Proceed
    }

    /** ADMIN 소유 단일 TX 실행 완료(PENDING → EXECUTED, 실행자 = 승인자). */
    fun executed(by: String, byName: String, now: Instant): ApprovalRequest = adminOwned { close(ApprovalStatus.EXECUTED, by, byName, now, null) }

    fun invalidated(by: String?, byName: String?, code: String, now: Instant): ApprovalRequest = close(ApprovalStatus.INVALIDATED, by, byName, now, code)

    /** ADMIN 소유 단일 TX의 소유 재검사 거절(PENDING → OWNER_REJECTED). */
    fun ownerRejected(by: String, byName: String, code: String, now: Instant): ApprovalRequest =
        adminOwned { close(ApprovalStatus.OWNER_REJECTED, by, byName, now, code) }

    fun rejected(by: String, byName: String, now: Instant): ApprovalRequest = close(ApprovalStatus.REJECTED, by, byName, now, null)

    fun withdrawn(by: String, byName: String, now: Instant): ApprovalRequest = close(ApprovalStatus.WITHDRAWN, by, byName, now, null)

    /** 만료 정리(화면용 job — 권위 판정은 승인 TX의 [decideApproval]): PENDING이고 만료 시각이 지났으면 EXPIRED, 아니면 null. EXECUTING 이후는 만료 대상이 아니다. */
    fun expireIfDue(now: Instant): ApprovalRequest? =
        if (status == ApprovalStatus.PENDING && !now.isBefore(expiresAt)) close(ApprovalStatus.EXPIRED, null, null, now, null) else null

    /** 소유 서비스 승인 TX1(§4.13 B): PENDING → EXECUTING. */
    fun beginExecution(approver: String, approverName: String, now: Instant): ApprovalRequest {
        check(kind.executionModel == ApprovalExecutionModel.OWNER_SERVICE) { "only owner-service kinds execute in two transactions (M-6)" }
        check(status == ApprovalStatus.PENDING) { "only a PENDING request begins its execution" }
        require(ApprovalParticipants.distinct(target, requestedBy, approver)) { "the approver differs from the requester (D-ADMIN1-004)" }
        require(now.isBefore(expiresAt)) { "an expired request cannot begin its execution" }
        return copy(ApprovalStatus.EXECUTING, approver, approverName, now, null)
    }

    /** 소유 서비스 결과: PAYMENT가 그 승인으로 취소를 내구 시작함(STARTED) — EXECUTING·EXECUTION_UNKNOWN → EXECUTED. */
    fun executed(): ApprovalRequest = ownerResult(ApprovalStatus.EXECUTED, null)

    /**
     * 소유 서비스 결과: 실행됐으나 결과 참조 대신 종결 code가 있는 결정(ACQ-ADMIN-001 B1-A — FINANCIAL `NOT_REQUIRED`: 실행 시점 순액 0이라 파일 없이 "재매입 불요" 종결, D-ACQA-004)
     * — EXECUTING·EXECUTION_UNKNOWN → EXECUTED([code]).
     */
    fun executedWith(code: String): ApprovalRequest = ownerResult(ApprovalStatus.EXECUTED, code)

    /** 소유 서비스 결과: PAYMENT 업무 거절(기록 결정) — EXECUTING·EXECUTION_UNKNOWN → OWNER_REJECTED([code]). */
    fun ownerRejected(code: String): ApprovalRequest = ownerResult(ApprovalStatus.OWNER_REJECTED, code)

    /** 소유 서비스 결과 불명확(D-OPS-003): EXECUTING → EXECUTION_UNKNOWN. */
    fun executionUnknown(): ApprovalRequest {
        check(kind.executionModel == ApprovalExecutionModel.OWNER_SERVICE && status == ApprovalStatus.EXECUTING) { "only an EXECUTING owner-service request becomes unknown" }
        return copy(ApprovalStatus.EXECUTION_UNKNOWN, decidedBy, decidedByDisplayName, decidedAt, null)
    }

    private fun ownerResult(next: ApprovalStatus, code: String?): ApprovalRequest {
        check(kind.executionModel == ApprovalExecutionModel.OWNER_SERVICE && status in ApprovalStatus.OWNER_SERVICE_ONLY) {
            "only an EXECUTING / EXECUTION_UNKNOWN owner-service request takes the owner's result"
        }
        return copy(next, decidedBy, decidedByDisplayName, decidedAt, code)
    }

    /** M-6 fail-closed: 단일 TX 종료 전이는 ADMIN 소유 종류만. */
    private inline fun adminOwned(block: () -> ApprovalRequest): ApprovalRequest {
        check(kind.executionModel == ApprovalExecutionModel.ADMIN_OWNED) { "the single-TX path never decides an owner-service approval (M-6)" }
        return block()
    }

    private fun close(next: ApprovalStatus, by: String?, byName: String?, now: Instant, code: String?): ApprovalRequest {
        check(status == ApprovalStatus.PENDING) { "only a PENDING request can be decided" }
        require(next.terminal) { "a decision ends in a terminal state" }
        return copy(next, by, byName, now, code)
    }

    private fun copy(next: ApprovalStatus, by: String?, byName: String?, at: Instant?, code: String?) = ApprovalRequest(
        id, kind, target, targetSnapshot, grantedRole, causeApprovalId, requestedBy, requestedByDisplayName, requestedAt, expiresAt, next, by, byName, at, code,
    )

    override fun equals(other: Any?): Boolean = other is ApprovalRequest && other.id == id

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "ApprovalRequest(id=$id, kind=$kind, status=$status)"

    companion object {
        const val SELF_APPROVAL = "SELF_APPROVAL"
        const val APPROVER_IS_TARGET = "APPROVER_IS_TARGET"
        const val NOT_REQUESTER = "NOT_REQUESTER"
        private const val NOT_ACCOUNT_TARGET = "not an account target — the ADMIN-owned single-TX path never handles owner-service approvals (M-6)"
        private val ID = Regex("^apr_[0-9A-HJKMNP-TV-Z]{26}$")
        private val CODE = Regex("^[A-Z][A-Z0-9_]{0,47}$")
        private val TERMINAL_WITH_DECIDER = setOf(ApprovalStatus.EXECUTED, ApprovalStatus.OWNER_REJECTED)

        /** 계정 대상 생성(ADMIN 소유 단일 TX 경로). 소유 서비스 종류는 대상 종류가 맞지 않아 거부된다(M-6). */
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
        ) = create(
            id, kind, ApprovalTarget.AdminAccount(targetActorId, targetSecurityVersion), targetSnapshot, grantedRole, causeApprovalId, requestedBy,
            requestedByDisplayName, requestedAt, validity,
        )

        /** 대상 일반 생성(ADMIN-PAYMENT-OPS-001 §4.12 — 대상 종류는 [kind]와 같아야 한다). */
        @Suppress("LongParameterList")
        fun create(
            id: String,
            kind: ApprovalKind,
            target: ApprovalTarget,
            targetSnapshot: String,
            grantedRole: String?,
            causeApprovalId: String?,
            requestedBy: String,
            requestedByDisplayName: String,
            requestedAt: Instant,
            validity: Duration,
        ) = ApprovalRequest(
            id, kind, target, targetSnapshot, grantedRole, causeApprovalId, requestedBy, requestedByDisplayName, requestedAt, requestedAt.plus(validity),
            ApprovalStatus.PENDING, null, null, null, null,
        )

        @Suppress("LongParameterList")
        fun reconstitute(
            id: String, kind: ApprovalKind, target: ApprovalTarget, targetSnapshot: String, grantedRole: String?, causeApprovalId: String?, requestedBy: String,
            requestedByDisplayName: String, requestedAt: Instant, expiresAt: Instant, status: ApprovalStatus, decidedBy: String?, decidedByDisplayName: String?,
            decidedAt: Instant?, resultCode: String?,
        ) = ApprovalRequest(
            id, kind, target, targetSnapshot, grantedRole, causeApprovalId, requestedBy, requestedByDisplayName, requestedAt, expiresAt, status, decidedBy,
            decidedByDisplayName, decidedAt, resultCode,
        )
    }
}

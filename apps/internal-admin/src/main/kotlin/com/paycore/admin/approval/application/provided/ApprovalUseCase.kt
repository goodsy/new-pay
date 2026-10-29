package com.paycore.admin.approval.application.provided

import com.paycore.admin.account.application.provided.AdminRequestContext

/**
 * 2인 승인(§4.7 — D-ADMIN1-004·006·009). 모든 명령은 로컬 TX 하나, 잠금 순서 §4.13(회차 → 승인 행 → 계정 오름차순 → 코드 행). 호출자가 보내는 값은 ID·세션 세대·TOTP 코드뿐이고
 * 종류·대상·요청자·대상 버전은 **잠근 승인 행**에서 읽는다. 1213·1205·version 충돌은 전체 rollback 뒤 [ApprovalOutcome.RetryLater](409 — 성공·결과 불명확으로 매핑 안 함).
 * - [request]: 승인 행 INSERT 먼저(같은 종류·대상 PENDING이면 `DUPLICATE_PENDING`) → 요청자·대상 계정 오름차순 잠금 → 요청자(ACTIVE·SECURITY_ADMIN·세션 세대)·대상(존재·
 *   스냅샷 버전·종류별 자격) 재검사. 실패면 전체 rollback, 거부 감사는 별도 TX.
 * - [approve]: §4.7.3 0~8단계(회차 → 승인 행 → 참여자 → 승인자 재검사(403/401) → TOTP 단계 소비(실패면 rollback + 별도 TX 계수) → 요청자·대상 재검사(`INVALIDATED`)
 *   → 소유 재검사·실행(`OWNER_REJECTED`) → `EXECUTED`·감사(실행자 = 승인자)). 재설정 종류의 등록 코드 원문은 [ApprovalOutcome.Executed]에만 1회.
 * - [reject]: 승인과 같은 잠금·승인자 재검사·TOTP. [withdraw]: 요청자 본인(TOTP 없음 — 안전 방향). [expireDue]: 만료 정리(권위 판정은 승인 TX).
 */
interface ApprovalUseCase {
    fun request(command: RequestApproval): ApprovalOutcome

    fun approve(command: DecideApproval): ApprovalOutcome

    fun reject(command: DecideApproval): ApprovalOutcome

    fun withdraw(command: WithdrawApproval): ApprovalOutcome

    /** 만료된 PENDING을 행마다 짧은 TX로 `EXPIRED`로 바꾼다(행위자 SYSTEM_JOB 감사). 바꾼 수. */
    fun expireDue(): Int
}

/** [kind]는 종류 이름(6종), [grantedRole]은 `ROLE_GRANT`만, [causeApprovalId]는 `ROLE_REACTIVATE`만. [sessionGeneration]은 요청 세션에 기록된 세대. */
data class RequestApproval(
    val requesterActorId: String,
    val sessionGeneration: Long,
    val kind: String,
    val targetActorId: String,
    val grantedRole: String?,
    val causeApprovalId: String?,
    val context: AdminRequestContext,
)

data class DecideApproval(val approvalId: String, val approverActorId: String, val sessionGeneration: Long, val totpCode: String, val context: AdminRequestContext) {
    override fun toString(): String = "DecideApproval(approvalId=$approvalId, approver=$approverActorId, totpCode=***)"
}

data class WithdrawApproval(val approvalId: String, val requesterActorId: String, val sessionGeneration: Long, val context: AdminRequestContext)

/** 승인 명령 결과(웹 매핑: Created 201, Executed·Closed 200, AlreadyDecided·Expired·Invalidated·OwnerRejected·Rejected·RetryLater 409, Denied·Refused 403, SessionReplaced 401, StepUpFailed 403, NotFound 404, IntegrityViolation 500). */
sealed interface ApprovalOutcome {
    data class Created(val approvalId: String) : ApprovalOutcome

    /** 실행 완료. [enrollmentCode]는 재설정 종류만 — 실행자(승인자)에게 1회. */
    data class Executed(val approvalId: String, val enrollmentCode: String? = null) : ApprovalOutcome {
        override fun toString(): String = "Executed(approvalId=$approvalId, enrollmentCode=${if (enrollmentCode == null) "none" else "***"})"
    }

    /** 거절·철회로 종료(상태 이름). */
    data class Closed(val status: String) : ApprovalOutcome

    data class Invalidated(val code: String) : ApprovalOutcome

    data class OwnerRejected(val code: String) : ApprovalOutcome

    /**
     * 업무 거절(상태 변경 없음 — 예: DUPLICATE_PENDING, TARGET_NOT_FOUND, NOT_ELIGIBLE, INVALID_INPUT). 입력 형식 거절은 [field]·[reason]에 고정 분류(I-111 — 값 원문 없음).
     */
    data class Rejected(val code: String, val field: String? = null, val reason: String? = null) : ApprovalOutcome

    /** 2인 규칙 위반(예: SELF_APPROVAL, APPROVER_IS_TARGET, NOT_REQUESTER, CAUSE_PARTICIPANT) — 상태 변경 없음. */
    data class Refused(val code: String) : ApprovalOutcome

    data object AlreadyDecided : ApprovalOutcome

    data object Expired : ApprovalOutcome

    /** 요청자·승인자 권한 없음(비활성·역할 없음) — 상태 변경 없음. */
    data object Denied : ApprovalOutcome

    /** 요청 세션의 세대가 계정 현재 세대와 다름(다른 곳 로그인 등). */
    data object SessionReplaced : ApprovalOutcome

    /** 재인증 TOTP 실패(승인 TX rollback, 계수는 별도 TX — §4.7.4). */
    data object StepUpFailed : ApprovalOutcome

    data object NotFound : ApprovalOutcome

    data object RetryLater : ApprovalOutcome

    /** 0단계·1단계 불변 열 불일치(정상 경로로 생길 수 없음 — rev 0.5 n-1): 전체 rollback, 별도 TX 감사, 500. */
    data object IntegrityViolation : ApprovalOutcome

    /** 소유 서비스(PAYMENT) 호출 불가 — 저장·상태 변경·TOTP 소비 없음(ADMIN-PAYMENT-OPS-001 §4.12 2·§4.13 A). 웹 503. */
    data class Unavailable(val code: String) : ApprovalOutcome

    /** 소유 서비스 승인이 승인되어 실행 중(`EXECUTING`) 또는 결과 불명확(`EXECUTION_UNKNOWN`) — 회복 job이 이어받는다(§4.13 D·§4.16). 웹 200. */
    data class Executing(val approvalId: String, val status: String) : ApprovalOutcome
}

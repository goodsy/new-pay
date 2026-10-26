package com.paycore.admin.account.application.provided

/**
 * SECURITY_ADMIN 1인 즉시 명령(안전 방향 — D-ADMIN1-003·004, §4.7.1): 역할 없는 계정 생성, `ENROLL` 코드 재발급, 정지, 역할 회수.
 * 각 명령은 로컬 TX 하나: 요청자·대상 계정을 actor ID 오름차순으로 잠그고(§4.13) 요청자 권한(ACTIVE + SECURITY_ADMIN, 계정 행의 현재 역할)을 판정한 뒤
 * 상태·역할 이벤트·코드·감사를 함께 커밋한다. 거부·거절도 감사 1행을 남긴다(같은 TX, 상태 변경 없음).
 * 등록 코드 원문은 결과로 **한 번만** 돌려준다(호출자는 별도 채널로 본인에게 전달 — 응답 `no-store`, 로그·감사 금지).
 */
interface AdminAccountCommandUseCase {
    fun createAccount(command: CreateAdminAccount): AccountCommandResult

    fun reissueEnrollCode(command: TargetedAccountCommand): AccountCommandResult

    fun suspend(command: TargetedAccountCommand): AccountCommandResult

    fun revokeRole(command: RevokeAdminRole): AccountCommandResult
}

data class CreateAdminAccount(val requesterActorId: String, val loginName: String, val displayName: String, val context: AdminRequestContext)

data class TargetedAccountCommand(val requesterActorId: String, val targetActorId: String, val context: AdminRequestContext)

/** [role]은 역할 enum 이름(VIEWER·OPERATOR·APPROVER·SECURITY_ADMIN·UNMASK). */
data class RevokeAdminRole(val requesterActorId: String, val targetActorId: String, val role: String, val context: AdminRequestContext)

sealed interface AccountCommandResult {
    /** 성공. [enrollmentCode]는 생성·재발급 때만(원문 1회). */
    data class Succeeded(val targetActorId: String, val enrollmentCode: String? = null) : AccountCommandResult {
        override fun toString(): String = "Succeeded(targetActorId=$targetActorId, enrollmentCode=${if (enrollmentCode == null) "none" else "***"})"
    }

    /** 요청자에게 권한이 없음(비활성·역할 없음). 웹은 403. */
    data object Denied : AccountCommandResult

    /** 업무 거절(고정 code — 예: SELF_SUSPEND, ROLE_NOT_HELD, LOGIN_NAME_TAKEN, TARGET_NOT_FOUND, INVALID_INPUT). */
    data class Rejected(val code: String) : AccountCommandResult
}

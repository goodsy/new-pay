package com.paycore.admin.approval.domain

/**
 * 2인 승인 종류(§4.7.1 — ADMIN 소유 6종, 모두 **단일 TX 실행**). 소유 서비스 종류(OPS·Webhook)는 그 작업에서 추가한다(2-TX — §4.12).
 * [needsGrantedRole]·[needsCause]: 요청 시 필수 값. [mayActivatePrivilegedRole]: SECURITY_ADMIN·APPROVER를 활성화할 수 있는 종류 — 열린 복구 회차가 있으면
 * 승인 TX가 회차 행을 먼저 잠근다(§4.7.3 0단계, §4.9.2).
 */
enum class ApprovalKind(val needsGrantedRole: Boolean = false, val needsCause: Boolean = false) {
    ROLE_GRANT(needsGrantedRole = true),
    ACCOUNT_UNLOCK,
    ACCOUNT_REACTIVATE,
    TOTP_REENROLL,
    PASSWORD_RESET,
    ROLE_REACTIVATE(needsCause = true),
    ;

    fun mayActivatePrivilegedRole(grantedRole: String?): Boolean = when (this) {
        ROLE_GRANT -> grantedRole == "SECURITY_ADMIN" || grantedRole == "APPROVER"
        ROLE_REACTIVATE, ACCOUNT_UNLOCK, ACCOUNT_REACTIVATE -> true
        TOTP_REENROLL, PASSWORD_RESET -> false
    }

    /** 권한 상승 종류(D-ADMIN1-014·015 — 대상 자격 발급 근거의 요청자·승인자는 이 종류의 요청자·승인자가 될 수 없다). */
    val escalatesPrivilege: Boolean get() = this == ROLE_GRANT || this == ROLE_REACTIVATE

    /** 실행 결과로 대상 등록 코드를 발급하는 종류(코드는 승인 응답에서 실행자에게 1회만 — §4.7.7). */
    val issuesEnrollmentCode: Boolean get() = this == TOTP_REENROLL || this == PASSWORD_RESET
}

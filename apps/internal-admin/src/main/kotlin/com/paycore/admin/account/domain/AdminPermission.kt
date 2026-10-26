package com.paycore.admin.account.domain

/**
 * 관리자 권한 판정의 단일 기준(§4.5.1 권한 표, D-ADMIN-002, D-ADMIN1-004·008). 서버만 판정한다(React는 표시만).
 * 역할은 세션이 아니라 요청마다 계정 행의 **현재 활성 역할**로 판정하고(D-08), ACTIVE 계정만 권한을 가진다.
 */
enum class AdminAction(val roles: Set<AdminRole>) {
    VIEW_MASKED(setOf(AdminRole.VIEWER, AdminRole.OPERATOR, AdminRole.APPROVER, AdminRole.SECURITY_ADMIN)),
    VIEW_OWN_AUDIT(setOf(AdminRole.VIEWER, AdminRole.OPERATOR, AdminRole.APPROVER, AdminRole.SECURITY_ADMIN)),
    VIEW_ALL_AUDIT(setOf(AdminRole.APPROVER, AdminRole.SECURITY_ADMIN)),
    REQUEST_OPERATION(setOf(AdminRole.OPERATOR)),
    DECIDE_APPROVAL(setOf(AdminRole.APPROVER)),
    CREATE_ACCOUNT(setOf(AdminRole.SECURITY_ADMIN)),
    REISSUE_ENROLL_CODE(setOf(AdminRole.SECURITY_ADMIN)),
    SUSPEND_ACCOUNT(setOf(AdminRole.SECURITY_ADMIN)),
    REVOKE_ROLE(setOf(AdminRole.SECURITY_ADMIN)),
    REQUEST_ADMIN_OWNED_COMMAND(setOf(AdminRole.SECURITY_ADMIN)),
    UNMASK(setOf(AdminRole.UNMASK)),
}

object AdminPermission {
    fun allows(account: AdminAccount, action: AdminAction): Boolean = allows(account.status, account.activeRoles, action)

    /** 계정 현재값(상태·활성 역할)만으로 판정 — 세션 요청·감사 조회 범위 계산이 같은 기준을 쓴다. */
    fun allows(status: AccountStatus, activeRoles: Set<AdminRole>, action: AdminAction): Boolean =
        status == AccountStatus.ACTIVE && activeRoles.any { it in action.roles }
}

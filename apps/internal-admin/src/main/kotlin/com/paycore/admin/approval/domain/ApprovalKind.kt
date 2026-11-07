package com.paycore.admin.approval.domain

/**
 * 2인 승인 종류(ADMIN-BOOT-001 §4.7.1 + ADMIN-PAYMENT-OPS-001 §4.9 — 7종). [executionModel]: ADMIN 소유 6종은 **단일 TX 실행**, 소유 서비스 종류
 * `OPERATOR_CORRECTION_CANCEL`은 2-TX(승인 TX → TX 밖 PAYMENT 호출 → 결과 TX). [requesterRole]: 요청자 역할(ADMIN 소유 = SECURITY_ADMIN, 운영자 보정 취소 = OPERATOR),
 * 승인자는 모두 [approverRole] APPROVER(+ 직전 TOTP). [targetKind]: 대상 종류(계정 또는 Payment).
 * [needsGrantedRole]·[needsCause]: 요청 시 필수 값. [mayActivatePrivilegedRole]: SECURITY_ADMIN·APPROVER를 활성화할 수 있는 종류 — 열린 복구 회차가 있으면
 * 승인 TX가 회차 행을 먼저 잠근다(§4.7.3 0단계, §4.9.2).
 */
enum class ApprovalKind(
    val executionModel: ApprovalExecutionModel,
    val requesterRole: String,
    val targetKind: ApprovalTargetKind,
    val needsGrantedRole: Boolean = false,
    val needsCause: Boolean = false,
) {
    ROLE_GRANT(ApprovalExecutionModel.ADMIN_OWNED, SECURITY_ADMIN, ApprovalTargetKind.ADMIN_ACCOUNT, needsGrantedRole = true),
    ACCOUNT_UNLOCK(ApprovalExecutionModel.ADMIN_OWNED, SECURITY_ADMIN, ApprovalTargetKind.ADMIN_ACCOUNT),
    ACCOUNT_REACTIVATE(ApprovalExecutionModel.ADMIN_OWNED, SECURITY_ADMIN, ApprovalTargetKind.ADMIN_ACCOUNT),
    TOTP_REENROLL(ApprovalExecutionModel.ADMIN_OWNED, SECURITY_ADMIN, ApprovalTargetKind.ADMIN_ACCOUNT),
    PASSWORD_RESET(ApprovalExecutionModel.ADMIN_OWNED, SECURITY_ADMIN, ApprovalTargetKind.ADMIN_ACCOUNT),
    ROLE_REACTIVATE(ApprovalExecutionModel.ADMIN_OWNED, SECURITY_ADMIN, ApprovalTargetKind.ADMIN_ACCOUNT, needsCause = true),

    /** 운영자 보정 취소(D-ADMIN-007, D-OPS-001~009) — 요청 OPERATOR, 승인 APPROVER + 직전 TOTP, 실행은 PAYMENT가 재검사(소유 서비스). */
    OPERATOR_CORRECTION_CANCEL(ApprovalExecutionModel.OWNER_SERVICE, OPERATOR, ApprovalTargetKind.PAYMENT),

    /** 재매입(ACQ-ADMIN-001 B1-A, D-ACQ-011·D-ACQA-002) — 요청 OPERATOR, 승인 APPROVER + 직전 TOTP, 실행은 FINANCIAL이 재검사(소유 서비스, 조작 전용 키 — D-ACQA-001). */
    ACQ_REACQUISITION(ApprovalExecutionModel.OWNER_SERVICE, OPERATOR, ApprovalTargetKind.ACQUISITION_FILE),
    ;

    val approverRole: String get() = APPROVER

    fun mayActivatePrivilegedRole(grantedRole: String?): Boolean = when (this) {
        ROLE_GRANT -> grantedRole == "SECURITY_ADMIN" || grantedRole == "APPROVER"
        ROLE_REACTIVATE, ACCOUNT_UNLOCK, ACCOUNT_REACTIVATE -> true
        TOTP_REENROLL, PASSWORD_RESET, OPERATOR_CORRECTION_CANCEL, ACQ_REACQUISITION -> false
    }

    /** 권한 상승 종류(D-ADMIN1-014·015 — 대상 자격 발급 근거의 요청자·승인자는 이 종류의 요청자·승인자가 될 수 없다). */
    val escalatesPrivilege: Boolean get() = this == ROLE_GRANT || this == ROLE_REACTIVATE

    /** 실행 결과로 대상 등록 코드를 발급하는 종류(코드는 승인 응답에서 실행자에게 1회만 — §4.7.7). */
    val issuesEnrollmentCode: Boolean get() = this == TOTP_REENROLL || this == PASSWORD_RESET
}

private const val SECURITY_ADMIN = "SECURITY_ADMIN"
private const val OPERATOR = "OPERATOR"
private const val APPROVER = "APPROVER"

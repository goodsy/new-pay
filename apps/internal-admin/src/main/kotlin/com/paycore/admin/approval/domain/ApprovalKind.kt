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

    /**
     * 가맹점 계약 Revision 승인(MERCHANT-OPS-002 B2-2 §9.5 — D-MOPS-004·007·008, C-11) — 요청 OPERATOR, 승인 APPROVER + 직전 TOTP, 실행은 MASTER가 승인 근거와 업무 규칙을 재검사
     * (소유 서비스, 조회 키 서명 — D-MOPS-005). 대상 = 가맹점(미해결 키 `MERCHANT:<merchantId>` — C-20). 승인자는 Revision 등록자와 달라야 한다(D-MOPS-008 ③ — TX1·MASTER 양쪽).
     */
    MERCHANT_CONTRACT_APPROVAL(ApprovalExecutionModel.OWNER_SERVICE, OPERATOR, ApprovalTargetKind.MERCHANT),

    /**
     * 첫 Default MID 발급(MERCHANT-OPS-002 B2-3 §9.5 — D-MOPS-004, C-11) — 요청 OPERATOR, 승인 APPROVER + 직전 TOTP, 실행은 MASTER가 승인 근거·발급 규칙을 재검사. 대상·미해결 키는 계약 승인과
     * 같다(`MERCHANT:<merchantId>` — 두 종류 합쳐 가맹점당 미해결 하나, C-20). Revision 등록자 규칙은 적용하지 않는다(계약 승인만).
     */
    MERCHANT_FIRST_MID_ISSUANCE(ApprovalExecutionModel.OWNER_SERVICE, OPERATOR, ApprovalTargetKind.MERCHANT),

    /**
     * 결제수단 개통 승인(MERCHANT-OPS-002 B3-1 §9.16 — D-MOPS-007 ③, C-45) — 요청 OPERATOR, 승인 APPROVER + 직전 TOTP, 실행은 MASTER가 승인 근거·개통 규칙을 재검사(PRIMARY Site 적용 확인
     * 동반). 미해결 키 `MERCHANT:PM:<merchantId>`(C-35). 승인자 ≠ pinned `registeredBy`(개통 등록자 — D-MOPS-009 ③, TX1 403 `APPROVER_IS_REGISTRANT`).
     */
    MERCHANT_PAYMENT_METHOD_ACTIVATION(ApprovalExecutionModel.OWNER_SERVICE, OPERATOR, ApprovalTargetKind.MERCHANT),

    /**
     * Site 심사 승인(MERCHANT-OPS-002 B3-1 §9.16 — APPROVED 고정, C-40) — 요청 OPERATOR, 승인 APPROVER + 직전 TOTP. 미해결 키 `MERCHANT:SITE_REVIEW:<merchantId>`(C-35).
     * 거절은 1인(화면 API `site-review-rejections`)이라 이 종류가 아니다. 승인자 ≠ pinned `registeredBy`(Site 등록자 — D-MOPS-009 ③·SUSPENDED 재승인 포함 D-MOPS-010 ②, TX1 403).
     */
    MERCHANT_SITE_REVIEW_APPROVAL(ApprovalExecutionModel.OWNER_SERVICE, OPERATOR, ApprovalTargetKind.MERCHANT),

    /**
     * 통화 활성화(MERCHANT-OPS-002 B3-2 §9.16·§9.26 — D-MOPS-009 ②, C-41 KRW만) — 요청 OPERATOR, 승인 APPROVER + 직전 TOTP, 실행은 MASTER가 승인 근거·통화 규칙(MID ACTIVE·소급·중첩)을
     * 재검사. 효력 시작 = `AT_EXECUTION`(MASTER 잠금 뒤 시각으로 확정) 또는 KST 날짜 00:00(내일 이후). 미해결 키 `MERCHANT:CURRENCY:<merchantId>`(C-35).
     * 1인 등록 단계가 없어 등록자 규칙은 없다(§9.27 적용 범위 밖 — 요청자≠승인자만).
     */
    MERCHANT_CURRENCY_ENABLEMENT(ApprovalExecutionModel.OWNER_SERVICE, OPERATOR, ApprovalTargetKind.MERCHANT),

    /**
     * 가맹점 코드 1회 변경(MERCHANT-CODE-001 MC-1b §5.5 — D-MCODE-002 Q-MC-7·Q-MC-10) — 요청 OPERATOR, 승인 APPROVER + 직전 TOTP, 실행은 MASTER `ChangeMerchantCode`가 승인 근거·코드 규칙
     * (계약 승인 전·1회·은퇴 코드 영구 점유)을 재검사. 미해결 키는 계약 승인·첫 MID와 같은 `MERCHANT:<mer_>`(C-15). **등록자 규칙 없음**(요청자 ≠ 승인자만 — Q-MC-10, D-MOPS-009 ③ 확장 안 함).
     */
    MERCHANT_CODE_CHANGE(ApprovalExecutionModel.OWNER_SERVICE, OPERATOR, ApprovalTargetKind.MERCHANT),
    ;

    val approverRole: String get() = APPROVER

    fun mayActivatePrivilegedRole(grantedRole: String?): Boolean = when (this) {
        ROLE_GRANT -> grantedRole == "SECURITY_ADMIN" || grantedRole == "APPROVER"
        ROLE_REACTIVATE, ACCOUNT_UNLOCK, ACCOUNT_REACTIVATE -> true
        TOTP_REENROLL, PASSWORD_RESET, OPERATOR_CORRECTION_CANCEL, ACQ_REACQUISITION, MERCHANT_CONTRACT_APPROVAL, MERCHANT_FIRST_MID_ISSUANCE,
        MERCHANT_PAYMENT_METHOD_ACTIVATION, MERCHANT_SITE_REVIEW_APPROVAL, MERCHANT_CURRENCY_ENABLEMENT, MERCHANT_CODE_CHANGE,
        -> false
    }

    /** MASTER 가맹점 승인 채널 종류(MERCHANT-OPS-002 — 대상 MERCHANT, [com.paycore.admin.approval.application.MerchantOpsApprovalService]가 처리). */
    val merchantOps: Boolean get() = targetKind == ApprovalTargetKind.MERCHANT

    /** 권한 상승 종류(D-ADMIN1-014·015 — 대상 자격 발급 근거의 요청자·승인자는 이 종류의 요청자·승인자가 될 수 없다). */
    val escalatesPrivilege: Boolean get() = this == ROLE_GRANT || this == ROLE_REACTIVATE

    /** 실행 결과로 대상 등록 코드를 발급하는 종류(코드는 승인 응답에서 실행자에게 1회만 — §4.7.7). */
    val issuesEnrollmentCode: Boolean get() = this == TOTP_REENROLL || this == PASSWORD_RESET
}

private const val SECURITY_ADMIN = "SECURITY_ADMIN"
private const val OPERATOR = "OPERATOR"
private const val APPROVER = "APPROVER"

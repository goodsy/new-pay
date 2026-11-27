package com.paycore.master.merchant.application.provided

/** 멱등 범위의 연산 이름. [code]는 영속 값(admin_request.operation)이며 바꾸지 않는다. */
enum class AdminOperation(val code: String) {
    REGISTER_BUSINESS("RegisterBusiness"),
    REGISTER_MERCHANT("RegisterMerchant"),
    REGISTER_MERCHANT_SITE("RegisterMerchantSite"),
    REGISTER_CONTRACT("RegisterContract"),
    REGISTER_CONTRACT_REVISION("RegisterContractRevision"),
    APPROVE_CONTRACT_REVISION("ApproveContractRevision"),
    ADD_SERVICE_AGREEMENT("AddServiceAgreement"),
    ISSUE_FIRST_DEFAULT_MERCHANT_ACCOUNT("IssueFirstDefaultMerchantAccount"),
    REGISTER_PAYMENT_METHOD_ACTIVATION("RegisterPaymentMethodActivation"),

    // MASTER-SNAPSHOT-001(D-MD-016~018, D-MS-002·003)
    APPROVE_PAYMENT_METHOD_ACTIVATION("ApprovePaymentMethodActivation"),
    SUSPEND_PAYMENT_METHOD_ACTIVATION("SuspendPaymentMethodActivation"),
    RESUME_PAYMENT_METHOD_ACTIVATION("ResumePaymentMethodActivation"),
    TERMINATE_PAYMENT_METHOD_ACTIVATION("TerminatePaymentMethodActivation"),
    CONFIRM_SITE_ACTIVATION("ConfirmSiteActivation"),
    REVOKE_SITE_ACTIVATION_CONFIRMATION("RevokeSiteActivationConfirmation"),
    ENABLE_CURRENCY("EnableCurrency"),
    END_CURRENCY_ENABLEMENT("EndCurrencyEnablement"),
    DECIDE_SITE_REVIEW("DecideSiteReview"),

    // MASTER-SNAPSHOT-002(D-AUTH-006, D-MD-017·018, D-MS2-001~005)
    REGISTER_TRANSACTION_LIMIT_POLICY("RegisterTransactionLimitPolicy"),
    END_TRANSACTION_LIMIT_POLICY("EndTransactionLimitPolicy"),
    REPLACE_TRANSACTION_LIMIT_POLICY("ReplaceTransactionLimitPolicy"),
    REGISTER_POLICY_VERSION("RegisterPolicyVersion"),
    ASSIGN_POLICY("AssignPolicy"),
    END_POLICY_ASSIGNMENT("EndPolicyAssignment"),
    REPLACE_POLICY_ASSIGNMENT("ReplacePolicyAssignment"),
    REGISTER_CARD_ROUTE_ACTIVATION("RegisterCardRouteActivation"),
    APPROVE_CARD_ROUTE_ACTIVATION("ApproveCardRouteActivation"),
    SUSPEND_CARD_ROUTE_ACTIVATION("SuspendCardRouteActivation"),
    RESUME_CARD_ROUTE_ACTIVATION("ResumeCardRouteActivation"),
    TERMINATE_CARD_ROUTE_ACTIVATION("TerminateCardRouteActivation"),

    // MERCHANT-OPS-002 B3-1(§9.14, nit-1): 1인 Site 심사 거절 — 2인 `DecideSiteReview`(APPROVED)와 멱등 범위·지표 label·이력 operation을 분리한다.
    REJECT_SITE_REVIEW("RejectSiteReview"),

    // MERCHANT-CODE-001 MC-1b(§3.7.2 — D-MCODE-002 Q-MC-7): 가맹점 코드 1회 변경(2인 근거 경로만 — 근거 없는 경로 없음, C-11). 근거 operation CHECK 값은 MC-1 V13에 선반영(C-20).
    CHANGE_MERCHANT_CODE("ChangeMerchantCode"),

    // BUSINESS-PROFILE-001 §6.7(M-3): 사업자 기본정보 명령(생성 DRAFT|REGISTERED·수정·등록 완료). 옛 REGISTER_BUSINESS는 영속 값이라 유지(경로만 BIZ-4 전환에서 제거).
    CREATE_BUSINESS("CreateBusiness"),
    UPDATE_BUSINESS("UpdateBusiness"),
    COMPLETE_BUSINESS_REGISTRATION("CompleteBusinessRegistration"),

    // MERCHANT-FLOW-001 §4B.7(FLOW-2b): 접수 건 생성(종류 시스템 판정 + expectedKind 대조)·전이(계약서 접수·되돌리기·계약 불가)·계약 완료(사업자 계약 + 버전 1 / 새 버전 + 닫기).
    // OPERATOR 1인, 이력 전용 실행기(Outbox 없음 — §4A.2 M-1). 해지·정지는 FLOW-2c.
    OPEN_CONTRACT_INTAKE("OpenContractIntake"),
    MOVE_CONTRACT_INTAKE("MoveContractIntake"),
    COMPLETE_CONTRACT_INTAKE("CompleteContractIntake"),

    // MERCHANT-FLOW-001 §4B.7(FLOW-2c): 사업자 계약 정지·정지 해제·해지(OPERATOR 1인, 사유 필수·근거 선택 — D-GOV-022, 이력 전용 실행기).
    SUSPEND_BUSINESS_CONTRACT("SuspendBusinessContract"),
    RESUME_BUSINESS_CONTRACT("ResumeBusinessContract"),
    TERMINATE_BUSINESS_CONTRACT("TerminateBusinessContract"),
    ;

    companion object {
        fun ofCode(code: String): AdminOperation = entries.single { it.code == code }
    }
}

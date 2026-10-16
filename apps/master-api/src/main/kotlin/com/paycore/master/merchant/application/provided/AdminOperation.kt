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
    ;

    companion object {
        fun ofCode(code: String): AdminOperation = entries.single { it.code == code }
    }
}

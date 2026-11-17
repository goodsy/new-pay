package com.paycore.admin.approval.application.provided

import com.paycore.admin.account.application.provided.AdminRequestContext

/**
 * 가맹점 계약 승인 2인 요청(MERCHANT-OPS-002 B2-2 §9.5 — D-MOPS-004 요청 OPERATOR, D-MOPS-008 ① 근거 참조 필수). 입력 검사(TX 없음) → 세션 역할(비권위) → MASTER 상세 조회(TX 밖, 조회
 * permit — 미승인·끝나지 않은 Revision이어야, advisory) → TX(승인 행 INSERT — 같은 가맹점 미해결이면 `DUPLICATE_UNRESOLVED` → 요청자 잠금·재검사 → 명령 행 INSERT(pinned·근거) → 감사).
 * 승인은 [OwnerServiceApprovalUseCase.approve](종류 분기), 거절·철회·만료는 [ApprovalUseCase](같은 판정표). 감사 대상(MERCHANT-CODE-001 §5.6.4 C-32): 표기 target_ref = 승인 행 표기
 * (MC-3a 뒤 새 행은 요청 시점 가맹점 코드, 그 전 행은 mer_), 내부 키 target_merchant_id = mer_.
 */
interface MerchantOpsApprovalUseCase {
    fun requestContractApproval(command: RequestMerchantContractApproval): ApprovalOutcome

    /** B2-3 첫 Default MID 발급 요청(§9.5 — 같은 틀, 미리 보기 적격: MID 0·승인 Revision·CSA·Scope 안 ACTIVE PRIMARY Site, 미해결 키 `MERCHANT:` 공유 — C-20). */
    fun requestFirstMidIssuance(command: RequestMerchantFirstMid): ApprovalOutcome

    /**
     * B3-1 결제수단 개통 승인 요청(§9.16 — OPERATOR, 근거 참조 필수 = 기관(카드사/VAN) 회신 참조 C-36). 미리 보기 적격(advisory): 개통 UNDER_REVIEW·MID ACTIVE·열린 PRIMARY 있음
     * (그 Site를 pinned `primarySiteId`로 — C-45). 미해결 키 `MERCHANT:PM:<merchantId>`(C-35).
     */
    fun requestPaymentMethodActivation(command: RequestMerchantPaymentMethodActivation): ApprovalOutcome

    /** B3-1 Site 심사 승인 요청(§9.16 — 근거 = PG 심사 근거, 사유 `REVIEW_PASSED`만). 적격(advisory): Site ACTIVE·심사 PENDING 또는 SUSPENDED. 키 `MERCHANT:SITE_REVIEW:<merchantId>`. */
    fun requestSiteReviewApproval(command: RequestMerchantSiteReviewApproval): ApprovalOutcome

    /**
     * B3-2 통화 활성화 요청(§9.16·§9.26 — OPERATOR, 근거 참조 = 기관(카드사/VAN) 회신 참조 필수, D-MOPS-009 ②). 효력 시작 `AT_EXECUTION`(MASTER 실행 시각으로 확정) 또는 `DATE`
     * (KST 날짜 — 요청 시각 기준 내일 이후, advisory). 미리 보기 적격(advisory): MID ACTIVE·같은 통화 끝나지 않은 기록 없음. 미해결 키 `MERCHANT:CURRENCY:<merchantId>`(C-35).
     */
    fun requestCurrencyEnablement(command: RequestMerchantCurrencyEnablement): ApprovalOutcome

    /**
     * MERCHANT-CODE-001 MC-1b 가맹점 코드 변경 요청(§5.5 — OPERATOR, 근거 참조 필수). 입력 형식 → 역할 → MASTER 상세(TX 밖) → 새 코드 = 현재 코드면 400 → advisory
     * `merchantCodeChangeable` false면 409 `TARGET_NOT_ELIGIBLE_ADVISORY` → TX(승인 행 `target_merchant_id` = mer_, 미해결 키 `MERCHANT:<mer_>` → 요청자 재검사 → 명령 행 → 감사 `from=;to=;reason=`).
     */
    fun requestMerchantCodeChange(command: RequestMerchantCodeChange): ApprovalOutcome
}

/** MC-1b 코드 변경 요청 입력(서버는 자르거나 정규화하지 않는다). toString에 근거 참조가 없다. */
data class RequestMerchantCodeChange(
    val requesterActorId: String,
    val sessionGeneration: Long,
    val merchantId: String,
    val newCode: String,
    val reasonCode: String,
    val evidenceRef: String,
    val context: AdminRequestContext,
) {
    override fun toString(): String = "RequestMerchantCodeChange(requester=$requesterActorId, merchantId=$merchantId, reason=$reasonCode)"
}

/** B3-2 통화 활성화 요청 입력(서버는 자르거나 정규화하지 않는다 — 날짜는 `YYYY-MM-DD` KST 날짜 문자열). toString에 근거 참조가 없다. */
data class RequestMerchantCurrencyEnablement(
    val requesterActorId: String,
    val sessionGeneration: Long,
    val merchantId: String,
    val merchantAccountId: String,
    val currency: String,
    val effectiveFromMode: String,
    val effectiveFromDate: String?,
    val effectiveToDate: String?,
    val evidenceRef: String,
    val context: AdminRequestContext,
) {
    override fun toString(): String =
        "RequestMerchantCurrencyEnablement(requester=$requesterActorId, merchantId=$merchantId, merchantAccountId=$merchantAccountId, mode=$effectiveFromMode)"
}

/** B3-1 개통 승인 요청 입력(서버는 자르거나 정규화하지 않는다). toString에 근거 참조가 없다. */
data class RequestMerchantPaymentMethodActivation(
    val requesterActorId: String,
    val sessionGeneration: Long,
    val merchantId: String,
    val merchantAccountId: String,
    val activationId: String,
    val evidenceRef: String,
    val context: AdminRequestContext,
) {
    override fun toString(): String = "RequestMerchantPaymentMethodActivation(requester=$requesterActorId, merchantId=$merchantId, activationId=$activationId)"
}

/** B3-1 Site 심사 승인 요청 입력. toString에 근거 참조가 없다. */
data class RequestMerchantSiteReviewApproval(
    val requesterActorId: String,
    val sessionGeneration: Long,
    val merchantId: String,
    val merchantSiteId: String,
    val reasonCode: String,
    val evidenceRef: String,
    val context: AdminRequestContext,
) {
    override fun toString(): String = "RequestMerchantSiteReviewApproval(requester=$requesterActorId, merchantId=$merchantId, merchantSiteId=$merchantSiteId)"
}

/** 계약 승인 요청 입력(서버는 자르거나 정규화하지 않는다). [revisionNo]는 JSON 정수 그대로(범위는 Use Case가 본다). toString에 근거 참조가 없다. */
data class RequestMerchantContractApproval(
    val requesterActorId: String,
    val sessionGeneration: Long,
    val merchantId: String,
    val contractId: String,
    val revisionNo: Long,
    val evidenceRef: String,
    val context: AdminRequestContext,
) {
    override fun toString(): String = "RequestMerchantContractApproval(requester=$requesterActorId, merchantId=$merchantId, contractId=$contractId, revisionNo=$revisionNo)"
}

/** B2-3 첫 Default MID 발급 요청 입력(서버는 자르거나 정규화하지 않는다). toString에 근거 참조가 없다. */
data class RequestMerchantFirstMid(
    val requesterActorId: String,
    val sessionGeneration: Long,
    val merchantId: String,
    val contractId: String,
    val agreementId: String,
    val revisionNo: Long,
    val primarySiteId: String,
    val evidenceRef: String,
    val context: AdminRequestContext,
) {
    override fun toString(): String = "RequestMerchantFirstMid(requester=$requesterActorId, merchantId=$merchantId, contractId=$contractId, revisionNo=$revisionNo)"
}

package com.paycore.master.merchant.application.provided

import com.paycore.master.merchant.domain.ApprovalEvidence
import com.paycore.master.merchant.domain.ApprovalWindow
import com.paycore.master.merchant.domain.CurrencyCode
import com.paycore.master.merchant.domain.CurrencyEnablementId
import com.paycore.master.merchant.domain.DecisionReason
import com.paycore.master.merchant.domain.EffectivePeriod
import com.paycore.master.merchant.domain.EvidenceRef
import com.paycore.master.merchant.domain.MerchantAccountId
import com.paycore.master.merchant.domain.MerchantSiteId
import com.paycore.master.merchant.domain.PaymentMethod
import com.paycore.master.merchant.domain.PaymentMethodActivationId
import com.paycore.master.merchant.domain.SiteActivationConfirmationId
import com.paycore.master.merchant.domain.SiteReviewStatus
import java.time.Instant

/**
 * 권리 입력 관리 명령(MASTER-SNAPSHOT-001, D-MD-016~018, D-MS-002·003). 각 명령은 MASTER 로컬 TX 하나에서 멱등 기록 → 업무 변경·이력 →
 * entitlement_revision +1 → 권리 Snapshot 발행(issue + Outbox `ENTITLEMENT_SNAPSHOT`, priority HIGH)을 함께 커밋한다. 외부 호출 없음
 * (전달은 Relay가 TX 밖에서). 관리자 web/API는 Q-ADMIN-AUTH 이후다(이 Port는 시험·향후 adapter가 부른다).
 */
interface MerchantEntitlementUseCase {
    fun approvePaymentMethodActivation(command: ApprovePaymentMethodActivationCommand): AdminCommandResult

    fun suspendPaymentMethodActivation(command: SuspendPaymentMethodActivationCommand): AdminCommandResult

    fun resumePaymentMethodActivation(command: ResumePaymentMethodActivationCommand): AdminCommandResult

    fun terminatePaymentMethodActivation(command: TerminatePaymentMethodActivationCommand): AdminCommandResult

    fun confirmSiteActivation(command: ConfirmSiteActivationCommand): AdminCommandResult

    fun revokeSiteActivationConfirmation(command: RevokeSiteActivationConfirmationCommand): AdminCommandResult

    fun enableCurrency(command: EnableCurrencyCommand): AdminCommandResult

    fun endCurrencyEnablement(command: EndCurrencyEnablementCommand): AdminCommandResult

    fun decideSiteReview(command: DecideSiteReviewCommand): AdminCommandResult

    /**
     * MERCHANT-OPS-002 B3-1(§9.14 — D-MOPS-007 ③·D-MOPS-008 ①, C-36·C-45): 근거 있는 개통 승인(2인). 승인 근거 실행기(§9.3 판정 1~4) + 기존 승인 본문,
     * Domain 기관 근거 = 승인 근거 `evidenceRef`(C-36), 실행 시점 열린 PRIMARY ≠ [ApprovedPaymentMethodActivationApprovalCommand.primarySiteId]이면 `PRIMARY_SITE_MISMATCH`.
     * 행위자 = 승인자(C-17). 개통 등록자 = 승인자면 `APPROVER_IS_ACTIVATION_REGISTRANT`(D-MOPS-009 ③ — Domain, MID ACTIVE 뒤·전이 앞 C-49; 저장된 거절).
     */
    fun approvePaymentMethodActivationWithEvidence(command: ApprovedPaymentMethodActivationApprovalCommand): AdminCommandResult

    /** MERCHANT-OPS-002 B3-1: 근거 있는 Site 심사 승인(2인 — APPROVED 고정, C-40). Domain 근거 = 승인 근거(C-36). Site 등록자 = 승인자면
     *  `APPROVER_IS_SITE_REGISTRANT`(D-MOPS-009 ③·D-MOPS-010 ② — SUSPENDED 재승인 포함, 저장된 거절). */
    fun approveSiteReviewWithEvidence(command: ApprovedSiteReviewApprovalCommand): AdminCommandResult

    /** MERCHANT-OPS-002 B3-1: 1인 Site 심사 거절(REJECTED 고정 — 결정값을 받지 않는다, C-40). operation `RejectSiteReview`(nit-1). REJECTED는 종결(D-MS-005). */
    fun rejectSiteReview(command: RejectSiteReviewCommand): AdminCommandResult

    /**
     * MERCHANT-OPS-002 B3-2(§9.14·§9.26 — D-MOPS-009 ②, C-36·C-41): 근거 있는 통화 활성화(2인). 승인 근거 실행기(§9.3 판정 1~4) + 기존 통화 활성화 본문(잠금 merchant → merchant_account,
     * Domain `CurrencyEnablement.enable` 그대로 — 새 Domain 규칙 없음). `AT_EXECUTION`이면 시작 = 잠금 뒤 `lockedNow()`, DATE면 본문 시각(기존 소급 판정).
     * Domain 기관 근거 = 승인 근거 `evidenceRef`(C-36), 행위자 = 승인자(C-17). 등록자 규칙 없음(1인 등록 단계 없음 — §9.27 적용 범위 밖, 요청자≠승인자만).
     */
    fun enableCurrencyWithEvidence(command: ApprovedCurrencyEnablementCommand): AdminCommandResult
}

/**
 * B3-2 근거 있는 통화 활성화(§9.14 `currency-enablements`): 명령 필드 `merchantAccountId`, `currency`, `effectiveFrom`([CurrencyEffectiveStart.text] — 본문 문자열 그대로),
 * `effectiveTo`(KST 00:00 정규 `…Z` 문자열, 없으면 빈 줄) — §9.14 표 순서 = `mop1` 끝 줄 순서 = 요청 지문 입력(확정 시각 아님, §9.26).
 */
data class ApprovedCurrencyEnablementCommand(
    override val request: AdminRequest,
    override val evidence: ApprovalEvidence,
    override val window: ApprovalWindow,
    val merchantAccountId: MerchantAccountId,
    val currency: CurrencyCode,
    val effectiveFrom: CurrencyEffectiveStart,
    val effectiveTo: Instant?,
) : ApprovedCommand {
    init {
        require(request.actor == evidence.approvedBy) { "the executing actor is the approver (C-17)" }
    }

    override val operation: AdminOperation get() = AdminOperation.ENABLE_CURRENCY

    override val commandFields: List<String>
        get() = listOf(merchantAccountId.value, currency.value, effectiveFrom.text, effectiveTo?.let { CurrencyEffectiveStart.canonical(it) } ?: "")
}

data class ApprovePaymentMethodActivationCommand(val request: AdminRequest, val activationId: PaymentMethodActivationId, val evidenceRef: EvidenceRef)

data class SuspendPaymentMethodActivationCommand(val request: AdminRequest, val activationId: PaymentMethodActivationId, val reason: DecisionReason)

data class ResumePaymentMethodActivationCommand(val request: AdminRequest, val activationId: PaymentMethodActivationId, val evidenceRef: EvidenceRef)

data class TerminatePaymentMethodActivationCommand(val request: AdminRequest, val activationId: PaymentMethodActivationId, val reason: DecisionReason)

data class ConfirmSiteActivationCommand(
    val request: AdminRequest,
    val merchantAccountId: MerchantAccountId,
    val merchantSiteId: MerchantSiteId,
    val paymentMethod: PaymentMethod,
    val evidenceRef: EvidenceRef,
)

data class RevokeSiteActivationConfirmationCommand(
    val request: AdminRequest,
    val confirmationId: SiteActivationConfirmationId,
    val reason: DecisionReason,
    val evidenceRef: EvidenceRef,
)

data class EnableCurrencyCommand(
    val request: AdminRequest,
    val merchantAccountId: MerchantAccountId,
    val currency: CurrencyCode,
    val effectivePeriod: EffectivePeriod,
    val evidenceRef: EvidenceRef,
)

data class EndCurrencyEnablementCommand(val request: AdminRequest, val enablementId: CurrencyEnablementId, val effectiveTo: Instant)

data class DecideSiteReviewCommand(
    val request: AdminRequest,
    val merchantSiteId: MerchantSiteId,
    val decision: SiteReviewStatus,
    val reason: DecisionReason,
    val evidenceRef: EvidenceRef?,
)

/**
 * B3-1 근거 있는 개통 승인(§9.14 `payment-method-activation-approvals`): 명령 필드 `activationId`, `primarySiteId`(C-45 — §9.14 표 순서 = `mop1` 끝 줄 순서).
 * [request]의 actor = 승인자(C-17). Domain 기관 근거는 [evidence]의 `evidenceRef`(C-36).
 */
data class ApprovedPaymentMethodActivationApprovalCommand(
    override val request: AdminRequest,
    override val evidence: ApprovalEvidence,
    override val window: ApprovalWindow,
    val activationId: PaymentMethodActivationId,
    val primarySiteId: MerchantSiteId,
) : ApprovedCommand {
    init {
        require(request.actor == evidence.approvedBy) { "the executing actor is the approver (C-17)" }
    }

    override val operation: AdminOperation get() = AdminOperation.APPROVE_PAYMENT_METHOD_ACTIVATION

    override val commandFields: List<String> get() = listOf(activationId.value, primarySiteId.value)
}

/**
 * B3-1 근거 있는 Site 심사 승인(§9.14 `site-review-approvals` — APPROVED 고정): 명령 필드 `merchantSiteId`, `reasonCode`(C-37 — 사유 코드 문자열을 [DecisionReason]으로).
 */
data class ApprovedSiteReviewApprovalCommand(
    override val request: AdminRequest,
    override val evidence: ApprovalEvidence,
    override val window: ApprovalWindow,
    val merchantSiteId: MerchantSiteId,
    val reason: DecisionReason,
) : ApprovedCommand {
    init {
        require(request.actor == evidence.approvedBy) { "the executing actor is the approver (C-17)" }
    }

    override val operation: AdminOperation get() = AdminOperation.DECIDE_SITE_REVIEW

    override val commandFields: List<String> get() = listOf(merchantSiteId.value, reason.value)
}

/** B3-1 1인 Site 심사 거절(§9.14 `site-review-rejections`): 결정값 없음(REJECTED 고정), 사유 코드(C-37), 근거 참조 선택. */
data class RejectSiteReviewCommand(
    val request: AdminRequest,
    val merchantSiteId: MerchantSiteId,
    val reason: DecisionReason,
    val evidenceRef: EvidenceRef?,
)

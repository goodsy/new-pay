package com.paycore.master.merchant.application.provided

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

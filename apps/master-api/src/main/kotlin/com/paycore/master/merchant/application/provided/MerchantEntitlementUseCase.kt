package com.paycore.master.merchant.application.provided

import com.paycore.master.merchant.domain.CurrencyCode
import com.paycore.master.merchant.domain.CurrencyEnablementId
import com.paycore.master.merchant.domain.DecisionReason
import com.paycore.master.merchant.domain.EffectivePeriod
import com.paycore.master.merchant.domain.EvidenceRef
import com.paycore.master.merchant.domain.MerchantAccountId
import com.paycore.master.merchant.domain.MerchantId
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

/** Outbox Relay 한 회차(명세 §4.7). 스케줄 adapter와 시험이 부른다. 외부 호출은 DB TX 밖. */
interface EntitlementRelayUseCase {
    fun relayOnce(): RelayReport
}

data class RelayReport(val claimed: Int, val acknowledged: Int, val retried: Int, val quarantined: Int, val claimLost: Int, val failed: Int = 0)

/** lease 갱신 한 회차(명세 §3.3): 잔여가 renewBefore보다 짧은 Merchant마다 현재 상태로 재발행(같은 revision, leaseSeq+1, priority LOW). */
interface EntitlementLeaseUseCase {
    fun renewDueLeases(): RenewalReport
}

data class RenewalReport(val candidates: Int, val renewed: Int, val skipped: Int)

/**
 * 전파·효력 확인 조회(CQRS, jOOQ — 명세 §3.4-5, §4.8). 발행(PUBLISHED)만으로 효력 확인을 표시하지 않는다: revision r의 전체 효력 확인 =
 * (a) appliedRevision ≥ r(또는 r 이상 revision의 UNSUPPORTED — PAYMENT fail-closed)인 ACK 시각, (b) r 미만 revision lease 최댓값 경과 중
 * 이른 쪽. 운영 화면은 Q-ADMIN-AUTH 이후.
 */
interface EntitlementPropagationQuery {
    fun merchant(merchantId: MerchantId): MerchantPropagationView?

    fun overview(): PropagationOverview
}

enum class EffectBasis { ACK, LEASE_EXPIRY }

data class MerchantPropagationView(
    val merchantId: String,
    val latestRevision: Long,
    val latestRevisionIssuedAt: Instant,
    val latestAckRevision: Long?,
    val latestAckAt: Instant?,
    val priorLeaseMaxUntil: Instant?,
    val effectConfirmedAt: Instant?,
    val effectBasis: EffectBasis?,
)

data class PropagationOverview(
    val pendingSnapshots: Long,
    val oldestPendingCreatedAt: Instant?,
    val quarantinedSnapshots: Long,
    val unconfirmedLatestRevisions: Long,
)

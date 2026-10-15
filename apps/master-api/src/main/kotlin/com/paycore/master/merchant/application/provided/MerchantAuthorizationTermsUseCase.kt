package com.paycore.master.merchant.application.provided

import com.paycore.master.merchant.domain.AcquirerCode
import com.paycore.master.merchant.domain.CardRouteActivationId
import com.paycore.master.merchant.domain.ContractId
import com.paycore.master.merchant.domain.ContractServiceAgreementId
import com.paycore.master.merchant.domain.CurrencyCode
import com.paycore.master.merchant.domain.DecisionReason
import com.paycore.master.merchant.domain.EffectivePeriod
import com.paycore.master.merchant.domain.EvidenceRef
import com.paycore.master.merchant.domain.InstitutionMerchantRef
import com.paycore.master.merchant.domain.LimitValue
import com.paycore.master.merchant.domain.MerchantAccountId
import com.paycore.master.merchant.domain.PaymentMethod
import com.paycore.master.merchant.domain.PolicyAssignmentId
import com.paycore.master.merchant.domain.PolicyCode
import com.paycore.master.merchant.domain.PolicyContentStatus
import com.paycore.master.merchant.domain.PolicyKind
import com.paycore.master.merchant.domain.PolicyVersionId
import com.paycore.master.merchant.domain.RouteRef
import com.paycore.master.merchant.domain.TransactionLimitPolicyId
import java.time.Instant

/**
 * 승인 시작 조건 관리 명령(MASTER-SNAPSHOT-002 명세 §3). Merchant 범위 명령은 MASTER 로컬 TX 하나에서 멱등 기록 → merchant 잠금 → 판정 →
 * 업무 행·이력 → entitlement_revision +1 → 권리 Snapshot 발행(HIGH)을 함께 커밋한다. 정책 버전 등록(전역 등록부)은 Merchant 권리를 바꾸지 않으므로
 * revision을 올리지 않는다(Assignment가 그 버전을 가리킬 때 Snapshot에 실린다). 관리자 web은 Q-ADMIN-AUTH 이후(2인 승인 포함).
 */
interface MerchantAuthorizationTermsUseCase {
    fun registerTransactionLimitPolicy(command: RegisterTransactionLimitPolicyCommand): AdminCommandResult

    fun endTransactionLimitPolicy(command: EndTransactionLimitPolicyCommand): AdminCommandResult

    fun replaceTransactionLimitPolicy(command: ReplaceTransactionLimitPolicyCommand): AdminCommandResult

    fun registerPolicyVersion(command: RegisterPolicyVersionCommand): AdminCommandResult

    fun assignPolicy(command: AssignPolicyCommand): AdminCommandResult

    fun endPolicyAssignment(command: EndPolicyAssignmentCommand): AdminCommandResult

    fun replacePolicyAssignment(command: ReplacePolicyAssignmentCommand): AdminCommandResult

    fun registerCardRouteActivation(command: RegisterCardRouteActivationCommand): AdminCommandResult

    fun approveCardRouteActivation(command: ApproveCardRouteActivationCommand): AdminCommandResult

    fun suspendCardRouteActivation(command: SuspendCardRouteActivationCommand): AdminCommandResult

    fun resumeCardRouteActivation(command: ResumeCardRouteActivationCommand): AdminCommandResult

    fun terminateCardRouteActivation(command: TerminateCardRouteActivationCommand): AdminCommandResult
}

/** 한도 정책 대상 입력. CSA 기본은 계약과 CSA를 함께 받는다(CSA → 계약 → Merchant 소속 확인). */
sealed interface LimitTargetInput {
    data class CsaDefault(val contractId: ContractId, val agreementId: ContractServiceAgreementId) : LimitTargetInput

    data class MidOverride(val merchantAccountId: MerchantAccountId) : LimitTargetInput
}

data class RegisterTransactionLimitPolicyCommand(
    val request: AdminRequest,
    val target: LimitTargetInput,
    val currency: CurrencyCode,
    val perTransaction: LimitValue,
    val daily: LimitValue,
    val effectivePeriod: EffectivePeriod,
    val reason: DecisionReason,
)

data class EndTransactionLimitPolicyCommand(val request: AdminRequest, val policyId: TransactionLimitPolicyId, val effectiveTo: Instant)

data class ReplaceTransactionLimitPolicyCommand(
    val request: AdminRequest,
    val policyId: TransactionLimitPolicyId,
    val perTransaction: LimitValue,
    val daily: LimitValue,
    val switchAt: Instant,
    val reason: DecisionReason,
)

data class RegisterPolicyVersionCommand(
    val request: AdminRequest,
    val kind: PolicyKind,
    val code: PolicyCode,
    val contentStatus: PolicyContentStatus,
    val contentHash: String?,
    val reason: DecisionReason,
)

data class AssignPolicyCommand(
    val request: AdminRequest,
    val merchantAccountId: MerchantAccountId,
    val agreementId: ContractServiceAgreementId,
    val currency: CurrencyCode,
    val policyVersionId: PolicyVersionId,
    val effectivePeriod: EffectivePeriod,
    val reason: DecisionReason,
)

data class EndPolicyAssignmentCommand(val request: AdminRequest, val assignmentId: PolicyAssignmentId, val effectiveTo: Instant)

data class ReplacePolicyAssignmentCommand(
    val request: AdminRequest,
    val assignmentId: PolicyAssignmentId,
    val policyVersionId: PolicyVersionId,
    val switchAt: Instant,
    val reason: DecisionReason,
)

/** [institutionMerchantRef]는 내부 식별자 등급(D-MS2-005) — 이 command의 toString에 원문이 나오지 않는다(값 객체가 가린다). */
data class RegisterCardRouteActivationCommand(
    val request: AdminRequest,
    val merchantAccountId: MerchantAccountId,
    val paymentMethod: PaymentMethod,
    val acquirerCode: AcquirerCode,
    val routeRef: RouteRef,
    val institutionMerchantRef: InstitutionMerchantRef,
)

data class ApproveCardRouteActivationCommand(val request: AdminRequest, val activationId: CardRouteActivationId, val evidenceRef: EvidenceRef)

data class SuspendCardRouteActivationCommand(val request: AdminRequest, val activationId: CardRouteActivationId, val reason: DecisionReason)

data class ResumeCardRouteActivationCommand(val request: AdminRequest, val activationId: CardRouteActivationId, val evidenceRef: EvidenceRef)

data class TerminateCardRouteActivationCommand(val request: AdminRequest, val activationId: CardRouteActivationId, val reason: DecisionReason)

/** 승인 시작 조건 누락 점검(CQRS, jOOQ — 명세 §9). 운영 지표·점검용(비권위). 화면은 Q-ADMIN-AUTH 이후. */
interface AuthorizationTermsCoverageQuery {
    fun overview(): TermsCoverageOverview
}

/** 지금 시각 기준 누락 수(MID·통화 식별자 없이 수만 — 지표 label에 식별자를 쓰지 않는다). */
data class TermsCoverageOverview(
    val enabledCurrenciesWithoutLimitPolicy: Long,
    val enabledCurrenciesWithoutFeeAssignment: Long,
    val enabledCurrenciesWithoutSettlementAssignment: Long,
    val activeMethodsWithoutCardRoute: Long,
)

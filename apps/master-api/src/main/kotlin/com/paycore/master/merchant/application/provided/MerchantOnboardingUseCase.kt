package com.paycore.master.merchant.application.provided

import com.paycore.master.merchant.domain.ApprovalEvidence
import com.paycore.master.merchant.domain.ApprovalWindow
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.ChannelType
import com.paycore.master.merchant.domain.ContractId
import com.paycore.master.merchant.domain.ContractServiceAgreementId
import com.paycore.master.merchant.domain.DisplayName
import com.paycore.master.merchant.domain.EffectivePeriod
import com.paycore.master.merchant.domain.MerchantAccountId
import com.paycore.master.merchant.domain.MerchantCode
import com.paycore.master.merchant.domain.MerchantCodeChangeReason
import com.paycore.master.merchant.domain.MerchantId
import com.paycore.master.merchant.domain.MerchantSiteId
import com.paycore.master.merchant.domain.PaymentMethod
import com.paycore.master.merchant.domain.ServiceKind

/**
 * MASTER 관리 명령(MASTER-DOMAIN-001 범위: 확정 규칙만 — 등록·계약·최초 Default 발급·개통 최소 저장). 각 명령은 MASTER 로컬 TX 하나에서
 * 멱등 기록 → 업무 변경 → 변경 이력 → Outbox를 함께 커밋한다(A02). 외부 호출 없음. 관리자 web/API는 Q-ADMIN-AUTH 결정 전이라 없고
 * (이 Port는 시험·향후 adapter가 부른다), [AdminRequest.actor]는 인증된 신원이 아니라 기록용 참조다.
 *
 * 생명주기 명령(중지·재개·종료, PRIMARY 교체, ADDITIONAL, Default 교체, 추가 MID)은 MASTER-LIFECYCLE-001 범위다. 개통 상태 전이·Site 적용 확인·
 * 통화 활성화·Site 심사는 [MerchantEntitlementUseCase](MASTER-SNAPSHOT-001). 권리 입력을 바꾸는 명령은 같은 TX에서 entitlement_revision +1과
 * 권리 Snapshot 발행(Outbox)을 함께 커밋한다(MID가 있는 Merchant만 발행).
 */
interface MerchantOnboardingUseCase {
    fun registerMerchant(command: RegisterMerchantCommand): AdminCommandResult

    fun registerMerchantSite(command: RegisterMerchantSiteCommand): AdminCommandResult

    fun registerContract(command: RegisterContractCommand): AdminCommandResult

    fun registerContractRevision(command: RegisterContractRevisionCommand): AdminCommandResult

    /**
     * 근거 없는 승인 — **Demo seed 전용**(D-MOPS-008 ②, C-26): 행위자가 정확히 `system:demo-seed`이고 `DeploymentModePort.isDemo()`가 true일 때만 실행한다
     * (아니면 IllegalStateException — TX 없음). 관리 채널은 [approveContractRevisionWithEvidence]만 부른다(ArchUnit).
     */
    fun approveContractRevision(command: ApproveContractRevisionCommand): AdminCommandResult

    /**
     * 승인 근거가 있는 계약 Revision 승인(MERCHANT-OPS-002 B2-2 §9.3·§9.4 — 2인). 근거 판정(같은 행위자·창·1회 사용·잠금 뒤 만료)과 Revision 등록자 ≠ 승인자(D-MOPS-008 ③)를
     * 더한 기존 승인 본문. 업무 효과·근거 행·결과·이력·Outbox·Snapshot이 MASTER 로컬 TX 하나다.
     */
    fun approveContractRevisionWithEvidence(command: ApprovedContractRevisionApprovalCommand): AdminCommandResult

    fun addServiceAgreement(command: AddServiceAgreementCommand): AdminCommandResult

    /** 근거 없는 첫 Default MID 발급 — **Demo seed 전용**(D-MOPS-008 ②, C-26 — [approveContractRevision]과 같은 진입 관문). 근거 있는 경로는 B2-3. */
    fun issueFirstDefaultMerchantAccount(command: IssueFirstDefaultMerchantAccountCommand): AdminCommandResult

    /**
     * 승인 근거가 있는 첫 Default MID 발급(MERCHANT-OPS-002 B2-3 §9.3·§9.4 — 2인). 근거 판정 1~4는 계약 승인과 같고, Revision 등록자 규칙(판정 5)은 적용하지 않는다(결정 범위가
     * "그 Revision을 승인"). 기존 발급 본문(MID·Default·PRIMARY·권리 Snapshot·이력·Outbox) + 근거 행이 MASTER 로컬 TX 하나다.
     */
    fun issueFirstDefaultMerchantAccountWithEvidence(command: ApprovedFirstDefaultIssuanceCommand): AdminCommandResult

    fun registerPaymentMethodActivation(command: RegisterPaymentMethodActivationCommand): AdminCommandResult

    /**
     * MERCHANT-CODE-001 MC-1b(§3.7 — D-MCODE-002 Q-MC-7·Q-MC-10): 가맹점 코드 1회 변경(2인 근거 경로만 — 근거 없는 변경 경로 없음 C-11, 호출자는 관리 채널 컨트롤러뿐).
     * 근거 판정 1~3(요청자 ≠ 승인자·창·approvalId 1회) → 가맹점 없음 → **merchant 잠금** → 잠금 뒤 만료 훅 → 잠금 뒤 "계약 승인 사실" 읽기 → Domain 판정(비활성·이미 변경·계약 승인 뒤·현재 코드 불일치·예약어)
     * → 배정 이력 사전 조회(`MERCHANT_CODE_TAKEN`) → seq 2 배정 행·merchant version +1·이력/Outbox `MerchantCodeChanged`·근거 EXECUTED 행이 MASTER 로컬 TX 하나. 원 가맹점 등록자 판정 없음(Q-MC-10).
     * 성공 reference = merchantId(`mer_…`).
     */
    fun changeMerchantCodeWithEvidence(command: ApprovedMerchantCodeChangeCommand): AdminCommandResult
}

/**
 * MC-1b 근거 있는 코드 변경(§3.7.2 `merchant-code-changes`): 명령 필드 `merchantId`, `currentCode`, `newCode`, `reasonCode`(§3.7.2 `mop1` 끝 줄 순서 = 요청 지문 입력).
 * 등록자 관련 필드 없음(Q-MC-10). [request]의 actor = 승인자(C-17).
 */
@Suppress("LongParameterList")
data class ApprovedMerchantCodeChangeCommand(
    override val request: AdminRequest,
    override val evidence: ApprovalEvidence,
    override val window: ApprovalWindow,
    val merchantId: MerchantId,
    val currentCode: MerchantCode,
    val newCode: MerchantCode,
    val reason: MerchantCodeChangeReason,
) : ApprovedCommand {
    init {
        require(request.actor == evidence.approvedBy) { "the executing actor is the approver (C-17)" }
    }

    override val operation: AdminOperation get() = AdminOperation.CHANGE_MERCHANT_CODE

    override val commandFields: List<String> get() = listOf(merchantId.value, currentCode.value, newCode.value, reason.name)
}

/** MERCHANT-CODE-001 MC-1(§3.3): [merchantCode]는 등록 시 필수(형식은 값 객체, 예약어·중복은 Use Case 판정). */
data class RegisterMerchantCommand(val request: AdminRequest, val businessId: BusinessId, val displayName: DisplayName, val merchantCode: MerchantCode)

data class RegisterMerchantSiteCommand(
    val request: AdminRequest,
    val merchantId: MerchantId,
    val channelType: ChannelType,
    val channelIdentifier: String,
)

/** 계약 Scope 입력: [siteIds]가 비어 있으면 MERCHANT Scope, 있으면 SITES Scope(D-MD-004). */
data class ScopeInput(val merchantId: MerchantId, val siteIds: Set<MerchantSiteId> = emptySet())

data class RegisterContractCommand(
    val request: AdminRequest,
    val partyBusinessId: BusinessId,
    val scope: ScopeInput,
    val effectivePeriod: EffectivePeriod,
)

data class RegisterContractRevisionCommand(
    val request: AdminRequest,
    val contractId: ContractId,
    val scope: ScopeInput,
    val effectivePeriod: EffectivePeriod,
)

data class ApproveContractRevisionCommand(val request: AdminRequest, val contractId: ContractId, val revisionNo: Int)

/** B2-2 근거 있는 계약 승인(§9.4 `contract-revision-approvals`): 명령 필드 `contractId`, `revisionNo`(§9.4 표 순서). [request]의 actor = 승인자(C-17). */
data class ApprovedContractRevisionApprovalCommand(
    override val request: AdminRequest,
    override val evidence: ApprovalEvidence,
    override val window: ApprovalWindow,
    val contractId: ContractId,
    val revisionNo: Int,
) : ApprovedCommand {
    init {
        require(revisionNo >= 1) { "revisionNo starts at 1" }
        require(request.actor == evidence.approvedBy) { "the executing actor is the approver (C-17)" }
    }

    override val operation: AdminOperation get() = AdminOperation.APPROVE_CONTRACT_REVISION

    override val commandFields: List<String> get() = listOf(contractId.value, revisionNo.toString())
}

/**
 * B2-3 첫 Default MID 발급의 근거 있는 명령. 명령 필드 순서 = §9.4 표(merchantId, contractId, agreementId, revisionNo, primarySiteId) = `mop1` 끝 줄 순서.
 */
@Suppress("LongParameterList")
data class ApprovedFirstDefaultIssuanceCommand(
    override val request: AdminRequest,
    override val evidence: ApprovalEvidence,
    override val window: ApprovalWindow,
    val merchantId: MerchantId,
    val contractId: ContractId,
    val agreementId: ContractServiceAgreementId,
    val revisionNo: Int,
    val primarySiteId: MerchantSiteId,
) : ApprovedCommand {
    init {
        require(revisionNo >= 1) { "revisionNo starts at 1" }
        require(request.actor == evidence.approvedBy) { "the executing actor is the approver (C-17)" }
    }

    override val operation: AdminOperation get() = AdminOperation.ISSUE_FIRST_DEFAULT_MERCHANT_ACCOUNT

    override val commandFields: List<String> get() = listOf(merchantId.value, contractId.value, agreementId.value, revisionNo.toString(), primarySiteId.value)
}

data class AddServiceAgreementCommand(val request: AdminRequest, val contractId: ContractId, val serviceKind: ServiceKind)

data class IssueFirstDefaultMerchantAccountCommand(
    val request: AdminRequest,
    val merchantId: MerchantId,
    val agreementId: ContractServiceAgreementId,
    val contractId: ContractId,
    val revisionNo: Int,
    val primarySiteId: MerchantSiteId,
)

data class RegisterPaymentMethodActivationCommand(
    val request: AdminRequest,
    val merchantAccountId: MerchantAccountId,
    val paymentMethod: PaymentMethod,
)

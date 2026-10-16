package com.paycore.master.merchant.application.provided

import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.BusinessRegistrationNumber
import com.paycore.master.merchant.domain.ChannelType
import com.paycore.master.merchant.domain.ContractId
import com.paycore.master.merchant.domain.ContractServiceAgreementId
import com.paycore.master.merchant.domain.DisplayName
import com.paycore.master.merchant.domain.EffectivePeriod
import com.paycore.master.merchant.domain.MerchantAccountId
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
    fun registerBusiness(command: RegisterBusinessCommand): AdminCommandResult

    fun registerMerchant(command: RegisterMerchantCommand): AdminCommandResult

    fun registerMerchantSite(command: RegisterMerchantSiteCommand): AdminCommandResult

    fun registerContract(command: RegisterContractCommand): AdminCommandResult

    fun registerContractRevision(command: RegisterContractRevisionCommand): AdminCommandResult

    fun approveContractRevision(command: ApproveContractRevisionCommand): AdminCommandResult

    fun addServiceAgreement(command: AddServiceAgreementCommand): AdminCommandResult

    fun issueFirstDefaultMerchantAccount(command: IssueFirstDefaultMerchantAccountCommand): AdminCommandResult

    fun registerPaymentMethodActivation(command: RegisterPaymentMethodActivationCommand): AdminCommandResult
}

data class RegisterBusinessCommand(
    val request: AdminRequest,
    val registrationNumber: BusinessRegistrationNumber,
    val displayName: DisplayName,
)

data class RegisterMerchantCommand(val request: AdminRequest, val businessId: BusinessId, val displayName: DisplayName)

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

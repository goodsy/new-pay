package com.paycore.master.merchant.application.provided

import com.paycore.master.merchant.domain.ActorRef
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.BusinessRegistrationNumber
import com.paycore.master.merchant.domain.ChannelType
import com.paycore.master.merchant.domain.ContractId
import com.paycore.master.merchant.domain.ContractServiceAgreementId
import com.paycore.master.merchant.domain.DisplayName
import com.paycore.master.merchant.domain.EffectivePeriod
import com.paycore.master.merchant.domain.MasterRejection
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

/**
 * 관리 명령 멱등키(형식은 D-CPC-002와 같은 관례: 1~64자 `[A-Za-z0-9-_.:]`, 대소문자 구분, 입력 그대로 비교).
 * 오류 메시지에 입력값을 넣지 않는다.
 */
@JvmInline
value class AdminIdempotencyKey(val value: String) {
    init {
        require(value.length in 1..MAX_LENGTH && value.all(::isAllowed)) { "idempotency key must be 1..$MAX_LENGTH characters of [A-Za-z0-9-_.:]" }
    }

    private companion object {
        const val MAX_LENGTH = 64

        fun isAllowed(c: Char): Boolean =
            c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' || c == '.' || c == ':'
    }
}

/**
 * 관리 명령 공통 맥락. [correlationId]는 로그·이벤트 상관 ID(업무 키 아님). 명령 시각은 받지 않는다 — 서버 `MasterClock` Port가 정한다
 * (review m-3 결정: 시각은 fingerprint 대상이 아니므로 요청 필드로 둘 이유가 없다).
 */
data class AdminRequest(
    val idempotencyKey: AdminIdempotencyKey,
    val actor: ActorRef,
    val correlationId: String,
) {
    init {
        require(correlationId.length in 1..MAX_CORRELATION && correlationId.all { it in '!'..'~' }) { "correlationId must be 1..128 printable ASCII" }
    }

    private companion object {
        const val MAX_CORRELATION = 128
    }
}

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

/**
 * 관리 명령 결과. 같은 키·같은 내용 재요청은 저장된 최초 결과를 [replayed]=true로 돌려준다(성공·업무 거절 모두). 같은 키·다른 내용은
 * [IdempotencyKeyReused]이며 아무것도 저장하지 않는다.
 */
sealed interface AdminCommandResult {
    /** [reference] = 만든 대상 ID(등록·발급은 새 ID, 승인은 `contractId#revisionNo`). */
    data class Succeeded(val reference: String, val replayed: Boolean) : AdminCommandResult

    /** 업무 거절(저장·재생). [reference] = 판단 근거 대상(예: 이미 있는 Default MID·같은 등록번호의 Business). */
    data class Rejected(val rejection: MasterRejection, val reference: String?, val replayed: Boolean) : AdminCommandResult

    data object IdempotencyKeyReused : AdminCommandResult
}

/** 결과를 바꾸지 않는 기술 실패. 호출자는 같은 키로 다시 요청해 결과를 확인한다(새 키 금지 — 결과 불명확 시 중복 효과 위험). */
sealed class AdminCommandFailure(message: String, cause: Throwable?) : RuntimeException(message, cause) {
    /** 같은 키의 앞 요청이 아직 커밋하지 않았다(잠금 대기 timeout). */
    class RequestInProgress(cause: Throwable) : AdminCommandFailure("an earlier request with the same key is still in progress", cause)

    class TemporarilyUnavailable(cause: Throwable) : AdminCommandFailure("temporarily unavailable", cause)

    /** commit 단계 실패: 커밋 여부를 알 수 없다. 같은 키로 다시 확인한다. */
    class OutcomeUnknown(cause: Throwable) : AdminCommandFailure("commit outcome unknown", cause)

    /** 서버 생성 ID([kind] = 어느 식별자 제약)가 기존 행과 충돌했다(사용자 입력 충돌이 아니다). 같은 키로 다시 요청하면 새 ID로 처리된다. */
    class IdentifierCollision(val kind: String) : AdminCommandFailure("server-generated identifier collided: $kind", null)
}

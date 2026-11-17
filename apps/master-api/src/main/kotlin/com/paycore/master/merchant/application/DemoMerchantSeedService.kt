package com.paycore.master.merchant.application

import com.paycore.master.merchant.application.provided.AddServiceAgreementCommand
import com.paycore.master.merchant.application.provided.AdminCommandFailure
import com.paycore.master.merchant.application.provided.AdminCommandResult
import com.paycore.master.merchant.application.provided.AdminRequest
import com.paycore.master.merchant.application.provided.ApproveCardRouteActivationCommand
import com.paycore.master.merchant.application.provided.ApproveContractRevisionCommand
import com.paycore.master.merchant.application.provided.ApprovePaymentMethodActivationCommand
import com.paycore.master.merchant.application.provided.BusinessProfileCommandUseCase
import com.paycore.master.merchant.application.provided.CreateBusinessCommand
import com.paycore.master.merchant.application.provided.AssignPolicyCommand
import com.paycore.master.merchant.application.provided.DecideSiteReviewCommand
import com.paycore.master.merchant.application.provided.DemoMerchantSeedCommand
import com.paycore.master.merchant.application.provided.DemoMerchantSeedResult
import com.paycore.master.merchant.application.provided.DemoMerchantSeedUseCase
import com.paycore.master.merchant.application.provided.DemoSeedStep
import com.paycore.master.merchant.application.provided.EffectiveFromRefusal
import com.paycore.master.merchant.application.provided.EnableCurrencyCommand
import com.paycore.master.merchant.application.provided.IssueFirstDefaultMerchantAccountCommand
import com.paycore.master.merchant.application.provided.LimitTargetInput
import com.paycore.master.merchant.application.provided.MerchantAuthorizationTermsUseCase
import com.paycore.master.merchant.application.provided.MerchantEntitlementUseCase
import com.paycore.master.merchant.application.provided.MerchantOnboardingUseCase
import com.paycore.master.merchant.application.provided.RegisterCardRouteActivationCommand
import com.paycore.master.merchant.application.provided.RegisterContractCommand
import com.paycore.master.merchant.application.provided.RegisterMerchantCommand
import com.paycore.master.merchant.application.provided.RegisterMerchantSiteCommand
import com.paycore.master.merchant.application.provided.RegisterPaymentMethodActivationCommand
import com.paycore.master.merchant.application.provided.RegisterPolicyVersionCommand
import com.paycore.master.merchant.application.provided.RegisterTransactionLimitPolicyCommand
import com.paycore.master.merchant.application.provided.ScopeInput
import com.paycore.master.merchant.application.required.AdminRequestScope
import com.paycore.master.merchant.application.required.AdminRequestStore
import com.paycore.master.merchant.application.required.MasterClock
import com.paycore.master.merchant.application.required.StoredAdminResult
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.BusinessRegistrationStatus
import com.paycore.master.merchant.domain.CardRouteActivationId
import com.paycore.master.merchant.domain.ChannelType
import com.paycore.master.merchant.domain.ContractId
import com.paycore.master.merchant.domain.ContractServiceAgreementId
import com.paycore.master.merchant.domain.EffectivePeriod
import com.paycore.master.merchant.domain.MerchantAccountId
import com.paycore.master.merchant.domain.MerchantId
import com.paycore.master.merchant.domain.MerchantSiteId
import com.paycore.master.merchant.domain.PaymentMethod
import com.paycore.master.merchant.domain.PaymentMethodActivationId
import com.paycore.master.merchant.domain.PolicyContentStatus
import com.paycore.master.merchant.domain.PolicyKind
import com.paycore.master.merchant.domain.PolicyVersionId
import com.paycore.master.merchant.domain.SeoulMidnight
import com.paycore.master.merchant.domain.ServiceKind
import com.paycore.master.merchant.domain.SiteReviewStatus
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.DefaultTransactionDefinition
import java.time.Duration

/**
 * DEMO-SEED-001 seed 조정자(명세 §4.2·§4.3). **TX를 열지 않는다** — 단계마다 기존 관리 명령 Use Case가 자기 MASTER 로컬 TX를 소유한다(하나의 큰
 * TX로 감싸면 영속성 컨텍스트·잠금을 18개 명령이 공유하고 실패 시 전부 rollback되어 재생 결과와 어긋난다). 사전 검사만 짧은 **읽기 전용** TX에서
 * `AdminRequestStore.find`(비잠금)로 한다 — `reserveOrLock`은 PENDING 행을 만들므로 쓰지 않는다(I-20, ArchitectureSpec).
 *
 * 순서: T0 형식(Seoul 자정) → 진행 판정(T0를 쓰는 단계 중 결과 없는 단계가 있으면 T0 ≥ 지금 + 10분 요구 — 소급 거절이 중간 단계에 저장돼 seed가 고착되는
 * 것을 막는다) → 18단계(앞 단계 결과 참조를 다음 입력으로). 첫 비성공 결과에서 멈춘다.
 */
// 빈 등록(DEMO-SEED-001 리뷰 m-1): stereotype 없음 — 명령 프로세스(`paycore.master.command=demo-seed`)의 `OneShotModeGuard`(adapter.batch)만 빈 정의를 등록한다.
// 일반 웹 컨텍스트에는 seed 조정자가 없다. application은 Spring Boot 조건 annotation에 의존하지 않는다(ArchUnit allowlist 유지 — payment `DemoCredentialIssueService`와 같은 방식).
class DemoMerchantSeedService(
    private val onboarding: MerchantOnboardingUseCase,
    private val businesses: BusinessProfileCommandUseCase,
    private val entitlement: MerchantEntitlementUseCase,
    private val terms: MerchantAuthorizationTermsUseCase,
    private val requests: AdminRequestStore,
    private val clock: MasterClock,
    private val transactionManager: PlatformTransactionManager,
) : DemoMerchantSeedUseCase {

    override fun seed(command: DemoMerchantSeedCommand): DemoMerchantSeedResult {
        require(SeoulMidnight.isBoundary(command.effectiveFrom)) { "effectiveFrom must be an Asia/Seoul midnight" }
        precheck(command)?.let { return DemoMerchantSeedResult.EffectiveFromRefused(it) }
        return Run(command).execute()
    }

    private fun precheck(command: DemoMerchantSeedCommand): EffectiveFromRefusal? {
        val pending = readOnly {
            DemoSeedStep.entries.filter { it.carriesEffectiveFrom }.filter { step ->
                requests.find(AdminRequestScope(step.operation, step.key(command.keyPrefix)))?.result == null
            }
        }
        if (pending.isEmpty()) return null
        val now = clock.now()
        return when {
            command.effectiveFrom.isBefore(now) -> EffectiveFromRefusal.EFFECTIVE_FROM_PASSED
            command.effectiveFrom.isBefore(now.plus(MIN_LEAD)) -> EffectiveFromRefusal.EFFECTIVE_FROM_TOO_SOON
            else -> null
        }
    }

    private fun <T> readOnly(block: () -> T): T {
        val definition = DefaultTransactionDefinition().apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRED
            isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
            isReadOnly = true
        }
        val status = transactionManager.getTransaction(definition)
        val value = try {
            block()
        } catch (e: Throwable) {
            runCatching { transactionManager.rollback(status) }.exceptionOrNull()?.let(e::addSuppressed)
            throw e
        }
        transactionManager.commit(status)
        return value
    }

    /** 단계 중단 신호(첫 비성공 결과). 스택 추적 없음 — 제어 흐름 전용. */
    private class Stop(val result: DemoMerchantSeedResult) : RuntimeException(null, null, false, false)

    /** 한 실행의 단계 상태. 각 단계 결과를 해석해 다음 입력을 만든다. */
    private inner class Run(private val c: DemoMerchantSeedCommand) {
        private val v = c.values
        private val period = EffectivePeriod(c.effectiveFrom)
        private var allReplayed = true

        private fun request(step: DemoSeedStep) = AdminRequest(step.key(c.keyPrefix), c.actor, c.correlationId)

        /**
         * MERCHANT-CODE-001 C-23(리뷰 MAJOR-2): Use Case를 부르기 **전에** 그 단계 키의 기록을 비잠금으로 읽어(precheck와 같은 `requests.find`) SUCCEEDED면 저장된 reference를 그대로 쓴다
         * (재생 취급 — 새 효과 0, 지문 비교를 거치지 않으므로 MC-1 전 지문으로 seed된 DB의 재실행이 ContentConflict가 되지 않는다). 기록이 없거나 SUCCEEDED가 아니면 지금처럼 호출한다.
         * C-27(rev 0.4.1 Coordinator 결정 — Q-MC1-1 채택): **T0를 싣는 단계(carriesEffectiveFrom)는 선확인하지 않고 호출한다** — 그 지문은 MC-1과 무관하게 같고, DEMO-SEED-001 I-7(같은 키 공간·다른 T0 =
         * ContentConflict, 출력 effectiveFrom이 실제 저장 T0와 같아야 함)을 유지하기 위해서다.
         */
        private fun recordedSuccess(step: DemoSeedStep): String? {
            if (step.carriesEffectiveFrom) return null
            // BUSINESS-PROFILE-001 C-24: 현재 operation의 성공 기록 → 없으면 옛 operation(단계 1의 RegisterBusiness)의 성공 기록. 옛 사업자는 DRAFT로 남는다(가맹점은 이미 등록됨).
            return listOfNotNull(step.operation, step.legacyOperation).firstNotNullOfOrNull { operation ->
                val stored = readOnly { requests.find(AdminRequestScope(operation, step.key(c.keyPrefix))) }?.result
                (stored as? StoredAdminResult.Succeeded)?.reference
            }
        }

        private fun step(step: DemoSeedStep, call: (AdminRequest) -> AdminCommandResult): String {
            recordedSuccess(step)?.let { return it }
            val result = try {
                call(request(step))
            } catch (_: AdminCommandFailure) {
                throw Stop(DemoMerchantSeedResult.RetryLater(step))
            }
            return when (result) {
                is AdminCommandResult.Succeeded -> result.reference.also { if (!result.replayed) allReplayed = false }
                is AdminCommandResult.Rejected -> throw Stop(DemoMerchantSeedResult.StepRejected(step, result.rejection))
                AdminCommandResult.IdempotencyKeyReused -> throw Stop(DemoMerchantSeedResult.ContentConflict(step))
            }
        }

        fun execute(): DemoMerchantSeedResult = try {
            run()
        } catch (stop: Stop) {
            stop.result
        }

        @Suppress("LongMethod")
        private fun run(): DemoMerchantSeedResult {
            // BUSINESS-PROFILE-001 C-24: 새 DB는 REGISTERED Demo 사업자(CreateBusiness — BIZ-4 전환 뒤 새 가맹점 등록 조건 D-BIZ-001을 만족).
            val business = BusinessId(
                step(DemoSeedStep.REGISTER_BUSINESS) {
                    businesses.create(
                        CreateBusinessCommand(it, BusinessRegistrationStatus.REGISTERED, v.businessType, v.registrationNumber, v.businessName, v.businessProfile),
                    )
                },
            )
            val merchant = MerchantId(step(DemoSeedStep.REGISTER_MERCHANT) { onboarding.registerMerchant(RegisterMerchantCommand(it, business, v.merchantName, v.merchantCode)) })
            val site = MerchantSiteId(
                step(DemoSeedStep.REGISTER_SITE) { onboarding.registerMerchantSite(RegisterMerchantSiteCommand(it, merchant, ChannelType.WEB, v.siteHost)) },
            )
            val contract = ContractId(
                step(DemoSeedStep.REGISTER_CONTRACT) { onboarding.registerContract(RegisterContractCommand(it, business, ScopeInput(merchant), period)) },
            )
            step(DemoSeedStep.APPROVE_CONTRACT) { onboarding.approveContractRevision(ApproveContractRevisionCommand(it, contract, FIRST_REVISION)) }
            val agreement = ContractServiceAgreementId(
                step(DemoSeedStep.ADD_AGREEMENT) { onboarding.addServiceAgreement(AddServiceAgreementCommand(it, contract, ServiceKind.CARD_PG_PAYMENT)) },
            )
            val mid = MerchantAccountId(
                step(DemoSeedStep.ISSUE_MID) {
                    onboarding.issueFirstDefaultMerchantAccount(IssueFirstDefaultMerchantAccountCommand(it, merchant, agreement, contract, FIRST_REVISION, site))
                },
            )
            val activation = PaymentMethodActivationId(
                step(DemoSeedStep.REGISTER_ACTIVATION) {
                    onboarding.registerPaymentMethodActivation(RegisterPaymentMethodActivationCommand(it, mid, PaymentMethod.CARD))
                },
            )
            step(DemoSeedStep.APPROVE_ACTIVATION) { entitlement.approvePaymentMethodActivation(ApprovePaymentMethodActivationCommand(it, activation, v.evidence)) }
            step(DemoSeedStep.APPROVE_SITE_REVIEW) {
                entitlement.decideSiteReview(DecideSiteReviewCommand(it, site, SiteReviewStatus.APPROVED, v.reason, v.evidence))
            }
            step(DemoSeedStep.ENABLE_CURRENCY) { entitlement.enableCurrency(EnableCurrencyCommand(it, mid, v.currency, period, v.evidence)) }
            step(DemoSeedStep.REGISTER_LIMIT) {
                terms.registerTransactionLimitPolicy(
                    RegisterTransactionLimitPolicyCommand(
                        it, LimitTargetInput.CsaDefault(contract, agreement), v.currency, v.perTransactionLimit, v.dailyLimit, period, v.reason,
                    ),
                )
            }
            val fee = PolicyVersionId(
                step(DemoSeedStep.REGISTER_FEE_VERSION) {
                    terms.registerPolicyVersion(RegisterPolicyVersionCommand(it, PolicyKind.FEE_SCHEDULE, v.feePolicyCode, PolicyContentStatus.DEMO, null, v.reason))
                },
            )
            val settlement = PolicyVersionId(
                step(DemoSeedStep.REGISTER_SETTLEMENT_VERSION) {
                    terms.registerPolicyVersion(
                        RegisterPolicyVersionCommand(it, PolicyKind.SETTLEMENT_POLICY, v.settlementPolicyCode, PolicyContentStatus.DEMO, null, v.reason),
                    )
                },
            )
            step(DemoSeedStep.ASSIGN_FEE) { terms.assignPolicy(AssignPolicyCommand(it, mid, agreement, v.currency, fee, period, v.reason)) }
            step(DemoSeedStep.ASSIGN_SETTLEMENT) { terms.assignPolicy(AssignPolicyCommand(it, mid, agreement, v.currency, settlement, period, v.reason)) }
            val route = CardRouteActivationId(
                step(DemoSeedStep.REGISTER_CARD_ROUTE) {
                    terms.registerCardRouteActivation(
                        RegisterCardRouteActivationCommand(it, mid, PaymentMethod.CARD, v.acquirerCode, v.routeRef, v.institutionMerchantRef),
                    )
                },
            )
            step(DemoSeedStep.APPROVE_CARD_ROUTE) { terms.approveCardRouteActivation(ApproveCardRouteActivationCommand(it, route, v.evidence)) }
            return DemoMerchantSeedResult.Done(merchant, mid, site, c.effectiveFrom, allReplayed)
        }
    }

    private companion object {
        const val FIRST_REVISION = 1
        val MIN_LEAD: Duration = Duration.ofMinutes(10)
    }
}

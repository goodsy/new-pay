package com.paycore.master.merchant.application

import com.paycore.master.MasterDbTest
import com.paycore.master.merchant.adapter.integration.MicrometerMasterOperationalMetrics
import com.paycore.master.merchant.application.EntitlementCommandFixture.evidence
import com.paycore.master.merchant.application.EntitlementCommandFixture.issuesOf
import com.paycore.master.merchant.application.EntitlementCommandFixture.latestSnapshot
import com.paycore.master.merchant.application.EntitlementCommandFixture.newEvidence
import com.paycore.master.merchant.application.EntitlementCommandFixture.reason
import com.paycore.master.merchant.application.EntitlementCommandFixture.revisionOf
import com.paycore.master.merchant.application.OnboardingFixture.request
import com.paycore.master.merchant.application.OnboardingFixture.seedRequest
import com.paycore.master.merchant.application.OnboardingFixture.runtime
import com.paycore.master.merchant.application.OnboardingFixture.succeeded
import com.paycore.master.merchant.application.provided.AddServiceAgreementCommand
import com.paycore.master.merchant.application.provided.AdminCommandResult
import com.paycore.master.merchant.application.provided.ApproveContractRevisionCommand
import com.paycore.master.merchant.application.provided.ApprovePaymentMethodActivationCommand
import com.paycore.master.merchant.application.provided.ConfirmSiteActivationCommand
import com.paycore.master.merchant.application.provided.DecideSiteReviewCommand
import com.paycore.master.merchant.application.provided.EnableCurrencyCommand
import com.paycore.master.merchant.application.provided.EndCurrencyEnablementCommand
import com.paycore.master.merchant.application.provided.EntitlementLeaseUseCase
import com.paycore.master.merchant.application.provided.MerchantEntitlementUseCase
import com.paycore.master.merchant.application.provided.ApproveCardRouteActivationCommand
import com.paycore.master.merchant.application.provided.AssignPolicyCommand
import com.paycore.master.merchant.application.provided.EndPolicyAssignmentCommand
import com.paycore.master.merchant.application.provided.EndTransactionLimitPolicyCommand
import com.paycore.master.merchant.application.provided.MerchantAuthorizationTermsUseCase
import com.paycore.master.merchant.application.provided.RegisterPolicyVersionCommand
import com.paycore.master.merchant.application.provided.ReplacePolicyAssignmentCommand
import com.paycore.master.merchant.application.provided.ReplaceTransactionLimitPolicyCommand
import com.paycore.master.merchant.application.provided.ResumeCardRouteActivationCommand
import com.paycore.master.merchant.application.provided.SuspendCardRouteActivationCommand
import com.paycore.master.merchant.application.provided.TerminateCardRouteActivationCommand
import com.paycore.master.merchant.domain.CardRouteActivationId
import com.paycore.master.merchant.domain.LimitValue
import com.paycore.master.merchant.domain.PolicyAssignmentId
import com.paycore.master.merchant.domain.PolicyContentStatus
import com.paycore.master.merchant.domain.PolicyKind
import com.paycore.master.merchant.domain.PolicyVersionId
import com.paycore.master.merchant.domain.TransactionLimitPolicyId
import com.paycore.master.merchant.application.provided.MerchantOnboardingUseCase
import com.paycore.master.merchant.application.provided.RegisterContractCommand
import com.paycore.master.merchant.application.provided.RegisterContractRevisionCommand
import com.paycore.master.merchant.application.provided.RegisterMerchantSiteCommand
import com.paycore.master.merchant.application.provided.ResumePaymentMethodActivationCommand
import com.paycore.master.merchant.application.provided.RevokeSiteActivationConfirmationCommand
import com.paycore.master.merchant.application.provided.ScopeInput
import com.paycore.master.merchant.application.provided.SuspendPaymentMethodActivationCommand
import com.paycore.master.merchant.application.required.EntitlementSourceStore
import com.paycore.master.merchant.domain.ChannelType
import com.paycore.master.merchant.domain.ContractId
import com.paycore.master.merchant.domain.CurrencyCode
import com.paycore.master.merchant.domain.CurrencyEnablementId
import com.paycore.master.merchant.domain.EffectivePeriod
import com.paycore.master.merchant.domain.EntitlementSnapshotAssembler
import com.paycore.master.merchant.domain.MasterRejection
import com.paycore.master.merchant.domain.MerchantId
import com.paycore.master.merchant.domain.PaymentMethod
import com.paycore.master.merchant.domain.ServiceKind
import com.paycore.master.merchant.domain.SiteActivationConfirmationId
import com.paycore.master.merchant.domain.SiteReviewStatus
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.mysql.MySQLContainer
import java.time.Duration

/**
 * revision 증가 매트릭스(review M-2): **조립된 Snapshot 내용이 바뀌는 MASTER 명령은 모두 같은 TX에서 entitlement_revision을 올리고 발행한다.**
 * 그렇지 않으면 다음 lease 갱신이 같은 revision·다른 hash가 되어 PAYMENT가 INTEGRITY_CONFLICT로 Merchant 전체를 막는다. 각 명령 전후로 같은
 * 시각·leaseSeq로 조립한 content hash를 비교해 "hash 변화 ⇒ revision 변화"를 확인한다. Site 적용 확인 중복(review M-1)도 명령 경로로 확인한다.
 */
@MasterDbTest
class EntitlementRevisionMatrixSpec(
    private val onboarding: MerchantOnboardingUseCase,
    private val entitlement: MerchantEntitlementUseCase,
    private val terms: MerchantAuthorizationTermsUseCase,
    private val leases: EntitlementLeaseUseCase,
    private val sources: EntitlementSourceStore,
    private val transactionManager: PlatformTransactionManager,
    private val container: MySQLContainer,
    private val clock: MutableMasterClock,
    private val registry: MeterRegistry,
) : BehaviorSpec({

    afterEach { clock.reset() }

    val tx = TransactionTemplate(transactionManager)

    fun assembledHash(merchantId: MerchantId): String? = tx.execute {
        sources.load(merchantId)?.let { EntitlementSnapshotAssembler.assemble(it, OnboardingFixture.t0, 0, Duration.ofSeconds(60)).contentHash }
    }

    fun scalar(sql: String, vararg args: Any?): Any? = runtime(container).use { c ->
        c.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
            ps.executeQuery().use { rs -> if (rs.next()) rs.getObject(1) else null }
        }
    }

    Given("every MASTER command applied to an entitled merchant") {
        Then("whenever the assembled snapshot content changes, the entitlement revision changed in the same command") {
            val e = EntitlementCommandFixture.entitled(onboarding, entitlement)
            val m = e.merchantId
            val violations = mutableListOf<String>()
            fun step(name: String, command: () -> AdminCommandResult) {
                val (hashBefore, revBefore) = assembledHash(m) to revisionOf(container, m)
                succeeded(command())
                val (hashAfter, revAfter) = assembledHash(m) to revisionOf(container, m)
                if (hashBefore != hashAfter && revBefore == revAfter) violations += name
            }
            val krw = CurrencyEnablementId(scalar("SELECT enablement_id FROM currency_enablement WHERE merchant_account_id = ?", e.mid.value) as String)
            step("RegisterMerchantSite") {
                onboarding.registerMerchantSite(RegisterMerchantSiteCommand(request(), m, ChannelType.WEB, OnboardingFixture.host()))
            }
            var secondContract: ContractId? = null
            step("RegisterContract") {
                onboarding.registerContract(RegisterContractCommand(request(), e.ready.businessId, ScopeInput(m), EffectivePeriod(OnboardingFixture.t0)))
                    .also { secondContract = ContractId((it as AdminCommandResult.Succeeded).reference) }
            }
            step("AddServiceAgreement") { onboarding.addServiceAgreement(AddServiceAgreementCommand(request(), checkNotNull(secondContract), ServiceKind.CARD_PG_PAYMENT)) }
            step("RegisterContractRevision") {
                onboarding.registerContractRevision(
                    RegisterContractRevisionCommand(request(), e.ready.contractId, ScopeInput(m, setOf(e.siteId)), EffectivePeriod(OnboardingFixture.t0.plusSeconds(3600))),
                )
            }
            step("ApproveContractRevision") { onboarding.approveContractRevision(ApproveContractRevisionCommand(OnboardingFixture.seedRequest(), e.ready.contractId, 2)) } // D-MOPS-008 ②: seed 경로 행위자
            step("SuspendActivation") { entitlement.suspendPaymentMethodActivation(SuspendPaymentMethodActivationCommand(request(), e.activationId, reason)) }
            step("ResumeActivation") { entitlement.resumePaymentMethodActivation(ResumePaymentMethodActivationCommand(request(), e.activationId, newEvidence)) }
            val primary = SiteActivationConfirmationId(
                scalar("SELECT confirmation_id FROM site_activation_confirmation WHERE activation_id = ?", e.activationId.value) as String,
            )
            step("RevokeConfirmation") { entitlement.revokeSiteActivationConfirmation(RevokeSiteActivationConfirmationCommand(request(), primary, reason, evidence)) }
            step("ConfirmSite") {
                entitlement.confirmSiteActivation(ConfirmSiteActivationCommand(request(), e.mid, e.siteId, PaymentMethod.CARD, evidence))
            }
            step("EnableCurrency") {
                entitlement.enableCurrency(EnableCurrencyCommand(request(), e.mid, CurrencyCode("USD"), EffectivePeriod(OnboardingFixture.t0), evidence))
            }
            step("EndCurrency") { entitlement.endCurrencyEnablement(EndCurrencyEnablementCommand(request(), krw, OnboardingFixture.t0.plusSeconds(60))) }
            step("DecideSiteReview") {
                entitlement.decideSiteReview(DecideSiteReviewCommand(request(), e.siteId, SiteReviewStatus.SUSPENDED, reason, null))
            }
            // MASTER-SNAPSHOT-002: 승인 시작 조건 명령(정책 버전 등록은 Assignment가 가리키기 전에는 조립 내용에 영향이 없다 — 위반 판정이 그것을 본다).
            var feeVersion: PolicyVersionId? = null
            step("RegisterPolicyVersion") {
                terms.registerPolicyVersion(RegisterPolicyVersionCommand(request(), PolicyKind.FEE_SCHEDULE, AuthorizationTermsCommandFixture.code("FEE"), PolicyContentStatus.DEMO, null, reason))
                    .also { feeVersion = PolicyVersionId((it as AdminCommandResult.Succeeded).reference) }
            }
            var assignment: PolicyAssignmentId? = null
            step("AssignPolicy") {
                terms.assignPolicy(AssignPolicyCommand(request(), e.mid, e.ready.agreementId, CurrencyCode("KRW"), checkNotNull(feeVersion), EffectivePeriod(OnboardingFixture.t0), reason))
                    .also { assignment = PolicyAssignmentId((it as AdminCommandResult.Succeeded).reference) }
            }
            var replacementVersion: PolicyVersionId? = null
            step("RegisterPolicyVersion#2") {
                terms.registerPolicyVersion(RegisterPolicyVersionCommand(request(), PolicyKind.FEE_SCHEDULE, AuthorizationTermsCommandFixture.code("FEE"), PolicyContentStatus.DEMO, null, reason))
                    .also { replacementVersion = PolicyVersionId((it as AdminCommandResult.Succeeded).reference) }
            }
            step("ReplacePolicyAssignment") {
                terms.replacePolicyAssignment(ReplacePolicyAssignmentCommand(request(), checkNotNull(assignment), checkNotNull(replacementVersion), OnboardingFixture.t0.plusSeconds(60), reason))
                    .also { assignment = PolicyAssignmentId((it as AdminCommandResult.Succeeded).reference) }
            }
            step("EndPolicyAssignment") { terms.endPolicyAssignment(EndPolicyAssignmentCommand(request(), checkNotNull(assignment), OnboardingFixture.t0.plusSeconds(120))) }
            var limit: TransactionLimitPolicyId? = null
            step("RegisterTransactionLimitPolicy") {
                terms.registerTransactionLimitPolicy(AuthorizationTermsCommandFixture.csaDefault(e)).also { limit = TransactionLimitPolicyId((it as AdminCommandResult.Succeeded).reference) }
            }
            step("ReplaceTransactionLimitPolicy") {
                terms.replaceTransactionLimitPolicy(
                    ReplaceTransactionLimitPolicyCommand(request(), checkNotNull(limit), LimitValue.Unlimited, LimitValue.Unlimited, AuthorizationTermsCommandFixture.midnight2, reason),
                ).also { limit = TransactionLimitPolicyId((it as AdminCommandResult.Succeeded).reference) }
            }
            step("EndTransactionLimitPolicy") {
                terms.endTransactionLimitPolicy(EndTransactionLimitPolicyCommand(request(), checkNotNull(limit), AuthorizationTermsCommandFixture.midnight2.plusSeconds(86_400)))
            }
            var route: CardRouteActivationId? = null
            step("RegisterCardRouteActivation") {
                terms.registerCardRouteActivation(AuthorizationTermsCommandFixture.route(e)).also { route = CardRouteActivationId((it as AdminCommandResult.Succeeded).reference) }
            }
            step("ApproveCardRouteActivation") { terms.approveCardRouteActivation(ApproveCardRouteActivationCommand(request(), checkNotNull(route), evidence)) }
            step("SuspendCardRouteActivation") { terms.suspendCardRouteActivation(SuspendCardRouteActivationCommand(request(), checkNotNull(route), reason)) }
            step("ResumeCardRouteActivation") { terms.resumeCardRouteActivation(ResumeCardRouteActivationCommand(request(), checkNotNull(route), newEvidence)) }
            step("TerminateCardRouteActivation") { terms.terminateCardRouteActivation(TerminateCardRouteActivationCommand(request(), checkNotNull(route), reason)) }
            violations shouldBe emptyList()
        }

        Then("registering a site and then renewing the lease issues a renewal with the same revision and the same content hash (no drift)") {
            val e = EntitlementCommandFixture.entitled(onboarding, entitlement)
            val drift = registry.find(MicrometerMasterOperationalMetrics.HASH_DRIFT).counter()?.count() ?: 0.0
            succeeded(onboarding.registerMerchantSite(RegisterMerchantSiteCommand(request(), e.merchantId, ChannelType.WEB, OnboardingFixture.host())))
            val afterSite = issuesOf(container, e.merchantId).last()
            latestSnapshot(container, e.merchantId).get("sites").size() shouldBe 3
            clock.set(OnboardingFixture.t0.plusMillis(1500))
            leases.renewDueLeases()
            val renewal = issuesOf(container, e.merchantId).last()
            listOf(renewal.revision, renewal.leaseSeq, renewal.contentHash) shouldBe listOf(afterSite.revision, 1L, afterSite.contentHash)
            (registry.find(MicrometerMasterOperationalMetrics.HASH_DRIFT).counter()?.count() ?: 0.0) shouldBe drift
        }
    }

    Given("site activation confirmations through the commands (review M-1)") {
        Then("PRIMARY cannot be confirmed before approval; approval records exactly one valid confirmation; after revoking it the snapshot carries none") {
            val (ready, ids) = EntitlementCommandFixture.issuedWithActivation(onboarding)
            val (mid, activation) = ids
            entitlement.confirmSiteActivation(ConfirmSiteActivationCommand(request(), mid, ready.siteId, PaymentMethod.CARD, evidence)) shouldBe
                AdminCommandResult.Rejected(MasterRejection.PRIMARY_CONFIRMED_BY_APPROVAL, null, replayed = false)
            succeeded(entitlement.approvePaymentMethodActivation(ApprovePaymentMethodActivationCommand(seedRequest(), activation, evidence)))
            fun validCount() = (scalar(
                "SELECT COUNT(*) FROM site_activation_confirmation c LEFT JOIN site_activation_confirmation_revocation r " +
                    "ON r.confirmation_id = c.confirmation_id WHERE c.activation_id = ? AND r.confirmation_id IS NULL",
                activation.value,
            ) as Number).toLong()
            validCount() shouldBe 1L
            val only = SiteActivationConfirmationId(
                scalar("SELECT confirmation_id FROM site_activation_confirmation WHERE activation_id = ?", activation.value) as String,
            )
            succeeded(entitlement.revokeSiteActivationConfirmation(RevokeSiteActivationConfirmationCommand(request(), only, reason, evidence)))
            validCount() shouldBe 0L
            // PAYMENT 판정: 확인 없음 → METHOD_NOT_ENABLED(EntitlementEvaluatorSpec "no site confirmation").
            latestSnapshot(container, ready.merchantId).get("accounts").get(0).get("siteConfirmations").size() shouldBe 0
        }
    }
})

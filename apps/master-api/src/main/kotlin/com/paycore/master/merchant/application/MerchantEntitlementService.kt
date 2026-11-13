package com.paycore.master.merchant.application

import com.paycore.master.merchant.application.provided.AdminCommandResult
import com.paycore.master.merchant.application.provided.AdminOperation
import com.paycore.master.merchant.application.provided.ApprovePaymentMethodActivationCommand
import com.paycore.master.merchant.application.provided.AdminRequest
import com.paycore.master.merchant.application.provided.ApprovedPaymentMethodActivationApprovalCommand
import com.paycore.master.merchant.application.provided.ApprovedSiteReviewApprovalCommand
import com.paycore.master.merchant.application.provided.RejectSiteReviewCommand
import com.paycore.master.merchant.application.provided.ConfirmSiteActivationCommand
import com.paycore.master.merchant.application.provided.DecideSiteReviewCommand
import com.paycore.master.merchant.application.provided.EnableCurrencyCommand
import com.paycore.master.merchant.application.provided.EndCurrencyEnablementCommand
import com.paycore.master.merchant.application.provided.MerchantEntitlementUseCase
import com.paycore.master.merchant.application.provided.ResumePaymentMethodActivationCommand
import com.paycore.master.merchant.application.provided.RevokeSiteActivationConfirmationCommand
import com.paycore.master.merchant.application.provided.SuspendPaymentMethodActivationCommand
import com.paycore.master.merchant.application.provided.TerminatePaymentMethodActivationCommand
import com.paycore.master.merchant.application.required.AdminRequestStore
import com.paycore.master.merchant.application.required.ApprovalEvidenceStore
import com.paycore.master.merchant.application.required.DeploymentModePort
import com.paycore.master.merchant.application.required.CurrencyEnablementRepository
import com.paycore.master.merchant.application.required.MasterChangeLog
import com.paycore.master.merchant.application.required.MasterClock
import com.paycore.master.merchant.application.required.MasterIdGenerator
import com.paycore.master.merchant.application.required.MasterOperationalMetrics
import com.paycore.master.merchant.application.required.MerchantAccountRepository
import com.paycore.master.merchant.application.required.MerchantSiteRepository
import com.paycore.master.merchant.application.required.MerchantRepository
import com.paycore.master.merchant.application.required.OutboxStore
import com.paycore.master.merchant.application.required.PaymentMethodActivationRepository
import com.paycore.master.merchant.application.required.SiteActivationConfirmationRepository
import com.paycore.master.merchant.application.required.SiteReviewStore
import com.paycore.master.merchant.application.required.Versioned
import com.paycore.master.merchant.domain.ActivationApproval
import com.paycore.master.merchant.domain.ActorRef
import com.paycore.master.merchant.domain.ActivationTransition
import com.paycore.master.merchant.domain.CurrencyEnablement
import com.paycore.master.merchant.domain.CurrencyEnablementId
import com.paycore.master.merchant.domain.MerchantSite
import com.paycore.master.merchant.domain.EffectivePeriod
import com.paycore.master.merchant.domain.EvidenceRef
import com.paycore.master.merchant.domain.MasterRejection
import com.paycore.master.merchant.domain.MasterRuleViolation
import com.paycore.master.merchant.domain.Merchant
import com.paycore.master.merchant.domain.MerchantAccount
import com.paycore.master.merchant.domain.MerchantAccountId
import com.paycore.master.merchant.domain.MerchantSiteId
import com.paycore.master.merchant.domain.PaymentMethodActivation
import com.paycore.master.merchant.domain.PaymentMethodActivationId
import com.paycore.master.merchant.domain.SiteActivationConfirmation
import com.paycore.master.merchant.domain.SiteActivationConfirmationId
import com.paycore.master.merchant.domain.SiteConfirmationRules
import com.paycore.master.merchant.domain.SiteReviewDecision
import com.paycore.master.merchant.domain.SiteReviewRules
import com.paycore.master.merchant.domain.SiteReviewStatus
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 권리 입력 관리 명령(MASTER-SNAPSHOT-001). [AdminCommandExecutor]의 MASTER 로컬 TX 하나에서:
 * 잠금(admin_request → merchant → merchant_account → 대상 행) → **마지막 잠금 뒤 시각**으로 domain 판정 → beginWrites → 업무 행·이력(append-only)
 * → entitlement_revision +1 → 이력·Outbox(참조 이벤트) → 권리 Snapshot 발행(issue + Outbox HIGH) → 요청 결과.
 *
 * 잠금 전 읽기는 대상의 소속(불변 값: Activation·확인·통화 기록의 MID, Site의 Merchant)만 쓴다. 판정에 쓰는 상태는 merchant 잠금 뒤에 다시 읽는다
 * (Merchant 행이 Merchant 안 모든 권리 입력 변경의 직렬화 지점이다 — 명세 §3.1).
 */
@Service
@Suppress("LongParameterList")
class MerchantEntitlementService(
    transactionManager: PlatformTransactionManager,
    requests: AdminRequestStore,
    changeLog: MasterChangeLog,
    outbox: OutboxStore,
    ids: MasterIdGenerator,
    clock: MasterClock,
    metrics: MasterOperationalMetrics,
    publisher: EntitlementSnapshotPublisher,
    private val merchants: MerchantRepository,
    private val sites: MerchantSiteRepository,
    private val accounts: MerchantAccountRepository,
    private val activations: PaymentMethodActivationRepository,
    private val confirmations: SiteActivationConfirmationRepository,
    private val currencies: CurrencyEnablementRepository,
    private val reviews: SiteReviewStore,
    evidences: ApprovalEvidenceStore,
    private val deploymentMode: DeploymentModePort,
) : MerchantEntitlementUseCase {

    private val executor = AdminCommandExecutor(transactionManager, requests, changeLog, outbox, ids, clock, metrics, publisher, evidences)
    private val log = LoggerFactory.getLogger(MerchantEntitlementService::class.java)

    /** 근거 없는 개통 승인 — Demo seed 경로 전용(MERCHANT-OPS-002 C-40, D-MOPS-008 ②): 행위자 정확히 `system:demo-seed` + Demo 모드. 관리 채널은 [approvePaymentMethodActivationWithEvidence]. */
    override fun approvePaymentMethodActivation(command: ApprovePaymentMethodActivationCommand): AdminCommandResult {
        requireDemoSeedPath(AdminOperation.APPROVE_PAYMENT_METHOD_ACTIVATION, command.request)
        return executor.execute(
            AdminOperation.APPROVE_PAYMENT_METHOD_ACTIVATION, command.request, listOf(command.activationId.value, command.evidenceRef.value),
        ) { work -> approveActivation(work, command.activationId, command.evidenceRef, command.request.actor, ActivationPath.SeedPath) }
    }

    override fun approvePaymentMethodActivationWithEvidence(command: ApprovedPaymentMethodActivationApprovalCommand): AdminCommandResult =
        executor.executeApproved(command.operation, command.request, command.evidence, command.window, ApprovedCommands.fingerprintFields(command)) { work ->
            // C-36: Domain 기관 근거 = 승인 근거 참조. C-45: 승인자가 본 PRIMARY 고정. 행위자 = 승인자(C-17). D-MOPS-009 ③ 등록자 검사(Domain — approveWithEvidence).
            approveActivation(work, command.activationId, command.evidence.evidenceRef, command.request.actor, ActivationPath.EvidencePath(command.primarySiteId))
        }

    /** 개통 승인 경로(B3-1F): seed(근거 없음 — 등록자 검사 없음, C-31 구조)와 근거 경로(등록자 검사 + 고정 PRIMARY C-45)를 Domain 진입 메서드로 나눈다. */
    private sealed interface ActivationPath {
        object SeedPath : ActivationPath

        data class EvidencePath(val primarySiteId: MerchantSiteId) : ActivationPath
    }

    /**
     * 개통 승인 본문(두 경로 공통): 잠금(merchant → merchant_account → activation) → `lockedNow`(근거 경로는 여기서 만료 훅) → Domain([ActivationApproval] — 근거 경로는 등록자 → 전이 →
     * C-45 PRIMARY 고정, C-49) → 쓰기(전이·이력·PRIMARY 확인·Snapshot). 잠금 전 `ACTIVATION_NOT_FOUND`는 만료보다 먼저 저장된다(m-4와 같은 의도된 순서).
     */
    private fun approveActivation(
        work: AdminCommandExecutor.Work,
        activationId: PaymentMethodActivationId,
        evidence: EvidenceRef,
        actor: ActorRef,
        path: ActivationPath,
    ): String {
        val ctx = lockActivation(activationId)
        work.lockedNow()
        val assignments = accounts.assignmentsOf(ctx.account.id)
        val existing = confirmations.listByActivation(ctx.activation.value.id)
        val confirmationId = SiteActivationConfirmationId(work.newId(SiteActivationConfirmationId.PREFIX))
        val result = when (path) {
            ActivationPath.SeedPath -> ActivationApproval.approve(
                ctx.activation.value, ctx.account, assignments, existing, confirmationId, evidence, work.now, actor, expectedPrimarySiteId = null,
            )
            is ActivationPath.EvidencePath -> ActivationApproval.approveWithEvidence(
                ctx.activation.value, ctx.account, assignments, existing, confirmationId, evidence, work.now, actor, path.primarySiteId,
            )
        }
        ctx.merchant.value.recordEntitlementChange()
        work.beginWrites()
        val version = writeTransition(work, ctx, result.transition, "PaymentMethodActivationApproved")
        result.primaryConfirmation?.let { recordConfirmation(work, it, ctx.merchant.value) }
        check(version > ctx.activation.version) { "activation version advances" }
        work.publishEntitlement(ctx.merchant.value)
        return ctx.activation.value.id.value
    }

    override fun suspendPaymentMethodActivation(command: SuspendPaymentMethodActivationCommand): AdminCommandResult =
        executor.execute(
            AdminOperation.SUSPEND_PAYMENT_METHOD_ACTIVATION, command.request, listOf(command.activationId.value, command.reason.value),
        ) { work ->
            val ctx = lockActivation(command.activationId)
            work.lockedNow()
            val transition = ctx.activation.value.suspend(command.reason, work.now, command.request.actor)
            ctx.merchant.value.recordEntitlementChange()
            work.beginWrites()
            writeTransition(work, ctx, transition, "PaymentMethodActivationSuspended")
            work.publishEntitlement(ctx.merchant.value)
            ctx.activation.value.id.value
        }

    override fun resumePaymentMethodActivation(command: ResumePaymentMethodActivationCommand): AdminCommandResult =
        executor.execute(
            AdminOperation.RESUME_PAYMENT_METHOD_ACTIVATION, command.request, listOf(command.activationId.value, command.evidenceRef.value),
        ) { work ->
            val ctx = lockActivation(command.activationId)
            work.lockedNow()
            // D-MS-003: 재개 시 기존 Site 적용 확인은 유지된다(다시 기록하지 않는다).
            val transition = ctx.activation.value.resume(
                ctx.account, command.evidenceRef, activations.evidenceHistory(ctx.activation.value.id), work.now, command.request.actor,
            )
            ctx.merchant.value.recordEntitlementChange()
            work.beginWrites()
            writeTransition(work, ctx, transition, "PaymentMethodActivationResumed")
            work.publishEntitlement(ctx.merchant.value)
            ctx.activation.value.id.value
        }

    override fun terminatePaymentMethodActivation(command: TerminatePaymentMethodActivationCommand): AdminCommandResult =
        executor.execute(
            AdminOperation.TERMINATE_PAYMENT_METHOD_ACTIVATION, command.request, listOf(command.activationId.value, command.reason.value),
        ) { work ->
            val ctx = lockActivation(command.activationId)
            work.lockedNow()
            val transition = ctx.activation.value.terminate(command.reason, work.now, command.request.actor)
            ctx.merchant.value.recordEntitlementChange()
            work.beginWrites()
            writeTransition(work, ctx, transition, "PaymentMethodActivationTerminated")
            work.publishEntitlement(ctx.merchant.value)
            ctx.activation.value.id.value
        }

    override fun confirmSiteActivation(command: ConfirmSiteActivationCommand): AdminCommandResult =
        executor.execute(
            AdminOperation.CONFIRM_SITE_ACTIVATION, command.request,
            listOf(command.merchantAccountId.value, command.merchantSiteId.value, command.paymentMethod.name, command.evidenceRef.value),
        ) { work ->
            val (merchant, account) = lockAccount(command.merchantAccountId)
            work.lockedNow()
            val site = sites.find(command.merchantSiteId) ?: throw MasterRuleViolation(MasterRejection.SITE_NOT_FOUND)
            val activation = activations.findOpen(account.id, command.paymentMethod)?.value
                ?: throw MasterRuleViolation(MasterRejection.ACTIVATION_NOT_FOUND)
            val confirmation = SiteConfirmationRules.confirm(
                SiteActivationConfirmationId(work.newId(SiteActivationConfirmationId.PREFIX)), account, site, activation,
                accounts.assignmentsOf(account.id), confirmations.listByActivation(activation.id), command.evidenceRef, work.now, command.request.actor,
            )
            merchant.value.recordEntitlementChange()
            work.beginWrites()
            merchants.update(merchant.value, merchant.version)
            recordConfirmation(work, confirmation, merchant.value)
            work.publishEntitlement(merchant.value)
            confirmation.id.value
        }

    override fun revokeSiteActivationConfirmation(command: RevokeSiteActivationConfirmationCommand): AdminCommandResult =
        executor.execute(
            AdminOperation.REVOKE_SITE_ACTIVATION_CONFIRMATION, command.request,
            listOf(command.confirmationId.value, command.reason.value, command.evidenceRef.value),
        ) { work ->
            val mid = confirmations.find(command.confirmationId)?.merchantAccountId
                ?: throw MasterRuleViolation(MasterRejection.SITE_CONFIRMATION_NOT_FOUND)
            val (merchant, _) = lockAccount(mid)
            work.lockedNow()
            val confirmation = checkNotNull(confirmations.find(command.confirmationId)) { "confirmation must exist" }
            val revocation = confirmation.revoke(command.reason, command.evidenceRef, work.now, command.request.actor)
            merchant.value.recordEntitlementChange()
            work.beginWrites()
            confirmations.insertRevocation(confirmation, work.requestRecordId)
            merchants.update(merchant.value, merchant.version)
            work.record(
                AGG_CONFIRMATION, confirmation.id.value, 1, "SiteActivationConfirmationRevoked",
                mapOf(
                    "confirmationId" to confirmation.id.value, "merchantAccountId" to confirmation.merchantAccountId.value,
                    "merchantSiteId" to confirmation.merchantSiteId.value, "paymentMethod" to confirmation.paymentMethod.name,
                    "reason" to revocation.reason.value, "evidenceRef" to revocation.evidenceRef.value,
                    "entitlementRevision" to merchant.value.entitlementRevision,
                ),
            )
            work.publishEntitlement(merchant.value)
            confirmation.id.value
        }

    override fun enableCurrency(command: EnableCurrencyCommand): AdminCommandResult =
        executor.execute(
            AdminOperation.ENABLE_CURRENCY, command.request,
            listOf(command.merchantAccountId.value, command.currency.value) + periodFields(command.effectivePeriod) + command.evidenceRef.value,
        ) { work ->
            val period = EffectivePeriod.ofMillis(command.effectivePeriod.from, command.effectivePeriod.to)
            val (merchant, account) = lockAccount(command.merchantAccountId)
            work.lockedNow()
            val unit = currencies.findUnit(command.currency) ?: throw MasterRuleViolation(MasterRejection.CURRENCY_UNKNOWN)
            val enablement = CurrencyEnablement.enable(
                CurrencyEnablementId(work.newId(CurrencyEnablementId.PREFIX)), account, unit, period, currencies.listByAccount(account.id),
                command.evidenceRef, work.now, command.request.actor,
            )
            merchant.value.recordEntitlementChange()
            work.beginWrites()
            currencies.insert(enablement, work.requestRecordId)
            merchants.update(merchant.value, merchant.version)
            work.record(AGG_CURRENCY, enablement.id.value, 0, "CurrencyEnabled", currencyPayload(enablement, merchant.value))
            work.publishEntitlement(merchant.value)
            enablement.id.value
        }

    override fun endCurrencyEnablement(command: EndCurrencyEnablementCommand): AdminCommandResult =
        executor.execute(
            AdminOperation.END_CURRENCY_ENABLEMENT, command.request,
            listOf(command.enablementId.value, command.effectiveTo.truncatedTo(ChronoUnit.MILLIS).toString()),
        ) { work ->
            val effectiveTo = command.effectiveTo.truncatedTo(ChronoUnit.MILLIS)
            val mid = currencies.find(command.enablementId)?.value?.merchantAccountId
                ?: throw MasterRuleViolation(MasterRejection.CURRENCY_ENABLEMENT_NOT_FOUND)
            val (merchant, _) = lockAccount(mid)
            val locked = checkNotNull(currencies.lock(command.enablementId)) { "enablement must exist" }
            work.lockedNow()
            locked.value.end(effectiveTo, work.now, command.request.actor)
            merchant.value.recordEntitlementChange()
            work.beginWrites()
            currencies.update(locked.value, locked.version)
            merchants.update(merchant.value, merchant.version)
            work.record(AGG_CURRENCY, locked.value.id.value, locked.version + 1, "CurrencyEnablementEnded", currencyPayload(locked.value, merchant.value))
            work.publishEntitlement(merchant.value)
            locked.value.id.value
        }

    /**
     * 근거 없는 Site 심사 결정. **APPROVED는 Demo seed 경로 전용**(C-40 — 2인 결정, D-MOPS-008 ②). REJECTED(1인 — 관리 채널은 [rejectSiteReview])·SUSPENDED(안전 방향 1인 — B5)는 관문 없음.
     */
    override fun decideSiteReview(command: DecideSiteReviewCommand): AdminCommandResult {
        if (command.decision == SiteReviewStatus.APPROVED) requireDemoSeedPath(AdminOperation.DECIDE_SITE_REVIEW, command.request)
        return executor.execute(
            AdminOperation.DECIDE_SITE_REVIEW, command.request,
            listOf(command.merchantSiteId.value, command.decision.name, command.reason.value, command.evidenceRef?.value),
        ) { work ->
            decideReview(work, command.merchantSiteId) { site, history, at ->
                SiteReviewRules.decide(site, history, command.decision, command.reason, command.evidenceRef, at, command.request.actor)
            }
        }
    }

    override fun approveSiteReviewWithEvidence(command: ApprovedSiteReviewApprovalCommand): AdminCommandResult =
        executor.executeApproved(command.operation, command.request, command.evidence, command.window, ApprovedCommands.fingerprintFields(command)) { work ->
            // D-MOPS-009 ③·D-MOPS-010 ②: 근거 경로만 Site 등록자 검사(Domain — approveWithEvidence). seed·1인 경로는 decide 그대로(C-31).
            decideReview(work, command.merchantSiteId) { site, history, at ->
                SiteReviewRules.approveWithEvidence(site, history, command.reason, command.evidence.evidenceRef, at, command.request.actor)
            }
        }

    override fun rejectSiteReview(command: RejectSiteReviewCommand): AdminCommandResult =
        executor.execute(
            AdminOperation.REJECT_SITE_REVIEW, command.request, listOf(command.merchantSiteId.value, command.reason.value, command.evidenceRef?.value),
        ) { work ->
            decideReview(work, command.merchantSiteId) { site, history, at ->
                SiteReviewRules.decide(site, history, SiteReviewStatus.REJECTED, command.reason, command.evidenceRef, at, command.request.actor)
            }
        }

    /**
     * 심사 결정 본문(세 경로 공통): 소속 Merchant(불변)만 먼저 읽고 잠근 뒤 `lockedNow` → Domain 판정([decide] — 경로별 Domain 진입: 근거 승인은 등록자 → 전이, 그 밖은 전이)
     * → 결정 행·이력·Snapshot. 잠금 전 `SITE_NOT_FOUND`가 만료보다 먼저.
     */
    private fun decideReview(
        work: AdminCommandExecutor.Work,
        siteId: MerchantSiteId,
        decide: (MerchantSite, List<SiteReviewDecision>, Instant) -> SiteReviewDecision,
    ): String {
        val merchantId = sites.find(siteId)?.merchantId ?: throw MasterRuleViolation(MasterRejection.SITE_NOT_FOUND)
        val merchant = merchants.lock(merchantId) ?: error("site merchant must exist")
        work.lockedNow()
        val site = checkNotNull(sites.find(siteId)) { "site must exist" }
        val decision = decide(site, reviews.history(site.id), work.now)
        merchant.value.recordEntitlementChange()
        work.beginWrites()
        reviews.append(decision, work.requestRecordId)
        merchants.update(merchant.value, merchant.version)
        work.record(
            AGG_SITE, site.id.value, decision.seq.toLong(), "MerchantSiteReviewDecided",
            mapOf(
                "merchantSiteId" to site.id.value, "merchantId" to site.merchantId.value, "seq" to decision.seq,
                "decision" to decision.decision.name, "reason" to decision.reason.value, "evidenceRef" to decision.evidenceRef?.value,
                "entitlementRevision" to merchant.value.entitlementRevision,
            ),
        )
        work.publishEntitlement(merchant.value)
        return "${site.id.value}#${decision.seq}"
    }

    /**
     * 근거 없는 2인 메서드의 진입 관문(D-MOPS-008 ②, C-26·C-40): 행위자가 **정확히** `system:demo-seed`이고 배포 모드가 Demo일 때만. 아니면 결함으로 보고 TX를 열기 전에
     * IllegalStateException(감사 WARN — 연산·상관 ID만). 관리 채널은 근거 있는 메서드만 부른다(ArchUnit).
     */
    private fun requireDemoSeedPath(operation: AdminOperation, request: AdminRequest) {
        if (request.actor == DEMO_SEED_ACTOR && deploymentMode.isDemo()) return
        log.warn("evidence-less command refused operation={} correlationId={} reason=NOT_DEMO_SEED_PATH", operation.code, request.correlationId)
        throw IllegalStateException("evidence-less ${operation.code} runs only on the Demo seed path (system:demo-seed in DEMO mode, D-MOPS-008 ②)")
    }

    private data class ActivationContext(
        val merchant: Versioned<Merchant>,
        val account: MerchantAccount,
        val activation: Versioned<PaymentMethodActivation>,
    )

    /** 소속 MID(불변)만 먼저 읽고 merchant → merchant_account → activation 순으로 잠근다. */
    private fun lockActivation(id: PaymentMethodActivationId): ActivationContext {
        val mid = activations.find(id)?.value?.merchantAccountId ?: throw MasterRuleViolation(MasterRejection.ACTIVATION_NOT_FOUND)
        val (merchant, account) = lockAccount(mid)
        val activation = activations.lock(id) ?: error("activation must exist")
        return ActivationContext(merchant, account, activation)
    }

    private fun lockAccount(mid: MerchantAccountId): Pair<Versioned<Merchant>, MerchantAccount> {
        val merchantId = accounts.find(mid)?.merchantId ?: throw MasterRuleViolation(MasterRejection.MERCHANT_ACCOUNT_NOT_FOUND)
        val merchant = merchants.lock(merchantId) ?: error("account merchant must exist")
        val account = accounts.lock(mid) ?: error("account must exist")
        return merchant to account
    }

    private fun writeTransition(work: AdminCommandExecutor.Work, ctx: ActivationContext, transition: ActivationTransition, eventType: String): Long {
        val activation = ctx.activation.value
        activations.update(activation, ctx.activation.version, transition, work.requestRecordId)
        merchants.update(ctx.merchant.value, ctx.merchant.version)
        val version = ctx.activation.version + 1
        work.record(
            AGG_ACTIVATION, activation.id.value, version, eventType,
            mapOf(
                "activationId" to activation.id.value, "merchantAccountId" to activation.merchantAccountId.value,
                "paymentMethod" to activation.paymentMethod.name, "fromStatus" to transition.from?.name, "status" to transition.to.name,
                "evidenceRef" to transition.evidenceRef?.value, "reason" to transition.reason?.value,
                "entitlementRevision" to ctx.merchant.value.entitlementRevision,
            ),
        )
        return version
    }

    private fun recordConfirmation(work: AdminCommandExecutor.Work, confirmation: SiteActivationConfirmation, merchant: Merchant) {
        confirmations.insert(confirmation, work.requestRecordId)
        work.record(
            AGG_CONFIRMATION, confirmation.id.value, 0, "SiteActivationConfirmed",
            mapOf(
                "confirmationId" to confirmation.id.value, "merchantAccountId" to confirmation.merchantAccountId.value,
                "merchantSiteId" to confirmation.merchantSiteId.value, "paymentMethod" to confirmation.paymentMethod.name,
                "activationId" to confirmation.activationId.value, "evidenceRef" to confirmation.evidenceRef.value,
                "entitlementRevision" to merchant.entitlementRevision,
            ),
        )
    }

    private fun currencyPayload(e: CurrencyEnablement, merchant: Merchant): Map<String, Any?> = mapOf(
        "enablementId" to e.id.value, "merchantAccountId" to e.merchantAccountId.value, "currency" to e.unit.code.value,
        "minorUnit" to e.unit.minorUnit, "effectiveFrom" to e.period.from, "effectiveTo" to e.period.to, "evidenceRef" to e.evidenceRef.value,
        "entitlementRevision" to merchant.entitlementRevision,
    )

    private fun periodFields(period: EffectivePeriod): List<String?> =
        listOf(period.from.truncatedTo(ChronoUnit.MILLIS).toString(), period.to?.truncatedTo(ChronoUnit.MILLIS)?.toString())

    private companion object {
        const val AGG_ACTIVATION = "PaymentMethodActivation"
        const val AGG_CONFIRMATION = "SiteActivationConfirmation"
        const val AGG_CURRENCY = "CurrencyEnablement"
        const val AGG_SITE = "MerchantSite"
        val DEMO_SEED_ACTOR = ActorRef("system:demo-seed")
    }
}

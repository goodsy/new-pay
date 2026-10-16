package com.paycore.master.merchant.application

import com.paycore.master.merchant.application.provided.AdminCommandResult
import com.paycore.master.merchant.application.provided.AdminOperation
import com.paycore.master.merchant.application.provided.ApproveCardRouteActivationCommand
import com.paycore.master.merchant.application.provided.AssignPolicyCommand
import com.paycore.master.merchant.application.provided.EndPolicyAssignmentCommand
import com.paycore.master.merchant.application.provided.EndTransactionLimitPolicyCommand
import com.paycore.master.merchant.application.provided.LimitTargetInput
import com.paycore.master.merchant.application.provided.MerchantAuthorizationTermsUseCase
import com.paycore.master.merchant.application.provided.RegisterCardRouteActivationCommand
import com.paycore.master.merchant.application.provided.RegisterPolicyVersionCommand
import com.paycore.master.merchant.application.provided.RegisterTransactionLimitPolicyCommand
import com.paycore.master.merchant.application.provided.ReplacePolicyAssignmentCommand
import com.paycore.master.merchant.application.provided.ReplaceTransactionLimitPolicyCommand
import com.paycore.master.merchant.application.provided.ResumeCardRouteActivationCommand
import com.paycore.master.merchant.application.provided.SuspendCardRouteActivationCommand
import com.paycore.master.merchant.application.provided.TerminateCardRouteActivationCommand
import com.paycore.master.merchant.application.required.AdminRequestStore
import com.paycore.master.merchant.application.required.CardRouteActivationRepository
import com.paycore.master.merchant.application.required.ContractRepository
import com.paycore.master.merchant.application.required.CurrencyEnablementRepository
import com.paycore.master.merchant.application.required.MasterChangeLog
import com.paycore.master.merchant.application.required.MasterClock
import com.paycore.master.merchant.application.required.MasterIdGenerator
import com.paycore.master.merchant.application.required.MasterOperationalMetrics
import com.paycore.master.merchant.application.required.MerchantAccountRepository
import com.paycore.master.merchant.application.required.MerchantRepository
import com.paycore.master.merchant.application.required.OutboxStore
import com.paycore.master.merchant.application.required.PaymentMethodActivationRepository
import com.paycore.master.merchant.application.required.PolicyAssignmentRepository
import com.paycore.master.merchant.application.required.PolicyVersionRepository
import com.paycore.master.merchant.application.required.TransactionLimitPolicyRepository
import com.paycore.master.merchant.application.required.Versioned
import com.paycore.master.merchant.domain.ActivationTransition
import com.paycore.master.merchant.domain.CardRouteActivation
import com.paycore.master.merchant.domain.CardRouteActivationId
import com.paycore.master.merchant.domain.CurrencyCode
import com.paycore.master.merchant.domain.CurrencyUnit
import com.paycore.master.merchant.domain.EffectivePeriod
import com.paycore.master.merchant.domain.LimitScope
import com.paycore.master.merchant.domain.LimitTarget
import com.paycore.master.merchant.domain.LimitValue
import com.paycore.master.merchant.domain.MasterRejection
import com.paycore.master.merchant.domain.MasterRuleViolation
import com.paycore.master.merchant.domain.Merchant
import com.paycore.master.merchant.domain.MerchantAccount
import com.paycore.master.merchant.domain.MerchantAccountId
import com.paycore.master.merchant.domain.MerchantId
import com.paycore.master.merchant.domain.PolicyAssignment
import com.paycore.master.merchant.domain.PolicyAssignmentId
import com.paycore.master.merchant.domain.PolicyVersion
import com.paycore.master.merchant.domain.PolicyVersionId
import com.paycore.master.merchant.domain.TransactionLimitPolicy
import com.paycore.master.merchant.domain.TransactionLimitPolicyId
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 승인 시작 조건 관리 명령(MASTER-SNAPSHOT-002). [MerchantEntitlementService]와 같은 순서: 잠금(admin_request → merchant → merchant_account →
 * 대상 행) → [AdminCommandExecutor.Work.lockedNow] → domain 판정 → beginWrites → 업무 행·이력 → revision +1 → Snapshot 발행 → 요청 결과.
 * 잠금 전 읽기는 대상의 불변 소속(정책·Assignment·개통의 Merchant/MID, 계약의 Merchant)만 쓴다.
 *
 * 기관 계약계정([com.paycore.master.merchant.domain.InstitutionMerchantRef])은 내부 식별자 등급(D-MS2-005): 통합 이벤트(Outbox) payload에는 싣지 않고
 * 감사 이력(master_change_history, 내부 DB)에만 남긴다. 로그에는 쓰지 않는다.
 */
@Service
@Suppress("LongParameterList", "TooManyFunctions")
class MerchantAuthorizationTermsService(
    transactionManager: PlatformTransactionManager,
    requests: AdminRequestStore,
    changeLog: MasterChangeLog,
    outbox: OutboxStore,
    ids: MasterIdGenerator,
    clock: MasterClock,
    metrics: MasterOperationalMetrics,
    publisher: EntitlementSnapshotPublisher,
    private val merchants: MerchantRepository,
    private val accounts: MerchantAccountRepository,
    private val contracts: ContractRepository,
    private val currencies: CurrencyEnablementRepository,
    private val activations: PaymentMethodActivationRepository,
    private val limits: TransactionLimitPolicyRepository,
    private val versions: PolicyVersionRepository,
    private val policyAssignments: PolicyAssignmentRepository,
    private val routes: CardRouteActivationRepository,
) : MerchantAuthorizationTermsUseCase {

    private val executor = AdminCommandExecutor(transactionManager, requests, changeLog, outbox, ids, clock, metrics, publisher)

    // ── 거래 한도 정책(D-AUTH-006, D-MS2-001·002) ─────────────────────────────────────────────

    override fun registerTransactionLimitPolicy(command: RegisterTransactionLimitPolicyCommand): AdminCommandResult =
        executor.execute(
            AdminOperation.REGISTER_TRANSACTION_LIMIT_POLICY, command.request,
            targetFields(command.target) + command.currency.value + limitFields(command.perTransaction) + limitFields(command.daily) +
                periodFields(command.effectivePeriod) + command.reason.value,
        ) { work ->
            val period = EffectivePeriod.ofMillis(command.effectivePeriod.from, command.effectivePeriod.to)
            val (merchant, target) = lockTarget(command.target)
            work.lockedNow()
            val unit = unitOf(command.currency)
            val policy = TransactionLimitPolicy.register(
                TransactionLimitPolicyId(work.newId(TransactionLimitPolicyId.PREFIX)), target(), unit, command.perTransaction, command.daily, period,
                limits.listByMerchant(merchant.value.id), command.reason, work.now, command.request.actor,
            )
            merchant.value.recordEntitlementChange()
            work.beginWrites()
            limits.insert(policy, work.requestRecordId)
            merchants.update(merchant.value, merchant.version)
            work.record(AGG_LIMIT, policy.id.value, 0, "TransactionLimitPolicyRegistered", limitPayload(policy, merchant.value))
            work.publishEntitlement(merchant.value)
            policy.id.value
        }

    override fun endTransactionLimitPolicy(command: EndTransactionLimitPolicyCommand): AdminCommandResult =
        executor.execute(
            AdminOperation.END_TRANSACTION_LIMIT_POLICY, command.request, listOf(command.policyId.value, millis(command.effectiveTo)),
        ) { work ->
            val effectiveTo = command.effectiveTo.truncatedTo(ChronoUnit.MILLIS)
            val merchantId = limits.find(command.policyId)?.value?.merchantId ?: throw MasterRuleViolation(MasterRejection.LIMIT_POLICY_NOT_FOUND)
            val merchant = lockMerchant(merchantId)
            val locked = checkNotNull(limits.lock(command.policyId)) { "limit policy must exist" }
            work.lockedNow()
            locked.value.end(effectiveTo, work.now, command.request.actor)
            merchant.value.recordEntitlementChange()
            work.beginWrites()
            limits.update(locked.value, locked.version)
            merchants.update(merchant.value, merchant.version)
            work.record(AGG_LIMIT, locked.value.id.value, locked.version + 1, "TransactionLimitPolicyEnded", limitPayload(locked.value, merchant.value))
            work.publishEntitlement(merchant.value)
            locked.value.id.value
        }

    override fun replaceTransactionLimitPolicy(command: ReplaceTransactionLimitPolicyCommand): AdminCommandResult =
        executor.execute(
            AdminOperation.REPLACE_TRANSACTION_LIMIT_POLICY, command.request,
            listOf(command.policyId.value) + limitFields(command.perTransaction) + limitFields(command.daily) + millis(command.switchAt) +
                command.reason.value,
        ) { work ->
            val switchAt = command.switchAt.truncatedTo(ChronoUnit.MILLIS)
            val merchantId = limits.find(command.policyId)?.value?.merchantId ?: throw MasterRuleViolation(MasterRejection.LIMIT_POLICY_NOT_FOUND)
            val merchant = lockMerchant(merchantId)
            val old = checkNotNull(limits.lock(command.policyId)) { "limit policy must exist" }
            val target = when (val scope = old.value.scope) {
                is LimitScope.CsaDefault -> {
                    val contract = contracts.find(checkNotNull(limits.contractOfAgreement(scope.agreementId)) { "agreement must exist" }) ?: error("contract must exist")
                    LimitTarget.csaDefault(merchant.value, contract, scope.agreementId)
                }
                is LimitScope.MidOverride -> LimitTarget.midOverride(merchant.value, checkNotNull(accounts.lock(scope.merchantAccountId)) { "MID must exist" })
            }
            work.lockedNow()
            val existing = limits.listByMerchant(merchantId).map { if (it.id == old.value.id) old.value else it }
            val replacement = TransactionLimitPolicy.replace(
                old.value, TransactionLimitPolicyId(work.newId(TransactionLimitPolicyId.PREFIX)), target, unitOf(old.value.currency),
                command.perTransaction, command.daily, switchAt, existing, command.reason, work.now, command.request.actor,
            )
            merchant.value.recordEntitlementChange()
            work.beginWrites()
            limits.update(old.value, old.version)
            limits.insert(replacement, work.requestRecordId)
            merchants.update(merchant.value, merchant.version)
            work.record(AGG_LIMIT, old.value.id.value, old.version + 1, "TransactionLimitPolicyEnded", limitPayload(old.value, merchant.value))
            work.record(AGG_LIMIT, replacement.id.value, 0, "TransactionLimitPolicyRegistered", limitPayload(replacement, merchant.value))
            work.publishEntitlement(merchant.value)
            replacement.id.value
        }

    // ── 정책 버전 등록부(D-MS2-003) ─────────────────────────────────────────────────────────

    override fun registerPolicyVersion(command: RegisterPolicyVersionCommand): AdminCommandResult =
        executor.execute(
            AdminOperation.REGISTER_POLICY_VERSION, command.request,
            listOf(command.kind.name, command.code.value, command.contentStatus.name, command.contentHash, command.reason.value),
        ) { work ->
            work.lockedNow()
            val version = PolicyVersion.register(
                PolicyVersionId(work.newId(PolicyVersionId.PREFIX)), command.kind, command.code, versions.listByCode(command.kind, command.code),
                command.contentStatus, command.contentHash, command.reason, work.now, command.request.actor,
            )
            work.beginWrites()
            // 전역 등록부: Merchant 권리를 바꾸지 않는다(revision·Snapshot 없음). 같은 (종류, 코드) 동시 등록은 UNIQUE → 재시도에서 다음 번호.
            versions.insert(version, work.requestRecordId)
            work.record(
                AGG_POLICY_VERSION, version.id.value, 0, "PolicyVersionRegistered",
                mapOf(
                    "policyVersionId" to version.id.value, "kind" to version.kind.name, "code" to version.code.value, "versionNo" to version.versionNo,
                    "contentStatus" to version.contentStatus.name, "contentHash" to version.contentHash, "reason" to version.reason.value,
                ),
            )
            version.id.value
        }

    // ── 정책 Assignment(D-MD-018) ──────────────────────────────────────────────────────────

    override fun assignPolicy(command: AssignPolicyCommand): AdminCommandResult =
        executor.execute(
            AdminOperation.ASSIGN_POLICY, command.request,
            listOf(command.merchantAccountId.value, command.agreementId.value, command.currency.value, command.policyVersionId.value) +
                periodFields(command.effectivePeriod) + command.reason.value,
        ) { work ->
            val period = EffectivePeriod.ofMillis(command.effectivePeriod.from, command.effectivePeriod.to)
            val (merchant, account) = lockAccount(command.merchantAccountId)
            work.lockedNow()
            val unit = unitOf(command.currency)
            val version = versions.find(command.policyVersionId) ?: throw MasterRuleViolation(MasterRejection.POLICY_VERSION_NOT_FOUND)
            val assignment = PolicyAssignment.assign(
                PolicyAssignmentId(work.newId(PolicyAssignmentId.PREFIX)), account, command.agreementId, unit, version, period,
                policyAssignments.listByAccount(account.id), command.reason, work.now, command.request.actor,
            )
            merchant.value.recordEntitlementChange()
            work.beginWrites()
            policyAssignments.insert(assignment, work.requestRecordId)
            merchants.update(merchant.value, merchant.version)
            work.record(AGG_ASSIGNMENT, assignment.id.value, 0, "PolicyAssigned", assignmentPayload(assignment, merchant.value))
            work.publishEntitlement(merchant.value)
            assignment.id.value
        }

    override fun endPolicyAssignment(command: EndPolicyAssignmentCommand): AdminCommandResult =
        executor.execute(
            AdminOperation.END_POLICY_ASSIGNMENT, command.request, listOf(command.assignmentId.value, millis(command.effectiveTo)),
        ) { work ->
            val effectiveTo = command.effectiveTo.truncatedTo(ChronoUnit.MILLIS)
            val mid = policyAssignments.find(command.assignmentId)?.value?.merchantAccountId
                ?: throw MasterRuleViolation(MasterRejection.POLICY_ASSIGNMENT_NOT_FOUND)
            val (merchant, _) = lockAccount(mid)
            val locked = checkNotNull(policyAssignments.lock(command.assignmentId)) { "assignment must exist" }
            work.lockedNow()
            locked.value.end(effectiveTo, work.now, command.request.actor)
            merchant.value.recordEntitlementChange()
            work.beginWrites()
            policyAssignments.update(locked.value, locked.version)
            merchants.update(merchant.value, merchant.version)
            work.record(AGG_ASSIGNMENT, locked.value.id.value, locked.version + 1, "PolicyAssignmentEnded", assignmentPayload(locked.value, merchant.value))
            work.publishEntitlement(merchant.value)
            locked.value.id.value
        }

    override fun replacePolicyAssignment(command: ReplacePolicyAssignmentCommand): AdminCommandResult =
        executor.execute(
            AdminOperation.REPLACE_POLICY_ASSIGNMENT, command.request,
            listOf(command.assignmentId.value, command.policyVersionId.value, millis(command.switchAt), command.reason.value),
        ) { work ->
            val switchAt = command.switchAt.truncatedTo(ChronoUnit.MILLIS)
            val mid = policyAssignments.find(command.assignmentId)?.value?.merchantAccountId
                ?: throw MasterRuleViolation(MasterRejection.POLICY_ASSIGNMENT_NOT_FOUND)
            val (merchant, account) = lockAccount(mid)
            val old = checkNotNull(policyAssignments.lock(command.assignmentId)) { "assignment must exist" }
            work.lockedNow()
            val version = versions.find(command.policyVersionId) ?: throw MasterRuleViolation(MasterRejection.POLICY_VERSION_NOT_FOUND)
            val existing = policyAssignments.listByAccount(mid).map { if (it.id == old.value.id) old.value else it }
            val replacement = PolicyAssignment.replace(
                old.value, PolicyAssignmentId(work.newId(PolicyAssignmentId.PREFIX)), account, version, switchAt, existing, unitOf(old.value.currency),
                command.reason, work.now, command.request.actor,
            )
            merchant.value.recordEntitlementChange()
            work.beginWrites()
            policyAssignments.update(old.value, old.version)
            policyAssignments.insert(replacement, work.requestRecordId)
            merchants.update(merchant.value, merchant.version)
            work.record(AGG_ASSIGNMENT, old.value.id.value, old.version + 1, "PolicyAssignmentEnded", assignmentPayload(old.value, merchant.value))
            work.record(AGG_ASSIGNMENT, replacement.id.value, 0, "PolicyAssigned", assignmentPayload(replacement, merchant.value))
            work.publishEntitlement(merchant.value)
            replacement.id.value
        }

    // ── 카드사(매입사)/Route 개통(D-MD-017, D-MS2-004·005) ───────────────────────────────────

    override fun registerCardRouteActivation(command: RegisterCardRouteActivationCommand): AdminCommandResult =
        executor.execute(
            AdminOperation.REGISTER_CARD_ROUTE_ACTIVATION, command.request,
            listOf(
                command.merchantAccountId.value, command.paymentMethod.name, command.acquirerCode.value, command.routeRef.value,
                command.institutionMerchantRef.value,
            ),
        ) { work ->
            val (merchant, account) = lockAccount(command.merchantAccountId)
            work.lockedNow()
            val (route, transition) = CardRouteActivation.register(
                CardRouteActivationId(work.newId(CardRouteActivationId.PREFIX)), account, activations.findOpen(account.id, command.paymentMethod)?.value,
                command.acquirerCode, command.routeRef, command.institutionMerchantRef, routes.listByAccount(account.id), work.now, command.request.actor,
            )
            merchant.value.recordEntitlementChange()
            work.beginWrites()
            routes.insert(route, transition, work.requestRecordId)
            merchants.update(merchant.value, merchant.version)
            recordRoute(work, route, 0, transition, "CardRouteActivationRegistered", merchant.value)
            work.publishEntitlement(merchant.value)
            route.id.value
        }

    override fun approveCardRouteActivation(command: ApproveCardRouteActivationCommand): AdminCommandResult =
        routeTransition(AdminOperation.APPROVE_CARD_ROUTE_ACTIVATION, command.request, command.activationId, command.evidenceRef.value, "CardRouteActivationApproved") {
            ctx, work -> ctx.route.value.approve(ctx.account, command.evidenceRef, work.now, command.request.actor)
        }

    override fun suspendCardRouteActivation(command: SuspendCardRouteActivationCommand): AdminCommandResult =
        routeTransition(AdminOperation.SUSPEND_CARD_ROUTE_ACTIVATION, command.request, command.activationId, command.reason.value, "CardRouteActivationSuspended") {
            ctx, work -> ctx.route.value.suspend(command.reason, work.now, command.request.actor)
        }

    override fun resumeCardRouteActivation(command: ResumeCardRouteActivationCommand): AdminCommandResult =
        routeTransition(AdminOperation.RESUME_CARD_ROUTE_ACTIVATION, command.request, command.activationId, command.evidenceRef.value, "CardRouteActivationResumed") {
            ctx, work -> ctx.route.value.resume(ctx.account, command.evidenceRef, routes.evidenceHistory(ctx.route.value.id), work.now, command.request.actor)
        }

    override fun terminateCardRouteActivation(command: TerminateCardRouteActivationCommand): AdminCommandResult =
        routeTransition(AdminOperation.TERMINATE_CARD_ROUTE_ACTIVATION, command.request, command.activationId, command.reason.value, "CardRouteActivationTerminated") {
            ctx, work -> ctx.route.value.terminate(command.reason, work.now, command.request.actor)
        }

    private data class RouteContext(val merchant: Versioned<Merchant>, val account: MerchantAccount, val route: Versioned<CardRouteActivation>)

    private fun routeTransition(
        operation: AdminOperation,
        request: com.paycore.master.merchant.application.provided.AdminRequest,
        id: CardRouteActivationId,
        detail: String,
        eventType: String,
        change: (RouteContext, AdminCommandExecutor.Work) -> ActivationTransition,
    ): AdminCommandResult = executor.execute(operation, request, listOf(id.value, detail)) { work ->
        val mid = routes.find(id)?.value?.merchantAccountId ?: throw MasterRuleViolation(MasterRejection.CARD_ROUTE_NOT_FOUND)
        val (merchant, account) = lockAccount(mid)
        val route = checkNotNull(routes.lock(id)) { "card route must exist" }
        work.lockedNow()
        val ctx = RouteContext(merchant, account, route)
        val transition = change(ctx, work)
        merchant.value.recordEntitlementChange()
        work.beginWrites()
        routes.update(route.value, route.version, transition, work.requestRecordId)
        merchants.update(merchant.value, merchant.version)
        recordRoute(work, route.value, route.version + 1, transition, eventType, merchant.value)
        work.publishEntitlement(merchant.value)
        route.value.id.value
    }

    // ── 잠금·공통 ─────────────────────────────────────────────────────────────────────────

    private fun lockMerchant(merchantId: MerchantId): Versioned<Merchant> = merchants.lock(merchantId) ?: error("merchant must exist")

    private fun lockAccount(mid: MerchantAccountId): Pair<Versioned<Merchant>, MerchantAccount> {
        val merchantId = accounts.find(mid)?.merchantId ?: throw MasterRuleViolation(MasterRejection.MERCHANT_ACCOUNT_NOT_FOUND)
        val merchant = lockMerchant(merchantId)
        val account = accounts.lock(mid) ?: error("account must exist")
        return merchant to account
    }

    /** 대상의 Merchant를 잠그고, 잠금 뒤 다시 읽은 값으로 대상 확인([LimitTarget])을 만드는 함수를 돌려준다(판정은 lockedNow 뒤). */
    private fun lockTarget(input: LimitTargetInput): Pair<Versioned<Merchant>, () -> LimitTarget> = when (input) {
        is LimitTargetInput.CsaDefault -> {
            val merchantId = contracts.find(input.contractId)?.merchantId ?: throw MasterRuleViolation(MasterRejection.CONTRACT_NOT_FOUND)
            val merchant = lockMerchant(merchantId)
            merchant to {
                val contract = contracts.find(input.contractId) ?: throw MasterRuleViolation(MasterRejection.CONTRACT_NOT_FOUND)
                LimitTarget.csaDefault(merchant.value, contract, input.agreementId)
            }
        }
        is LimitTargetInput.MidOverride -> {
            val (merchant, account) = lockAccount(input.merchantAccountId)
            merchant to { LimitTarget.midOverride(merchant.value, account) }
        }
    }

    private fun unitOf(code: CurrencyCode): CurrencyUnit = currencies.findUnit(code) ?: throw MasterRuleViolation(MasterRejection.CURRENCY_UNKNOWN)

    private fun recordRoute(work: AdminCommandExecutor.Work, route: CardRouteActivation, version: Long, t: ActivationTransition, eventType: String, merchant: Merchant) {
        val payload = mapOf(
            "activationId" to route.id.value, "merchantAccountId" to route.merchantAccountId.value, "paymentMethod" to route.paymentMethod.name,
            "acquirerCode" to route.acquirerCode.value, "routeRef" to route.routeRef.value, "fromStatus" to t.from?.name, "status" to t.to.name,
            "evidenceRef" to t.evidenceRef?.value, "reason" to t.reason?.value, "entitlementRevision" to merchant.entitlementRevision,
        )
        // D-MS2-005: 기관 계약계정 원문은 통합 이벤트에 싣지 않고 감사 이력에만 남긴다.
        work.record(AGG_ROUTE, route.id.value, version, eventType, payload, payload + ("institutionMerchantRef" to route.institutionMerchantRef.value))
    }

    private fun limitPayload(p: TransactionLimitPolicy, merchant: Merchant): Map<String, Any?> = mapOf(
        "policyId" to p.id.value, "merchantId" to p.merchantId.value, "scope" to p.scope.kind.name,
        "agreementId" to (p.scope as? LimitScope.CsaDefault)?.agreementId?.value,
        "merchantAccountId" to (p.scope as? LimitScope.MidOverride)?.merchantAccountId?.value, "currency" to p.currency.value,
        "perTransaction" to limitText(p.perTransaction), "daily" to limitText(p.daily), "effectiveFrom" to p.period.from, "effectiveTo" to p.period.to,
        "reason" to p.reason.value, "entitlementRevision" to merchant.entitlementRevision,
    )

    private fun assignmentPayload(a: PolicyAssignment, merchant: Merchant): Map<String, Any?> = mapOf(
        "assignmentId" to a.id.value, "merchantAccountId" to a.merchantAccountId.value, "agreementId" to a.agreementId.value,
        "currency" to a.currency.value, "kind" to a.kind.name, "policyVersionId" to a.policyVersionId.value, "effectiveFrom" to a.period.from,
        "effectiveTo" to a.period.to, "reason" to a.reason.value, "entitlementRevision" to merchant.entitlementRevision,
    )

    private fun targetFields(t: LimitTargetInput): List<String?> = when (t) {
        is LimitTargetInput.CsaDefault -> listOf("CSA", t.contractId.value, t.agreementId.value)
        is LimitTargetInput.MidOverride -> listOf("MID", null, t.merchantAccountId.value)
    }

    private fun limitFields(v: LimitValue): List<String?> = listOf(limitText(v))

    private fun limitText(v: LimitValue): String = when (v) {
        LimitValue.Unlimited -> "UNLIMITED"
        is LimitValue.Limited -> "LIMITED:${v.amountMinor}"
    }

    private fun periodFields(period: EffectivePeriod): List<String?> = listOf(millis(period.from), period.to?.let(::millis))

    private fun millis(at: Instant): String = at.truncatedTo(ChronoUnit.MILLIS).toString()

    private companion object {
        const val AGG_LIMIT = "TransactionLimitPolicy"
        const val AGG_POLICY_VERSION = "PolicyVersion"
        const val AGG_ASSIGNMENT = "PolicyAssignment"
        const val AGG_ROUTE = "CardRouteActivation"
    }
}

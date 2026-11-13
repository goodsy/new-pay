package com.paycore.admin.approval.application

import com.paycore.admin.account.application.provided.AdminOwnedCommandExecutor
import com.paycore.admin.account.application.provided.AdminRequestContext
import com.paycore.admin.account.application.provided.AdminRetryLaterException
import com.paycore.admin.account.application.provided.AdminSessionQuery
import com.paycore.admin.account.application.provided.AuthenticationFailureRecorder
import com.paycore.admin.account.application.provided.AuthenticationStep
import com.paycore.admin.account.application.provided.ParticipantView
import com.paycore.admin.account.application.provided.StepUpResult
import com.paycore.admin.approval.application.provided.ApprovalOutcome
import com.paycore.admin.approval.application.provided.DecideApproval
import com.paycore.admin.approval.application.provided.MerchantOpsApprovalUseCase
import com.paycore.admin.approval.application.provided.RequestMerchantContractApproval
import com.paycore.admin.approval.application.provided.RequestMerchantFirstMid
import com.paycore.admin.approval.application.provided.RequestMerchantPaymentMethodActivation
import com.paycore.admin.approval.application.provided.RequestMerchantSiteReviewApproval
import com.paycore.admin.approval.application.required.ApprovalEnvironment
import com.paycore.admin.approval.application.required.ApprovalMetrics
import com.paycore.admin.approval.application.required.ApprovalPersistenceFailure
import com.paycore.admin.approval.application.required.ApprovalRequestRepository
import com.paycore.admin.approval.application.required.MerchantOpsCommandStore
import com.paycore.admin.approval.application.required.OwnerExecutionRepository
import com.paycore.admin.approval.application.required.OwnerOpsMetrics
import com.paycore.admin.approval.application.required.ReconciliationFindingRecord
import com.paycore.admin.approval.application.required.ReconciliationFindingStore
import com.paycore.admin.approval.application.required.VersionedApproval
import com.paycore.admin.approval.domain.ApprovalDecision
import com.paycore.admin.approval.domain.ApprovalKind
import com.paycore.admin.approval.domain.ApprovalParticipants
import com.paycore.admin.approval.domain.ApprovalRequest
import com.paycore.admin.approval.domain.ApprovalStatus
import com.paycore.admin.approval.domain.ApprovalTarget
import com.paycore.admin.approval.domain.MerchantOpsCommandKind
import com.paycore.admin.approval.domain.MerchantOpsInputRules
import com.paycore.admin.approval.domain.MerchantOpsRequestSnapshot
import com.paycore.admin.approval.domain.OwnerExecution
import com.paycore.admin.approval.domain.PinnedActivationApprovalTarget
import com.paycore.admin.approval.domain.PinnedContractApprovalTarget
import com.paycore.admin.approval.domain.PinnedFirstMidTarget
import com.paycore.admin.approval.domain.PinnedMerchantTarget
import com.paycore.admin.approval.domain.PinnedSiteReviewApprovalTarget
import com.paycore.admin.approval.domain.ReconciliationFindingKind
import com.paycore.admin.audit.application.provided.AdminAuditRecorder
import com.paycore.admin.audit.application.provided.AuditRecord
import com.paycore.admin.merchantops.application.provided.ActivationTarget
import com.paycore.admin.merchantops.application.provided.ContractRevisionTarget
import com.paycore.admin.merchantops.application.provided.FirstMidTarget
import com.paycore.admin.merchantops.application.provided.FirstMidTargetResult
import com.paycore.admin.merchantops.application.provided.MerchantApprovalClient
import com.paycore.admin.merchantops.application.provided.MerchantApprovalContract
import com.paycore.admin.merchantops.application.provided.MerchantApprovalOperation
import com.paycore.admin.merchantops.application.provided.MerchantApprovalPolicy
import com.paycore.admin.merchantops.application.provided.MerchantApprovalResult
import com.paycore.admin.merchantops.application.provided.MerchantTargetResult
import com.paycore.admin.merchantops.application.provided.RevisionTargetResult
import com.paycore.admin.merchantops.application.provided.SiteReviewTarget
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionStatus
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 가맹점 계약 승인 2인 승인(MERCHANT-OPS-002 B2-2 §9.5 — 재매입 [AcquiringOpsApprovalService]와 같은 OWNER_SERVICE 실행 모델). **MASTER 호출은 모두 DB TX 밖**
 * (요청 전·승인 A 상세 조회 — 조회 permit, C 실행 POST — 쓰기 permit: gateway가 TX 안이면 거부). 로컬 TX는 재시도하지 않는다(TOTP 재사용 없음).
 * - 요청(OPERATOR): 입력 규칙(근거 참조 필수 — D-MOPS-008 ①) → 세션 역할(비권위) → MASTER 상세(미승인·끝나지 않은 Revision — advisory) → TX(승인 행 INSERT(`MERCHANT:` 미해결 하나 — C-20)
 *   → 요청자 잠금·재검사 → 명령 행 INSERT(pinned·근거) → 감사).
 * - 승인(APPROVER + 직전 TOTP): A 상세 재조회(실패 503 — 상태·TOTP 불변) → TX1(잠금·판정표·참여자·**승인자 ≠ pinned registeredBy**(403 `APPROVER_IS_REGISTRANT` — TOTP 전, D-MOPS-008 ③)·
 *   TOTP 소비·요청자 재검사·pinned vs 현재(다르면 INVALIDATED(TARGET_CHANGED) — MASTER 미호출)·본문·`mop1` 지문·EXECUTING) → C 저장 바이트 POST → TX2([OwnerOutcomeApplier] — 회복 job과 같은 규칙).
 * - 회복([recover] — PAYMENT·FINANCIAL과 같은 순서, C-18·C-28): 결정 조회 → 기한 + 여유 뒤 기한 확인만(실행 POST 없음) → 참여자 재검사 → 무결성 플래그면 조회만 → 같은 저장 바이트 재전송.
 *   기한 뒤에도 확정할 수 없으면(NONE + 기한 확인 400·413·409) `OWNER_UNRESOLVABLE` finding 1회·경보, 자동 종결 없음.
 * 감사 target_ref = merchantId. 근거 참조·본문·MASTER 키 원문은 감사·로그·지표에 없다(키 = `mop1.<approvalId>`는 승인 ID에서 유도되지만 원문을 쓰지 않는다).
 * Kafka/Redis 없음 — 미해결 하나는 ADMIN UNIQUE, 최초 실행은 MASTER `admin_request` UNIQUE·근거 PK·업무 잠금이 지킨다.
 *
 * B3-1(§9.16): 결제수단 개통 승인([ApprovalKind.MERCHANT_PAYMENT_METHOD_ACTIVATION] — `ApprovePaymentMethodActivation`, 소유 참조 = pinned `act_…`)·Site 심사 승인
 * ([ApprovalKind.MERCHANT_SITE_REVIEW_APPROVAL] — `DecideSiteReview`, 소유 참조 `site_…#n`의 Site 부분 = pinned Site)이 같은 틀(요청 → A → TX1 → C → TX2·회복)을 쓴다.
 * **D-MOPS-009 ③(B3-1F — §9.27, Q-MOPS-24 답)**: 두 종류도 계약 승인과 같은 위치(참여자 잠금·pinned 종류 확인 뒤, TOTP 소비 **전**)에서 승인자 ≠ pinned `registeredBy`
 * (개통 등록자·Site 등록자 — SUSPENDED 재승인 포함, D-MOPS-010 ②)를 검사한다([registrantOf] — 403 `APPROVER_IS_REGISTRANT`, ADMIN 응답 code는 하나 — C-50). 첫 MID 발급은 대상 아님.
 */
@Service
class MerchantOpsApprovalService(
    transactionManager: PlatformTransactionManager,
    private val repository: ApprovalRequestRepository,
    private val commands: MerchantOpsCommandStore,
    private val executions: OwnerExecutionRepository,
    private val findings: ReconciliationFindingStore,
    private val executor: AdminOwnedCommandExecutor,
    private val failures: AuthenticationFailureRecorder,
    private val sessions: AdminSessionQuery,
    private val recorder: AdminAuditRecorder,
    private val environment: ApprovalEnvironment,
    private val metrics: ApprovalMetrics,
    private val opsMetrics: OwnerOpsMetrics,
    private val client: MerchantApprovalClient,
    private val contract: MerchantApprovalContract,
    private val policy: MerchantApprovalPolicy,
    private val applier: OwnerOutcomeApplier,
) : MerchantOpsApprovalUseCase {

    private val transactions = LocalTransactions(transactionManager, maxAttempts = 1)
    /** 계약 승인 요청 경로의 종류(지표·감사 detail 기본값). B2-3 첫 MID는 [MID_KIND]. */
    private val kind = ApprovalKind.MERCHANT_CONTRACT_APPROVAL

    /** 승인 종류 → MASTER operation(B2-2 계약 승인, B2-3 첫 Default MID 발급). 다른 종류는 이 서비스 대상이 아니다. */
    private fun operationOf(kind: ApprovalKind): MerchantApprovalOperation = when (kind) {
        ApprovalKind.MERCHANT_CONTRACT_APPROVAL -> MerchantApprovalOperation.APPROVE_CONTRACT_REVISION
        ApprovalKind.MERCHANT_FIRST_MID_ISSUANCE -> MerchantApprovalOperation.ISSUE_FIRST_DEFAULT_MERCHANT_ACCOUNT
        ApprovalKind.MERCHANT_PAYMENT_METHOD_ACTIVATION -> MerchantApprovalOperation.APPROVE_PAYMENT_METHOD_ACTIVATION
        ApprovalKind.MERCHANT_SITE_REVIEW_APPROVAL -> MerchantApprovalOperation.DECIDE_SITE_REVIEW
        else -> throw IllegalArgumentException("not a merchant approval kind: $kind")
    }

    // ------------------------------------------------------------------ request

    override fun requestContractApproval(command: RequestMerchantContractApproval): ApprovalOutcome = observed(REQUEST) { requestStep(command) }

    private fun requestStep(command: RequestMerchantContractApproval): ApprovalOutcome {
        MerchantOpsInputRules.contractApprovalProblem(command.merchantId, command.contractId, command.revisionNo, command.evidenceRef)?.let { (field, reason) ->
            return ApprovalOutcome.Rejected(INVALID_INPUT, field, reason)
        }
        if (!holdsNow(command.requesterActorId, ApprovalParticipants.requesterRole(kind))) return ApprovalOutcome.Denied
        val target = when (val found = client.revisionTarget(command.merchantId, command.contractId, command.revisionNo.toInt())) {
            is RevisionTargetResult.Found -> found.target
            RevisionTargetResult.MerchantNotFound -> return refuseAudited(command, ApprovalOutcome.Rejected(MERCHANT_NOT_FOUND), MERCHANT_NOT_FOUND)
            RevisionTargetResult.RevisionNotFound -> return refuseAudited(command, ApprovalOutcome.Rejected(NOT_ELIGIBLE), "REVISION_NOT_FOUND")
            is RevisionTargetResult.Unavailable -> return ApprovalOutcome.Unavailable(MASTER_UNAVAILABLE)
        }
        // advisory(비권위 — MASTER가 실행 TX에서 REVISION_ALREADY_APPROVED·REVISION_ENDED로 다시 판정한다).
        if (target.approved) return refuseAudited(command, ApprovalOutcome.Rejected(NOT_ELIGIBLE), "REVISION_ALREADY_APPROVED")
        if (target.endedAt(now())) return refuseAudited(command, ApprovalOutcome.Rejected(NOT_ELIGIBLE), "REVISION_ENDED")
        val pinned = pinnedOf(target) ?: return ApprovalOutcome.Unavailable(MASTER_UNAVAILABLE)
        return guarded(command.context, command.requesterActorId, command.merchantId) {
            transactions.execute({ false }) {
                val now = now()
                val request = ApprovalRequest.create(
                    environment.newApprovalId(), kind, ApprovalTarget.Merchant(command.merchantId), pinned.toSnapshotJson(), null, null, command.requesterActorId,
                    snapshotName(command.requesterActorId), now, environment.validity,
                )
                try {
                    repository.insert(request)
                } catch (_: ApprovalPersistenceFailure.DuplicateUnresolved) {
                    throw Refusal(ApprovalOutcome.Rejected(DUPLICATE_UNRESOLVED), DUPLICATE_UNRESOLVED, command.merchantId)
                }
                val requester = executor.lockParticipants(listOf(command.requesterActorId))[command.requesterActorId]
                if (requester == null || !requester.holds(ApprovalParticipants.requesterRole(kind))) throw Refusal(ApprovalOutcome.Denied, REQUESTER_NOT_ELIGIBLE, command.merchantId)
                if (requester.sessionGeneration != command.sessionGeneration) throw Refusal(ApprovalOutcome.SessionReplaced, null, null)
                commands.insert(MerchantOpsRequestSnapshot(request.id, MerchantOpsCommandKind.CONTRACT_APPROVAL, command.merchantId, pinned, command.evidenceRef, now))
                audit(now, requester, "APPROVAL_REQUESTED", "SUCCESS", command.context, request, null)
                ApprovalOutcome.Created(request.id)
            }
        }
    }

    /** MASTER 상세 한 줄 → 고정 대상. MASTER 값이 Domain 형식에 맞지 않으면 null(해석 불가 — 요청·승인 모두 503). */
    private fun pinnedOf(t: ContractRevisionTarget): PinnedContractApprovalTarget? = runCatching {
        PinnedContractApprovalTarget(t.merchantId, t.merchantName, t.contractId, t.revisionNo, t.scopeKind, t.scopeSiteIds, t.effectiveFrom, t.effectiveTo, t.registeredBy, t.approved)
    }.getOrNull()

    // ------------------------------------------------------------------ request (B2-3 첫 Default MID 발급)

    /**
     * 첫 MID 발급 요청(§9.5 — OPERATOR, 근거 참조 필수). MASTER 상세로 미리 보기 적격(advisory): MID 0개, 승인되고 지금 적용되는 Revision, 그 계약의 CSA, PRIMARY Site가 Scope 안·ACTIVE.
     * 미해결 키는 계약 승인과 같은 `MERCHANT:<merchantId>`(C-20). 적격의 권위는 MASTER 실행 TX.
     */
    override fun requestFirstMidIssuance(command: RequestMerchantFirstMid): ApprovalOutcome = observed(REQUEST, MID_KIND) { requestMidStep(command) }

    private fun requestMidStep(command: RequestMerchantFirstMid): ApprovalOutcome {
        MerchantOpsInputRules.firstMidProblem(command.merchantId, command.contractId, command.agreementId, command.revisionNo, command.primarySiteId, command.evidenceRef)
            ?.let { (field, reason) -> return ApprovalOutcome.Rejected(INVALID_INPUT, field, reason) }
        if (!holdsNow(command.requesterActorId, ApprovalParticipants.requesterRole(MID_KIND))) return ApprovalOutcome.Denied
        val target = when (val found = client.firstMidTarget(command.merchantId, command.contractId, command.agreementId, command.revisionNo.toInt(), command.primarySiteId)) {
            is FirstMidTargetResult.Found -> found.target
            FirstMidTargetResult.MerchantNotFound -> return refuseMid(command, ApprovalOutcome.Rejected(MERCHANT_NOT_FOUND), MERCHANT_NOT_FOUND)
            is FirstMidTargetResult.Unavailable -> return ApprovalOutcome.Unavailable(MASTER_UNAVAILABLE)
        }
        midIneligibility(target, now())?.let { return refuseMid(command, ApprovalOutcome.Rejected(NOT_ELIGIBLE), it) }
        val pinned = pinnedMidOf(target) ?: return ApprovalOutcome.Unavailable(MASTER_UNAVAILABLE)
        return guarded(command.context, command.requesterActorId, command.merchantId, MID_KIND) {
            transactions.execute({ false }) {
                val now = now()
                val request = ApprovalRequest.create(
                    environment.newApprovalId(), MID_KIND, ApprovalTarget.Merchant(command.merchantId), pinned.toSnapshotJson(), null, null, command.requesterActorId,
                    snapshotName(command.requesterActorId), now, environment.validity,
                )
                try {
                    repository.insert(request)
                } catch (_: ApprovalPersistenceFailure.DuplicateUnresolved) {
                    throw Refusal(ApprovalOutcome.Rejected(DUPLICATE_UNRESOLVED), DUPLICATE_UNRESOLVED, command.merchantId)
                }
                val requester = executor.lockParticipants(listOf(command.requesterActorId))[command.requesterActorId]
                if (requester == null || !requester.holds(ApprovalParticipants.requesterRole(MID_KIND))) throw Refusal(ApprovalOutcome.Denied, REQUESTER_NOT_ELIGIBLE, command.merchantId)
                if (requester.sessionGeneration != command.sessionGeneration) throw Refusal(ApprovalOutcome.SessionReplaced, null, null)
                commands.insert(MerchantOpsRequestSnapshot(request.id, MerchantOpsCommandKind.FIRST_MID_ISSUANCE, command.merchantId, pinned, command.evidenceRef, now))
                audit(now, requester, "APPROVAL_REQUESTED", "SUCCESS", command.context, request, null)
                ApprovalOutcome.Created(request.id)
            }
        }
    }

    /** 미리 보기 부적격 사유(감사 detail code) — 없으면 null. 순서: MID 있음 → Revision 없음·미승인·적용 아님 → CSA → Site. */
    private fun midIneligibility(t: FirstMidTarget, now: Instant): String? {
        val revision = t.revision
        return when {
            t.accountCount > 0 -> "MERCHANT_ACCOUNT_EXISTS"
            revision == null -> "REVISION_NOT_FOUND"
            !revision.approved -> "REVISION_NOT_APPROVED"
            // 적용 Revision 판정(지금 적용 또는 시작 시 적용 — D-MD-011·015)은 MASTER가 한다(REVISION_NOT_APPLICABLE). 여기서는 끝난 Revision만 거른다.
            revision.endedAt(now) -> "REVISION_ENDED"
            !t.agreementExists -> "AGREEMENT_NOT_FOUND"
            t.siteLifecycle == null -> "SITE_NOT_FOUND"
            t.siteLifecycle != "ACTIVE" -> "SITE_NOT_ACTIVE"
            !t.siteInScope -> "SITE_OUTSIDE_CONTRACT_SCOPE"
            else -> null
        }
    }

    private fun pinnedMidOf(t: FirstMidTarget): PinnedFirstMidTarget? = runCatching {
        val r = requireNotNull(t.revision)
        PinnedFirstMidTarget(
            t.merchantId, t.merchantName, t.contractId, t.agreementId, r.revisionNo, r.effectiveFrom, r.effectiveTo, t.primarySiteId, requireNotNull(t.siteChannelType),
            accountCount = t.accountCount, approved = r.approved,
        )
    }.getOrNull()

    private fun refuseMid(command: RequestMerchantFirstMid, outcome: ApprovalOutcome, reason: String): ApprovalOutcome {
        separateAudit(command.context, command.requesterActorId, "APPROVAL_DENIED", "DENIED", command.merchantId, null, "kind=${MID_KIND.name};reason=$reason")
        return outcome
    }

    /** 승인 A 단계 재조회 → 현재 값(요청 pinned와 같은 종류). 없어졌거나 부적격이면 null(TX1에서 TARGET_CHANGED), MASTER 장애·해석 불가는 [Unreadable]. */
    private fun currentOf(pinned: PinnedMerchantTarget): PinnedMerchantTarget? = when (pinned) {
        is PinnedContractApprovalTarget -> when (val found = client.revisionTarget(pinned.merchantId, pinned.contractId, pinned.revisionNo)) {
            is RevisionTargetResult.Found -> pinnedOf(found.target) ?: throw Unreadable()
            RevisionTargetResult.MerchantNotFound, RevisionTargetResult.RevisionNotFound -> null
            is RevisionTargetResult.Unavailable -> throw Unreadable()
        }
        is PinnedFirstMidTarget -> when (val found = client.firstMidTarget(pinned.merchantId, pinned.contractId, pinned.agreementId, pinned.revisionNo, pinned.primarySiteId)) {
            is FirstMidTargetResult.Found -> if (midIneligibility(found.target, now()) != null) null else pinnedMidOf(found.target) ?: throw Unreadable()
            FirstMidTargetResult.MerchantNotFound -> null
            is FirstMidTargetResult.Unavailable -> throw Unreadable()
        }
        is PinnedActivationApprovalTarget -> when (val found = client.activationTarget(pinned.merchantId, pinned.merchantAccountId, pinned.activationId, now())) {
            is MerchantTargetResult.Found -> if (activationIneligibility(found.target) != null) null else pinnedActivationOf(found.target) ?: throw Unreadable()
            MerchantTargetResult.MerchantNotFound -> null
            is MerchantTargetResult.Unavailable -> throw Unreadable()
        }
        is PinnedSiteReviewApprovalTarget -> when (val found = client.siteReviewTarget(pinned.merchantId, pinned.merchantSiteId)) {
            is MerchantTargetResult.Found -> if (siteReviewIneligibility(found.target) != null) null else pinnedSiteReviewOf(found.target, pinned.reasonCode) ?: throw Unreadable()
            MerchantTargetResult.MerchantNotFound -> null
            is MerchantTargetResult.Unavailable -> throw Unreadable()
        }
    }

    private fun sameTarget(pinned: PinnedMerchantTarget, current: PinnedMerchantTarget?): Boolean = when (pinned) {
        is PinnedContractApprovalTarget -> current is PinnedContractApprovalTarget && pinned.sameTargetAs(current)
        is PinnedFirstMidTarget -> current is PinnedFirstMidTarget && pinned.sameTargetAs(current)
        is PinnedActivationApprovalTarget -> current is PinnedActivationApprovalTarget && pinned.sameTargetAs(current)
        is PinnedSiteReviewApprovalTarget -> current is PinnedSiteReviewApprovalTarget && pinned.sameTargetAs(current)
    }

    private class Unreadable : RuntimeException(null, null, false, false)

    // ------------------------------------------------------------------ request (B3-1 결제수단 개통 승인·Site 심사 승인 — §9.16)

    override fun requestPaymentMethodActivation(command: RequestMerchantPaymentMethodActivation): ApprovalOutcome = observed(REQUEST, ACTIVATION_KIND) {
        MerchantOpsInputRules.activationProblem(command.merchantId, command.merchantAccountId, command.activationId, command.evidenceRef)?.let { (field, reason) ->
            return@observed ApprovalOutcome.Rejected(INVALID_INPUT, field, reason)
        }
        if (!holdsNow(command.requesterActorId, ApprovalParticipants.requesterRole(ACTIVATION_KIND))) return@observed ApprovalOutcome.Denied
        val target = when (val found = client.activationTarget(command.merchantId, command.merchantAccountId, command.activationId, now())) {
            is MerchantTargetResult.Found -> found.target
            MerchantTargetResult.MerchantNotFound -> return@observed refuseKind(ACTIVATION_KIND, command.context, command.requesterActorId, command.merchantId, ApprovalOutcome.Rejected(MERCHANT_NOT_FOUND), MERCHANT_NOT_FOUND)
            is MerchantTargetResult.Unavailable -> return@observed ApprovalOutcome.Unavailable(MASTER_UNAVAILABLE)
        }
        activationIneligibility(target)?.let {
            return@observed refuseKind(ACTIVATION_KIND, command.context, command.requesterActorId, command.merchantId, ApprovalOutcome.Rejected(NOT_ELIGIBLE), it)
        }
        val pinned = pinnedActivationOf(target) ?: return@observed ApprovalOutcome.Unavailable(MASTER_UNAVAILABLE)
        requestPinned(
            ACTIVATION_KIND, MerchantOpsCommandKind.PAYMENT_METHOD_ACTIVATION, command.requesterActorId, command.sessionGeneration, command.merchantId, pinned, command.evidenceRef,
            command.context,
        )
    }

    override fun requestSiteReviewApproval(command: RequestMerchantSiteReviewApproval): ApprovalOutcome = observed(REQUEST, SITE_REVIEW_KIND) {
        MerchantOpsInputRules.siteReviewProblem(command.merchantId, command.merchantSiteId, command.reasonCode, command.evidenceRef)?.let { (field, reason) ->
            return@observed ApprovalOutcome.Rejected(INVALID_INPUT, field, reason)
        }
        if (!holdsNow(command.requesterActorId, ApprovalParticipants.requesterRole(SITE_REVIEW_KIND))) return@observed ApprovalOutcome.Denied
        val target = when (val found = client.siteReviewTarget(command.merchantId, command.merchantSiteId)) {
            is MerchantTargetResult.Found -> found.target
            MerchantTargetResult.MerchantNotFound -> return@observed refuseKind(SITE_REVIEW_KIND, command.context, command.requesterActorId, command.merchantId, ApprovalOutcome.Rejected(MERCHANT_NOT_FOUND), MERCHANT_NOT_FOUND)
            is MerchantTargetResult.Unavailable -> return@observed ApprovalOutcome.Unavailable(MASTER_UNAVAILABLE)
        }
        siteReviewIneligibility(target)?.let {
            return@observed refuseKind(SITE_REVIEW_KIND, command.context, command.requesterActorId, command.merchantId, ApprovalOutcome.Rejected(NOT_ELIGIBLE), it)
        }
        val pinned = pinnedSiteReviewOf(target, command.reasonCode) ?: return@observed ApprovalOutcome.Unavailable(MASTER_UNAVAILABLE)
        requestPinned(
            SITE_REVIEW_KIND, MerchantOpsCommandKind.SITE_REVIEW_APPROVAL, command.requesterActorId, command.sessionGeneration, command.merchantId, pinned, command.evidenceRef,
            command.context,
        )
    }

    /** B3 공통 요청 TX(B2와 같은 순서 — 승인 행 INSERT(그룹 미해결 키 C-35) → 요청자 잠금·재검사 → 명령 행 INSERT(pinned·근거) → 감사). */
    @Suppress("LongParameterList")
    private fun requestPinned(
        kind: ApprovalKind, commandKind: MerchantOpsCommandKind, requesterId: String, sessionGeneration: Long, merchantId: String, pinned: PinnedMerchantTarget, evidenceRef: String,
        context: AdminRequestContext,
    ): ApprovalOutcome = guarded(context, requesterId, merchantId, kind) {
        transactions.execute({ false }) {
            val now = now()
            val request = ApprovalRequest.create(
                environment.newApprovalId(), kind, ApprovalTarget.Merchant(merchantId), pinned.toSnapshotJson(), null, null, requesterId, snapshotName(requesterId), now,
                environment.validity,
            )
            try {
                repository.insert(request)
            } catch (_: ApprovalPersistenceFailure.DuplicateUnresolved) {
                throw Refusal(ApprovalOutcome.Rejected(DUPLICATE_UNRESOLVED), DUPLICATE_UNRESOLVED, merchantId)
            }
            val requester = executor.lockParticipants(listOf(requesterId))[requesterId]
            if (requester == null || !requester.holds(ApprovalParticipants.requesterRole(kind))) throw Refusal(ApprovalOutcome.Denied, REQUESTER_NOT_ELIGIBLE, merchantId)
            if (requester.sessionGeneration != sessionGeneration) throw Refusal(ApprovalOutcome.SessionReplaced, null, null)
            commands.insert(MerchantOpsRequestSnapshot(request.id, commandKind, merchantId, pinned, evidenceRef, now))
            audit(now, requester, "APPROVAL_REQUESTED", "SUCCESS", context, request, null)
            ApprovalOutcome.Created(request.id)
        }
    }

    /** 개통 승인 미리 보기 부적격 사유(advisory — 권위는 MASTER 실행 TX). 순서: MID 없음·비활성 → 개통 없음·상태 → PRIMARY 없음(C-45). */
    private fun activationIneligibility(t: ActivationTarget): String? = when {
        t.accountLifecycle == null -> "MERCHANT_ACCOUNT_NOT_FOUND"
        t.accountLifecycle != "ACTIVE" -> "MERCHANT_ACCOUNT_NOT_ACTIVE"
        t.paymentMethod == null -> "ACTIVATION_NOT_FOUND"
        t.status != "UNDER_REVIEW" -> "ACTIVATION_NOT_UNDER_REVIEW"
        t.primarySiteId == null -> "PRIMARY_SITE_NOT_OPEN"
        else -> null
    }

    /** Site 심사 승인 미리 보기 부적격 사유: Site 없음·비활성 → 심사 상태가 PENDING·SUSPENDED가 아님(APPROVED·REJECTED — REJECTED는 종결 D-MS-005). */
    private fun siteReviewIneligibility(t: SiteReviewTarget): String? = when {
        t.siteLifecycle == null -> "SITE_NOT_FOUND"
        t.siteLifecycle != "ACTIVE" -> "SITE_NOT_ACTIVE"
        t.reviewStatus !in SITE_REVIEW_OPEN -> "SITE_REVIEW_NOT_OPEN"
        else -> null
    }

    private fun pinnedActivationOf(t: ActivationTarget): PinnedActivationApprovalTarget? = runCatching {
        PinnedActivationApprovalTarget(
            t.merchantId, t.merchantName, t.merchantAccountId, t.activationId, requireNotNull(t.paymentMethod), requireNotNull(t.status), requireNotNull(t.primarySiteId),
            requireNotNull(t.registeredBy),
        )
    }.getOrNull()

    private fun pinnedSiteReviewOf(t: SiteReviewTarget, reasonCode: String): PinnedSiteReviewApprovalTarget? = runCatching {
        PinnedSiteReviewApprovalTarget(
            t.merchantId, t.merchantName, t.merchantSiteId, requireNotNull(t.channelType), requireNotNull(t.reviewStatus), reasonCode, requireNotNull(t.registeredBy),
        )
    }.getOrNull()

    @Suppress("LongParameterList")
    private fun refuseKind(kind: ApprovalKind, context: AdminRequestContext, actorId: String, merchantId: String, outcome: ApprovalOutcome, reason: String): ApprovalOutcome {
        separateAudit(context, actorId, "APPROVAL_DENIED", "DENIED", merchantId, null, "kind=${kind.name};reason=$reason")
        return outcome
    }

    // ------------------------------------------------------------------ approve

    fun approve(command: DecideApproval): ApprovalOutcome {
        // B2-3 리뷰 n-4: 비권위 0단계 읽기는 한 번 — 지표 종류 label과 경로 판정에 같은 값을 쓴다(권위 판정은 TX1의 잠근 행).
        val seen = transactions.execute({ false }) { repository.find(command.approvalId) }
        return observed(APPROVE, seen?.kind?.takeIf { it in KINDS } ?: kind) { approveStep(command, seen) }
    }

    private fun approveStep(command: DecideApproval, seen: ApprovalRequest?): ApprovalOutcome {
        if (!holdsNow(command.approverActorId, ApprovalParticipants.APPROVER_ROLE)) return ApprovalOutcome.Denied
        if (seen == null) return ApprovalOutcome.NotFound
        if (seen.kind !in KINDS) return ApprovalOutcome.Rejected(WRONG_EXECUTION_MODEL)
        if (seen.status != ApprovalStatus.PENDING) return ApprovalOutcome.AlreadyDecided
        val pinned = transactions.execute({ false }) { commands.find(seen.id) }?.pinnedTarget ?: return ApprovalOutcome.IntegrityViolation
        // A(TX 밖, 조회 permit): 대상 재조회. 장애면 503(상태·TOTP 불변). 없어졌으면 현재 = null(TX1에서 TARGET_CHANGED).
        val current = try {
            currentOf(pinned)
        } catch (_: Unreadable) {
            return ApprovalOutcome.Unavailable(MASTER_UNAVAILABLE)
        }
        val begun = try {
            guardedTx1(command) { status -> tx1(command, current, status) }
        } catch (e: CommitOutcomeUnknownException) {
            log.warn("operation=merchant-approve-tx1 approvalId={} result=commit-unknown", command.approvalId)
            converge(command.approvalId) ?: throw e
        }
        if (begun !is Tx1.Begun) return (begun as Tx1.Done).outcome
        // C(TX 밖, 쓰기 permit): 저장 본문 그대로.
        val result = client.execute(operationOf(seen.kind), begun.body)
        return try {
            apply(command.approvalId, result, OwnerOutcomeApplier.Source.Web(command.approverActorId, command.context))
        } catch (e: RuntimeException) {
            log.warn("operation=merchant-approve-tx2 approvalId={} result=failed failure={}", command.approvalId, e.javaClass.simpleName)
            ApprovalOutcome.Executing(command.approvalId, ApprovalStatus.EXECUTING.name)
        }
    }

    /**
     * 결과 반영(웹 TX2·회복 job 공용, §9.5 매핑 표): 결정 조회 DECIDED면 지문·operation 대조(다르면 무결성 플래그 먼저 + 경보, 그 뒤 반영 — I-35). 400·413이면 재전송 중단
     * `OWNER_MALFORMED`(1회). 409면 무결성 플래그(1회 감사). 다음 시도는 가맹점 정책 backoff(상한 PT30M).
     */
    internal fun apply(approvalId: String, result: MerchantApprovalResult, source: OwnerOutcomeApplier.Source, quietIfUnchanged: Boolean = false): ApprovalOutcome {
        val (request, execution, command) = transactions.execute({ false }) { Triple(repository.find(approvalId), executions.find(approvalId), commands.find(approvalId)) }
        val operation = request?.kind?.takeIf { it in KINDS }?.let(::operationOf)
        val mismatch = result is MerchantApprovalResult.Decided && result.fingerprint != null && execution != null &&
            (result.fingerprint != execution.fingerprint || result.operation != operation?.code)
        if (mismatch) opsMetrics.integrityAlert(MerchantOutcomes.DECISION_CONTENT_MISMATCH)
        // B2-2 리뷰 n-2: 성공 reference는 이 승인의 대상이어야 한다(계약 승인 = pinned `contractId#revisionNo`, 첫 MID = `mid_…` 문법 — 어느 MID인지는 MASTER가 정한다).
        // 다르면 이 승인의 결과라고 볼 수 없다 → EXECUTED로 닫지 않고 UNKNOWN + 무결성 플래그 REFERENCE_MISMATCH(실행 POST 중단, 조회·기한 확인으로만 — 사람 검토) + 경보.
        val foreign = result is MerchantApprovalResult.Decided && result.succeeded && !referenceIsOwn(command?.pinnedTarget, requireNotNull(result.reference))
        if (foreign) {
            // B2-3 리뷰 m-1: 경보는 플래그를 처음 세울 때 1회, 같은 응답 반복은 감사·lastFailure를 다시 쌓지 않는다(quiet), 확정 불가 finding 1회(C-28과 대칭 — 자동 종결 없음).
            if (execution?.integrityFlag == null) opsMetrics.integrityAlert(REFERENCE_MISMATCH)
            val outcome = applier.apply(
                approvalId, OwnerOutcomeApplier.Outcome.Unknown(REFERENCE_MISMATCH, REFERENCE_MISMATCH), source, contentMismatch = mismatch, quietIfUnchanged = true,
                integrityAudit = true, backoff = policy::backoff,
            )
            if (request != null) unresolvable(approvalId, request, REFERENCE_MISMATCH, now())
            return outcome
        }
        MerchantOutcomes.alert(result)?.let(opsMetrics::integrityAlert)
        val outcome = applier.apply(
            approvalId, MerchantOutcomes.outcome(result), source, contentMismatch = mismatch, quietIfUnchanged = quietIfUnchanged, integrityAudit = true, backoff = policy::backoff,
        )
        if (result is MerchantApprovalResult.Malformed && applier.haltResend(approvalId, MerchantOutcomes.OWNER_MALFORMED)) opsMetrics.resendHalted(MerchantOutcomes.OWNER_MALFORMED)
        return outcome
    }

    private fun referenceIsOwn(pinned: PinnedMerchantTarget?, reference: String): Boolean = when (pinned) {
        is PinnedContractApprovalTarget -> reference == "${pinned.contractId}#${pinned.revisionNo}"
        is PinnedFirstMidTarget -> MERCHANT_ACCOUNT_ID.matches(reference)
        // B3-1(C-34): 개통 승인 = pinned activationId 그대로, Site 심사 승인 = `site_…#n` 문법이고 Site 부분 = pinned Site.
        is PinnedActivationApprovalTarget -> reference == pinned.activationId
        is PinnedSiteReviewApprovalTarget -> SITE_REVIEW_REFERENCE.matches(reference) && reference.substringBefore('#') == pinned.merchantSiteId
        null -> false
    }

    private sealed interface Tx1 {
        class Done(val outcome: ApprovalOutcome) : Tx1

        class Begun(val body: ByteArray) : Tx1
    }

    private fun tx1(command: DecideApproval, current: PinnedMerchantTarget?, status: TransactionStatus): Tx1 {
        val approverId = command.approverActorId
        val locked = repository.lock(command.approvalId) ?: return Tx1.Done(ApprovalOutcome.NotFound)
        val request = locked.value
        if (request.kind !in KINDS) throw IntegrityViolation(EXECUTION_MODEL, null, request.id)
        val now = now()
        when (val decision = request.decideApproval(approverId, now)) {
            ApprovalDecision.AlreadyDecided -> return Tx1.Done(ApprovalOutcome.AlreadyDecided)
            is ApprovalDecision.Expired -> {
                repository.update(locked, decision.expired)
                audit(now, null, "APPROVAL_EXPIRED", "EXPIRED", command.context, request, null, actorId = approverId)
                return Tx1.Done(ApprovalOutcome.Expired)
            }
            is ApprovalDecision.Refused -> {
                audit(now, null, "APPROVAL_DENIED", "REFUSED", command.context, request, "reason=${decision.code}", actorId = approverId)
                return Tx1.Done(ApprovalOutcome.Refused(decision.code))
            }
            ApprovalDecision.Proceed -> Unit
        }
        val merchantId = request.target.ref
        val participants = executor.lockParticipants(listOf(request.requestedBy, approverId))
        val approver = participants[approverId]
        if (approver == null || !approver.holds(ApprovalParticipants.APPROVER_ROLE)) throw Refusal(ApprovalOutcome.Denied, "APPROVER_NOT_ELIGIBLE", merchantId, request.id)
        if (approver.sessionGeneration != command.sessionGeneration) throw Refusal(ApprovalOutcome.SessionReplaced, null, null)
        // 명령 행은 INSERT-only — 잠그지 않고 읽는다. 권위 고정 값 = pinned_target.
        val snapshot = commands.find(request.id) ?: throw IntegrityViolation("command", merchantId, request.id)
        if (snapshot.merchantId != merchantId) throw IntegrityViolation("target_ref", merchantId, request.id)
        val pinned = snapshot.pinnedTarget
        val pinnedKindMatches = (request.kind == ApprovalKind.MERCHANT_CONTRACT_APPROVAL && pinned is PinnedContractApprovalTarget) ||
            (request.kind == MID_KIND && pinned is PinnedFirstMidTarget) || (request.kind == ACTIVATION_KIND && pinned is PinnedActivationApprovalTarget) ||
            (request.kind == SITE_REVIEW_KIND && pinned is PinnedSiteReviewApprovalTarget)
        if (!pinnedKindMatches) throw IntegrityViolation("command_kind", merchantId, request.id)
        // D-MOPS-008 ③(C-27): Revision 등록자는 그 Revision을 승인할 수 없다 — TOTP를 쓰기 전에 거부(상태·TOTP 불변, 감사 APPROVAL_DENIED reason=APPROVER_IS_REGISTRANT).
        // B2-3: 첫 MID 발급에는 적용하지 않는다(결정 범위가 "그 Revision을 승인" — §9.3 판정 5).
        // B3-1F(D-MOPS-009 ③·D-MOPS-010 ②, §9.27): 개통·Site 심사 승인도 같은 줄에서 대상 등록자(pinned registeredBy — 요청 시점 고정 값)와 비교한다.
        registrantOf(pinned)?.let { registrant ->
            if (registrant == ACTOR_PREFIX + approverId) throw Refusal(ApprovalOutcome.Refused(APPROVER_IS_REGISTRANT), APPROVER_IS_REGISTRANT, merchantId, request.id)
        }
        if (executor.consumeStepUpTotp(approverId, command.totpCode) == StepUpResult.FAILED) {
            status.setRollbackOnly()
            return Tx1.Done(ApprovalOutcome.StepUpFailed)
        }
        val requester = participants[request.requestedBy]
        if (requester == null || !requester.holds(ApprovalParticipants.requesterRole(request.kind))) {
            return Tx1.Done(invalidate(locked, request, approver, REQUESTER_NOT_ELIGIBLE, now, command.context))
        }
        if (!sameTarget(pinned, current)) return Tx1.Done(invalidate(locked, request, approver, TARGET_CHANGED, now, command.context))
        val executeBefore = now.plus(policy.executionWindow)
        val built = try {
            when (pinned) {
                is PinnedContractApprovalTarget -> contract.contractRevisionApproval(
                    request.id, ACTOR_PREFIX + request.requestedBy, ACTOR_PREFIX + approverId, now, executeBefore, snapshot.evidenceRef, pinned.contractId, pinned.revisionNo,
                )
                is PinnedFirstMidTarget -> contract.firstDefaultIssuance(
                    request.id, ACTOR_PREFIX + request.requestedBy, ACTOR_PREFIX + approverId, now, executeBefore, snapshot.evidenceRef, pinned.merchantId, pinned.contractId,
                    pinned.agreementId, pinned.revisionNo, pinned.primarySiteId,
                )
                is PinnedActivationApprovalTarget -> contract.paymentMethodActivationApproval(
                    request.id, ACTOR_PREFIX + request.requestedBy, ACTOR_PREFIX + approverId, now, executeBefore, snapshot.evidenceRef, pinned.activationId, pinned.primarySiteId,
                )
                is PinnedSiteReviewApprovalTarget -> contract.siteReviewApproval(
                    request.id, ACTOR_PREFIX + request.requestedBy, ACTOR_PREFIX + approverId, now, executeBefore, snapshot.evidenceRef, pinned.merchantSiteId, pinned.reasonCode,
                )
            }
        } catch (_: IllegalArgumentException) {
            throw IntegrityViolation("command_body", merchantId, request.id)
        }
        val body = built.body()
        val execution = try {
            OwnerExecution.start(request.kind, request.id, body, built.fingerprint, now, executeBefore, approverId, now.plus(policy.staleAfter))
        } catch (_: IllegalArgumentException) {
            throw IntegrityViolation("fingerprint", merchantId, request.id)
        }
        executions.insert(execution)
        repository.update(locked, request.beginExecution(approverId, approver.displayName, now))
        audit(now, approver, "APPROVAL_EXECUTING", "EXECUTING", command.context, request, null, executor = approverId)
        return Tx1.Begun(body)
    }

    /**
     * 2인 승인자가 될 수 없는 1인 등록자(MERCHANT-OPS-002 §9.27 — D-MOPS-008 ③ 계약 Revision 등록자, D-MOPS-009 ③ 개통 등록자·Site 등록자). 첫 MID 발급은 없음(null — 결정 범위 밖).
     * sealed `when` — 새 pinned 종류(B3-2·B3-3)를 더하면 컴파일러가 이 결정을 요구한다.
     */
    private fun registrantOf(pinned: PinnedMerchantTarget): String? = when (pinned) {
        is PinnedContractApprovalTarget -> pinned.registeredBy
        is PinnedActivationApprovalTarget -> pinned.registeredBy
        is PinnedSiteReviewApprovalTarget -> pinned.registeredBy
        is PinnedFirstMidTarget -> null
    }

    private fun invalidate(locked: VersionedApproval, request: ApprovalRequest, approver: ParticipantView, code: String, now: Instant, context: AdminRequestContext): ApprovalOutcome {
        repository.update(locked, request.invalidated(approver.actorId, approver.displayName, code, now))
        audit(now, approver, "APPROVAL_INVALIDATED", "INVALIDATED", context, request, "reason=$code")
        return ApprovalOutcome.Invalidated(code)
    }

    private fun converge(approvalId: String): Tx1? = transactions.execute({ false }) {
        val request = repository.find(approvalId) ?: return@execute null
        val execution = executions.find(approvalId)
        when {
            execution != null && request.status == ApprovalStatus.EXECUTING -> Tx1.Begun(execution.requestBody())
            execution != null -> Tx1.Done(ApprovalOutcome.Executing(approvalId, request.status.name))
            else -> null
        }
    }

    // ------------------------------------------------------------------ recovery (OwnerExecutionRecoveryService가 선점한 행 하나)

    /**
     * 회복 한 행(§9.5 0~5 — PAYMENT·FINANCIAL과 같은 순서 시험 MerchantOpsRecoverySpec). 시도 상한 없음, 수동 미실행 확정 없음(D-OPS-003 선례).
     * 1. 결정 조회 → DECIDED면 대조 후 반영(종결), 조회 불가면 UNKNOWN 기록 후 다음 주기, NONE이면 2.
     * 2. ADMIN 시각 `now ≥ executeBefore + deadline-grace`면 **기한 확인만**(저장 바이트): 결정이면 반영, NOT_YET_DUE·503이면 다음 주기, 400·413·409면 확정 불가 → finding 1회·경보. 실행 POST 없음.
     * 3. 재전송 중단 건은 여기서 끝(조회·기한 확인만). 참여자 자격 상실 → 재전송 중단(1회).
     * 4. 무결성 플래그 → 실행 POST 없음.
     * 5. 같은 저장 바이트 실행 POST → [apply].
     */
    internal fun recover(id: String, now: Instant) {
        val (request, execution, command) = transactions.execute({ false }) { Triple(repository.find(id), executions.find(id), commands.find(id)) }
        if (request == null || execution == null || command == null) {
            applier.reportIntegrity(id, if (request == null) "approval" else if (command == null) "command" else "execution", request?.target?.ref)
            return
        }
        if (request.status !in ApprovalStatus.OWNER_SERVICE_ONLY) return
        if (!execution.conformsTo(request.kind)) {
            applier.reportIntegrity(id, "execution_kind", request.target.ref)
            return
        }
        val job = OwnerOutcomeApplier.Source.Job
        when (val decision = client.decision(id)) {
            is MerchantApprovalResult.Decided -> {
                opsMetrics.resend("LOOKUP_DECIDED")
                apply(id, decision, job)
                return
            }
            MerchantApprovalResult.None -> Unit
            else -> {
                opsMetrics.resend("LOOKUP_UNAVAILABLE")
                MerchantOutcomes.alert(decision)?.let(opsMetrics::integrityAlert)
                applier.apply(id, OwnerOutcomeApplier.Outcome.Unknown("DECISION_${failureOf(decision)}".take(MAX_CODE), null), job, quietIfUnchanged = true, backoff = policy::backoff)
                return
            }
        }
        if (!now.isBefore(execution.executeBefore.plus(policy.deadlineGrace))) {
            when (val confirmed = client.confirmDeadline(operationOf(request.kind), execution.requestBody())) {
                is MerchantApprovalResult.Decided -> {
                    opsMetrics.resend("DEADLINE_DECIDED")
                    apply(id, confirmed, job)
                }
                MerchantApprovalResult.KeyReused, is MerchantApprovalResult.Malformed -> {
                    opsMetrics.resend("DEADLINE_UNRESOLVABLE")
                    apply(id, confirmed, job, quietIfUnchanged = true)
                    unresolvable(id, request, failureOf(confirmed), now)
                }
                else -> {
                    opsMetrics.resend(if (confirmed == MerchantApprovalResult.NotYetDue) "DEADLINE_NOT_YET_DUE" else "DEADLINE_UNAVAILABLE")
                    MerchantOutcomes.alert(confirmed)?.let(opsMetrics::integrityAlert)
                    applier.apply(id, OwnerOutcomeApplier.Outcome.Unknown("DEADLINE_${failureOf(confirmed)}".take(MAX_CODE), null), job, quietIfUnchanged = true, backoff = policy::backoff)
                }
            }
            return
        }
        if (execution.resendHaltedReason != null) return
        ineligibility(request, execution)?.let { reason ->
            if (applier.haltResend(id, reason)) opsMetrics.resendHalted(reason)
            return
        }
        if (execution.integrityFlag != null) return
        val result = client.execute(operationOf(request.kind), execution.requestBody())
        opsMetrics.resend(
            when (result) {
                is MerchantApprovalResult.Decided -> if (result.succeeded) "EXECUTE_SUCCEEDED" else "EXECUTE_REJECTED"
                is MerchantApprovalResult.Malformed -> "EXECUTE_MALFORMED"
                MerchantApprovalResult.KeyReused -> "EXECUTE_KEY_REUSED"
                else -> "EXECUTE_UNKNOWN"
            },
        )
        apply(id, result, job)
    }

    /** C-28 확정 불가: 운영 대사 finding `OWNER_UNRESOLVABLE`(승인당 1회 — UNIQUE(kind, approval_id)) + 경보. 상태는 EXECUTION_UNKNOWN 그대로(자동 종결 없음). */
    private fun unresolvable(id: String, request: ApprovalRequest, code: String, now: Instant) {
        val detail = code.takeIf { FINDING_DETAIL.matches(it) } ?: MerchantOutcomes.OWNER_MALFORMED
        try {
            transactions.execute({ false }) { findings.record(ReconciliationFindingRecord(ReconciliationFindingKind.OWNER_UNRESOLVABLE, id, request.target.ref, detail, now)) }
            opsMetrics.reconFinding(ReconciliationFindingKind.OWNER_UNRESOLVABLE.name)
            opsMetrics.integrityAlert(ReconciliationFindingKind.OWNER_UNRESOLVABLE.name)
            log.warn("operation=merchant-recovery approvalId={} result=owner-unresolvable code={}", id, detail)
        } catch (_: ApprovalPersistenceFailure.DuplicateFinding) {
            opsMetrics.reconFindingDuplicate()
        }
    }

    private fun failureOf(result: MerchantApprovalResult): String = when (result) {
        is MerchantApprovalResult.Unknown -> result.failure
        is MerchantApprovalResult.Malformed -> result.code
        MerchantApprovalResult.KeyReused -> MerchantOutcomes.KEY_REUSED
        MerchantApprovalResult.NotYetDue -> "NOT_YET_DUE"
        MerchantApprovalResult.None -> "NONE"
        is MerchantApprovalResult.Decided -> "DECIDED"
    }

    private fun ineligibility(request: ApprovalRequest, execution: OwnerExecution): String? = transactions.execute({ false }) {
        val requester = executor.snapshot(request.requestedBy)
        val approver = executor.snapshot(execution.executorActorId)
        when {
            requester == null || !requester.holds(ApprovalParticipants.requesterRole(request.kind)) -> REQUESTER_NOT_ELIGIBLE
            approver == null || !approver.holds(ApprovalParticipants.APPROVER_ROLE) -> "APPROVER_NOT_ELIGIBLE"
            else -> null
        }
    }

    // ------------------------------------------------------------------ helpers (AcquiringOpsApprovalService와 같은 규칙 — target_ref = merchantId)

    private fun holdsNow(actorId: String, role: String): Boolean = sessions.current(actorId)?.let { it.isActive && role in it.roles } == true

    private fun snapshotName(actorId: String): String = sessions.current(actorId)?.displayName ?: actorId

    private fun refuseAudited(command: RequestMerchantContractApproval, outcome: ApprovalOutcome, reason: String): ApprovalOutcome {
        separateAudit(command.context, command.requesterActorId, "APPROVAL_DENIED", "DENIED", command.merchantId, null, "kind=${kind.name};reason=$reason")
        return outcome
    }

    private fun guardedTx1(command: DecideApproval, block: (TransactionStatus) -> Tx1): Tx1 {
        val outcome = try {
            retryLater { transactions.execute({ false }, block) }
        } catch (refusal: Refusal) {
            refusal.auditReason?.let { separateAudit(command.context, command.approverActorId, "APPROVAL_DENIED", "DENIED", refusal.targetRef, refusal.approvalId, "reason=$it") }
            return Tx1.Done(refusal.outcome)
        } catch (violation: IntegrityViolation) {
            metrics.integrityViolation(violation.column)
            separateAudit(command.context, command.approverActorId, "APPROVAL_INTEGRITY_VIOLATION", "FAILED", violation.targetRef, violation.approvalId ?: command.approvalId, "column=${violation.column}")
            return Tx1.Done(ApprovalOutcome.IntegrityViolation)
        } catch (_: AdminRetryLaterException) {
            return Tx1.Done(ApprovalOutcome.RetryLater)
        }
        if (outcome is Tx1.Done && outcome.outcome == ApprovalOutcome.StepUpFailed) failures.recordFailure(command.approverActorId, AuthenticationStep.STEP_UP_TOTP, command.context)
        return outcome
    }

    private fun guarded(context: AdminRequestContext, actorId: String, targetRef: String, auditKind: ApprovalKind = kind, block: () -> ApprovalOutcome): ApprovalOutcome = try {
        retryLater(block)
    } catch (refusal: Refusal) {
        refusal.auditReason?.let { separateAudit(context, actorId, "APPROVAL_DENIED", "DENIED", refusal.targetRef, refusal.approvalId, "kind=${auditKind.name};reason=$it") }
        refusal.outcome
    } catch (violation: IntegrityViolation) {
        metrics.integrityViolation(violation.column)
        separateAudit(context, actorId, "APPROVAL_INTEGRITY_VIOLATION", "FAILED", violation.targetRef ?: targetRef, violation.approvalId, "column=${violation.column}")
        ApprovalOutcome.IntegrityViolation
    } catch (_: AdminRetryLaterException) {
        ApprovalOutcome.RetryLater
    }

    private inline fun <T> retryLater(block: () -> T): T = try {
        block()
    } catch (e: ApprovalPersistenceFailure.Deadlock) {
        throw AdminRetryLaterException(e)
    } catch (e: ApprovalPersistenceFailure.LockTimeout) {
        throw AdminRetryLaterException(e)
    } catch (e: ApprovalPersistenceFailure.OptimisticConflict) {
        throw AdminRetryLaterException(e)
    }

    @Suppress("LongParameterList")
    private fun separateAudit(context: AdminRequestContext, actorId: String, action: String, result: String, targetRef: String?, approvalId: String?, detail: String) {
        transactions.execute({ false }) {
            val actor = executor.snapshot(actorId)
            recorder.record(
                AuditRecord(
                    occurredAt = now(), actorKind = if (actor != null) "ADMIN" else "ANONYMOUS", actorId = actor?.actorId, actorDisplayName = actor?.displayName,
                    action = action, resultCode = result, correlationId = context.correlationId, approvalId = approvalId, remoteIp = context.remoteIp,
                    detail = detail, targetRef = targetRef,
                ),
            )
        }
    }

    @Suppress("LongParameterList")
    private fun audit(
        now: Instant, actor: ParticipantView?, action: String, result: String, context: AdminRequestContext, request: ApprovalRequest, reasonDetail: String?,
        actorId: String? = actor?.actorId, executor: String? = null,
    ) {
        recorder.record(
            AuditRecord(
                occurredAt = now, actorKind = "ADMIN", actorId = actorId, actorDisplayName = actor?.displayName, action = action, resultCode = result,
                correlationId = context.correlationId, executorActorId = executor, approvalId = request.id, remoteIp = context.remoteIp,
                detail = listOfNotNull("kind=${request.kind.name}", reasonDetail).joinToString(";"), targetRef = request.target.ref,
            ),
        )
    }

    private fun now(): Instant = environment.now().truncatedTo(ChronoUnit.MILLIS)

    private inline fun observed(operation: String, metricKind: ApprovalKind = kind, block: () -> ApprovalOutcome): ApprovalOutcome {
        val outcome = try {
            block()
        } catch (e: RuntimeException) {
            metrics.decision(operation, metricKind.name, "ERROR")
            throw e
        }
        if (outcome == ApprovalOutcome.RetryLater) metrics.retryLater(operation)
        metrics.decision(operation, metricKind.name, outcome.javaClass.simpleName.uppercase())
        return outcome
    }

    private class Refusal(val outcome: ApprovalOutcome, val auditReason: String?, val targetRef: String?, val approvalId: String? = null) :
        RuntimeException(null, null, false, false)

    private class IntegrityViolation(val column: String, val targetRef: String? = null, val approvalId: String? = null) : RuntimeException(null, null, false, false)

    private companion object {
        val log = LoggerFactory.getLogger(MerchantOpsApprovalService::class.java)
        const val ACTOR_PREFIX = "admin:"
        const val INVALID_INPUT = "INVALID_INPUT"
        const val REQUEST = "OWNER_REQUEST"
        const val APPROVE = "OWNER_APPROVE"
        const val MERCHANT_NOT_FOUND = "MERCHANT_NOT_FOUND"
        const val MASTER_UNAVAILABLE = "MASTER_UNAVAILABLE"
        const val NOT_ELIGIBLE = "TARGET_NOT_ELIGIBLE_ADVISORY"
        const val DUPLICATE_UNRESOLVED = "DUPLICATE_UNRESOLVED"
        const val REQUESTER_NOT_ELIGIBLE = "REQUESTER_NOT_ELIGIBLE"
        const val APPROVER_IS_REGISTRANT = "APPROVER_IS_REGISTRANT"
        const val TARGET_CHANGED = "TARGET_CHANGED"
        const val WRONG_EXECUTION_MODEL = "WRONG_EXECUTION_MODEL"
        const val EXECUTION_MODEL = "execution_model"
        const val MAX_CODE = 48
        const val REFERENCE_MISMATCH = "REFERENCE_MISMATCH"
        val MID_KIND = ApprovalKind.MERCHANT_FIRST_MID_ISSUANCE
        val ACTIVATION_KIND = ApprovalKind.MERCHANT_PAYMENT_METHOD_ACTIVATION
        val SITE_REVIEW_KIND = ApprovalKind.MERCHANT_SITE_REVIEW_APPROVAL
        val KINDS = setOf(ApprovalKind.MERCHANT_CONTRACT_APPROVAL, ApprovalKind.MERCHANT_FIRST_MID_ISSUANCE, ACTIVATION_KIND, SITE_REVIEW_KIND)
        val SITE_REVIEW_REFERENCE = Regex("^site_[0-9A-HJKMNP-TV-Z]{26}#[1-9][0-9]{0,9}$")

        /** Site 심사 승인 요청이 가능한 심사 상태(PENDING = 결정 없음, SUSPENDED → APPROVED 재개 — §9.13 해석). */
        val SITE_REVIEW_OPEN = setOf("PENDING", "SUSPENDED")
        val MERCHANT_ACCOUNT_ID = Regex("^mid_[0-9A-HJKMNP-TV-Z]{26}\$")
        val FINDING_DETAIL = Regex("^[A-Z_]{1,48}$")
    }
}

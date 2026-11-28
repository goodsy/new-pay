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
import com.paycore.admin.approval.application.provided.BusinessChangeOriginalQuery
import com.paycore.admin.approval.application.provided.BusinessChangeOriginalResult
import com.paycore.admin.approval.application.provided.DecideApproval
import com.paycore.admin.approval.application.required.ApprovalEnvironment
import com.paycore.admin.approval.application.required.ApprovalMetrics
import com.paycore.admin.approval.application.required.ApprovalPersistenceFailure
import com.paycore.admin.approval.application.required.ApprovalRequestRepository
import com.paycore.admin.approval.application.required.BusinessChangeCommandStore
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
import com.paycore.admin.approval.domain.BusinessChangeRequestSnapshot
import com.paycore.admin.approval.domain.OwnerExecution
import com.paycore.admin.approval.domain.ReconciliationFindingKind
import com.paycore.admin.audit.application.provided.AdminAuditRecorder
import com.paycore.admin.merchantops.application.provided.BusinessChangeClient
import com.paycore.admin.merchantops.application.provided.BusinessChangeProposalRequest
import com.paycore.admin.merchantops.application.provided.BusinessChangeProposalResult
import com.paycore.admin.merchantops.application.provided.BusinessChangeRead
import com.paycore.admin.merchantops.application.provided.BusinessChangeRequestOutcome
import com.paycore.admin.merchantops.application.provided.BusinessChangeRequests
import com.paycore.admin.merchantops.application.provided.BusinessPendingApprovals
import com.paycore.admin.merchantops.application.provided.BusinessProfileRequest
import com.paycore.admin.merchantops.application.provided.MerchantApprovalClient
import com.paycore.admin.merchantops.application.provided.MerchantApprovalContract
import com.paycore.admin.merchantops.application.provided.MerchantApprovalOperation
import com.paycore.admin.merchantops.application.provided.MerchantApprovalPolicy
import com.paycore.admin.merchantops.application.provided.MerchantApprovalResult
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionStatus
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 사업자 중요정보 변경 2인 승인(BIZ-CRIT-APPROVAL-001 §3.1·§3.3·§4.1 — S3). [MerchantOpsApprovalService]와 **같은 단계 순서**의 OWNER_SERVICE 2-TX(가맹점 서비스에 종류를 더하지 않는다:
 * `merchantKey`·`mer_` 전제). MASTER 호출은 모두 DB TX 밖. 로컬 TX는 재시도하지 않는다(TOTP 재사용 없음).
 *
 * - 요청(OPERATOR — [request]): ① 입력 규칙(MASTER 호출 전 — 사유·근거 필수, 내용 값 규칙) ② 세션 역할 ③ 사전 미해결 검사(advisory — 있으면 409 `DUPLICATE_UNRESOLVED`, MASTER 미호출 — m-5)
 *   ④ MASTER 제안(TX 밖 — 제안 행만, 사업자 불변) → 응답 요청자 = 세션 요청자 확인(NIT-5·M-6 이중 — 다르면 승인 요청을 붙이지 않는다, 무결성) ⑤ 요청 TX(승인 행 INSERT(미해결 키 `BUSINESS:<biz_>`
 *   UNIQUE — 권위) → 요청자 잠금·재검사 → 명령 행 INSERT(제안 ID·digest·항목 이름 — 상호·개인정보 없음, M-5) → 감사 `APPROVAL_REQUESTED`(값 없는 detail)).
 * - 승인(APPROVER + 직전 TOTP + 대조 확인 — [approve]): ① 세션 역할 ② A(TX 밖 — MASTER `approval-state`: version·생명주기·등록 상태만, 장애 503·상태·TOTP 불변) ③ TX1(잠금·판정표) ④ 참여자
 *   ⑤ 대조 확인 체크(없으면 400 — TOTP 소비 전) ⑥ TOTP ⑦ 요청자 재검사 ⑧ 대상 비교(version ≠ base_version·종료·INACTIVE면 INVALIDATED(TARGET_CHANGED), MASTER 미호출) ⑨ 본문·`mop1`·EXECUTING
 *   ⑩ C 저장 바이트 POST ⑪ TX2([OwnerOutcomeApplier] — 회복 job과 같은 규칙, 성공 reference = 그 사업자 ID여야 한다).
 * - 회복([recover] — 가맹점 회복과 같은 순서): 결정 조회 → 기한 + 여유 뒤 기한 확인만 → 참여자 재검사 → 무결성 플래그면 조회만 → 같은 저장 바이트 재전송. 확정 불가면 `OWNER_UNRESOLVABLE` finding 1회.
 *   [OwnerExecutionRecoveryService]가 결제(PAYMENT) 경로보다 **앞에서** 이 서비스로 보낸다(사업자 승인 행이 PAYMENT·FINANCIAL client에 닿지 않게 — m-7).
 * - 원문 프록시([original] — M-1): APPROVER 또는 그 요청의 요청자, **미해결 상태(PENDING·EXECUTING·EXECUTION_UNKNOWN)에서만**(끝난 요청 409 `APPROVAL_NOT_OPEN`·MASTER 미호출 — R2-m4),
 *   거부 시도 포함 열람마다 감사(값 없음), 사업자 조회 창 공유 속도 제한(D-BIZ-006 ②).
 * 감사 대상 = 승인 행의 사업자 ID(target_ref) — 승인 행이 없는 요청 단계 거절은 요청 본문의 형식 검사한 사업자 ID. 근거 참조·사유·원문·digest 원문은 감사·로그·지표에 없다.
 */
@Service
class BusinessChangeApprovalService(
    transactionManager: PlatformTransactionManager,
    private val repository: ApprovalRequestRepository,
    private val commands: BusinessChangeCommandStore,
    private val executions: OwnerExecutionRepository,
    private val findings: ReconciliationFindingStore,
    private val executor: AdminOwnedCommandExecutor,
    private val failures: AuthenticationFailureRecorder,
    private val sessions: AdminSessionQuery,
    private val recorder: AdminAuditRecorder,
    private val environment: ApprovalEnvironment,
    private val metrics: ApprovalMetrics,
    private val opsMetrics: OwnerOpsMetrics,
    private val channel: BusinessChangeClient,
    private val client: MerchantApprovalClient,
    private val contract: MerchantApprovalContract,
    private val policy: MerchantApprovalPolicy,
    private val applier: OwnerOutcomeApplier,
    private val pending: BusinessPendingApprovals,
) : BusinessChangeRequests, BusinessChangeOriginalQuery {

    private val transactions = LocalTransactions(transactionManager, maxAttempts = 1)

    // ------------------------------------------------------------------ request

    override fun request(actorId: String, sessionGeneration: Long, request: BusinessProfileRequest.Change, context: AdminRequestContext): BusinessChangeRequestOutcome =
        observedRequest { requestStep(actorId, sessionGeneration, request, context) }

    private fun requestStep(actorId: String, sessionGeneration: Long, change: BusinessProfileRequest.Change, context: AdminRequestContext): BusinessChangeRequestOutcome {
        val proposal = BusinessChangeProposalRequest(
            actorId, change.submissionId, change.businessId, change.version, change.reason, change.evidenceRef.orEmpty(), change.content,
        )
        // ① 입력 규칙(MASTER 호출 전, 감사 없음 — 1인 수정과 같은 관례).
        val errors = channel.inputErrors(proposal) ?: return BusinessChangeRequestOutcome.Invalid(emptyList())
        if (errors.isNotEmpty()) return BusinessChangeRequestOutcome.Invalid(errors)
        // ② 세션 역할(비권위 — 요청 TX가 잠근 계정 행으로 다시 본다).
        if (!holdsNow(actorId, ApprovalParticipants.requesterRole(KIND))) return BusinessChangeRequestOutcome.Denied
        val auditTarget = ApprovalAuditTarget.ofBusinessRef(change.businessId)
        // ③ 사전 미해결 검사(advisory — 권위는 요청 TX의 UNIQUE). 고아 제안을 줄인다(m-5).
        if (pending.hasUnresolved(change.businessId)) return refused(context, actorId, auditTarget, DUPLICATE_UNRESOLVED, BusinessChangeRequestOutcome.Conflict(DUPLICATE_UNRESOLVED))
        // ④ MASTER 제안(TX 밖).
        val proposed = when (val result = channel.propose(proposal)) {
            is BusinessChangeProposalResult.Proposed -> result
            is BusinessChangeProposalResult.Rejected -> return refused(context, actorId, auditTarget, result.rejection, BusinessChangeRequestOutcome.Conflict(result.rejection))
            is BusinessChangeProposalResult.Invalid -> return BusinessChangeRequestOutcome.Invalid(result.fieldErrors)
            BusinessChangeProposalResult.KeyReused -> return BusinessChangeRequestOutcome.Conflict(KEY_REUSED)
            is BusinessChangeProposalResult.Busy -> return BusinessChangeRequestOutcome.Unavailable(MASTER_BUSY)
            is BusinessChangeProposalResult.RetrySameKey -> return BusinessChangeRequestOutcome.Unavailable(MASTER_RETRY_SAME_KEY)
            is BusinessChangeProposalResult.Unknown -> {
                // 결과 불명 — 같은 submissionId 재전송이 MASTER 재생으로 확인한다(새 제안 없음). 응답 제안 ID ≠ reference는 결함 신호(경보).
                if (result.reason == REFERENCE_MISMATCH) opsMetrics.integrityAlert(PROPOSAL_REFERENCE_MISMATCH)
                return BusinessChangeRequestOutcome.Unavailable(MASTER_OUTCOME_UNKNOWN)
            }
        }
        // NIT-5(M-6 ADMIN 쪽): MASTER가 기록한 제안 요청자 = 이 세션 요청자. 다르면 승인 요청을 붙이지 않는다(남의 제안에 자기 이름으로 요청 붙이기 방지 — 무결성).
        if (proposed.requestedBy != ACTOR_PREFIX + actorId) {
            metrics.integrityViolation(REQUESTED_BY)
            separateAudit(context, actorId, "APPROVAL_INTEGRITY_VIOLATION", "FAILED", auditTarget, null, "kind=${KIND.name};column=$REQUESTED_BY")
            return BusinessChangeRequestOutcome.IntegrityViolation
        }
        // 제안 요약 → 명령 행 Domain 값(S3 리뷰 MINOR-2): 응답 모양 검사를 지났는데도 규칙을 어기면 MASTER·ADMIN 결함 — 승인 요청을 붙이지 않는다(무결성, 요청 단계 감사 + 지표).
        val now = now()
        val snapshot = try {
            BusinessChangeRequestSnapshot(
                environment.newApprovalId(), change.businessId, proposed.baseVersion, proposed.proposalId, proposed.contentDigest, proposed.changedFields,
                proposed.representativeChanged, proposed.subjectCorrection, proposal.evidenceRef, now,
            )
        } catch (_: IllegalArgumentException) {
            metrics.integrityViolation(COMMAND_SNAPSHOT)
            separateAudit(context, actorId, "APPROVAL_INTEGRITY_VIOLATION", "FAILED", auditTarget, null, "kind=${KIND.name};column=$COMMAND_SNAPSHOT")
            return BusinessChangeRequestOutcome.IntegrityViolation
        }
        // ⑤ 요청 TX.
        return guardedRequest(context, actorId, auditTarget) {
            transactions.execute({ false }) {
                val request = ApprovalRequest.create(
                    snapshot.approvalId, KIND, ApprovalTarget.Business(change.businessId), snapshotJson(snapshot), null, null, actorId, snapshotName(actorId), now,
                    environment.validity,
                )
                try {
                    repository.insert(request)
                } catch (_: ApprovalPersistenceFailure.DuplicateUnresolved) {
                    throw RequestRefusal(BusinessChangeRequestOutcome.Conflict(DUPLICATE_UNRESOLVED), DUPLICATE_UNRESOLVED)
                }
                val requester = executor.lockParticipants(listOf(actorId))[actorId]
                if (requester == null || !requester.holds(ApprovalParticipants.requesterRole(KIND))) throw RequestRefusal(BusinessChangeRequestOutcome.Denied, REQUESTER_NOT_ELIGIBLE)
                if (requester.sessionGeneration != sessionGeneration) throw RequestRefusal(BusinessChangeRequestOutcome.SessionReplaced, null)
                try {
                    commands.insert(snapshot)
                } catch (_: ApprovalPersistenceFailure.DuplicateProposal) {
                    // MINOR-1: 끝난 요청의 같은 submissionId 재전송 — MASTER가 같은 제안을 재생했다. 제안 하나에 승인 요청 하나(V17 UNIQUE) — 새 submissionId로 새 제안이 필요하다.
                    throw RequestRefusal(BusinessChangeRequestOutcome.Conflict(PROPOSAL_ALREADY_USED), PROPOSAL_ALREADY_USED)
                }
                audit(now, requester, "APPROVAL_REQUESTED", "SUCCESS", context, request, snapshot, null)
                BusinessChangeRequestOutcome.Created(request.id)
            }
        }
    }

    // ------------------------------------------------------------------ approve

    fun approve(command: DecideApproval): ApprovalOutcome = observed(APPROVE) { approveStep(command) }

    private fun approveStep(command: DecideApproval): ApprovalOutcome {
        if (!holdsNow(command.approverActorId, ApprovalParticipants.APPROVER_ROLE)) return ApprovalOutcome.Denied
        val seen = transactions.execute({ false }) { repository.find(command.approvalId) } ?: return ApprovalOutcome.NotFound
        if (seen.kind != KIND) return ApprovalOutcome.Rejected(WRONG_EXECUTION_MODEL)
        if (seen.status != ApprovalStatus.PENDING) return ApprovalOutcome.AlreadyDecided
        val businessId = (seen.target as? ApprovalTarget.Business)?.businessId ?: return ApprovalOutcome.IntegrityViolation
        // A(TX 밖, 조회 permit): version·생명주기·등록 상태만(m-1). 장애면 503(상태·TOTP 불변). 없어졌으면 현재 = null(TX1에서 TARGET_CHANGED).
        val current = when (val read = channel.approvalState(businessId)) {
            is BusinessChangeRead.Found -> read.document
            BusinessChangeRead.NotFound -> null
            BusinessChangeRead.RateLimited, is BusinessChangeRead.Unavailable -> return ApprovalOutcome.Unavailable(MASTER_UNAVAILABLE)
        }
        val begun = try {
            guardedTx1(command) { status -> tx1(command, current, status) }
        } catch (e: CommitOutcomeUnknownException) {
            log.warn("operation=business-approve-tx1 approvalId={} result=commit-unknown", command.approvalId)
            converge(command.approvalId) ?: throw e
        }
        if (begun !is Tx1.Begun) return (begun as Tx1.Done).outcome
        val result = client.execute(OPERATION, begun.body)
        return try {
            apply(command.approvalId, result, OwnerOutcomeApplier.Source.Web(command.approverActorId, command.context))
        } catch (e: RuntimeException) {
            log.warn("operation=business-approve-tx2 approvalId={} result=failed failure={}", command.approvalId, e.javaClass.simpleName)
            ApprovalOutcome.Executing(command.approvalId, ApprovalStatus.EXECUTING.name)
        }
    }

    private sealed interface Tx1 {
        class Done(val outcome: ApprovalOutcome) : Tx1

        class Begun(val body: ByteArray) : Tx1
    }

    private fun tx1(command: DecideApproval, current: Map<String, Any?>?, status: TransactionStatus): Tx1 {
        val approverId = command.approverActorId
        val locked = repository.lock(command.approvalId) ?: return Tx1.Done(ApprovalOutcome.NotFound)
        val request = locked.value
        val audited = ApprovalAuditTarget.of(request.target)
        if (request.kind != KIND) throw IntegrityViolation(EXECUTION_MODEL, audited, request.id)
        val now = now()
        when (val decision = request.decideApproval(approverId, now)) {
            ApprovalDecision.AlreadyDecided -> return Tx1.Done(ApprovalOutcome.AlreadyDecided)
            is ApprovalDecision.Expired -> {
                repository.update(locked, decision.expired)
                audit(now, null, "APPROVAL_EXPIRED", "EXPIRED", command.context, request, null, null, actorId = approverId)
                return Tx1.Done(ApprovalOutcome.Expired)
            }
            is ApprovalDecision.Refused -> {
                audit(now, null, "APPROVAL_DENIED", "REFUSED", command.context, request, null, "reason=${decision.code}", actorId = approverId)
                return Tx1.Done(ApprovalOutcome.Refused(decision.code))
            }
            ApprovalDecision.Proceed -> Unit
        }
        val businessId = (request.target as? ApprovalTarget.Business)?.businessId ?: throw IntegrityViolation("target_kind", audited, request.id)
        val participants = executor.lockParticipants(listOf(request.requestedBy, approverId))
        val approver = participants[approverId]
        if (approver == null || !approver.holds(ApprovalParticipants.APPROVER_ROLE)) throw Refusal(ApprovalOutcome.Denied, "APPROVER_NOT_ELIGIBLE", audited, request.id)
        if (approver.sessionGeneration != command.sessionGeneration) throw Refusal(ApprovalOutcome.SessionReplaced, null, null)
        val snapshot = commands.find(request.id) ?: throw IntegrityViolation("command", audited, request.id)
        if (snapshot.businessId != businessId) throw IntegrityViolation("target_ref", audited, request.id)
        // ⑤ P-5 대조 확인: 체크 없으면 400 — TOTP를 쓰기 전(상태·TOTP 불변, 감사 APPROVAL_DENIED reason=REVALIDATION_REQUIRED).
        if (!command.revalidated) throw Refusal(ApprovalOutcome.Rejected(INVALID_INPUT, "revalidated", "REQUIRED"), REVALIDATION_REQUIRED, audited, request.id)
        if (executor.verifyStepUp(approverId, command.totpCode, command.context) == StepUpResult.FAILED) {
            status.setRollbackOnly()
            return Tx1.Done(ApprovalOutcome.StepUpFailed)
        }
        val requester = participants[request.requestedBy]
        if (requester == null || !requester.holds(ApprovalParticipants.requesterRole(request.kind))) {
            return Tx1.Done(invalidate(locked, request, snapshot, approver, REQUESTER_NOT_ELIGIBLE, now, command.context))
        }
        // ⑧ 대상 비교(A 단계 값): 기준 version과 다르거나 종료·사용중지면 무효 — MASTER 미호출(MASTER 실행 TX가 다시 판정한다).
        val sameTarget = current != null && (current["version"] as? Long) == snapshot.baseVersion && current["lifecycle"] == "ACTIVE" && current["registrationStatus"] != "INACTIVE"
        if (!sameTarget) return Tx1.Done(invalidate(locked, request, snapshot, approver, TARGET_CHANGED, now, command.context))
        val executeBefore = now.plus(policy.executionWindow)
        val built = try {
            contract.businessCriticalChange(
                request.id, ACTOR_PREFIX + request.requestedBy, ACTOR_PREFIX + approverId, now, executeBefore, snapshot.evidenceRef, snapshot.businessId, snapshot.baseVersion,
                snapshot.proposalId, snapshot.contentDigest,
            )
        } catch (_: IllegalArgumentException) {
            throw IntegrityViolation("command_body", audited, request.id)
        }
        val body = built.body()
        val execution = try {
            OwnerExecution.start(request.kind, request.id, body, built.fingerprint, now, executeBefore, approverId, now.plus(policy.staleAfter))
        } catch (_: IllegalArgumentException) {
            throw IntegrityViolation("fingerprint", audited, request.id)
        }
        executions.insert(execution)
        repository.update(locked, request.beginExecution(approverId, approver.displayName, now))
        audit(now, approver, "APPROVAL_EXECUTING", "EXECUTING", command.context, request, snapshot, "revalidated=Y", executor = approverId)
        return Tx1.Begun(body)
    }

    private fun invalidate(
        locked: VersionedApproval, request: ApprovalRequest, snapshot: BusinessChangeRequestSnapshot, approver: ParticipantView, code: String, now: Instant, context: AdminRequestContext,
    ): ApprovalOutcome {
        repository.update(locked, request.invalidated(approver.actorId, approver.displayName, code, now))
        audit(now, approver, "APPROVAL_INVALIDATED", "INVALIDATED", context, request, snapshot, "reason=$code")
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

    /**
     * 결과 반영(웹 TX2·회복 공용 — 가맹점 [MerchantOpsApprovalService.apply]와 같은 매핑): 결정 조회 지문·operation 대조(다르면 무결성 플래그 + 경보), 성공 reference = 그 사업자 ID(아니면
     * UNKNOWN + `REFERENCE_MISMATCH` + 확정 불가 finding — 자동 종결 없음), 400·413 재전송 중단(`OWNER_MALFORMED`).
     */
    internal fun apply(approvalId: String, result: MerchantApprovalResult, source: OwnerOutcomeApplier.Source, quietIfUnchanged: Boolean = false): ApprovalOutcome {
        val (request, execution, command) = transactions.execute({ false }) { Triple(repository.find(approvalId), executions.find(approvalId), commands.find(approvalId)) }
        val mismatch = result is MerchantApprovalResult.Decided && result.fingerprint != null && execution != null &&
            (result.fingerprint != execution.fingerprint || result.operation != OPERATION.code)
        if (mismatch) opsMetrics.integrityAlert(MerchantOutcomes.DECISION_CONTENT_MISMATCH)
        val foreign = result is MerchantApprovalResult.Decided && result.succeeded && result.reference != command?.businessId
        if (foreign) {
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

    // ------------------------------------------------------------------ recovery

    /** 회복 한 행(가맹점 [MerchantOpsApprovalService.recover]와 같은 0~5단계 — 시도 상한 없음, 수동 미실행 확정 없음). */
    internal fun recover(id: String, now: Instant) {
        val (request, execution, command) = transactions.execute({ false }) { Triple(repository.find(id), executions.find(id), commands.find(id)) }
        if (request == null || execution == null || command == null) {
            applier.reportIntegrity(id, if (request == null) "approval" else if (command == null) "command" else "execution", request?.let { ApprovalAuditTarget.of(it.target) })
            return
        }
        if (request.status !in ApprovalStatus.OWNER_SERVICE_ONLY) return
        if (request.kind != KIND || !execution.conformsTo(request.kind)) {
            applier.reportIntegrity(id, "execution_kind", ApprovalAuditTarget.of(request.target))
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
            when (val confirmed = client.confirmDeadline(OPERATION, execution.requestBody())) {
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
        val result = client.execute(OPERATION, execution.requestBody())
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

    private fun unresolvable(id: String, request: ApprovalRequest, code: String, now: Instant) {
        val detail = code.takeIf { FINDING_DETAIL.matches(it) } ?: MerchantOutcomes.OWNER_MALFORMED
        try {
            transactions.execute({ false }) { findings.record(ReconciliationFindingRecord(ReconciliationFindingKind.OWNER_UNRESOLVABLE, id, request.target.ref, detail, now)) }
            opsMetrics.reconFinding(ReconciliationFindingKind.OWNER_UNRESOLVABLE.name)
            opsMetrics.integrityAlert(ReconciliationFindingKind.OWNER_UNRESOLVABLE.name)
            log.warn("operation=business-recovery approvalId={} result=owner-unresolvable code={}", id, detail)
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

    // ------------------------------------------------------------------ original proxy (M-1)

    /**
     * 원문 프록시(R2-m4 — 판정 순서는 근거 조회 [OperatorCancelJustificationService] 관례, S3 리뷰 NIT-1): ① 승인 ID 형식(아니면 404 — 조회 없음) ② 세션 역할 판정(APPROVER·ACTIVE)
     * ③ 승인 행(없음·사업자 종류 아님 → 404, 감사 없음 — 열람할 원문이 없다) ④ 호출자 = APPROVER 또는 ACTIVE인 그 요청의 요청자(아니면 403 + 감사 DENIED) ⑤ 미해결 상태만(아니면 409
     * `APPROVAL_NOT_OPEN` + 감사 DENIED, MASTER 미호출) ⑥ 열람 감사(`APPROVAL_PROPOSAL_UNMASKED` — 값 없음, **반환 전** — 감사를 못 남기면 503) ⑦ MASTER 원문(속도 제한 공유).
     */
    override fun original(actorId: String, approvalId: String, context: AdminRequestContext): BusinessChangeOriginalResult {
        if (!APPROVAL_ID.matches(approvalId)) return BusinessChangeOriginalResult.NotFound
        val view = sessions.current(actorId)
        val allowedRole = view != null && view.isActive && ApprovalParticipants.APPROVER_ROLE in view.roles
        val request = transactions.execute({ false }) { repository.find(approvalId) }?.takeIf { it.kind == KIND } ?: return BusinessChangeOriginalResult.NotFound
        val audited = ApprovalAuditTarget.of(request.target)
        val mayView = allowedRole || (view != null && view.isActive && request.requestedBy == actorId)
        if (!mayView) {
            separateAudit(context, actorId, UNMASKED, "DENIED", audited, request.id, "kind=${KIND.name};reason=FORBIDDEN")
            return BusinessChangeOriginalResult.Forbidden
        }
        if (request.status !in UNRESOLVED) {
            separateAudit(context, actorId, UNMASKED, "DENIED", audited, request.id, "kind=${KIND.name};reason=$NOT_OPEN")
            return BusinessChangeOriginalResult.NotOpen
        }
        val command = transactions.execute({ false }) { commands.find(request.id) } ?: return BusinessChangeOriginalResult.Unavailable(INTERNAL_INTEGRITY)
        val read = channel.original(actorId, command.businessId, command.proposalId)
        val result = when (read) {
            is BusinessChangeRead.Found -> "OK"
            BusinessChangeRead.NotFound -> "NOT_FOUND"
            BusinessChangeRead.RateLimited -> "RATE_LIMITED"
            is BusinessChangeRead.Unavailable -> "UNAVAILABLE"
        }
        try {
            separateAudit(context, actorId, UNMASKED, result, audited, request.id, "kind=${KIND.name}")
        } catch (e: RuntimeException) {
            log.warn("operation=business-proposal-original approvalId={} result=audit-unavailable failure={}", approvalId, e.javaClass.simpleName)
            return BusinessChangeOriginalResult.Unavailable(AUDIT_UNAVAILABLE)
        }
        @Suppress("UNCHECKED_CAST")
        return when (read) {
            is BusinessChangeRead.Found -> {
                if (read.document["proposalId"] != command.proposalId || read.document["businessId"] != command.businessId) return BusinessChangeOriginalResult.Unavailable(MASTER_UNAVAILABLE)
                BusinessChangeOriginalResult.Found(request.id, read.document["content"] as Map<String, Any?>)
            }
            BusinessChangeRead.NotFound -> BusinessChangeOriginalResult.Unavailable(MASTER_UNAVAILABLE)
            BusinessChangeRead.RateLimited -> BusinessChangeOriginalResult.RateLimited
            is BusinessChangeRead.Unavailable -> BusinessChangeOriginalResult.Unavailable(MASTER_UNAVAILABLE)
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun holdsNow(actorId: String, role: String): Boolean = sessions.current(actorId)?.let { it.isActive && role in it.roles } == true

    private fun snapshotName(actorId: String): String = sessions.current(actorId)?.displayName ?: actorId

    /** 승인 행 표시 스냅샷(값 없음 — 제안 ID·기준 version·항목 이름·정정 여부만, M-5). */
    private fun snapshotJson(s: BusinessChangeRequestSnapshot): String =
        """{"proposalId":"${s.proposalId}","baseVersion":${s.baseVersion},"changedFields":[${s.changedFields.joinToString(",") { "\"$it\"" }}],""" +
            """"representativeChanged":${s.representativeChanged},"subjectCorrection":${s.subjectCorrection}}"""

    private fun refused(
        context: AdminRequestContext, actorId: String, target: ApprovalAuditTarget, reason: String, outcome: BusinessChangeRequestOutcome,
    ): BusinessChangeRequestOutcome {
        separateAudit(context, actorId, "APPROVAL_DENIED", "DENIED", target, null, "kind=${KIND.name};reason=$reason")
        return outcome
    }

    private fun guardedRequest(context: AdminRequestContext, actorId: String, target: ApprovalAuditTarget, block: () -> BusinessChangeRequestOutcome): BusinessChangeRequestOutcome = try {
        retryLater(block)
    } catch (refusal: RequestRefusal) {
        refusal.auditReason?.let { separateAudit(context, actorId, "APPROVAL_DENIED", "DENIED", target, null, "kind=${KIND.name};reason=$it") }
        refusal.outcome
    } catch (_: AdminRetryLaterException) {
        BusinessChangeRequestOutcome.RetryLater
    }

    private fun guardedTx1(command: DecideApproval, block: (TransactionStatus) -> Tx1): Tx1 {
        val outcome = try {
            retryLater { transactions.execute({ false }, block) }
        } catch (refusal: Refusal) {
            refusal.auditReason?.let { separateAudit(command.context, command.approverActorId, "APPROVAL_DENIED", "DENIED", refusal.target, refusal.approvalId, "kind=${KIND.name};reason=$it") }
            return Tx1.Done(refusal.outcome)
        } catch (violation: IntegrityViolation) {
            metrics.integrityViolation(violation.column)
            separateAudit(
                command.context, command.approverActorId, "APPROVAL_INTEGRITY_VIOLATION", "FAILED", violation.target, violation.approvalId ?: command.approvalId,
                "column=${violation.column}",
            )
            return Tx1.Done(ApprovalOutcome.IntegrityViolation)
        } catch (_: AdminRetryLaterException) {
            return Tx1.Done(ApprovalOutcome.RetryLater)
        }
        if (outcome is Tx1.Done && outcome.outcome == ApprovalOutcome.StepUpFailed) failures.recordFailure(command.approverActorId, AuthenticationStep.STEP_UP_TOTP, command.context)
        return outcome
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
    private fun separateAudit(context: AdminRequestContext, actorId: String, action: String, result: String, target: ApprovalAuditTarget?, approvalId: String?, detail: String) {
        transactions.execute({ false }) {
            val actor = executor.snapshot(actorId)
            recorder.record(
                target.auditRecord(
                    occurredAt = now(), actorKind = if (actor != null) "ADMIN" else "ANONYMOUS", actorId = actor?.actorId, actorDisplayName = actor?.displayName,
                    action = action, resultCode = result, correlationId = context.correlationId, approvalId = approvalId, remoteIp = context.remoteIp, detail = detail,
                ),
            )
        }
    }

    /** 승인 행 감사: 대상 = 사업자 ID, detail = 종류 + (명령 행이 있으면) 값 없는 항목 요약(§5.2 `fields=;rep=;subject=;evidence=PRESENT`) + 사유 분류. */
    @Suppress("LongParameterList")
    private fun audit(
        now: Instant, actor: ParticipantView?, action: String, result: String, context: AdminRequestContext, request: ApprovalRequest, snapshot: BusinessChangeRequestSnapshot?,
        reasonDetail: String?, actorId: String? = actor?.actorId, executor: String? = null,
    ) {
        recorder.record(
            ApprovalAuditTarget.of(request.target).auditRecord(
                occurredAt = now, actorKind = "ADMIN", actorId = actorId, actorDisplayName = actor?.displayName, action = action, resultCode = result,
                correlationId = context.correlationId, executorActorId = executor, approvalId = request.id, remoteIp = context.remoteIp,
                detail = listOfNotNull("kind=${request.kind.name}", snapshot?.auditDetail(), reasonDetail).joinToString(";"),
            ),
        )
    }

    private fun now(): Instant = environment.now().truncatedTo(ChronoUnit.MILLIS)

    private inline fun observed(operation: String, block: () -> ApprovalOutcome): ApprovalOutcome {
        val outcome = try {
            block()
        } catch (e: RuntimeException) {
            metrics.decision(operation, KIND.name, "ERROR")
            throw e
        }
        if (outcome == ApprovalOutcome.RetryLater) metrics.retryLater(operation)
        metrics.decision(operation, KIND.name, outcome.javaClass.simpleName.uppercase())
        return outcome
    }

    private inline fun observedRequest(block: () -> BusinessChangeRequestOutcome): BusinessChangeRequestOutcome {
        val outcome = try {
            block()
        } catch (e: RuntimeException) {
            metrics.decision(REQUEST, KIND.name, "ERROR")
            throw e
        }
        if (outcome == BusinessChangeRequestOutcome.RetryLater) metrics.retryLater(REQUEST)
        metrics.decision(REQUEST, KIND.name, outcome.javaClass.simpleName.uppercase())
        return outcome
    }

    private class Refusal(val outcome: ApprovalOutcome, val auditReason: String?, val target: ApprovalAuditTarget?, val approvalId: String? = null) :
        RuntimeException(null, null, false, false)

    /** 요청 TX rollback 거절(감사 대상 = 요청 본문 사업자 ID — 승인 행 없음). */
    private class RequestRefusal(val outcome: BusinessChangeRequestOutcome, val auditReason: String?) : RuntimeException(null, null, false, false)

    private class IntegrityViolation(val column: String, val target: ApprovalAuditTarget? = null, val approvalId: String? = null) : RuntimeException(null, null, false, false)

    private companion object {
        val log = LoggerFactory.getLogger(BusinessChangeApprovalService::class.java)
        val KIND = ApprovalKind.BUSINESS_CRITICAL_CHANGE
        val OPERATION = MerchantApprovalOperation.APPLY_BUSINESS_CRITICAL_CHANGE
        val UNRESOLVED = setOf(ApprovalStatus.PENDING, ApprovalStatus.EXECUTING, ApprovalStatus.EXECUTION_UNKNOWN)
        const val ACTOR_PREFIX = "admin:"
        const val INVALID_INPUT = "INVALID_INPUT"
        const val REQUEST = "OWNER_REQUEST"
        const val APPROVE = "OWNER_APPROVE"
        const val UNMASKED = "APPROVAL_PROPOSAL_UNMASKED"
        const val NOT_OPEN = "APPROVAL_NOT_OPEN"
        const val MASTER_UNAVAILABLE = "MASTER_UNAVAILABLE"
        const val MASTER_BUSY = "MASTER_BUSY"
        const val MASTER_RETRY_SAME_KEY = "MASTER_RETRY_SAME_KEY"
        const val MASTER_OUTCOME_UNKNOWN = "MASTER_OUTCOME_UNKNOWN"
        const val AUDIT_UNAVAILABLE = "AUDIT_UNAVAILABLE"
        const val INTERNAL_INTEGRITY = "INTERNAL_INTEGRITY"
        const val KEY_REUSED = "IDEMPOTENCY_KEY_REUSED"
        const val DUPLICATE_UNRESOLVED = "DUPLICATE_UNRESOLVED"
        const val REQUESTER_NOT_ELIGIBLE = "REQUESTER_NOT_ELIGIBLE"
        const val REVALIDATION_REQUIRED = "REVALIDATION_REQUIRED"
        const val TARGET_CHANGED = "TARGET_CHANGED"
        const val WRONG_EXECUTION_MODEL = "WRONG_EXECUTION_MODEL"
        const val EXECUTION_MODEL = "execution_model"
        const val REQUESTED_BY = "requested_by"
        const val COMMAND_SNAPSHOT = "command_snapshot"
        const val PROPOSAL_ALREADY_USED = "PROPOSAL_ALREADY_USED"
        val APPROVAL_ID = Regex("^apr_[0-9A-HJKMNP-TV-Z]{26}$")
        const val MAX_CODE = 48
        const val REFERENCE_MISMATCH = "REFERENCE_MISMATCH"
        const val PROPOSAL_REFERENCE_MISMATCH = "PROPOSAL_REFERENCE_MISMATCH"
        val FINDING_DETAIL = Regex("^[A-Z_]{1,48}$")
    }
}

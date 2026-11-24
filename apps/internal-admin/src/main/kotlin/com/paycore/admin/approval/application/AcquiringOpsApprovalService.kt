package com.paycore.admin.approval.application

import com.paycore.admin.account.application.provided.AdminOwnedCommandExecutor
import com.paycore.admin.account.application.provided.AdminRequestContext
import com.paycore.admin.account.application.provided.AdminRetryLaterException
import com.paycore.admin.account.application.provided.AdminSessionQuery
import com.paycore.admin.account.application.provided.AuthenticationFailureRecorder
import com.paycore.admin.account.application.provided.AuthenticationStep
import com.paycore.admin.account.application.provided.ParticipantView
import com.paycore.admin.account.application.provided.StepUpResult
import com.paycore.admin.approval.application.provided.AcquiringOpsApprovalUseCase
import com.paycore.admin.approval.application.provided.ApprovalOutcome
import com.paycore.admin.approval.application.provided.DecideApproval
import com.paycore.admin.approval.application.provided.RequestReacquisition
import com.paycore.admin.approval.application.required.AcquiringOpsCommandStore
import com.paycore.admin.approval.application.required.ApprovalEnvironment
import com.paycore.admin.approval.application.required.ApprovalMetrics
import com.paycore.admin.approval.application.required.ApprovalPersistenceFailure
import com.paycore.admin.approval.application.required.ApprovalRequestRepository
import com.paycore.admin.approval.application.required.OwnerExecutionRepository
import com.paycore.admin.approval.application.required.OwnerOpsMetrics
import com.paycore.admin.approval.application.required.VersionedApproval
import com.paycore.admin.approval.domain.ApprovalDecision
import com.paycore.admin.approval.domain.ApprovalKind
import com.paycore.admin.approval.domain.ApprovalParticipants
import com.paycore.admin.approval.domain.ApprovalRequest
import com.paycore.admin.approval.domain.ApprovalStatus
import com.paycore.admin.approval.domain.ApprovalTarget
import com.paycore.admin.approval.domain.OwnerExecution
import com.paycore.admin.approval.domain.PinnedReacquisitionItem
import com.paycore.admin.approval.domain.PinnedReacquisitionTarget
import com.paycore.admin.approval.domain.ReacquisitionInputRules
import com.paycore.admin.approval.domain.ReacquisitionRequestSnapshot
import com.paycore.admin.audit.application.provided.AdminAuditRecorder
import com.paycore.admin.financialops.application.provided.AcquiringOpsClient
import com.paycore.admin.financialops.application.provided.AcquiringOpsPolicy
import com.paycore.admin.financialops.application.provided.AcquiringOpsResult
import com.paycore.admin.financialops.application.provided.ReacquisitionCommandInput
import com.paycore.admin.financialops.application.provided.ReacquisitionCommandItem
import com.paycore.admin.financialops.application.provided.ReacquisitionContract
import com.paycore.admin.financialops.application.provided.ReacquisitionTargetsResult
import com.paycore.admin.financialops.application.provided.ReacquisitionTargetsView
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionStatus
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 재매입 2인 승인(ACQ-ADMIN-001 B1-A §3.1 — 운영자 보정 취소 [OwnerServiceApprovalService]와 같은 OWNER_SERVICE 실행 모델, I-1). **FINANCIAL 호출은 모두 DB TX 밖**
 * (요청 전 미리 보기·승인 A 미리 보기 — 조회 키, C 실행 POST — 조작 키: client가 TX 안이면 거부). 로컬 TX는 재시도하지 않는다(TOTP 재사용 없음).
 * - 요청(OPERATOR — D-ACQA-002): 입력 규칙 → 세션 역할(비권위) → 미리 보기(묶음 전부 FINANCIAL eligible이어야 — I-8 advisory) → TX(승인 행 INSERT(`ACQFILE:` 미해결 하나) →
 *   요청자 잠금·재검사 → 명령 행 INSERT(pinned = 레코드별 세대·상태·예상 순액) → 감사).
 * - 승인(APPROVER + 직전 TOTP): A 미리 보기 재조회 → TX1(잠금·판정표·참여자·TOTP 소비·요청자 재검사·pinned vs 현재(세대·상태·eligible — 다르면 INVALIDATED(TARGET_CHANGED))·
 *   acd1 내용 hash·본문 저장·EXECUTING) → C 조작 POST → TX2([OwnerOutcomeApplier] — 회복 job과 같은 규칙).
 * 잠금 순서: 승인 행 → 계정 행(오름차순) → 실행 행(§4.14 그대로). 감사 target_ref = 원 파일 ID. 사유 문구·근거·본문은 감사·로그·지표에 없다(I-4).
 * Kafka/Redis 없음 — 미해결 하나는 ADMIN UNIQUE, 최초 결정은 FINANCIAL approvalId UNIQUE가 지킨다.
 */
@Service
class AcquiringOpsApprovalService(
    transactionManager: PlatformTransactionManager,
    private val repository: ApprovalRequestRepository,
    private val commands: AcquiringOpsCommandStore,
    private val executions: OwnerExecutionRepository,
    private val executor: AdminOwnedCommandExecutor,
    private val failures: AuthenticationFailureRecorder,
    private val sessions: AdminSessionQuery,
    private val recorder: AdminAuditRecorder,
    private val environment: ApprovalEnvironment,
    private val metrics: ApprovalMetrics,
    private val opsMetrics: OwnerOpsMetrics,
    private val client: AcquiringOpsClient,
    private val contract: ReacquisitionContract,
    private val policy: AcquiringOpsPolicy,
    private val applier: OwnerOutcomeApplier,
) : AcquiringOpsApprovalUseCase {

    private val transactions = LocalTransactions(transactionManager, maxAttempts = 1)
    private val kind = ApprovalKind.ACQ_REACQUISITION

    // ------------------------------------------------------------------ request

    override fun requestReacquisition(command: RequestReacquisition): ApprovalOutcome = observed(REQUEST) { requestStep(command) }

    private fun requestStep(command: RequestReacquisition): ApprovalOutcome {
        ReacquisitionInputRules.problem(command.fileId, command.seqs, command.reasonCode, command.evidenceRef, command.reason)?.let { (field, reason) ->
            return ApprovalOutcome.Rejected(INVALID_INPUT, field, reason)
        }
        if (!holdsNow(command.requesterActorId, ApprovalParticipants.requesterRole(kind))) return ApprovalOutcome.Denied
        val view = when (val found = client.targets(command.fileId, command.seqs)) {
            is ReacquisitionTargetsResult.Found -> found.view
            ReacquisitionTargetsResult.NotFound -> return refuseAudited(command, ApprovalOutcome.Rejected(FILE_NOT_FOUND), FILE_NOT_FOUND)
            is ReacquisitionTargetsResult.Unavailable -> return ApprovalOutcome.Unavailable(FINANCIAL_UNAVAILABLE)
        }
        val pinned = pinnedOf(command.fileId, command.seqs, view)
            ?: return refuseAudited(command, ApprovalOutcome.Rejected(NOT_ELIGIBLE), NOT_ELIGIBLE)
        return guarded(command.context, command.requesterActorId, command.fileId) {
            transactions.execute({ false }) {
                val now = now()
                val request = ApprovalRequest.create(
                    environment.newApprovalId(), kind, ApprovalTarget.AcquisitionFile(command.fileId), pinned.toSnapshotJson(), null, null, command.requesterActorId,
                    snapshotName(command.requesterActorId), now, environment.validity,
                )
                try {
                    repository.insert(request)
                } catch (_: ApprovalPersistenceFailure.DuplicateUnresolved) {
                    throw Refusal(ApprovalOutcome.Rejected(DUPLICATE_UNRESOLVED), DUPLICATE_UNRESOLVED, command.fileId)
                }
                val requester = executor.lockParticipants(listOf(command.requesterActorId))[command.requesterActorId]
                if (requester == null || !requester.holds(ApprovalParticipants.requesterRole(kind))) throw Refusal(ApprovalOutcome.Denied, REQUESTER_NOT_ELIGIBLE, command.fileId)
                if (requester.sessionGeneration != command.sessionGeneration) throw Refusal(ApprovalOutcome.SessionReplaced, null, null)
                commands.insert(ReacquisitionRequestSnapshot(request.id, command.fileId, command.reasonCode, command.evidenceRef, command.reason, pinned, now))
                audit(now, requester, "APPROVAL_REQUESTED", "SUCCESS", command.context, request, null)
                ApprovalOutcome.Created(request.id)
            }
        }
    }

    /** 미리 보기 → 고정 대상. 요청한 순번이 모두 있고 모두 eligible(FINANCIAL 계산)일 때만, 아니면 null(advisory 거절 — 부분 요청 없음, D-ACQA-003). */
    private fun pinnedOf(fileId: String, seqs: List<Int>, view: ReacquisitionTargetsView): PinnedReacquisitionTarget? {
        if (view.fileId != fileId) return null
        val bySeq = view.items.associateBy { it.seq }
        val items = seqs.map { seq ->
            val i = bySeq[seq] ?: return null
            if (!i.eligible || i.transactionId == null || i.recordType == null || i.generation == null || i.status == null || i.approvedAmountMinor == null ||
                i.cancelledAmountMinor == null || i.expectedNetMinor == null
            ) {
                return null
            }
            runCatching {
                PinnedReacquisitionItem(seq, i.transactionId, i.recordType, i.generation, i.status, i.approvedAmountMinor, i.cancelledAmountMinor, i.expectedNetMinor)
            }.getOrNull() ?: return null
        }
        return PinnedReacquisitionTarget(fileId, items)
    }

    // ------------------------------------------------------------------ approve

    fun approve(command: DecideApproval): ApprovalOutcome = observed(APPROVE) { approveStep(command) }

    private fun approveStep(command: DecideApproval): ApprovalOutcome {
        if (!holdsNow(command.approverActorId, ApprovalParticipants.APPROVER_ROLE)) return ApprovalOutcome.Denied
        val seen = transactions.execute({ false }) { repository.find(command.approvalId) } ?: return ApprovalOutcome.NotFound
        if (seen.kind != kind) return ApprovalOutcome.Rejected(WRONG_EXECUTION_MODEL)
        if (seen.status != ApprovalStatus.PENDING) return ApprovalOutcome.AlreadyDecided
        val pinnedSeqs = transactions.execute({ false }) { commands.find(seen.id) }?.pinnedTarget?.seqs ?: return ApprovalOutcome.IntegrityViolation
        // A(TX 밖, 조회 키): 대상 재조회. 장애면 503(상태·TOTP 불변).
        val current = when (val found = client.targets(seen.target.ref, pinnedSeqs)) {
            is ReacquisitionTargetsResult.Found -> found.view
            ReacquisitionTargetsResult.NotFound -> return ApprovalOutcome.Rejected(TARGET_NOT_FOUND)
            is ReacquisitionTargetsResult.Unavailable -> return ApprovalOutcome.Unavailable(FINANCIAL_UNAVAILABLE)
        }
        val begun = try {
            guardedTx1(command) { status -> tx1(command, current, status) }
        } catch (e: CommitOutcomeUnknownException) {
            log.warn("operation=acq-approve-tx1 approvalId={} result=commit-unknown", command.approvalId)
            converge(command.approvalId) ?: throw e
        }
        if (begun !is Tx1.Begun) return (begun as Tx1.Done).outcome
        // C(TX 밖, 조작 키): 저장 본문 그대로.
        val result = client.execute(command.approvalId, begun.body)
        AcquiringOutcomes.alert(result)?.let(opsMetrics::integrityAlert)
        return try {
            apply(command.approvalId, result, OwnerOutcomeApplier.Source.Web(command.approverActorId, command.context))
        } catch (e: RuntimeException) {
            log.warn("operation=acq-approve-tx2 approvalId={} result=failed failure={}", command.approvalId, e.javaClass.simpleName)
            ApprovalOutcome.Executing(command.approvalId, ApprovalStatus.EXECUTING.name)
        }
    }

    /** 결과 반영(웹 TX2·회복 job 공용): 결정이면 내용 대조 후 반영(불일치면 플래그 먼저). */
    internal fun apply(approvalId: String, result: AcquiringOpsResult, source: OwnerOutcomeApplier.Source, quietIfUnchanged: Boolean = false): ApprovalOutcome {
        val mismatch = if (result is AcquiringOpsResult.Decided) {
            val (request, execution) = transactions.execute({ false }) { repository.find(approvalId) to executions.find(approvalId) }
            request != null && execution != null && AcquiringOutcomes.mismatch(result.decision, request, execution)
        } else {
            false
        }
        if (mismatch) opsMetrics.integrityAlert(AcquiringOutcomes.DECISION_CONTENT_MISMATCH)
        return applier.apply(approvalId, AcquiringOutcomes.outcome(result), source, contentMismatch = mismatch, quietIfUnchanged = quietIfUnchanged)
    }

    private sealed interface Tx1 {
        class Done(val outcome: ApprovalOutcome) : Tx1

        class Begun(val body: ByteArray) : Tx1
    }

    private fun tx1(command: DecideApproval, current: ReacquisitionTargetsView, status: TransactionStatus): Tx1 {
        val approverId = command.approverActorId
        val locked = repository.lock(command.approvalId) ?: return Tx1.Done(ApprovalOutcome.NotFound)
        val request = locked.value
        if (request.kind != kind) throw IntegrityViolation(EXECUTION_MODEL, null, request.id)
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
        val fileId = request.target.ref
        val participants = executor.lockParticipants(listOf(request.requestedBy, approverId))
        val approver = participants[approverId]
        if (approver == null || !approver.holds(ApprovalParticipants.APPROVER_ROLE)) throw Refusal(ApprovalOutcome.Denied, "APPROVER_NOT_ELIGIBLE", fileId, request.id)
        if (approver.sessionGeneration != command.sessionGeneration) throw Refusal(ApprovalOutcome.SessionReplaced, null, null)
        if (executor.verifyStepUp(approverId, command.totpCode, command.context) == StepUpResult.FAILED) {
            status.setRollbackOnly()
            return Tx1.Done(ApprovalOutcome.StepUpFailed)
        }
        val requester = participants[request.requestedBy]
        if (requester == null || !requester.holds(ApprovalParticipants.requesterRole(request.kind))) {
            return Tx1.Done(invalidate(locked, request, approver, REQUESTER_NOT_ELIGIBLE, now, command.context))
        }
        // 명령 행은 INSERT-only — 잠그지 않고 읽는다. 권위 고정 값 = pinned_target.
        val snapshot = commands.find(request.id) ?: throw IntegrityViolation("command", fileId, request.id)
        if (snapshot.originalFileId != fileId) throw IntegrityViolation("target_ref", fileId, request.id)
        if (changed(snapshot.pinnedTarget, current)) return Tx1.Done(invalidate(locked, request, approver, TARGET_CHANGED, now, command.context))
        val executeBefore = now.plus(policy.executionWindow)
        val built = contract.command(
            ReacquisitionCommandInput(
                request.id, fileId, snapshot.pinnedTarget.items.map { ReacquisitionCommandItem(it.seq, it.generation, it.status) }, request.requestedBy, approverId,
                snapshot.reasonCode, snapshot.evidenceRef, now, executeBefore, command.context.correlationId,
            ),
        )
        val body = built.body()
        // B1-A 리뷰 m-4: 종류를 아는 Factory — 계약이 재매입 지문(acd1)이 아닌 값을 주면 rollback + 무결성 위반(POST 없음).
        val execution = try {
            OwnerExecution.start(kind, request.id, body, built.contentHash, now, executeBefore, approverId, now.plus(policy.staleAfter))
        } catch (_: IllegalArgumentException) {
            throw IntegrityViolation("fingerprint", fileId, request.id)
        }
        executions.insert(execution)
        repository.update(locked, request.beginExecution(approverId, approver.displayName, now))
        audit(now, approver, "APPROVAL_EXECUTING", "EXECUTING", command.context, request, null, executor = approverId)
        return Tx1.Begun(body)
    }

    /** 고정 값 vs 현재(승인 A 재조회 — 비권위지만 FINANCIAL이 실행 TX에서 잠금 아래 다시 비교): 레코드마다 세대·상태 같고 지금도 eligible. */
    private fun changed(pinned: PinnedReacquisitionTarget, current: ReacquisitionTargetsView): Boolean {
        if (current.fileId != pinned.fileId) return true
        val bySeq = current.items.associateBy { it.seq }
        return pinned.items.any { p ->
            val c = bySeq[p.seq]
            c == null || !c.eligible || c.generation != p.generation || c.status != p.status
        }
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

    // ------------------------------------------------------------------ helpers (OwnerServiceApprovalService와 같은 규칙 — target_ref = 원 파일 ID)

    private fun holdsNow(actorId: String, role: String): Boolean = sessions.current(actorId)?.let { it.isActive && role in it.roles } == true

    private fun snapshotName(actorId: String): String = sessions.current(actorId)?.displayName ?: actorId

    private fun refuseAudited(command: RequestReacquisition, outcome: ApprovalOutcome, reason: String): ApprovalOutcome {
        separateAudit(command.context, command.requesterActorId, "APPROVAL_DENIED", "DENIED", command.fileId, null, "kind=${kind.name};reason=$reason")
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

    private fun guarded(context: AdminRequestContext, actorId: String, targetRef: String, block: () -> ApprovalOutcome): ApprovalOutcome = try {
        retryLater(block)
    } catch (refusal: Refusal) {
        refusal.auditReason?.let { separateAudit(context, actorId, "APPROVAL_DENIED", "DENIED", refusal.targetRef, refusal.approvalId, "kind=${kind.name};reason=$it") }
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
            // MERCHANT-CODE-001 MC-3a(§5.6.4 — C-36): 대상 열은 ApprovalAuditTarget에서만(매입 원 파일 ID — 값 불변).
            recorder.record(
                ApprovalAuditTarget.ofFileRef(targetRef).auditRecord(
                    occurredAt = now(), actorKind = if (actor != null) "ADMIN" else "ANONYMOUS", actorId = actor?.actorId, actorDisplayName = actor?.displayName,
                    action = action, resultCode = result, correlationId = context.correlationId, approvalId = approvalId, remoteIp = context.remoteIp, detail = detail,
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
            ApprovalAuditTarget.of(request.target).auditRecord(
                occurredAt = now, actorKind = "ADMIN", actorId = actorId, actorDisplayName = actor?.displayName, action = action, resultCode = result,
                correlationId = context.correlationId, executorActorId = executor, approvalId = request.id, remoteIp = context.remoteIp,
                detail = listOfNotNull("kind=${request.kind.name}", reasonDetail).joinToString(";"),
            ),
        )
    }

    private fun now(): Instant = environment.now().truncatedTo(ChronoUnit.MILLIS)

    private inline fun observed(operation: String, block: () -> ApprovalOutcome): ApprovalOutcome {
        val outcome = try {
            block()
        } catch (e: RuntimeException) {
            metrics.decision(operation, kind.name, "ERROR")
            throw e
        }
        if (outcome == ApprovalOutcome.RetryLater) metrics.retryLater(operation)
        metrics.decision(operation, kind.name, outcome.javaClass.simpleName.uppercase())
        return outcome
    }

    private class Refusal(val outcome: ApprovalOutcome, val auditReason: String?, val targetRef: String?, val approvalId: String? = null) :
        RuntimeException(null, null, false, false)

    private class IntegrityViolation(val column: String, val targetRef: String? = null, val approvalId: String? = null) : RuntimeException(null, null, false, false)

    private companion object {
        val log = LoggerFactory.getLogger(AcquiringOpsApprovalService::class.java)
        const val INVALID_INPUT = "INVALID_INPUT"
        const val REQUEST = "OWNER_REQUEST"
        const val APPROVE = "OWNER_APPROVE"
        const val FILE_NOT_FOUND = "ACQUISITION_FILE_NOT_FOUND"
        const val FINANCIAL_UNAVAILABLE = "FINANCIAL_UNAVAILABLE"
        const val NOT_ELIGIBLE = "TARGET_NOT_ELIGIBLE_ADVISORY"
        const val DUPLICATE_UNRESOLVED = "DUPLICATE_UNRESOLVED"
        const val REQUESTER_NOT_ELIGIBLE = "REQUESTER_NOT_ELIGIBLE"
        const val TARGET_CHANGED = "TARGET_CHANGED"
        const val TARGET_NOT_FOUND = "TARGET_NOT_FOUND"
        const val WRONG_EXECUTION_MODEL = "WRONG_EXECUTION_MODEL"
        const val EXECUTION_MODEL = "execution_model"
    }
}

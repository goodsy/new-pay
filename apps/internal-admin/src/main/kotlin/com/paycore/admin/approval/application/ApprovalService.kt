package com.paycore.admin.approval.application

import com.paycore.admin.account.application.provided.AdminOwnedCommandExecutor
import com.paycore.admin.account.application.provided.AdminRequestContext
import com.paycore.admin.account.application.provided.AdminRetryLaterException
import com.paycore.admin.account.application.provided.AuthenticationFailureRecorder
import com.paycore.admin.account.application.provided.AuthenticationStep
import com.paycore.admin.account.application.provided.OwnedCommandEvidence
import com.paycore.admin.account.application.provided.OwnedCommandOutcome
import com.paycore.admin.account.application.provided.ParticipantView
import com.paycore.admin.account.application.provided.StepUpResult
import com.paycore.admin.approval.application.provided.ApprovalOutcome
import com.paycore.admin.approval.application.provided.ApprovalUseCase
import com.paycore.admin.approval.application.provided.DecideApproval
import com.paycore.admin.approval.application.provided.RequestApproval
import com.paycore.admin.approval.application.provided.WithdrawApproval
import com.paycore.admin.approval.application.required.ApprovalEnvironment
import com.paycore.admin.approval.application.required.ApprovalMetrics
import com.paycore.admin.approval.application.required.ApprovalPersistenceFailure
import com.paycore.admin.approval.application.required.ApprovalRequestRepository
import com.paycore.admin.approval.domain.ApprovalCause
import com.paycore.admin.approval.domain.ApprovalDecision
import com.paycore.admin.approval.domain.ApprovalKind
import com.paycore.admin.approval.domain.ApprovalParticipants
import com.paycore.admin.approval.domain.ApprovalRequest
import com.paycore.admin.approval.domain.ApprovalStatus
import com.paycore.admin.audit.application.provided.AdminAuditRecorder
import com.paycore.admin.audit.application.provided.AuditRecord
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionStatus
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * [ApprovalUseCase] orchestrator(§4.7.3 `ApproveAdminOwnedRequestService` 역할 — 요청·승인·거절·철회·만료를 한 서비스에 둔다). 로컬 TX는 재시도하지 않는다
 * (`maxAttempts = 1` — 승인자의 TOTP 입력을 다시 쓰지 않는다). 거부 감사가 주 TX와 함께 사라지면 안 되는 경우(rollback되는 거부)는 별도 TX로 커밋한다(rev 0.5 n-4).
 * 감사 행위: `APPROVAL_REQUESTED`·`APPROVAL_EXECUTED`(실행자 = 승인자)·`APPROVAL_INVALIDATED`·`APPROVAL_OWNER_REJECTED`·`APPROVAL_REJECTED`·`APPROVAL_WITHDRAWN`·
 * `APPROVAL_EXPIRED`·`APPROVAL_DENIED`(`reason=…`)·`APPROVAL_INTEGRITY_VIOLATION`(`column=…`). 재인증 실패는 `AuthenticationFailureRecorder`가 `AUTHENTICATION_FAILED step=STEP_UP_TOTP`로.
 */
@Service
class ApprovalService(
    transactionManager: PlatformTransactionManager,
    private val repository: ApprovalRequestRepository,
    private val executor: AdminOwnedCommandExecutor,
    private val failures: AuthenticationFailureRecorder,
    private val recorder: AdminAuditRecorder,
    private val environment: ApprovalEnvironment,
    private val metrics: ApprovalMetrics,
) : ApprovalUseCase {

    private val transactions = LocalTransactions(transactionManager, maxAttempts = 1)

    // ------------------------------------------------------------------ request

    override fun request(command: RequestApproval): ApprovalOutcome =
        observed(REQUEST, KindLabel(ApprovalKind.entries.firstOrNull { it.name == command.kind }?.name ?: UNKNOWN)) { requestStep(command) }

    private fun requestStep(command: RequestApproval): ApprovalOutcome {
        val kind = ApprovalKind.entries.firstOrNull { it.name == command.kind } ?: return INVALID
        if (kind.needsGrantedRole != (command.grantedRole != null) || kind.needsCause != (command.causeApprovalId != null)) return INVALID
        if (command.grantedRole != null && command.grantedRole !in ROLES) return INVALID
        // S6 리뷰 m-2: 원인 형식(승인 ID 또는 복구 회차)은 TX 전에 검사한다 — 형식 오류는 400 INVALID_INPUT(감사·TX 없음, D-65).
        if (command.causeApprovalId != null && !ApprovalCause.isValid(command.causeApprovalId)) return INVALID
        if (command.requesterActorId == command.targetActorId) return ApprovalOutcome.Refused("SELF_TARGET")
        return guarded(command.context, command.requesterActorId) {
            transactions.execute({ false }) {
                val now = now()
                val requesterSnapshot = executor.snapshot(command.requesterActorId) ?: throw Refusal(ApprovalOutcome.Denied, "REQUESTER_NOT_ELIGIBLE", null)
                val targetSnapshot = executor.snapshot(command.targetActorId) ?: throw Refusal(ApprovalOutcome.Rejected(TARGET_NOT_FOUND), TARGET_NOT_FOUND, null)
                val request = ApprovalRequest.create(
                    environment.newApprovalId(), kind, command.targetActorId, targetSnapshot.securityVersion, snapshotJson(targetSnapshot), command.grantedRole,
                    command.causeApprovalId, command.requesterActorId, requesterSnapshot.displayName, now, environment.validity,
                )
                // §4.13 생성 순서: 승인 행 INSERT 먼저(새 행은 다른 TX에 보이지 않음 — "승인 행 → 계정 행" 순서와 같다), 그다음 요청자·대상 계정 오름차순 잠금.
                try {
                    repository.insert(request)
                } catch (_: ApprovalPersistenceFailure.DuplicatePending) {
                    throw Refusal(ApprovalOutcome.Rejected(DUPLICATE_PENDING), DUPLICATE_PENDING, command.targetActorId)
                }
                val participants = executor.lockParticipants(listOf(command.requesterActorId, command.targetActorId))
                val requester = participants[command.requesterActorId]
                if (requester == null || !requester.holds(ApprovalParticipants.REQUESTER_ROLE)) throw Refusal(ApprovalOutcome.Denied, "REQUESTER_NOT_ELIGIBLE", command.targetActorId)
                if (requester.sessionGeneration != command.sessionGeneration) throw Refusal(ApprovalOutcome.SessionReplaced, null, null)
                val target = participants[command.targetActorId] ?: throw Refusal(ApprovalOutcome.Rejected(TARGET_NOT_FOUND), TARGET_NOT_FOUND, command.targetActorId)
                if (target.securityVersion != targetSnapshot.securityVersion) throw Refusal(ApprovalOutcome.RetryLater, null, null)
                eligibility(kind, command, target, requester)?.let { throw Refusal(ApprovalOutcome.Rejected(it), it, command.targetActorId) }
                audit(now, requester, "APPROVAL_REQUESTED", "SUCCESS", command.context, request, null)
                ApprovalOutcome.Created(request.id)
            }
        }
    }

    /** 종류별 대상 자격(요청 시점 — 실행 시 account가 다시 판정한다). null이면 통과. */
    private fun eligibility(kind: ApprovalKind, command: RequestApproval, target: ParticipantView, requester: ParticipantView): String? = when (kind) {
        ApprovalKind.ROLE_GRANT -> ApprovalParticipants.credentialIssuerConflict(kind, requester.actorId, target.credentialIssuers) ?: when {
            target.status == "SUSPENDED" -> NOT_ELIGIBLE
            command.grantedRole in target.activeRoles || command.grantedRole in target.suspendedRoles -> "ROLE_ALREADY_HELD"
            else -> null
        }
        ApprovalKind.ACCOUNT_UNLOCK -> if (target.status != "LOCKED") NOT_ELIGIBLE else null
        ApprovalKind.ACCOUNT_REACTIVATE -> if (target.status != "SUSPENDED") NOT_ELIGIBLE else null
        ApprovalKind.TOTP_REENROLL, ApprovalKind.PASSWORD_RESET -> if (target.status == "SUSPENDED") NOT_ELIGIBLE else null
        ApprovalKind.ROLE_REACTIVATE -> reactivationRefusal(command.causeApprovalId!!, command.targetActorId, requester.actorId, null)
            ?: ApprovalParticipants.credentialIssuerConflict(kind, requester.actorId, target.credentialIssuers)
            ?: if (target.status != "ACTIVE") NOT_ELIGIBLE else if (executor.rolesSuspendedBy(command.targetActorId, command.causeApprovalId).isEmpty()) "NOTHING_TO_REACTIVATE" else null
    }

    /** `ROLE_REACTIVATE` 원인 검사: 원인은 같은 대상의 실행된 재설정 승인이고, 요청자·승인자는 원인의 요청자·승인자 모두와 달라야 한다(H-A). */
    private fun reactivationRefusal(causeId: String, target: String, requester: String?, approver: String?): String? {
        // 복구 회차 원인(S6 리뷰 M-3 (a)): 시스템 원인이라 원인 참여자 배제가 없다 — 일반 2인 규칙(요청자 ≠ 승인자 ≠ 대상)과 "그 회차 원인으로 정지된 역할 있음"만.
        if (ApprovalCause.isEpisode(causeId)) return null
        val cause = repository.find(causeId) ?: return "CAUSE_NOT_FOUND"
        if (cause.targetActorId != target || cause.status != ApprovalStatus.EXECUTED || !cause.kind.issuesEnrollmentCode) return "CAUSE_NOT_APPLICABLE"
        return listOfNotNull(requester, approver).firstNotNullOfOrNull { ApprovalParticipants.reactivationConflict(it, cause.requestedBy, cause.decidedBy) }
    }

    // ------------------------------------------------------------------ approve

    override fun approve(command: DecideApproval): ApprovalOutcome {
        val kind = KindLabel()
        return observed(APPROVE, kind) {
            val outcome = guarded(command.context, command.approverActorId) {
                transactions.execute({ false }) { status -> approveInTransaction(command, status, kind) }
            }
            afterDecision(outcome, command)
        }
    }

    private fun approveInTransaction(command: DecideApproval, status: TransactionStatus, kind: KindLabel): ApprovalOutcome {
        val approverId = command.approverActorId
        // 0단계: 불변 열(종류·대상·부여 역할)만 비잠금 읽기 → SA·APPROVER를 활성화할 수 있는 종류면 열린 복구 회차를 먼저 잠근다(§4.13).
        val before = repository.immutableColumns(command.approvalId) ?: return ApprovalOutcome.NotFound
        kind.value = ApprovalKind.entries.firstOrNull { it.name == before.kind }?.name ?: UNKNOWN
        val beforeKind = ApprovalKind.entries.firstOrNull { it.name == before.kind } ?: throw IntegrityViolation("kind")
        if (beforeKind.mayActivatePrivilegedRole(before.grantedRole)) executor.lockOpenRecoveryEpisode()
        // 1단계: 승인 행 잠금 재독 — 불변 열이 0단계와 다르면 무결성 위반(rev 0.5 n-1).
        val current = repository.lock(command.approvalId) ?: return ApprovalOutcome.NotFound
        val request = current.value
        integrityMismatch(before.kind, before.targetActorId, before.grantedRole, request)?.let { throw IntegrityViolation(it) }
        val now = now()
        // 2단계: 판정표 — PENDING·만료 전(TX 시각)·승인자 ≠ 요청자·승인자 ≠ 대상.
        when (val decision = request.decideApproval(approverId, now)) {
            ApprovalDecision.AlreadyDecided -> return ApprovalOutcome.AlreadyDecided
            is ApprovalDecision.Expired -> {
                repository.update(current, decision.expired)
                audit(now, null, "APPROVAL_EXPIRED", "EXPIRED", command.context, request, null, actorId = approverId)
                return ApprovalOutcome.Expired
            }
            is ApprovalDecision.Refused -> {
                audit(now, null, "APPROVAL_DENIED", "REFUSED", command.context, request, "reason=${decision.code}", actorId = approverId)
                return ApprovalOutcome.Refused(decision.code)
            }
            ApprovalDecision.Proceed -> Unit
        }
        // 3단계: 참여자 계정 오름차순 잠금.
        val participants = executor.lockParticipants(listOf(request.requestedBy, approverId, request.targetActorId))
        // 4단계: 승인자 재검사 — 실패면 상태 변경 없이 rollback(403·401).
        val approver = participants[approverId]
        if (approver == null || !approver.holds(ApprovalParticipants.APPROVER_ROLE)) throw Refusal(ApprovalOutcome.Denied, "APPROVER_NOT_ELIGIBLE", request.targetActorId, request.id)
        if (approver.sessionGeneration != command.sessionGeneration) throw Refusal(ApprovalOutcome.SessionReplaced, null, null)
        if (request.kind == ApprovalKind.ROLE_REACTIVATE) {
            reactivationRefusal(request.causeApprovalId!!, request.targetActorId, null, approverId)?.let {
                throw Refusal(ApprovalOutcome.Refused(it), it, request.targetActorId, request.id)
            }
        }
        // D-ADMIN1-014·015: 대상 자격 발급 근거의 참여자(코드 수령자 포함)는 권한 상승을 승인할 수 없다 — TOTP 전, 상태 불변(403).
        participants[request.targetActorId]?.let { target ->
            ApprovalParticipants.credentialIssuerConflict(request.kind, approverId, target.credentialIssuers)?.let {
                throw Refusal(ApprovalOutcome.Refused(it), it, request.targetActorId, request.id)
            }
        }
        // 5단계: 재인증 TOTP 검증·단계 소비(같은 TX). 실패면 전체 rollback → 별도 TX 계수(afterDecision).
        if (executor.consumeStepUpTotp(approverId, command.totpCode) == StepUpResult.FAILED) {
            status.setRollbackOnly()
            return ApprovalOutcome.StepUpFailed
        }
        // 6단계: 요청자·대상 재검사 — 실패면 INVALIDATED 커밋(승인자 TOTP 단계 소비도 함께 커밋).
        val requester = participants[request.requestedBy]
        val target = participants[request.targetActorId]
        val invalid = when {
            requester == null || !requester.holds(ApprovalParticipants.REQUESTER_ROLE) -> "REQUESTER_NOT_ELIGIBLE"
            target == null -> TARGET_NOT_FOUND
            target.securityVersion != request.targetSecurityVersion -> "TARGET_CHANGED"
            else -> null
        }
        if (invalid != null) {
            repository.update(current, request.invalidated(approverId, approver.displayName, invalid, now))
            audit(now, approver, "APPROVAL_INVALIDATED", "INVALIDATED", command.context, request, "reason=$invalid")
            return ApprovalOutcome.Invalidated(invalid)
        }
        // 7단계: 소유 재검사·실행(account가 증거로 다시 판정).
        val evidence = OwnedCommandEvidence(
            request.id, request.kind.name, request.requestedBy, approverId, request.targetActorId, request.targetSecurityVersion, request.grantedRole,
            request.causeApprovalId,
        )
        return when (val outcome = executor.execute(evidence)) {
            is OwnedCommandOutcome.Rejected -> {
                repository.update(current, request.ownerRejected(approverId, approver.displayName, outcome.code, now))
                audit(now, approver, "APPROVAL_OWNER_REJECTED", "OWNER_REJECTED", command.context, request, "reason=${outcome.code}")
                ApprovalOutcome.OwnerRejected(outcome.code)
            }
            is OwnedCommandOutcome.Executed -> {
                // 8단계: EXECUTED·감사(실행자 = 승인자 — D-ADMIN1-006). 등록 코드 원문은 감사·승인 행에 없다.
                repository.update(current, request.executed(approverId, approver.displayName, now))
                audit(now, approver, "APPROVAL_EXECUTED", "SUCCESS", command.context, request, null, executor = approverId)
                ApprovalOutcome.Executed(request.id, outcome.enrollmentCode)
            }
        }
    }

    // ------------------------------------------------------------------ reject / withdraw

    override fun reject(command: DecideApproval): ApprovalOutcome {
        val kind = KindLabel()
        return observed(REJECT, kind) { rejectStep(command, kind) }
    }

    private fun rejectStep(command: DecideApproval, kind: KindLabel): ApprovalOutcome {
        val outcome = guarded(command.context, command.approverActorId) {
            transactions.execute({ false }) { status ->
                val current = repository.lock(command.approvalId) ?: return@execute ApprovalOutcome.NotFound
                val request = current.value
                kind.value = request.kind.name
                val now = now()
                when (val decision = request.decideRejection(command.approverActorId, now)) {
                    ApprovalDecision.AlreadyDecided -> return@execute ApprovalOutcome.AlreadyDecided
                    is ApprovalDecision.Expired -> {
                        repository.update(current, decision.expired)
                        audit(now, null, "APPROVAL_EXPIRED", "EXPIRED", command.context, request, null, actorId = command.approverActorId)
                        return@execute ApprovalOutcome.Expired
                    }
                    is ApprovalDecision.Refused -> {
                        audit(now, null, "APPROVAL_DENIED", "REFUSED", command.context, request, "reason=${decision.code}", actorId = command.approverActorId)
                        return@execute ApprovalOutcome.Refused(decision.code)
                    }
                    ApprovalDecision.Proceed -> Unit
                }
                val participants = executor.lockParticipants(listOf(request.requestedBy, command.approverActorId))
                val approver = participants[command.approverActorId]
                if (approver == null || !approver.holds(ApprovalParticipants.APPROVER_ROLE)) {
                    throw Refusal(ApprovalOutcome.Denied, "APPROVER_NOT_ELIGIBLE", request.targetActorId, request.id)
                }
                if (approver.sessionGeneration != command.sessionGeneration) throw Refusal(ApprovalOutcome.SessionReplaced, null, null)
                if (executor.consumeStepUpTotp(command.approverActorId, command.totpCode) == StepUpResult.FAILED) {
                    status.setRollbackOnly()
                    return@execute ApprovalOutcome.StepUpFailed
                }
                repository.update(current, request.rejected(command.approverActorId, approver.displayName, now))
                audit(now, approver, "APPROVAL_REJECTED", "REJECTED", command.context, request, null)
                ApprovalOutcome.Closed(ApprovalStatus.REJECTED.name)
            }
        }
        return afterDecision(outcome, command)
    }

    override fun withdraw(command: WithdrawApproval): ApprovalOutcome {
        val kind = KindLabel()
        return observed(WITHDRAW, kind) { withdrawStep(command, kind) }
    }

    private fun withdrawStep(command: WithdrawApproval, kind: KindLabel): ApprovalOutcome = guarded(command.context, command.requesterActorId) {
        transactions.execute({ false }) {
            val current = repository.lock(command.approvalId) ?: return@execute ApprovalOutcome.NotFound
            val request = current.value
            kind.value = request.kind.name
            val now = now()
            when (val decision = request.decideWithdrawal(command.requesterActorId, now)) {
                ApprovalDecision.AlreadyDecided -> return@execute ApprovalOutcome.AlreadyDecided
                is ApprovalDecision.Expired -> {
                    repository.update(current, decision.expired)
                    audit(now, null, "APPROVAL_EXPIRED", "EXPIRED", command.context, request, null, actorId = command.requesterActorId)
                    return@execute ApprovalOutcome.Expired
                }
                is ApprovalDecision.Refused -> {
                    audit(now, null, "APPROVAL_DENIED", "REFUSED", command.context, request, "reason=${decision.code}", actorId = command.requesterActorId)
                    return@execute ApprovalOutcome.Refused(decision.code)
                }
                ApprovalDecision.Proceed -> Unit
            }
            val requester = executor.lockParticipants(listOf(command.requesterActorId))[command.requesterActorId]
            if (requester == null || !requester.isActive) throw Refusal(ApprovalOutcome.Denied, "REQUESTER_NOT_ELIGIBLE", request.targetActorId, request.id)
            if (requester.sessionGeneration != command.sessionGeneration) throw Refusal(ApprovalOutcome.SessionReplaced, null, null)
            repository.update(current, request.withdrawn(command.requesterActorId, requester.displayName, now))
            audit(now, requester, "APPROVAL_WITHDRAWN", "WITHDRAWN", command.context, request, null)
            ApprovalOutcome.Closed(ApprovalStatus.WITHDRAWN.name)
        }
    }

    override fun expireDue(): Int {
        var expired = 0
        val due = retryLaterOnConflictApprovalOrEmpty { transactions.execute({ false }) { repository.dueForExpiry(now(), EXPIRY_BATCH) } }
        due.forEach { id ->
            val changed = try {
                retryLaterOnConflictApproval {
                    transactions.execute({ false }) {
                        val current = repository.lock(id) ?: return@execute null
                        val now = now()
                        val next = current.value.expireIfDue(now) ?: return@execute null
                        repository.update(current, next)
                        recorder.record(
                            AuditRecord(
                                occurredAt = now, actorKind = "SYSTEM_JOB", actorId = null, actorDisplayName = null, action = "APPROVAL_EXPIRED",
                                resultCode = "EXPIRED", correlationId = "job-expiry-$id", targetActorId = current.value.targetActorId,
                                approvalId = id, detail = "kind=${current.value.kind.name}",
                            ),
                        )
                        current.value.kind.name
                    }
                }
            } catch (_: AdminRetryLaterException) {
                metrics.retryLater(EXPIRE_JOB)
                null
            }
            if (changed != null) {
                metrics.decision(EXPIRE_JOB, changed, "EXPIRED")
                expired++
            }
        }
        return expired
    }

    // ------------------------------------------------------------------ helpers

    /**
     * 재인증 실패는 승인 TX rollback 뒤 별도 TX로 승인자 계수(§4.7.4), 그 밖은 그대로. 계수 TX가 1213·1205를 3회 재시도한 뒤에도 실패하면 그 저장 실패가
     * 그대로 전파된다(웹 500 — 승인 TX는 이미 rollback이라 상태·TOTP 단계 불변, 그 1회 실패는 계수되지 않는다. S5 리뷰 nit-2 — §22.11 기록).
     */
    private fun afterDecision(outcome: ApprovalOutcome, command: DecideApproval): ApprovalOutcome {
        if (outcome == ApprovalOutcome.StepUpFailed) failures.recordFailure(command.approverActorId, AuthenticationStep.STEP_UP_TOTP, command.context)
        return outcome
    }

    /**
     * 공통 예외 처리: [Refusal]은 rollback 뒤 별도 TX 감사(rev 0.5 n-4), 무결성 위반은 rollback 뒤 별도 TX 감사 + 500, 1213·1205·version 충돌은 409.
     */
    private fun guarded(context: AdminRequestContext, actorId: String, block: () -> ApprovalOutcome): ApprovalOutcome = try {
        retryLaterOnConflictApproval(block)
    } catch (refusal: Refusal) {
        refusal.auditReason?.let { reason -> separateAudit(context, actorId, "APPROVAL_DENIED", "DENIED", refusal.target, refusal.approvalId, "reason=$reason") }
        refusal.outcome
    } catch (violation: IntegrityViolation) {
        metrics.integrityViolation(violation.column)
        separateAudit(context, actorId, "APPROVAL_INTEGRITY_VIOLATION", "FAILED", null, null, "column=${violation.column}")
        ApprovalOutcome.IntegrityViolation
    } catch (_: AdminRetryLaterException) {
        ApprovalOutcome.RetryLater
    }

    /** 만료 정리 job 후보 읽기: 경합이면 이번 회차는 건너뛴다(표시용 job — 권위 판정은 승인 TX, n-5). */
    private inline fun retryLaterOnConflictApprovalOrEmpty(block: () -> List<String>): List<String> = try {
        retryLaterOnConflictApproval(block)
    } catch (_: AdminRetryLaterException) {
        emptyList()
    }

    private inline fun <T> retryLaterOnConflictApproval(block: () -> T): T = try {
        block()
    } catch (e: ApprovalPersistenceFailure.Deadlock) {
        throw AdminRetryLaterException(e)
    } catch (e: ApprovalPersistenceFailure.LockTimeout) {
        throw AdminRetryLaterException(e)
    } catch (e: ApprovalPersistenceFailure.OptimisticConflict) {
        throw AdminRetryLaterException(e)
    }

    private fun separateAudit(context: AdminRequestContext, actorId: String, action: String, result: String, target: String?, approvalId: String?, detail: String) {
        transactions.execute({ false }) {
            val actor = executor.snapshot(actorId)
            recorder.record(
                AuditRecord(
                    occurredAt = now(), actorKind = if (actor != null) "ADMIN" else "ANONYMOUS", actorId = actor?.actorId, actorDisplayName = actor?.displayName,
                    action = action, resultCode = result, correlationId = context.correlationId, targetActorId = target, approvalId = approvalId,
                    remoteIp = context.remoteIp, detail = detail,
                ),
            )
        }
    }

    @Suppress("LongParameterList")
    private fun audit(
        now: Instant,
        actor: ParticipantView?,
        action: String,
        result: String,
        context: AdminRequestContext,
        request: ApprovalRequest,
        reasonDetail: String?,
        actorId: String? = actor?.actorId,
        executor: String? = null,
    ) {
        val kindDetail = "kind=${request.kind.name}" + (request.grantedRole?.let { ";role=$it" } ?: "")
        recorder.record(
            AuditRecord(
                occurredAt = now, actorKind = "ADMIN", actorId = actorId, actorDisplayName = actor?.displayName, action = action, resultCode = result,
                correlationId = context.correlationId, targetActorId = request.targetActorId, executorActorId = executor, approvalId = request.id,
                remoteIp = context.remoteIp, detail = listOfNotNull(kindDetail, reasonDetail).joinToString(";"),
            ),
        )
    }

    private fun integrityMismatch(kind: String, target: String, grantedRole: String?, locked: ApprovalRequest): String? = when {
        locked.kind.name != kind -> "kind"
        locked.targetActorId != target -> "target_actor_id"
        locked.grantedRole != grantedRole -> "granted_role"
        else -> null
    }

    /**
     * 대상 스냅샷(요청 시점 표시용 — 판정은 실행 시 account가 다시 한다). D-ADMIN1-020(Q-S8-C): login_name은 **저장하지 않는다** — 승인 조회는 VIEWER·OPERATOR·APPROVER도 하므로
     * 읽을 때 지우는 대신 원천에서 뺀다(합산 잠금 공격 대상 열거 방지 — D-ADMIN1-017과 같은 이유). 대상 식별은 actorId·표시명.
     */
    private fun snapshotJson(p: ParticipantView): String =
        "{\"displayName\":${json(p.displayName)},\"status\":${json(p.status)}," +
            "\"roles\":[${p.activeRoles.sorted().joinToString(",") { json(it) }}],\"suspendedRoles\":[${p.suspendedRoles.sorted().joinToString(",") { json(it) }}]}"

    private fun json(value: String): String = buildString {
        append('"')
        value.forEach { c ->
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c.code < 0x20 -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }

    private fun now(): Instant = environment.now().truncatedTo(ChronoUnit.MILLIS)

    /** rollback할 거절(주 TX 변경을 버리고, [auditReason]이 있으면 별도 TX 감사). */
    private class Refusal(val outcome: ApprovalOutcome, val auditReason: String?, val target: String?, val approvalId: String? = null) : RuntimeException(null, null, false, false)

    private class IntegrityViolation(val column: String) : RuntimeException(null, null, false, false)

    /** 한 호출의 지표 종류 label(TX 안에서 읽은 승인 종류 — 읽기 전 실패면 `UNKNOWN`). */
    private class KindLabel(var value: String = UNKNOWN)

    /**
     * S8 지표: 결과마다 `paycore.admin.approval{operation,kind,result}` 1회(TX가 끝난 뒤 — commit 결과 불명은 `COMMIT_UNKNOWN`), 409면 `retry_later`도.
     */
    private inline fun observed(operation: String, kind: KindLabel, block: () -> ApprovalOutcome): ApprovalOutcome {
        val outcome = try {
            block()
        } catch (e: CommitOutcomeUnknownException) {
            metrics.decision(operation, kind.value, "COMMIT_UNKNOWN")
            throw e
        } catch (e: RuntimeException) {
            metrics.decision(operation, kind.value, "ERROR")
            throw e
        }
        if (outcome == ApprovalOutcome.RetryLater) metrics.retryLater(operation)
        metrics.decision(operation, kind.value, resultName(outcome))
        return outcome
    }

    private fun resultName(outcome: ApprovalOutcome): String = when (outcome) {
        is ApprovalOutcome.Created -> "CREATED"
        is ApprovalOutcome.Executed -> "EXECUTED"
        is ApprovalOutcome.Closed -> outcome.status
        is ApprovalOutcome.Invalidated -> "INVALIDATED"
        is ApprovalOutcome.OwnerRejected -> "OWNER_REJECTED"
        is ApprovalOutcome.Rejected -> "REJECTED"
        is ApprovalOutcome.Refused -> "REFUSED"
        ApprovalOutcome.AlreadyDecided -> "ALREADY_DECIDED"
        ApprovalOutcome.Expired -> "EXPIRED"
        ApprovalOutcome.Denied -> "DENIED"
        ApprovalOutcome.SessionReplaced -> "SESSION_REPLACED"
        ApprovalOutcome.StepUpFailed -> "STEP_UP_FAILED"
        ApprovalOutcome.NotFound -> "NOT_FOUND"
        ApprovalOutcome.RetryLater -> "RETRY_LATER"
        ApprovalOutcome.IntegrityViolation -> "INTEGRITY_VIOLATION"
    }

    private companion object {
        const val DUPLICATE_PENDING = "DUPLICATE_PENDING"
        const val TARGET_NOT_FOUND = "TARGET_NOT_FOUND"
        const val NOT_ELIGIBLE = "NOT_ELIGIBLE"
        const val EXPIRY_BATCH = 100
        const val REQUEST = "REQUEST"
        const val APPROVE = "APPROVE"
        const val REJECT = "REJECT"
        const val WITHDRAW = "WITHDRAW"
        const val EXPIRE_JOB = "EXPIRE_JOB"
        const val UNKNOWN = "UNKNOWN"
        val INVALID = ApprovalOutcome.Rejected("INVALID_INPUT")
        val ROLES = setOf("VIEWER", "OPERATOR", "APPROVER", "SECURITY_ADMIN", "UNMASK")
    }
}

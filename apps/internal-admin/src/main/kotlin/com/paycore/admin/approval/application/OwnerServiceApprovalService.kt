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
import com.paycore.admin.approval.application.provided.OwnerServiceApprovalUseCase
import com.paycore.admin.approval.application.provided.RequestOperatorCancel
import com.paycore.admin.approval.application.required.ApprovalEnvironment
import com.paycore.admin.approval.application.required.ApprovalMetrics
import com.paycore.admin.approval.application.required.ApprovalPersistenceFailure
import com.paycore.admin.approval.application.required.ApprovalRequestRepository
import com.paycore.admin.approval.application.required.OperatorCancelCommandStore
import com.paycore.admin.approval.application.required.OwnerExecutionRepository
import com.paycore.admin.approval.application.required.OwnerOpsMetrics
import com.paycore.admin.approval.domain.ApprovalDecision
import com.paycore.admin.approval.domain.ApprovalKind
import com.paycore.admin.approval.domain.ApprovalParticipants
import com.paycore.admin.approval.domain.ApprovalRequest
import com.paycore.admin.approval.domain.ApprovalStatus
import com.paycore.admin.approval.domain.ApprovalTarget
import com.paycore.admin.approval.domain.OperatorCancelInputRules
import com.paycore.admin.approval.domain.OperatorCancelPurposeKind
import com.paycore.admin.approval.domain.OperatorCancelRequestSnapshot
import com.paycore.admin.approval.domain.OwnerExecution
import com.paycore.admin.approval.domain.PinnedPaymentTarget
import com.paycore.admin.audit.application.provided.AdminAuditRecorder
import com.paycore.admin.paymentops.application.provided.CallPurpose
import com.paycore.admin.paymentops.application.provided.ExecutionBodyInput
import com.paycore.admin.paymentops.application.provided.ExecutionResult
import com.paycore.admin.paymentops.application.provided.FingerprintInput
import com.paycore.admin.paymentops.application.provided.OperatorCancelContract
import com.paycore.admin.paymentops.application.provided.PaymentOpsClient
import com.paycore.admin.paymentops.application.provided.PaymentOpsPolicy
import com.paycore.admin.paymentops.application.provided.PaymentTargetView
import com.paycore.admin.paymentops.application.provided.TargetResult
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionStatus
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * [OwnerServiceApprovalUseCase](ADMIN-PAYMENT-OPS-001 §4.12·§4.13·§4.15). 잠금 순서 §4.14: 승인 행 → 계정 행(오름차순) → 실행 행. **PAYMENT 호출은 모두 TX 밖**
 * (요청 전 조회·승인 A 조회·C 실행 POST — gateway가 TX 안이면 거부). 로컬 TX는 재시도하지 않는다(TOTP 입력을 다시 쓰지 않는다 — maxAttempts 1).
 * 감사 행은 모두 `target_ref = paymentId`(target_actor_id NULL — §4.10), 실행자 = 승인자(D-ADMIN1-006). 사유·근거·본문은 감사·로그·지표에 없다(§6).
 * 무결성·운영 경보(파생 409·401·400·`APPROVAL_WINDOW_INVALID`)는 `paycore.admin.ops.integrity_alert{code}`(1건이라도 경보 — I-83), ADMIN 내부 무결성 위반
 * (고정 값 불일치 등)은 기존 `paycore.admin.approval.integrity_violation{check}`.
 */
@Service
class OwnerServiceApprovalService(
    transactionManager: PlatformTransactionManager,
    private val repository: ApprovalRequestRepository,
    private val commands: OperatorCancelCommandStore,
    private val executions: OwnerExecutionRepository,
    private val executor: AdminOwnedCommandExecutor,
    private val failures: AuthenticationFailureRecorder,
    private val sessions: AdminSessionQuery,
    private val recorder: AdminAuditRecorder,
    private val environment: ApprovalEnvironment,
    private val metrics: ApprovalMetrics,
    private val opsMetrics: OwnerOpsMetrics,
    private val client: PaymentOpsClient,
    private val contract: OperatorCancelContract,
    private val policy: PaymentOpsPolicy,
    private val applier: OwnerOutcomeApplier,
    private val acquiring: AcquiringOpsApprovalService,
    private val merchants: MerchantOpsApprovalService,
) : OwnerServiceApprovalUseCase {

    private val transactions = LocalTransactions(transactionManager, maxAttempts = 1)
    private val kind = ApprovalKind.OPERATOR_CORRECTION_CANCEL

    // ------------------------------------------------------------------ request (§4.12)

    override fun requestOperatorCancel(command: RequestOperatorCancel): ApprovalOutcome = observed(REQUEST) { requestStep(command) }

    private fun requestStep(command: RequestOperatorCancel): ApprovalOutcome {
        // 1. 입력 형식(TX·PAYMENT 호출 없음, 감사 없음 — 400). 서버는 자르지 않는다(§4.18).
        val purpose = OperatorCancelPurposeKind.entries.firstOrNull { it.name == command.purposeKind }
        // I-111(S8): 첫 거절 필드와 고정 이유 code를 돌려준다(화면 문구용 — 값 원문 없음).
        val problem = when {
            !PAYMENT_ID.matches(command.paymentId) -> "paymentId" to "FORMAT"
            command.amountMinor <= 0 -> "amountMinor" to "NOT_POSITIVE"
            purpose == null -> "purposeKind" to "UNKNOWN_KIND"
            else -> OperatorCancelInputRules.referenceProblem(command.purposeReference)?.let { "purposeReference" to it }
                ?: OperatorCancelInputRules.reasonProblem(command.reason)?.let { "reason" to it }
        }
        if (problem != null || purpose == null) return ApprovalOutcome.Rejected(INVALID.code, problem?.first, problem?.second)
        // 세션 역할(비권위 — TX 안 계정 잠금에서 다시 본다): OPERATOR가 아니면 PAYMENT를 부르지 않고 403.
        if (!holdsNow(command.requesterActorId, ApprovalParticipants.requesterRole(kind))) return ApprovalOutcome.Denied
        // 2. 대상 조회(TX 밖, approval pool). 장애 → 503(저장 없음), 없음 → 400 + 별도 TX 감사.
        val target = when (val found = client.target(command.paymentId, CallPurpose.APPROVAL)) {
            is TargetResult.Found -> found.view
            TargetResult.NotFound -> return refuseAudited(command, ApprovalOutcome.Rejected(PAYMENT_NOT_FOUND), PAYMENT_NOT_FOUND)
            is TargetResult.Unavailable -> return ApprovalOutcome.Unavailable(PAYMENT_UNAVAILABLE)
        }
        // 3. advisory(비권위 — PAYMENT가 실행 때 잠금 아래 다시 판정). 일시 거절도 승인을 소진하므로(D-OPS-005) 진행 중 취소는 미리 막는다(L-7).
        advisoryRefusal(target, command)?.let { return refuseAudited(command, ApprovalOutcome.Rejected(it), it) }
        // 4. TX: 승인 행 INSERT → 요청자 잠금·재검사 → 명령 행 INSERT → 감사.
        return guarded(command.context, command.requesterActorId) {
            transactions.execute({ false }) {
                val now = now()
                val pinned = PinnedPaymentTarget(
                    target.paymentId, target.paymentStatus, target.currency, target.authorizedMinor, target.cancellableMinor, target.balanceRevision, target.onHold,
                )
                val request = ApprovalRequest.create(
                    environment.newApprovalId(), kind, ApprovalTarget.Payment(target.paymentId, target.balanceRevision), pinned.toSnapshotJson(), null, null,
                    command.requesterActorId, snapshotName(command.requesterActorId), now, environment.validity,
                )
                try {
                    repository.insert(request)
                } catch (_: ApprovalPersistenceFailure.DuplicateUnresolved) {
                    throw Refusal(ApprovalOutcome.Rejected(DUPLICATE_UNRESOLVED), DUPLICATE_UNRESOLVED, command.paymentId)
                }
                val requester = executor.lockParticipants(listOf(command.requesterActorId))[command.requesterActorId]
                if (requester == null || !requester.holds(ApprovalParticipants.requesterRole(kind))) {
                    throw Refusal(ApprovalOutcome.Denied, REQUESTER_NOT_ELIGIBLE, command.paymentId)
                }
                if (requester.sessionGeneration != command.sessionGeneration) throw Refusal(ApprovalOutcome.SessionReplaced, null, null)
                val snapshot = OperatorCancelRequestSnapshot.of(
                    request.id, command.paymentId, command.amountMinor, requireNotNull(target.currency), purpose, command.purposeReference, command.reason, pinned, now,
                )
                assertSameTarget(request, snapshot)
                commands.insert(snapshot)
                audit(now, requester, "APPROVAL_REQUESTED", "SUCCESS", command.context, request, null)
                ApprovalOutcome.Created(request.id)
            }
        }
    }

    private fun advisoryRefusal(target: PaymentTargetView, command: RequestOperatorCancel): String? = when {
        target.inFlightCancel -> "CANCEL_IN_PROGRESS_ADVISORY"
        !target.cancellable || target.currency == null -> "PAYMENT_NOT_CANCELLABLE_ADVISORY"
        command.amountMinor > target.cancellableMinor -> "AMOUNT_EXCEEDS_CANCELLABLE_ADVISORY"
        else -> null
    }

    // ------------------------------------------------------------------ approve (§4.13)

    /**
     * 종류 분기(ACQ-ADMIN-001 B1-A §17.5 4 — C-3 권장안 "종류별 소유 서비스 gateway로 일반화"): 승인 행 종류를 비잠금으로 읽어 재매입이면 [AcquiringOpsApprovalService](FINANCIAL 조작 채널),
     * 아니면 기존 운영자 보정 취소 경로(PAYMENT — 동작·시험 기대값 그대로). 분기는 비권위 — 각 경로의 TX1이 잠근 행 종류를 다시 확인한다(다르면 무결성 위반, fail-closed).
     */
    override fun approve(command: DecideApproval): ApprovalOutcome {
        val seenKind = transactions.execute({ false }) { repository.find(command.approvalId)?.kind }
        if (seenKind == ApprovalKind.ACQ_REACQUISITION) return acquiring.approve(command)
        // MERCHANT-OPS-002 B2-2: 가맹점 계약 승인은 MASTER 승인 채널(같은 OWNER_SERVICE 2-TX — 잠근 행 종류를 TX1이 다시 확인).
        // B3-1: 가맹점 대상 종류 전부(개통·Site 심사 포함)가 같은 채널이다.
        if (seenKind?.merchantOps == true) return merchants.approve(command)
        return observed(APPROVE) { approveStep(command) }
    }

    private fun approveStep(command: DecideApproval): ApprovalOutcome {
        // A0(비권위): 세션 역할·승인 행 비잠금 읽기. 상태를 바꾸지 않는다 — 권위 판정은 TX1.
        if (!holdsNow(command.approverActorId, ApprovalParticipants.APPROVER_ROLE)) return ApprovalOutcome.Denied
        val seen = transactions.execute({ false }) { repository.find(command.approvalId) } ?: return ApprovalOutcome.NotFound
        if (seen.kind != kind) return ApprovalOutcome.Rejected(WRONG_EXECUTION_MODEL)
        if (seen.status != ApprovalStatus.PENDING) return ApprovalOutcome.AlreadyDecided
        val paymentId = (seen.target as ApprovalTarget.Payment).paymentId
        // A(TX 밖): 대상 재조회. 진행 중 취소면 TARGET_BUSY — PENDING 유지, TOTP 검증·소비 없음(L-7). 장애면 503(상태·TOTP 불변).
        val current = when (val found = client.target(paymentId, CallPurpose.APPROVAL)) {
            is TargetResult.Found -> found.view
            TargetResult.NotFound -> return ApprovalOutcome.Rejected(TARGET_NOT_FOUND)
            is TargetResult.Unavailable -> return ApprovalOutcome.Unavailable(PAYMENT_UNAVAILABLE)
        }
        if (current.inFlightCancel) return ApprovalOutcome.Rejected(TARGET_BUSY)
        // B(TX1).
        val begun = try {
            guardedTx1(command) { status -> tx1(command, current, status) }
        } catch (e: CommitOutcomeUnknownException) {
            // commit 불명확 → 재조회 수렴(§4.13 B): 실행 행이 있으면 커밋된 것 — 저장 본문으로 C를 이어 간다.
            log.warn("operation=owner-approve-tx1 approvalId={} result=commit-unknown", command.approvalId)
            converge(command.approvalId) ?: throw e
        }
        if (begun !is Tx1.Begun) return (begun as Tx1.Done).outcome
        // C(TX 밖): 저장 본문 그대로 서명 POST(execution pool).
        val result = client.execute(command.approvalId, begun.body)
        alertOf(result)?.let(opsMetrics::integrityAlert)
        // D(TX2): 결과 반영은 회복 job과 같은 규칙(OwnerOutcomeApplier — 승인 행 → 실행 행 잠금, 내부 무결성 위반은 지표 + 별도 TX 감사).
        return try {
            applier.apply(command.approvalId, toOutcome(result), OwnerOutcomeApplier.Source.Web(command.approverActorId, command.context))
        } catch (e: RuntimeException) {
            // TX2 실패 → EXECUTING 유지, 회복 job이 이어받는다(§4.13 D).
            log.warn("operation=owner-approve-tx2 approvalId={} result=failed failure={}", command.approvalId, e.javaClass.simpleName)
            ApprovalOutcome.Executing(command.approvalId, ApprovalStatus.EXECUTING.name)
        }
    }

    private sealed interface Tx1 {
        class Done(val outcome: ApprovalOutcome) : Tx1

        class Begun(val body: ByteArray) : Tx1
    }

    private fun tx1(command: DecideApproval, current: PaymentTargetView, status: TransactionStatus): Tx1 {
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
        val paymentId = (request.target as ApprovalTarget.Payment).paymentId
        val participants = executor.lockParticipants(listOf(request.requestedBy, approverId))
        val approver = participants[approverId]
        if (approver == null || !approver.holds(ApprovalParticipants.APPROVER_ROLE)) throw Refusal(ApprovalOutcome.Denied, "APPROVER_NOT_ELIGIBLE", paymentId, request.id)
        if (approver.sessionGeneration != command.sessionGeneration) throw Refusal(ApprovalOutcome.SessionReplaced, null, null)
        if (executor.verifyStepUp(approverId, command.totpCode, command.context) == StepUpResult.FAILED) {
            status.setRollbackOnly()
            return Tx1.Done(ApprovalOutcome.StepUpFailed)
        }
        val requester = participants[request.requestedBy]
        if (requester == null || !requester.holds(ApprovalParticipants.requesterRole(request.kind))) {
            return Tx1.Done(invalidate(locked, request, approver, REQUESTER_NOT_ELIGIBLE, now, command.context))
        }
        // 명령 행은 INSERT-only — 잠그지 않고 읽는다(S4 리뷰 10). 권위 고정 값 = pinned_target.
        val snapshot = commands.find(request.id) ?: throw IntegrityViolation("command", paymentId, request.id)
        assertSameTarget(request, snapshot)
        if (current.paymentId != paymentId) throw IntegrityViolation("target_ref", paymentId, request.id)
        val pinned = snapshot.pinnedTarget
        val changed = current.balanceRevision != pinned.balanceRevision || current.currency != pinned.currency || current.currency != snapshot.currency ||
            !current.cancellable || snapshot.amountMinor > current.cancellableMinor
        if (changed) return Tx1.Done(invalidate(locked, request, approver, TARGET_CHANGED, now, command.context))
        val fingerprint = contract.fingerprint(
            FingerprintInput(
                paymentId, snapshot.amountMinor, snapshot.currency, current.balanceRevision, request.id, request.requestedBy, approverId, snapshot.purposeKind.name,
                snapshot.purposeReference,
            ),
        )
        val executeBefore = now.plus(policy.executionWindow)
        val body = contract.executionBody(
            ExecutionBodyInput(
                request.id, paymentId, snapshot.amountMinor, snapshot.currency, request.requestedBy, approverId, snapshot.reason, snapshot.purposeKind.name,
                snapshot.purposeReference, fingerprint, now, executeBefore, command.context.correlationId,
            ),
        )
        executions.insert(OwnerExecution.start(kind, request.id, body, fingerprint, now, executeBefore, approverId, now.plus(policy.staleAfter)))
        repository.update(locked, request.beginExecution(approverId, approver.displayName, now))
        audit(now, approver, "APPROVAL_EXECUTING", "EXECUTING", command.context, request, null, executor = approverId)
        return Tx1.Begun(body)
    }

    private fun invalidate(
        locked: com.paycore.admin.approval.application.required.VersionedApproval,
        request: ApprovalRequest,
        approver: ParticipantView,
        code: String,
        now: Instant,
        context: AdminRequestContext,
    ): ApprovalOutcome {
        // 승인자의 TOTP 단계 소비도 함께 커밋된다(§4.13 B — 무효도 승인 시도의 결과).
        repository.update(locked, request.invalidated(approver.actorId, approver.displayName, code, now))
        audit(now, approver, "APPROVAL_INVALIDATED", "INVALIDATED", context, request, "reason=$code")
        return ApprovalOutcome.Invalidated(code)
    }

    /** TX1 commit 불명확 뒤 재조회: 실행 행이 있으면 그 저장 본문으로 이어 가고, 없으면(커밋 안 됨) null. */
    private fun converge(approvalId: String): Tx1? = transactions.execute({ false }) {
        val request = repository.find(approvalId) ?: return@execute null
        val execution = executions.find(approvalId)
        when {
            execution != null && request.status == ApprovalStatus.EXECUTING -> Tx1.Begun(execution.requestBody())
            execution != null -> Tx1.Done(ApprovalOutcome.Executing(approvalId, request.status.name))
            else -> null
        }
    }

    private fun toOutcome(result: ExecutionResult): OwnerOutcomeApplier.Outcome = when (result) {
        is ExecutionResult.Started -> OwnerOutcomeApplier.Outcome.Started(result.cancellationId)
        is ExecutionResult.OwnerRejected -> OwnerOutcomeApplier.Outcome.Rejected(result.code)
        is ExecutionResult.Unknown -> OwnerOutcomeApplier.Outcome.Unknown(result.failure, result.integrityFlag)
    }

    /** 경보 code(§4.15 — I-97 무결성·운영 경보): 파생 409는 그 code, 400 형식·`APPROVAL_WINDOW_INVALID`는 거절 code, 401은 `UNAUTHENTICATED`. 그 밖 null. */
    private fun alertOf(result: ExecutionResult): String? = when (result) {
        is ExecutionResult.OwnerRejected -> result.code.takeIf { result.integrityAlert || result.operationalAlert }
        is ExecutionResult.Unknown -> result.integrityFlag ?: result.failure.takeIf { result.alert }
        is ExecutionResult.Started -> null
    }

    // ------------------------------------------------------------------ helpers

    /** 요청 TX·TX1 공통 단언(S4 리뷰 9): 승인 대상 = 명령 Payment = 고정 대상 Payment, 대상 버전 = 고정 revision. 다르면 무결성 위반(fail-closed). */
    private fun assertSameTarget(request: ApprovalRequest, snapshot: OperatorCancelRequestSnapshot) {
        val target = request.target as? ApprovalTarget.Payment ?: throw IntegrityViolation("target_kind", null, request.id)
        if (target.paymentId != snapshot.paymentId || snapshot.paymentId != snapshot.pinnedTarget.paymentId) throw IntegrityViolation("target_ref", target.paymentId, request.id)
        if (target.balanceRevision != snapshot.pinnedTarget.balanceRevision) throw IntegrityViolation("target_security_version", target.paymentId, request.id)
    }

    private fun holdsNow(actorId: String, role: String): Boolean = sessions.current(actorId)?.let { it.isActive && role in it.roles } == true

    private fun snapshotName(actorId: String): String = sessions.current(actorId)?.displayName ?: actorId

    private fun refuseAudited(command: RequestOperatorCancel, outcome: ApprovalOutcome, reason: String): ApprovalOutcome {
        separateAudit(command.context, command.requesterActorId, "APPROVAL_DENIED", "DENIED", command.paymentId, null, "kind=${kind.name};reason=$reason")
        return outcome
    }

    private fun guardedTx1(command: DecideApproval, block: (TransactionStatus) -> Tx1): Tx1 {
        val outcome = try {
            retryLater { transactions.execute({ false }, block) }
        } catch (refusal: Refusal) {
            refusal.auditReason?.let { separateAudit(command.context, command.approverActorId, "APPROVAL_DENIED", "DENIED", refusal.paymentId, refusal.approvalId, "reason=$it") }
            return Tx1.Done(refusal.outcome)
        } catch (violation: IntegrityViolation) {
            metrics.integrityViolation(violation.column)
            separateAudit(command.context, command.approverActorId, "APPROVAL_INTEGRITY_VIOLATION", "FAILED", violation.paymentId, violation.approvalId ?: command.approvalId, "column=${violation.column}")
            return Tx1.Done(ApprovalOutcome.IntegrityViolation)
        } catch (_: AdminRetryLaterException) {
            return Tx1.Done(ApprovalOutcome.RetryLater)
        }
        if (outcome is Tx1.Done && outcome.outcome == ApprovalOutcome.StepUpFailed) {
            failures.recordFailure(command.approverActorId, AuthenticationStep.STEP_UP_TOTP, command.context)
        }
        return outcome
    }

    private fun guarded(context: AdminRequestContext, actorId: String, block: () -> ApprovalOutcome): ApprovalOutcome = try {
        retryLater(block)
    } catch (refusal: Refusal) {
        refusal.auditReason?.let { separateAudit(context, actorId, "APPROVAL_DENIED", "DENIED", refusal.paymentId, refusal.approvalId, "kind=${kind.name};reason=$it") }
        refusal.outcome
    } catch (violation: IntegrityViolation) {
        metrics.integrityViolation(violation.column)
        separateAudit(context, actorId, "APPROVAL_INTEGRITY_VIOLATION", "FAILED", violation.paymentId, violation.approvalId, "column=${violation.column}")
        ApprovalOutcome.IntegrityViolation
    } catch (_: AdminRetryLaterException) {
        ApprovalOutcome.RetryLater
    }

    /** 1213·1205·version 충돌 → 409 RETRY_LATER(전체 rollback, 상태 불변 — L-9). */
    private inline fun <T> retryLater(block: () -> T): T = try {
        block()
    } catch (e: ApprovalPersistenceFailure.Deadlock) {
        throw AdminRetryLaterException(e)
    } catch (e: ApprovalPersistenceFailure.LockTimeout) {
        throw AdminRetryLaterException(e)
    } catch (e: ApprovalPersistenceFailure.OptimisticConflict) {
        throw AdminRetryLaterException(e)
    }

    private fun separateAudit(context: AdminRequestContext, actorId: String, action: String, result: String, paymentId: String?, approvalId: String?, detail: String) {
        transactions.execute({ false }) {
            val actor = executor.snapshot(actorId)
            // MERCHANT-CODE-001 MC-3a(§5.6.4 — C-36): 대상 열은 ApprovalAuditTarget에서만(paymentId — 값 불변).
            recorder.record(
                ApprovalAuditTarget.ofPaymentRef(paymentId).auditRecord(
                    occurredAt = now(), actorKind = if (actor != null) "ADMIN" else "ANONYMOUS", actorId = actor?.actorId, actorDisplayName = actor?.displayName,
                    action = action, resultCode = result, correlationId = context.correlationId, approvalId = approvalId, remoteIp = context.remoteIp, detail = detail,
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
        recorder.record(
            ApprovalAuditTarget.of(request.target as ApprovalTarget.Payment).auditRecord(
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

    private class Refusal(val outcome: ApprovalOutcome, val auditReason: String?, val paymentId: String?, val approvalId: String? = null) :
        RuntimeException(null, null, false, false)

    /** 무결성 위반(fail-closed). [paymentId]·[approvalId]는 별도 TX 감사 행의 target_ref·approval_id(S5 리뷰 M-2). */
    private class IntegrityViolation(val column: String, val paymentId: String? = null, val approvalId: String? = null) : RuntimeException(null, null, false, false)

    private companion object {
        val log = LoggerFactory.getLogger(OwnerServiceApprovalService::class.java)
        val PAYMENT_ID = Regex("^pay_[0-9A-HJKMNP-TV-Z]{26}$")
        val INVALID = ApprovalOutcome.Rejected("INVALID_INPUT")
        const val REQUEST = "OWNER_REQUEST"
        const val APPROVE = "OWNER_APPROVE"
        const val PAYMENT_NOT_FOUND = "PAYMENT_NOT_FOUND"
        const val PAYMENT_UNAVAILABLE = "PAYMENT_UNAVAILABLE"
        const val DUPLICATE_UNRESOLVED = "DUPLICATE_UNRESOLVED"
        const val REQUESTER_NOT_ELIGIBLE = "REQUESTER_NOT_ELIGIBLE"
        const val TARGET_CHANGED = "TARGET_CHANGED"
        const val TARGET_BUSY = "TARGET_BUSY"
        const val TARGET_NOT_FOUND = "TARGET_NOT_FOUND"
        const val WRONG_EXECUTION_MODEL = "WRONG_EXECUTION_MODEL"
        const val EXECUTION_MODEL = "execution_model"
        const val MAX_CODE = 48
    }
}

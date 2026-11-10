package com.paycore.admin.approval.application

import com.paycore.admin.account.application.provided.AdminRequestContext
import com.paycore.admin.approval.application.provided.ApprovalOutcome
import com.paycore.admin.approval.application.required.ApprovalEnvironment
import com.paycore.admin.approval.application.required.ApprovalMetrics
import com.paycore.admin.approval.application.required.ApprovalRequestRepository
import com.paycore.admin.approval.application.required.OwnerExecutionRepository
import com.paycore.admin.approval.application.required.OwnerOpsMetrics
import com.paycore.admin.approval.domain.ApprovalRequest
import com.paycore.admin.approval.domain.ApprovalStatus
import com.paycore.admin.approval.domain.ApprovalTarget
import com.paycore.admin.approval.domain.OwnerExecution
import com.paycore.admin.audit.application.provided.AdminAuditRecorder
import com.paycore.admin.audit.application.provided.AuditRecord
import com.paycore.admin.paymentops.application.provided.PaymentOpsPolicy
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 소유 서비스 결과 반영(ADMIN-PAYMENT-OPS-001 §4.13 D·§4.15·§4.16 TX2 규칙 L-e — **웹 TX2와 회복 job 공용**). 승인 행 → 실행 행 잠금 아래에서만 상태를 바꾼다.
 * - 승인 행이 이미 종료(다른 쪽이 먼저 반영)면 바꾸지 않고, 도착한 결과가 기록된 결과와 다르면 `outcome_divergence`(§4.13 D).
 * - STARTED → EXECUTED(소유 참조), REJECTED → OWNER_REJECTED(code) — 다음 시도·마지막 실패 유지. UNKNOWN → EXECUTING이면 EXECUTION_UNKNOWN, 다음 시도는 앞으로만,
 *   파생 409면 무결성 플래그. **시도 수는 바꾸지 않는다**(회복 선점만 +1).
 * - [contentMismatch](L-b, I-35): 기록 결정의 내용이 ADMIN과 다르면 `DECISION_CONTENT_MISMATCH` 플래그를 **먼저** 세우고(같은 TX) 감사 `APPROVAL_INTEGRITY_MISMATCH`를
 *   남긴 뒤 결정을 반영한다(조용한 EXECUTED 없음, 반영 보류 없음).
 * - 내부 무결성 위반(승인·실행 행 없음 등)은 rollback 뒤 지표 `integrity_violation{check}` + **별도 TX 감사(승인 ID·target_ref)**(S5 리뷰 L-A — 로그에 묻지 않음),
 *   결과는 `Executing(EXECUTING)`(회복 job이 이어받음).
 * 감사 행위자: 웹은 승인자(ADMIN), 회복 job은 SYSTEM_JOB(실행자 = 원 승인자). 모두 target_ref = 소유 대상 참조(paymentId 또는 매입 원 파일 ID — ACQ-ADMIN-001 B1-A).
 */
@Component
class OwnerOutcomeApplier(
    transactionManager: PlatformTransactionManager,
    private val repository: ApprovalRequestRepository,
    private val executions: OwnerExecutionRepository,
    private val recorder: AdminAuditRecorder,
    private val environment: ApprovalEnvironment,
    private val metrics: ApprovalMetrics,
    private val opsMetrics: OwnerOpsMetrics,
    private val policy: PaymentOpsPolicy,
) {
    private val transactions = LocalTransactions(transactionManager, maxAttempts = 1)

    /** 반영할 결과(PAYMENT 응답·기록 결정을 정규화한 값). */
    sealed interface Outcome {
        data class Started(val cancellationId: String) : Outcome

        data class Rejected(val code: String) : Outcome

        /** 실행됐으나 참조 대신 종결 code(ACQ-ADMIN-001 B1-A — FINANCIAL `NOT_REQUIRED`: 순액 0 "재매입 불요", D-ACQA-004) → EXECUTED([code]). */
        data class Completed(val code: String) : Outcome

        data class Unknown(val failure: String, val integrityFlag: String?) : Outcome
    }

    /** 반영 주체: 웹 승인 스레드(승인자·요청 문맥) 또는 회복 job(SYSTEM_JOB). */
    sealed interface Source {
        data class Web(val approverId: String, val context: AdminRequestContext) : Source

        data object Job : Source
    }

    /**
     * [quietIfUnchanged](S6 리뷰 L-1, I-112): 외부 효과가 없는 결과(결정 조회 실패)가 **같은 분류로 반복**되면(이미 EXECUTION_UNKNOWN이고 마지막 실패가 같음) 감사 행 없이
     * 마지막 응답·다음 시도만 갱신한다. 분류가 바뀌거나 상태가 바뀌면 감사한다.
     */
    @Suppress("LongParameterList")
    fun apply(
        approvalId: String, outcome: Outcome, source: Source, contentMismatch: Boolean = false, quietIfUnchanged: Boolean = false, integrityAudit: Boolean = false,
        backoff: ((Int) -> Duration)? = null,
    ): ApprovalOutcome = guarded(approvalId, source) {
        transactions.execute({ false }) {
            val now = now()
            val locked = repository.lock(approvalId) ?: throw IntegrityViolation("approval", null)
            val request = locked.value
            // ACQ-ADMIN-001 B1-A: 소유 서비스 대상(Payment·매입 파일)의 참조가 감사 target_ref다. 계정 대상이면 결함(fail-closed).
            val paymentId = ownerRef(request) ?: throw IntegrityViolation("target_kind", null)
            if (request.status !in ApprovalStatus.OWNER_SERVICE_ONLY) {
                if (diverges(request, outcome)) opsMetrics.outcomeDivergence()
                return@execute terminalOutcome(request)
            }
            val row = executions.lock(approvalId) ?: throw IntegrityViolation("execution", paymentId)
            var execution = row.value
            // B1-A 리뷰 m-4: 실행 행 지문·기록 참조와 도착한 참조가 승인 종류의 문법인지 대조(행 형식 검사는 두 종류의 합집합) — 어긋나면 rollback + 무결성 위반.
            if (!execution.conformsTo(request.kind)) throw IntegrityViolation("execution_kind", paymentId)
            if (outcome is Outcome.Started && !OwnerExecution.referenceFits(request.kind, outcome.cancellationId)) throw IntegrityViolation("owner_reference", paymentId)
            if (contentMismatch) {
                execution = execution.flagIntegrity(DECISION_CONTENT_MISMATCH)
                audit(now, source, "APPROVAL_INTEGRITY_MISMATCH", "FLAGGED", request, "reason=$DECISION_CONTENT_MISMATCH", execution.executorActorId)
            }
            when (outcome) {
                is Outcome.Started -> {
                    repository.update(locked, request.executed())
                    executions.update(row, execution.ownerResult(now, null, outcome.cancellationId))
                    audit(now, source, "APPROVAL_EXECUTED", "SUCCESS", request, null, execution.executorActorId)
                    ApprovalOutcome.Executed(approvalId)
                }
                is Outcome.Completed -> {
                    repository.update(locked, request.executedWith(outcome.code))
                    executions.update(row, execution.ownerResult(now, outcome.code, null))
                    audit(now, source, "APPROVAL_EXECUTED", "SUCCESS", request, "result=${outcome.code}", execution.executorActorId)
                    ApprovalOutcome.Executed(approvalId)
                }
                is Outcome.Rejected -> {
                    repository.update(locked, request.ownerRejected(outcome.code))
                    executions.update(row, execution.ownerResult(now, outcome.code, null))
                    audit(now, source, "APPROVAL_OWNER_REJECTED", "OWNER_REJECTED", request, "reason=${outcome.code}", execution.executorActorId)
                    ApprovalOutcome.OwnerRejected(outcome.code)
                }
                is Outcome.Unknown -> {
                    val next = if (request.status == ApprovalStatus.EXECUTING) request.executionUnknown() else request
                    if (next !== request) repository.update(locked, next)
                    val failure = outcome.failure.take(MAX_CODE)
                    var updated = execution.unknownResponse(now, failure, now.plus((backoff ?: policy::backoff)(execution.attemptCount)))
                    outcome.integrityFlag?.let { updated = updated.flagIntegrity(it) }
                    // MERCHANT-OPS-002 C-28: 무결성 플래그를 이번에 처음 세웠으면 감사 APPROVAL_INTEGRITY_VIOLATION 1회(같은 TX — [integrityAudit]을 켠 종류만, 기존 종류는 그대로).
                    if (integrityAudit && execution.integrityFlag == null && outcome.integrityFlag != null) {
                        audit(now, source, "APPROVAL_INTEGRITY_VIOLATION", "FLAGGED", request, "reason=${outcome.integrityFlag}", execution.executorActorId)
                    }
                    executions.update(row, updated)
                    val repeated = quietIfUnchanged && request.status == ApprovalStatus.EXECUTION_UNKNOWN && execution.lastFailure == failure
                    if (!repeated) audit(now, source, "APPROVAL_EXECUTION_UNKNOWN", "UNKNOWN", request, "reason=$failure", execution.executorActorId)
                    ApprovalOutcome.Executing(approvalId, ApprovalStatus.EXECUTION_UNKNOWN.name)
                }
            }
        }
    }

    /**
     * 재전송 중단(D-OPS-006, I-26): 참여자 정지·역할 상실 — EXECUTING이면 UNKNOWN으로, 실행 행에 중단 사유(처음 1회만), 감사 `APPROVAL_RESEND_HALTED`(SYSTEM_JOB).
     * 이미 중단됐거나 종료면 바꾸지 않는다. 새로 중단했으면 true.
     */
    fun haltResend(approvalId: String, reason: String): Boolean = transactions.execute({ false }) {
        val now = now()
        val locked = repository.lock(approvalId) ?: return@execute false
        val request = locked.value
        if (request.status !in ApprovalStatus.OWNER_SERVICE_ONLY) return@execute false
        val row = executions.lock(approvalId) ?: return@execute false
        if (row.value.resendHaltedReason != null) return@execute false
        if (request.status == ApprovalStatus.EXECUTING) repository.update(locked, request.executionUnknown())
        executions.update(row, row.value.haltResend(reason))
        audit(now, Source.Job, "APPROVAL_RESEND_HALTED", "HALTED", request, "reason=$reason", row.value.executorActorId)
        true
    }

    /** 회복 job이 찾은 내부 무결성 위반(예: 명령 행 없는 실행 행 — S6 리뷰 L-2): 지표 + 별도 TX 감사(SYSTEM_JOB, 승인 ID·target_ref). */
    fun reportIntegrity(approvalId: String, column: String, paymentId: String?) {
        guarded(approvalId, Source.Job) { throw IntegrityViolation(column, paymentId) }
    }

    private fun diverges(request: ApprovalRequest, outcome: Outcome): Boolean = when (outcome) {
        is Outcome.Started -> request.status != ApprovalStatus.EXECUTED ||
            executions.find(request.id)?.ownerReference?.let { it != outcome.cancellationId } == true
        is Outcome.Rejected -> request.status != ApprovalStatus.OWNER_REJECTED || request.resultCode != outcome.code
        is Outcome.Completed -> request.status != ApprovalStatus.EXECUTED || request.resultCode != outcome.code
        is Outcome.Unknown -> false
    }

    private fun terminalOutcome(request: ApprovalRequest): ApprovalOutcome = when (request.status) {
        ApprovalStatus.EXECUTED -> ApprovalOutcome.Executed(request.id)
        ApprovalStatus.OWNER_REJECTED -> ApprovalOutcome.OwnerRejected(request.resultCode ?: "UNKNOWN")
        else -> ApprovalOutcome.AlreadyDecided
    }

    private inline fun guarded(approvalId: String, source: Source, block: () -> ApprovalOutcome): ApprovalOutcome = try {
        block()
    } catch (violation: IntegrityViolation) {
        metrics.integrityViolation(violation.column)
        transactions.execute({ false }) {
            recorder.record(
                AuditRecord(
                    occurredAt = now(), actorKind = if (source is Source.Web) "ADMIN" else "SYSTEM_JOB", actorId = (source as? Source.Web)?.approverId,
                    actorDisplayName = null, action = "APPROVAL_INTEGRITY_VIOLATION", resultCode = "FAILED", correlationId = correlation(source, approvalId),
                    approvalId = approvalId, remoteIp = (source as? Source.Web)?.context?.remoteIp, detail = "column=${violation.column}", targetRef = violation.paymentId,
                ),
            )
        }
        ApprovalOutcome.Executing(approvalId, ApprovalStatus.EXECUTING.name)
    }

    @Suppress("LongParameterList")
    private fun audit(now: Instant, source: Source, action: String, result: String, request: ApprovalRequest, reasonDetail: String?, executor: String) {
        recorder.record(
            AuditRecord(
                occurredAt = now, actorKind = if (source is Source.Web) "ADMIN" else "SYSTEM_JOB", actorId = (source as? Source.Web)?.approverId, actorDisplayName = null,
                action = action, resultCode = result, correlationId = correlation(source, request.id), executorActorId = executor, approvalId = request.id,
                remoteIp = (source as? Source.Web)?.context?.remoteIp, detail = listOfNotNull("kind=${request.kind.name}", reasonDetail).joinToString(";"),
                targetRef = ownerRef(request),
            ),
        )
    }

    /** 소유 서비스 대상 참조(Payment ID·매입 원 파일 ID). 계정 대상이면 null. */
    private fun ownerRef(request: ApprovalRequest): String? = request.target.takeIf { it !is ApprovalTarget.AdminAccount }?.ref

    private fun correlation(source: Source, approvalId: String): String = (source as? Source.Web)?.context?.correlationId ?: "job-recovery-$approvalId"

    private fun now(): Instant = environment.now().truncatedTo(ChronoUnit.MILLIS)

    private class IntegrityViolation(val column: String, val paymentId: String?) : RuntimeException(null, null, false, false)

    private companion object {
        const val DECISION_CONTENT_MISMATCH = "DECISION_CONTENT_MISMATCH"
        const val MAX_CODE = 48
    }
}

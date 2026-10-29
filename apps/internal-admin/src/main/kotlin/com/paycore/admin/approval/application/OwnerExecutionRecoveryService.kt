package com.paycore.admin.approval.application

import com.paycore.admin.account.application.provided.AdminOwnedCommandExecutor
import com.paycore.admin.approval.application.provided.RecoverOwnerExecutionsUseCase
import com.paycore.admin.approval.application.required.ApprovalRequestRepository
import com.paycore.admin.approval.application.required.OperatorCancelCommandStore
import com.paycore.admin.approval.application.required.OwnerExecutionRepository
import com.paycore.admin.approval.application.required.OwnerOpsMetrics
import com.paycore.admin.approval.domain.ApprovalParticipants
import com.paycore.admin.approval.domain.ApprovalRequest
import com.paycore.admin.approval.domain.ApprovalStatus
import com.paycore.admin.approval.domain.OperatorCancelRequestSnapshot
import com.paycore.admin.approval.domain.OwnerExecution
import com.paycore.admin.paymentops.application.provided.DecisionContent
import com.paycore.admin.paymentops.application.provided.DecisionResult
import com.paycore.admin.paymentops.application.provided.ExecutionResult
import com.paycore.admin.paymentops.application.provided.PaymentOpsClient
import com.paycore.admin.paymentops.application.provided.PaymentOpsPolicy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import java.time.Duration
import java.time.Instant

/**
 * [RecoverOwnerExecutionsUseCase](ADMIN-PAYMENT-OPS-001 §4.16 — D-OPS-003·006·007·009, I-22·I-26·I-34·I-35).
 * - **선점**(행마다 처리 직전, 짧은 TX — S7 리뷰 L-1, I-121): `lockDue(limit 1)`(`FOR UPDATE SKIP LOCKED`) → `claimed`(시도 +1, 마지막 시도 = 선점 시각, 다음 시도 = 선점 시각 +
 *   backoff(새 시도 수)) → 커밋 → 그 행 처리 → 다음 행. 묶음으로 미리 선점하지 않으므로 앞 행 처리가 길어도 뒤 행의 backoff 시계가 미리 돌지 않는다(실행당 최대 batch-size 행).
 *   두 인스턴스가 동시에 돌아도 한 건은 한 번만 처리된다. 승인 직후 행은 다음 시도가 `승인 + stale`이라 웹 TX2와 겹치지 않는다.
 * - **처리**(TX 밖, 행마다 — 한 행의 실패는 그 행만 건너뛴다):
 *   1. 기록 결정 조회 → STARTED·REJECTED면 내용 대조(L-b) 후 반영(불일치면 플래그 먼저, I-35), 조회 불가면 UNKNOWN 기록 후 다음 주기, NONE이면 다음 단계.
 *   2. 기한 경과(ADMIN 시각 `now ≥ executeBefore + 여유`, I-34) → **실행 명령 대신** 저장 본문 그대로 기한 확인 호출 → 결정이면 반영(EXPIRED → OWNER_REJECTED, Payment 해제),
 *      NONE이면 다음 주기. 재전송 중단·무결성 플래그와 무관하게 이 단계가 먼저다.
 *   3. 참여자 재검사(D-OPS-006): 요청자 ACTIVE·OPERATOR, 승인자 ACTIVE·APPROVER — 아니면 재전송 중단(1회 기록·경보·감사), 이후 조회만. 자격을 되찾아도 재개 없음(I-26).
 *   4. 무결성 플래그가 있으면 POST 안 함(D-OPS-007 — 조회로만 수렴).
 *   5. 그 밖 → **같은 저장 본문 바이트**를 POST → 결과 반영.
 * - 시도 상한 없음(D-OPS-003), 수동 미실행 확정 없음. 끝에 미해결 gauge 갱신.
 */
@Service
class OwnerExecutionRecoveryService(
    transactionManager: PlatformTransactionManager,
    private val repository: ApprovalRequestRepository,
    private val commands: OperatorCancelCommandStore,
    private val executions: OwnerExecutionRepository,
    private val executor: AdminOwnedCommandExecutor,
    private val client: PaymentOpsClient,
    private val policy: PaymentOpsPolicy,
    private val applier: OwnerOutcomeApplier,
    private val opsMetrics: OwnerOpsMetrics,
) : RecoverOwnerExecutionsUseCase {

    private val transactions = LocalTransactions(transactionManager, maxAttempts = 1)

    override fun recoverDue(): Int {
        var processed = 0
        while (processed < policy.recoveryBatchSize) {
            val now = policy.now()
            val id = claimNext(now) ?: break
            processed++
            try {
                process(id, now)
            } catch (e: RuntimeException) {
                // S6 리뷰 L-2: 한 행의 실패는 그 행만 건너뛰고 지표로 보인다(경보 — 대사 UNRESOLVED_WITH_DECISION과 함께 조사).
                opsMetrics.resend("ERROR")
                log.warn("operation=owner-recovery approvalId={} result=error failure={}", id, e.javaClass.simpleName)
            }
        }
        refreshGauges(policy.now())
        return processed
    }

    /** 처리 직전에 한 행만 선점(S7 리뷰 L-1 — SKIP LOCKED limit 1, 짧은 TX). 선점할 행이 없으면 null. */
    private fun claimNext(now: Instant): String? = transactions.execute({ false }) {
        executions.lockDue(now, 1).singleOrNull()?.let { row ->
            executions.update(row, row.value.claimed(now, policy.backoff(row.value.attemptCount + 1)))
            row.value.approvalId
        }
    }

    private class Snapshot(val request: ApprovalRequest, val execution: OwnerExecution, val command: OperatorCancelRequestSnapshot)

    private fun process(id: String, now: Instant) {
        val read = transactions.execute({ false }) { Triple(repository.find(id), executions.find(id), commands.find(id)) }
        val (request, execution, command) = read
        if (request == null || execution == null || command == null) {
            // S6 리뷰 L-2: 선점된 실행 행에 승인·명령 행이 없으면 내부 무결성 위반(조용히 건너뛰지 않는다).
            val column = if (request == null) "approval" else if (command == null) "command" else "execution"
            applier.reportIntegrity(id, column, (request?.target as? com.paycore.admin.approval.domain.ApprovalTarget.Payment)?.paymentId)
            return
        }
        val s = Snapshot(request, execution, command)
        if (s.request.status !in ApprovalStatus.OWNER_SERVICE_ONLY) return
        // 1. 기록 결정 조회.
        when (val decision = client.decision(id)) {
            is DecisionResult.Recorded -> return applyRecorded(s, decision)
            is DecisionResult.Unavailable -> {
                // 외부 효과 없음 — 같은 분류 반복은 감사 없이 last_failure + 지표만(I-112).
                opsMetrics.resend("LOOKUP_UNAVAILABLE")
                applier.apply(
                    id, OwnerOutcomeApplier.Outcome.Unknown("DECISION_${decision.reason}".take(MAX_CODE), null), OwnerOutcomeApplier.Source.Job, quietIfUnchanged = true,
                )
                return
            }
            DecisionResult.None -> Unit
        }
        // 2. 기한 경과 → 기한 확인 전용 호출(실행 명령 아님 — D-OPS-009).
        if (!now.isBefore(s.execution.executeBefore.plus(policy.deadlineSkew))) {
            when (val confirmed = client.confirmDeadline(id, s.execution.requestBody())) {
                is DecisionResult.Recorded -> {
                    opsMetrics.resend("DEADLINE_${confirmed.decision}")
                    applyRecorded(s, confirmed)
                }
                DecisionResult.None -> opsMetrics.resend("DEADLINE_NONE")
                is DecisionResult.Unavailable -> {
                    opsMetrics.resend("DEADLINE_UNAVAILABLE")
                    applier.apply(id, OwnerOutcomeApplier.Outcome.Unknown("DEADLINE_${confirmed.reason}".take(MAX_CODE), null), OwnerOutcomeApplier.Source.Job)
                }
            }
            return
        }
        // 3. 참여자 재검사(D-OPS-006 — 중단된 승인은 다시 보지 않는다, I-26).
        if (s.execution.resendHaltedReason != null) return
        ineligibility(s)?.let { reason ->
            if (applier.haltResend(id, reason)) opsMetrics.resendHalted(reason)
            return
        }
        // 4. 무결성 플래그 → 조회로만 수렴(D-OPS-007).
        if (s.execution.integrityFlag != null) return
        // 5. 같은 저장 본문 재전송.
        val result = client.execute(id, s.execution.requestBody())
        opsMetrics.resend(
            when (result) {
                is ExecutionResult.Started -> "EXECUTE_STARTED"
                is ExecutionResult.OwnerRejected -> "EXECUTE_OWNER_REJECTED"
                is ExecutionResult.Unknown -> "EXECUTE_UNKNOWN"
            },
        )
        alertOf(result)?.let(opsMetrics::integrityAlert)
        applier.apply(id, toOutcome(result), OwnerOutcomeApplier.Source.Job)
    }

    /** 기록 결정 반영 전 내용 대조(L-b): paymentId·금액·통화·요청자·승인자·지문. 다르면 경보 + 플래그 후 반영(I-35 — 보류하지 않는다). */
    private fun applyRecorded(s: Snapshot, d: DecisionResult.Recorded) {
        val expected = DecisionContent(
            s.command.paymentId, s.command.amountMinor, s.command.currency, s.request.requestedBy, s.execution.executorActorId, s.execution.fingerprint,
        )
        val mismatch = d.content != expected
        if (mismatch) opsMetrics.integrityAlert(DECISION_CONTENT_MISMATCH)
        val outcome = if (d.decision == "STARTED") {
            OwnerOutcomeApplier.Outcome.Started(requireNotNull(d.cancellationId))
        } else {
            OwnerOutcomeApplier.Outcome.Rejected(requireNotNull(d.code))
        }
        applier.apply(s.request.id, outcome, OwnerOutcomeApplier.Source.Job, contentMismatch = mismatch)
    }

    private fun ineligibility(s: Snapshot): String? = transactions.execute({ false }) {
        val requester = executor.snapshot(s.request.requestedBy)
        val approver = executor.snapshot(s.execution.executorActorId)
        when {
            requester == null || !requester.holds(ApprovalParticipants.requesterRole(s.request.kind)) -> "REQUESTER_NOT_ELIGIBLE"
            approver == null || !approver.holds(ApprovalParticipants.APPROVER_ROLE) -> "APPROVER_NOT_ELIGIBLE"
            else -> null
        }
    }

    private fun refreshGauges(now: Instant) {
        try {
            val (counts, oldest) = transactions.execute({ false }) { repository.unresolvedCounts() to repository.oldestUnknownDecidedAt() }
            counts.forEach { (status, count) -> opsMetrics.unresolved(status, count) }
            opsMetrics.unknownOldestAge(oldest?.let { Duration.between(it, now).seconds.coerceAtLeast(0) } ?: 0)
        } catch (e: RuntimeException) {
            log.warn("operation=owner-recovery-gauges result=error failure={}", e.javaClass.simpleName)
        }
    }

    private fun toOutcome(result: ExecutionResult): OwnerOutcomeApplier.Outcome = when (result) {
        is ExecutionResult.Started -> OwnerOutcomeApplier.Outcome.Started(result.cancellationId)
        is ExecutionResult.OwnerRejected -> OwnerOutcomeApplier.Outcome.Rejected(result.code)
        is ExecutionResult.Unknown -> OwnerOutcomeApplier.Outcome.Unknown(result.failure, result.integrityFlag)
    }

    private fun alertOf(result: ExecutionResult): String? = when (result) {
        is ExecutionResult.OwnerRejected -> result.code.takeIf { result.integrityAlert || result.operationalAlert }
        is ExecutionResult.Unknown -> result.integrityFlag ?: result.failure.takeIf { result.alert }
        is ExecutionResult.Started -> null
    }

    private companion object {
        val log = LoggerFactory.getLogger(OwnerExecutionRecoveryService::class.java)
        const val DECISION_CONTENT_MISMATCH = "DECISION_CONTENT_MISMATCH"
        const val MAX_CODE = 48
    }
}

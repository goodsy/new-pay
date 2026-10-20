package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.provided.RecoverCancellationsCommand
import com.paycore.payment.payment.application.provided.RecoverCancellationsUseCase
import com.paycore.payment.payment.application.provided.RecoveryRunResult
import com.paycore.payment.payment.application.required.CancelAttemptViewRow
import com.paycore.payment.payment.application.required.CancelInquiryCall
import com.paycore.payment.payment.application.required.CancelInquiryCallResult
import com.paycore.payment.payment.application.required.CancelInquiryPort
import com.paycore.payment.payment.application.required.CancellationIdentifierGenerator
import com.paycore.payment.payment.application.required.CancellationRecoverySchedule
import com.paycore.payment.payment.application.required.CancellationRecoveryScheduleStore
import com.paycore.payment.payment.application.required.CancellationTimings
import com.paycore.payment.payment.application.required.CancellationViewReader
import com.paycore.payment.payment.application.required.EscalationReason
import com.paycore.payment.payment.application.required.InstitutionRouteResolver
import com.paycore.payment.payment.application.required.IntegrationEventIdGenerator
import com.paycore.payment.payment.application.required.OutboxStore
import com.paycore.payment.payment.application.required.PaymentCancellationRepository
import com.paycore.payment.payment.application.required.PaymentClock
import com.paycore.payment.payment.application.required.PersistenceFailure
import com.paycore.payment.payment.domain.AttemptState
import com.paycore.payment.payment.domain.CancelResolutionOutcome
import com.paycore.payment.payment.domain.CancellationStatus
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 취소 회복 Worker(CANCEL-001 §7 — AUTH-001 `AuthorizationRecoveryService` 설계 복제, CN-24 선택 A). 한 회차:
 * 1. **선점**(짧은 단독 TX): 닫히지 않았고 점검 시각이 된 취소 회복 일정을 `FOR UPDATE SKIP LOCKED`로 잡고 다음 점검 시각을 간격 뒤로 민다(분산 lock 없음 — ADR-001, K14).
 * 2. 일정마다(한 건 실패는 그 건만 — 다음 건 계속):
 *    - PREPARED ∧ 취소 UNKNOWN(보내지 않은 Attempt에 온 확정 결과 — 사건) → 조회·이관(검토 #3 m-1).
 *    - PREPARED ∧ `preparedAt + preparedDeadline` 경과 → fencing(TXd와 같은 행 잠금 — K7) → FAILED(PRE_TRANSMISSION)·RELEASED.
 *    - DISPATCHING → lease 경과면 OUTCOME_UNKNOWN(DISPATCH_LEASE_EXPIRED). "보내지 않았다"로 가정하지 않는다.
 *    - OUTCOME_UNKNOWN(또는 사건 처리된 UNKNOWN 취소) → (이관 전) 조회 수 ≥ 예산·연령 ≥ 최대 연령·보존기간 도달이면 대사 이관 `CancellationOutcomeUnresolved`(상태·예약 불변 —
 *      **D-CANCEL1-003 임시 출구 없음**). (이관 뒤) 취소 조회 보존기간이 지나면 조회를 멈춘다(일정 closed, UNKNOWN 그대로 — RECON만). 그 밖에는 **DB TX 밖에서** 원 취소 요청 ID로 조회 한 번
 *      (새 기관 요청 ID·새 취소 요청 없음 — K-14). `closeIfAbsent`는 Attempt에 고정된 Route 프로파일 버전 = 현재 등록부 버전(**D-CANCEL1-004** — 다르거나 없으면 종결 요청 없음, fail-closed,
 *      로그 `closure=WITHHELD`) ∧ 취소 Capability `confirmsNoEffect` ∧ `closableAfter ≤ 연령 < inquiryRetention`일 때만. Route가 없으면 조회는 불가(UNKNOWN_ROUTE)로 세고 예산·연령으로
 *      이관한다(rev 0.3 n-7). 반영은 TX2와 같은 단계([CancellationResultService.applyInquiry]) — NOT_FOUND·PENDING·UNAVAILABLE은 UNKNOWN 유지("never auto-fail").
 * 인과 값: correlationId = 회차 ID, causationId = `cancellation-recovery:<회차 ID>`. 로그에는 내부 ID만(기관 요청 ID·기관 계약계정·키 없음).
 */
@Service
class CancellationRecoveryService(
    transactionManager: PlatformTransactionManager,
    private val views: CancellationViewReader,
    private val cancellations: PaymentCancellationRepository,
    private val schedules: CancellationRecoveryScheduleStore,
    private val outbox: OutboxStore,
    private val eventIds: IntegrationEventIdGenerator,
    private val ids: CancellationIdentifierGenerator,
    private val results: CancellationResultService,
    private val inquiry: CancelInquiryPort,
    private val routes: InstitutionRouteResolver,
    private val timings: CancellationTimings,
    private val clock: PaymentClock,
) : RecoverCancellationsUseCase {
    private val log = LoggerFactory.getLogger(CancellationRecoveryService::class.java)
    private val transactions = LocalTransactions(transactionManager, MAX_ATTEMPTS)

    private class Tally {
        var fenced = 0
        var leaseExpired = 0
        var inquiries = 0
        var concluded = 0
        var escalated = 0
        var failed = 0
    }

    override fun recover(command: RecoverCancellationsCommand): RecoveryRunResult {
        val causation = EventCausation(command.runId.take(MAX_CORRELATION), "cancellation-recovery:${command.runId}".take(MAX_CAUSATION))
        val tally = Tally()
        val claimed = try {
            claim(command.batchSize)
        } catch (e: RuntimeException) {
            log.warn("operation=CancellationRecovery runId={} result=CLAIM_FAILED failure={}", command.runId, e::class.simpleName)
            emptyList()
        }
        claimed.forEach { schedule ->
            try {
                handle(schedule, causation, tally)
            } catch (e: RuntimeException) {
                tally.failed++
                log.warn(
                    "operation=CancellationRecovery runId={} cancellationId={} attemptId={} result=FAILED failure={}",
                    command.runId, schedule.cancellationId.value, schedule.attemptId.value, e::class.simpleName,
                )
            }
        }
        val result = RecoveryRunResult(claimed.size, tally.fenced, tally.leaseExpired, tally.inquiries, tally.concluded, tally.escalated, tally.failed)
        log.info(
            "operation=CancellationRecovery runId={} claimed={} fenced={} leaseExpired={} inquiries={} concluded={} escalated={} failed={}",
            command.runId, result.claimed, result.fenced, result.leaseExpired, result.inquiries, result.concluded, result.escalated, result.failed,
        )
        return result
    }

    private fun claim(limit: Int): List<CancellationRecoverySchedule> = transactions.execute(::isRetryable) {
        val now = now()
        schedules.claimDue(now, limit).map { s -> s.copy(nextCheckAt = now.plus(interval(s))).also { schedules.update(it, now) } }
    }

    private fun handle(schedule: CancellationRecoverySchedule, causation: EventCausation, tally: Tally) {
        val attempt = views.findAttempt(schedule.attemptId) ?: error("a recovery schedule refers to a stored cancel attempt")
        val status = views.find(attempt.cancellationId)?.status ?: error("a cancel attempt belongs to a stored cancellation")
        val now = now()
        when (attempt.state) {
            // 검토 #3 m-1: 보내지 않은 Attempt에 확정 결과가 와 사건 처리된 UNKNOWN 취소 — fencing은 NoChange뿐이라 조회·이관 경로로 간다
            // (고정 버전 NULL → 종결 요청 없음, D-CANCEL1-004; 끝은 이관 — D-CANCEL1-003).
            AttemptState.PREPARED -> if (status == CancellationStatus.UNKNOWN) {
                inquireOrEscalate(schedule, attempt, now, causation, tally)
            } else if (!now.isBefore(attempt.preparedAt.plus(timings.preparedDeadline))) {
                val outcome = results.fence(attempt.cancellationId, attempt.attemptId, causation)
                if (outcome is CancelResolutionOutcome.Failed) tally.fenced++
                record(causation, attempt, "FENCE", outcome)
            }
            AttemptState.DISPATCHING ->
                if (status == CancellationStatus.UNKNOWN) {
                    // 사건 처리된 진행 중 취소(Attempt가 아직 DISPATCHING) — 조회·이관 경로.
                    inquireOrEscalate(schedule, attempt, now, causation, tally)
                } else {
                    // 선점 사본의 lease는 TXd 커밋 전 값일 수 있다 — 판정은 결과 반영 TX가 잠근 일정으로 한다(아직이면 NoChange).
                    val outcome = results.markLeaseExpired(attempt.cancellationId, attempt.attemptId, causation)
                    if (outcome != CancelResolutionOutcome.NoChange) tally.leaseExpired++
                    record(causation, attempt, "LEASE_EXPIRED", outcome)
                }
            AttemptState.OUTCOME_UNKNOWN -> inquireOrEscalate(schedule, attempt, now, causation, tally)
            AttemptState.RESOLVED -> if (status == CancellationStatus.UNKNOWN) inquireOrEscalate(schedule, attempt, now, causation, tally) else closeSchedule(attempt)
            AttemptState.ABANDONED_BEFORE_DISPATCH -> closeSchedule(attempt)
        }
    }

    private fun closeSchedule(attempt: CancelAttemptViewRow) {
        transactions.execute(::isRetryable) {
            schedules.lock(attempt.attemptId)?.takeIf { it.closedAt == null }?.let { schedules.update(it.copy(closedAt = now()), now()) }
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun inquireOrEscalate(schedule: CancellationRecoverySchedule, attempt: CancelAttemptViewRow, now: Instant, causation: EventCausation, tally: Tally) {
        val age = Duration.between(attempt.preparedAt, now)
        val profile = routes.resolve(attempt.routeRef)
        val cancel = profile?.capabilities?.cancel
        if (profile == null) {
            log.info(
                "operation=CancellationRecovery runId={} cancellationId={} attemptId={} closure=WITHHELD reason=ROUTE_NOT_REGISTERED",
                causation.correlationId, attempt.cancellationId.value, attempt.attemptId.value,
            )
        }
        val retentionReached = cancel != null && age >= cancel.inquiryRetention
        if (schedule.escalatedAt == null && (schedule.inquiryCount >= timings.inquiryBudget || age >= timings.maxAge || retentionReached)) {
            val reason = if (schedule.inquiryCount >= timings.inquiryBudget) EscalationReason.BUDGET else EscalationReason.MAX_AGE
            if (escalate(attempt, reason, causation)) tally.escalated++
            return
        }
        if (schedule.escalatedAt != null && cancel != null && age >= cancel.inquiryRetention) {
            stopInquiries(attempt, "RETENTION_ELAPSED", null)
            return
        }
        // 검토 #3 n-6: Route가 없으면 보존기간을 알 수 없다 — 이관 뒤 최대 연령에서 느린 조회를 멈춘다(UNKNOWN 그대로 — RECON만).
        if (schedule.escalatedAt != null && profile == null && age >= timings.maxAge) {
            stopInquiries(attempt, "MAX_AGE_ELAPSED", "ROUTE_NOT_REGISTERED")
            return
        }
        // D-CANCEL1-004: 무효과 종결 요청은 전송 때 고정한 Route 프로파일 버전이 현재 등록부 버전과 같을 때만 — 다르거나 없으면 조회·이관만(fail-closed).
        val sameProfile = profile != null && attempt.routeProfileVersion == profile.profileVersion
        val closable = cancel != null && cancel.confirmsNoEffect && age >= cancel.closableAfter && age < cancel.inquiryRetention
        val closeIfAbsent = sameProfile && closable
        if (!sameProfile && closable) {
            log.info(
                "operation=CancellationRecovery runId={} cancellationId={} attemptId={} action=INQUIRY closure=WITHHELD reason=ROUTE_PROFILE_VERSION_MISMATCH",
                causation.correlationId, attempt.cancellationId.value, attempt.attemptId.value,
            )
        }
        tally.inquiries++
        val call = CancelInquiryCall(attempt.attemptId, attempt.providerRequest, attempt.routeRef, ids.inquiryId(now), closeIfAbsent)
        val result = inquiry.inquire(call)
        if (result is CancelInquiryCallResult.Unavailable) {
            inquiryUnavailable(attempt, result.reason.name, causation)
            return
        }
        val outcome = results.applyInquiry(attempt.cancellationId, attempt.attemptId, result, causation)
        if (outcome is CancelResolutionOutcome.Completed || outcome is CancelResolutionOutcome.Failed) tally.concluded++
        record(causation, attempt, "INQUIRY", outcome)
    }

    /** 조회가 결과를 주지 못함(관찰 없음): 조회 수 +1·다음 조회 시각만 적는다(일정 행 하나 — 업무 행을 잠그지 않는다). */
    private fun inquiryUnavailable(attempt: CancelAttemptViewRow, reason: String, causation: EventCausation) {
        transactions.execute(::isRetryable) {
            val now = now()
            val s = schedules.lock(attempt.attemptId) ?: error("cancellation recovery schedule is not stored")
            if (s.closedAt == null) schedules.update(s.copy(inquiryCount = s.inquiryCount + 1, nextCheckAt = now.plus(interval(s))), now)
        }
        log.info(
            "operation=CancellationRecovery runId={} cancellationId={} attemptId={} action=INQUIRY result=UNAVAILABLE reason={}",
            causation.correlationId, attempt.cancellationId.value, attempt.attemptId.value, reason,
        )
    }

    /** 대사 이관(§7.1): 잠금 payment_cancellation → 회복 일정. 아직 진행 중(UNKNOWN)이고 이관 전일 때만 이벤트 하나(effect_key = Attempt). 상태·예약은 바꾸지 않는다(D-CANCEL1-003). */
    private fun escalate(attempt: CancelAttemptViewRow, reason: EscalationReason, causation: EventCausation): Boolean {
        val escalated = transactions.execute(::isRetryable) {
            val now = now()
            val cancellation = cancellations.lock(attempt.cancellationId) ?: error("the cancellation is not stored")
            val c = cancellation.value
            val s = schedules.lock(attempt.attemptId) ?: error("cancellation recovery schedule is not stored")
            if (c.status != CancellationStatus.UNKNOWN || s.escalatedAt != null || s.closedAt != null) return@execute false
            val configVersion = timings.recoveryConfigVersion
            outbox.append(
                CancellationEvents.unresolved(
                    c, attempt.attemptId, s.inquiryCount, attempt.preparedAt, configVersion, reason, cancellation.version, now, eventIds.newEventId(), causation,
                ),
            )
            schedules.update(s.copy(escalatedAt = now, escalationConfigVersion = configVersion, nextCheckAt = now.plus(timings.slowInquiryInterval)), now)
            true
        }
        if (escalated) {
            log.warn(
                "operation=CancellationRecovery runId={} cancellationId={} attemptId={} action=ESCALATE result=OUTCOME_UNRESOLVED reason={}",
                causation.correlationId, attempt.cancellationId.value, attempt.attemptId.value, reason,
            )
        }
        return escalated
    }

    /**
     * 이관 뒤 조회 중단: 취소 조회 보존기간 경과, 또는 Route가 없을 때 최대 연령 경과(검토 #3 n-6). 일정 closed. 취소는 UNKNOWN·예약 RESERVED 그대로 —
     * RECON만 남는다(D-CANCEL1-003, LIVE Gate 조건).
     */
    private fun stopInquiries(attempt: CancelAttemptViewRow, result: String, reason: String?) {
        transactions.execute(::isRetryable) {
            val now = now()
            schedules.lock(attempt.attemptId)?.takeIf { it.closedAt == null }?.let { schedules.update(it.copy(closedAt = now), now) }
        }
        log.warn(
            "operation=CancellationRecovery cancellationId={} attemptId={} action=STOP_INQUIRY result={} reason={}",
            attempt.cancellationId.value, attempt.attemptId.value, result, reason ?: "-",
        )
    }

    private fun record(causation: EventCausation, attempt: CancelAttemptViewRow, action: String, outcome: CancelResolutionOutcome) {
        log.info(
            "operation=CancellationRecovery runId={} cancellationId={} attemptId={} action={} result={}",
            causation.correlationId, attempt.cancellationId.value, attempt.attemptId.value, action, outcome::class.simpleName,
        )
    }

    private fun interval(s: CancellationRecoverySchedule): Duration = if (s.escalatedAt != null) timings.slowInquiryInterval else timings.inquiryInterval

    private fun now(): Instant = clock.now().truncatedTo(ChronoUnit.MILLIS)

    private fun isRetryable(e: RuntimeException): Boolean = e is PersistenceFailure.Deadlock || e is PersistenceFailure.OptimisticConflict

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val MAX_CORRELATION = 128
        const val MAX_CAUSATION = 64
    }
}

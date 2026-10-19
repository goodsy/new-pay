package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.provided.RecoverAuthorizationsCommand
import com.paycore.payment.payment.application.provided.RecoverAuthorizationsUseCase
import com.paycore.payment.payment.application.provided.RecoveryRunResult
import com.paycore.payment.payment.application.required.AuthorizationAttemptViewRow
import com.paycore.payment.payment.application.required.AuthorizationIdentifierGenerator
import com.paycore.payment.payment.application.required.AuthorizationInquiryCall
import com.paycore.payment.payment.application.required.AuthorizationInquiryPort
import com.paycore.payment.payment.application.required.AuthorizationRecoveryScheduleStore
import com.paycore.payment.payment.application.required.AuthorizationTimings
import com.paycore.payment.payment.application.required.AuthorizationViewReader
import com.paycore.payment.payment.application.required.EscalationReason
import com.paycore.payment.payment.application.required.InquiryCallResult
import com.paycore.payment.payment.application.required.InquiryUnavailableReason
import com.paycore.payment.payment.application.required.InstitutionRouteResolver
import com.paycore.payment.payment.application.required.IntegrationEventIdGenerator
import com.paycore.payment.payment.application.required.OutboxStore
import com.paycore.payment.payment.application.required.PaymentAuthorizationRepository
import com.paycore.payment.payment.application.required.PaymentClock
import com.paycore.payment.payment.application.required.PersistenceFailure
import com.paycore.payment.payment.application.required.RecoverySchedule
import com.paycore.payment.payment.domain.AttemptState
import com.paycore.payment.payment.domain.InstitutionObservation
import com.paycore.payment.payment.domain.ResolutionOutcome
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 승인 회복 Worker(AUTH-001 §6). 한 회차:
 * 1. **선점**(짧은 단독 TX): 닫히지 않았고 점검 시각이 된 회복 일정을 `FOR UPDATE SKIP LOCKED`로 잡고 다음 점검 시각을 조회 간격 뒤로 민다 — 다른 Worker는 그 행을 건너뛰고,
 *    이 Worker가 도중에 죽으면 간격 뒤 다시 후보가 된다(분산 lock 없음 — ADR-001). 판정은 일정 행이 아니라 잠근 Aggregate가 한다.
 * 2. 일정마다(한 건 실패는 그 건만 — 다음 건 계속, 다음 회차 재시도):
 *    - PREPARED ∧ `preparedAt + preparedDeadline` 경과 → fencing(TXd와 같은 행 조건부 갱신 — C23) → FAILED(PRE_TRANSMISSION).
 *    - DISPATCHING ∧ dispatch lease 경과 → OUTCOME_UNKNOWN(DISPATCH_LEASE_EXPIRED). "보내지 않았다"로 가정하지 않는다(A06).
 *    - OUTCOME_UNKNOWN → (이관 전) 조회 수 ≥ 예산 또는 연령 ≥ 최대 연령이면 대사 이관: `AuthorizationOutcomeUnresolved` + `escalatedAt`(상태 불변, D-AUTH-009).
 *      (이관 뒤) Route 조회 보존기간이 지나면 조회를 멈춘다(일정 closed — 상태 UNKNOWN 그대로, RECON exact-match만 남음, AU-22).
 *      그 밖에는 **DB TX 밖에서** 원 기관 요청 ID로 조회 한 번(새 요청 ID·새 승인 요청 없음). `closeIfAbsent`는 Attempt에 고정된 Route 프로파일 버전 = 현재 등록부 버전
 *      (D-AUTH1-007 — 다르거나 없으면 종결 요청 없음, fail-closed) ∧ Route Capability `confirmsNoEffect` ∧ 연령 ≥ `closableAfter`일 때만.
 *      결과는 confirm TX2와 같은 반영 단계([AuthorizationResultService.applyInquiry])로 — NOT_FOUND·PENDING·UNAVAILABLE은 UNKNOWN 유지(§6.3 "never auto-fail").
 * 인과 값: correlationId = 회차 ID, causationId = `authorization-recovery:<회차 ID>`. 로그에는 내부 ID만(기관 요청 ID·기관 계약계정·키 없음).
 */
@Service
class AuthorizationRecoveryService(
    transactionManager: PlatformTransactionManager,
    private val views: AuthorizationViewReader,
    private val authorizations: PaymentAuthorizationRepository,
    private val schedules: AuthorizationRecoveryScheduleStore,
    private val outbox: OutboxStore,
    private val eventIds: IntegrationEventIdGenerator,
    private val ids: AuthorizationIdentifierGenerator,
    private val results: AuthorizationResultService,
    private val inquiry: AuthorizationInquiryPort,
    private val routes: InstitutionRouteResolver,
    private val timings: AuthorizationTimings,
    private val clock: PaymentClock,
) : RecoverAuthorizationsUseCase {
    private val log = LoggerFactory.getLogger(AuthorizationRecoveryService::class.java)
    private val transactions = LocalTransactions(transactionManager, MAX_ATTEMPTS)

    private class Tally {
        var fenced = 0
        var leaseExpired = 0
        var inquiries = 0
        var concluded = 0
        var escalated = 0
        var failed = 0
    }

    override fun recover(command: RecoverAuthorizationsCommand): RecoveryRunResult {
        val causation = EventCausation(command.runId, "authorization-recovery:${command.runId}")
        val tally = Tally()
        val claimed = try {
            claim(command.batchSize)
        } catch (e: RuntimeException) {
            log.warn("operation=AuthorizationRecovery runId={} result=CLAIM_FAILED failure={}", command.runId, e::class.simpleName)
            emptyList()
        }
        claimed.forEach { schedule ->
            try {
                handle(schedule, causation, tally)
            } catch (e: RuntimeException) {
                tally.failed++
                log.warn(
                    "operation=AuthorizationRecovery runId={} authorizationId={} attemptId={} result=FAILED failure={}",
                    command.runId, schedule.authorizationId.value, schedule.attemptId.value, e::class.simpleName,
                )
            }
        }
        val result = RecoveryRunResult(claimed.size, tally.fenced, tally.leaseExpired, tally.inquiries, tally.concluded, tally.escalated, tally.failed)
        log.info(
            "operation=AuthorizationRecovery runId={} claimed={} fenced={} leaseExpired={} inquiries={} concluded={} escalated={} failed={}",
            command.runId, result.claimed, result.fenced, result.leaseExpired, result.inquiries, result.concluded, result.escalated, result.failed,
        )
        return result
    }

    private fun claim(limit: Int): List<RecoverySchedule> = transactions.execute(::isRetryable) {
        val now = now()
        // 검토 #3 n-6: 이관된 일정은 느린 간격으로 민다.
        schedules.claimDue(now, limit).map { s -> s.copy(nextCheckAt = now.plus(interval(s))).also { schedules.update(it, now) } }
    }

    private fun handle(schedule: RecoverySchedule, causation: EventCausation, tally: Tally) {
        val attempt = views.findAttempt(schedule.attemptId) ?: error("a recovery schedule refers to a stored attempt")
        val now = now()
        when (attempt.state) {
            AttemptState.PREPARED -> if (!now.isBefore(attempt.preparedAt.plus(timings.preparedDeadline))) {
                val outcome = results.fence(attempt.authorizationId, attempt.attemptId, causation)
                if (outcome is ResolutionOutcome.Failed) tally.fenced++
                record(causation, attempt, "FENCE", outcome)
            }
            // 검토 #3 m-1: 선점 사본의 lease는 TXd 커밋 전 값일 수 있다 — 판정은 결과 반영 TX가 잠근 일정으로 한다(아직이면 NoChange).
            AttemptState.DISPATCHING -> {
                val outcome = results.markLeaseExpired(attempt.authorizationId, attempt.attemptId, causation)
                if (outcome != ResolutionOutcome.NoChange) tally.leaseExpired++
                record(causation, attempt, "LEASE_EXPIRED", outcome)
            }
            AttemptState.OUTCOME_UNKNOWN -> inquireOrEscalate(schedule, attempt, now, causation, tally)
            // 이미 결론난 Attempt의 열린 일정(정상 경로에서는 결과 반영 TX가 닫는다) — 일정만 닫는다.
            AttemptState.RESOLVED, AttemptState.ABANDONED_BEFORE_DISPATCH -> transactions.execute(::isRetryable) {
                schedules.lock(schedule.attemptId)?.takeIf { it.closedAt == null }?.let { schedules.update(it.copy(closedAt = now()), now()) }
            }
        }
    }

    private fun inquireOrEscalate(schedule: RecoverySchedule, attempt: AuthorizationAttemptViewRow, now: Instant, causation: EventCausation, tally: Tally) {
        val age = Duration.between(attempt.preparedAt, now)
        val profile = routes.resolve(attempt.routeRef)
        val capabilities = profile?.capabilities
        // 검토 #3 m-2: 보존기간에 이르면(기동 검사로 maxAge < 보존기간이라 정상적으로는 그 전에) 종결 요청 없이 먼저 이관한다.
        val retentionReached = capabilities != null && age >= capabilities.inquiryRetention
        if (schedule.escalatedAt == null && (schedule.inquiryCount >= timings.inquiryBudget || age >= timings.maxAge || retentionReached)) {
            val reason = if (schedule.inquiryCount >= timings.inquiryBudget) EscalationReason.BUDGET else EscalationReason.MAX_AGE
            if (escalate(attempt, reason, causation)) tally.escalated++
            return
        }
        if (schedule.escalatedAt != null && capabilities != null && age >= capabilities.inquiryRetention) {
            stopInquiries(attempt)
            return
        }
        // D-AUTH1-007: 무효과 종결 요청은 전송 때 고정한 Route 프로파일 버전이 현재 등록부 버전과 같을 때만 — 다르거나 없으면(V14 이전 행) 조회·이관만(fail-closed).
        val sameProfile = profile != null && attempt.routeProfileVersion == profile.profileVersion
        // 검토 #3 m-2: 종결 요청은 보존기간 안에서만(보존기간 밖 "없음"은 기관이 확인해 줄 수 없다).
        val closeIfAbsent = sameProfile && capabilities != null && capabilities.confirmsNoEffect && age >= capabilities.closableAfter && age < capabilities.inquiryRetention
        if (!sameProfile && capabilities != null && capabilities.confirmsNoEffect && age >= capabilities.closableAfter) {
            log.info(
                "operation=AuthorizationRecovery runId={} authorizationId={} attemptId={} action=INQUIRY closure=WITHHELD reason=ROUTE_PROFILE_VERSION_MISMATCH",
                causation.correlationId, attempt.authorizationId.value, attempt.attemptId.value,
            )
        }
        tally.inquiries++
        val call = AuthorizationInquiryCall(attempt.attemptId, attempt.providerRequest, attempt.routeRef, ids.inquiryId(now), closeIfAbsent)
        when (val result = inquiry.inquire(call)) {
            is InquiryCallResult.Found -> applyInquiry(attempt, result.observation, causation, tally)
            is InquiryCallResult.NotFound -> applyInquiry(attempt, result.observation, causation, tally)
            is InquiryCallResult.NoEffectConfirmed -> applyInquiry(attempt, result.observation, causation, tally)
            is InquiryCallResult.Unavailable -> inquiryUnavailable(attempt, result.reason, causation)
        }
    }

    private fun applyInquiry(attempt: AuthorizationAttemptViewRow, observation: InstitutionObservation, causation: EventCausation, tally: Tally) {
        val outcome = results.applyInquiry(attempt.authorizationId, attempt.attemptId, observation, causation)
        if (outcome is ResolutionOutcome.Authorized || outcome is ResolutionOutcome.Failed) tally.concluded++
        record(causation, attempt, "INQUIRY_${observation.outcome}", outcome)
    }

    /** 조회가 결과를 주지 못함(관찰 없음): 조회 수 +1·다음 조회 시각만 적는다(일정 행 하나 — 업무 행을 잠그지 않는다). */
    private fun inquiryUnavailable(attempt: AuthorizationAttemptViewRow, reason: InquiryUnavailableReason, causation: EventCausation) {
        transactions.execute(::isRetryable) {
            val now = now()
            val s = schedules.lock(attempt.attemptId) ?: error("recovery schedule is not stored")
            if (s.closedAt == null) schedules.update(s.copy(inquiryCount = s.inquiryCount + 1, nextCheckAt = now.plus(interval(s))), now)
        }
        log.info(
            "operation=AuthorizationRecovery runId={} authorizationId={} attemptId={} action=INQUIRY result=UNAVAILABLE reason={}",
            causation.correlationId, attempt.authorizationId.value, attempt.attemptId.value, reason,
        )
    }

    /** 대사 이관(§6.2): 잠금 순서 payment_authorization → 회복 일정. 아직 OUTCOME_UNKNOWN이고 이관 전일 때만 이벤트 하나(effect_key = Attempt). 상태는 바꾸지 않는다. */
    private fun escalate(attempt: AuthorizationAttemptViewRow, reason: EscalationReason, causation: EventCausation): Boolean {
        val escalated = transactions.execute(::isRetryable) {
            val now = now()
            val authorization = authorizations.lock(attempt.authorizationId) ?: error("the authorization is not stored")
            val a = authorization.value
            val s = schedules.lock(attempt.attemptId) ?: error("recovery schedule is not stored")
            if (a.attempt(attempt.attemptId).state != AttemptState.OUTCOME_UNKNOWN || s.escalatedAt != null || s.closedAt != null) return@execute false
            val configVersion = timings.recoveryConfigVersion
            outbox.append(
                AuthorizationEvents.unresolved(
                    a, attempt.attemptId, s.inquiryCount, attempt.preparedAt, configVersion, reason, authorization.version, now, eventIds.newEventId(), causation,
                ),
            )
            schedules.update(s.copy(escalatedAt = now, escalationConfigVersion = configVersion, nextCheckAt = now.plus(timings.slowInquiryInterval)), now)
            true
        }
        if (escalated) {
            log.warn(
                "operation=AuthorizationRecovery runId={} authorizationId={} attemptId={} action=ESCALATE result=OUTCOME_UNRESOLVED reason={}",
                causation.correlationId, attempt.authorizationId.value, attempt.attemptId.value, reason,
            )
        }
        return escalated
    }

    /** 이관 뒤 Route 조회 보존기간 경과: 조회를 멈춘다(일정 closed). Payment·Authorization은 UNKNOWN 그대로 — RECON exact-match Recovery만 남는다(AU-22). */
    private fun stopInquiries(attempt: AuthorizationAttemptViewRow) {
        transactions.execute(::isRetryable) {
            val now = now()
            schedules.lock(attempt.attemptId)?.takeIf { it.closedAt == null }?.let { schedules.update(it.copy(closedAt = now), now) }
        }
        log.warn(
            "operation=AuthorizationRecovery authorizationId={} attemptId={} action=STOP_INQUIRY result=RETENTION_ELAPSED",
            attempt.authorizationId.value, attempt.attemptId.value,
        )
    }

    private fun record(causation: EventCausation, attempt: AuthorizationAttemptViewRow, action: String, outcome: ResolutionOutcome) {
        log.info(
            "operation=AuthorizationRecovery runId={} authorizationId={} attemptId={} action={} result={}",
            causation.correlationId, attempt.authorizationId.value, attempt.attemptId.value, action, outcome::class.simpleName,
        )
    }

    private fun interval(s: RecoverySchedule): Duration = if (s.escalatedAt != null) timings.slowInquiryInterval else timings.inquiryInterval

    private fun now(): Instant = clock.now().truncatedTo(ChronoUnit.MILLIS)

    private fun isRetryable(e: RuntimeException): Boolean = e is PersistenceFailure.Deadlock || e is PersistenceFailure.OptimisticConflict

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}

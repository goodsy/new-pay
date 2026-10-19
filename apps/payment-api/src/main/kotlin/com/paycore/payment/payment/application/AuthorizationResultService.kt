package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.required.AuthenticationSessionRepository
import com.paycore.payment.payment.application.required.AuthorizationCallResult
import com.paycore.payment.payment.application.required.AuthorizationIdentifierGenerator
import com.paycore.payment.payment.application.required.AuthorizationRecoveryScheduleStore
import com.paycore.payment.payment.application.required.AuthorizationTimings
import com.paycore.payment.payment.application.required.AuthorizationViewReader
import com.paycore.payment.payment.application.required.DailyLimitUsageRepository
import com.paycore.payment.payment.application.required.IntegrationEventIdGenerator
import com.paycore.payment.payment.application.required.LimitPolicyHistoryPort
import com.paycore.payment.payment.application.required.OrderPaymentControlRepository
import com.paycore.payment.payment.application.required.OutboxStore
import com.paycore.payment.payment.application.required.PaymentAuthorizationRepository
import com.paycore.payment.payment.application.required.PaymentClock
import com.paycore.payment.payment.application.required.PaymentRepository
import com.paycore.payment.payment.application.required.PersistenceFailure
import com.paycore.payment.payment.domain.AbandonCause
import com.paycore.payment.payment.domain.AttemptId
import com.paycore.payment.payment.domain.AttemptState
import com.paycore.payment.payment.domain.AuthenticationSession
import com.paycore.payment.payment.domain.AuthorizationId
import com.paycore.payment.payment.domain.AuthorizationResolution
import com.paycore.payment.payment.domain.DailyLimitUsage
import com.paycore.payment.payment.domain.InstitutionObservation
import com.paycore.payment.payment.domain.OrderPaymentControl
import com.paycore.payment.payment.domain.Payment
import com.paycore.payment.payment.domain.PaymentAuthorization
import com.paycore.payment.payment.domain.ResolutionOutcome
import com.paycore.payment.payment.domain.UnknownCause
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 기관 결과 반영 TX2(AUTH-001 §4.6) — confirm의 응답 반영과 회복 Worker의 조회 반영·lease 표시가 **같은 단계**를 쓴다. 한 PAYMENT 로컬 TX, 잠금 순서 §4.7:
 * control → payment → payment_authorization(→ attempt) → 일 한도 사용량 → (승인 확정 때 인증 세션 읽기) → 회복 일정. 관찰 INSERT·Aggregate 갱신·거래·Outbox가 같은 TX다
 * (관찰을 별도 선저장하지 않는다 — CA-16). 사건 이벤트는 **새 hold가 append됐을 때만**(F-1·AU-17). 늦은 승인 FORCED 초과 표시는 원 Authorization에 고정된 한도 정책(AU-29).
 * 교착·version 충돌은 새 TX로 다시 판정한다. 그 밖 실패는 호출자에게 던진다(confirm은 202 — 회복이 원 Attempt 조회로 정리, AC1 §6-3).
 */
@Service
class AuthorizationResultService(
    transactionManager: PlatformTransactionManager,
    private val views: AuthorizationViewReader,
    private val controls: OrderPaymentControlRepository,
    private val payments: PaymentRepository,
    private val authorizations: PaymentAuthorizationRepository,
    private val usages: DailyLimitUsageRepository,
    private val sessions: AuthenticationSessionRepository,
    private val schedules: AuthorizationRecoveryScheduleStore,
    private val outbox: OutboxStore,
    private val eventIds: IntegrationEventIdGenerator,
    private val ids: AuthorizationIdentifierGenerator,
    private val history: LimitPolicyHistoryPort,
    private val timings: AuthorizationTimings,
    private val clock: PaymentClock,
) {
    private val transactions = LocalTransactions(transactionManager, MAX_ATTEMPTS)

    /** 기관 호출 결과(TX2): 관찰이면 판정표, connect 단계 실패면 전송 전 포기(CONNECT_FAILED), 쓰기 뒤 실패면 결과 불명확 표시. */
    fun applyCall(authorizationId: AuthorizationId, attemptId: AttemptId, result: AuthorizationCallResult, causation: EventCausation): ResolutionOutcome =
        apply(authorizationId, causation) { c ->
            when (result) {
                is AuthorizationCallResult.Approved -> observe(c, result.observation)
                is AuthorizationCallResult.Declined -> observe(c, result.observation)
                is AuthorizationCallResult.Pending -> observe(c, result.observation)
                AuthorizationCallResult.NotSentConfirmed ->
                    AuthorizationResolution.abandonBeforeDispatch(c.payment, c.authorization, c.usage, attemptId, AbandonCause.CONNECT_FAILED, c.at)
                is AuthorizationCallResult.OutcomeUnknown -> AuthorizationResolution.markOutcomeUnknown(c.payment, c.authorization, attemptId, result.cause)
            }
        }

    /** 관찰 하나(응답·조회·주입)를 반영한다. */
    fun applyObservation(authorizationId: AuthorizationId, observation: InstitutionObservation, causation: EventCausation = DEFAULT_CAUSATION): ResolutionOutcome =
        apply(authorizationId, causation) { c -> observe(c, observation) }

    /**
     * 회복 조회 결과 하나(AUTH-001 §6.1 ③): 관찰 반영과 회복 일정의 조회 수 +1·다음 조회 시각(이관 전 조회 간격, 이관 뒤 느린 간격 — AU-22)을 **같은 TX**에 적는다.
     * NOT_FOUND·PENDING은 관찰로만 남고 상태는 UNKNOWN 그대로다(§6.3).
     */
    fun applyInquiry(authorizationId: AuthorizationId, attemptId: AttemptId, observation: InstitutionObservation, causation: EventCausation): ResolutionOutcome =
        apply(authorizationId, causation, inquired = attemptId) { c -> observe(c, observation) }

    /** 회복: dispatch lease 만료(전송 여부 불명 — "호출 안 함"으로 가정하지 않는다, A06) → OUTCOME_UNKNOWN(DISPATCH_LEASE_EXPIRED). */
    fun markLeaseExpired(authorizationId: AuthorizationId, attemptId: AttemptId, causation: EventCausation): ResolutionOutcome =
        apply(authorizationId, causation) { c ->
            // 검토 #3 m-1: 선점 시점 사본이 아니라 이 TX에서 잠근 일정(잠금 순서 authorization → … → 일정)의 lease로 판정한다 — lease가 없거나 아직이면 NoChange.
            val lease = schedules.lock(attemptId)?.dispatchLeaseUntil
            if (lease == null || c.at.isBefore(lease)) ResolutionOutcome.NoChange
            else AuthorizationResolution.markOutcomeUnknown(c.payment, c.authorization, attemptId, UnknownCause.DISPATCH_LEASE_EXPIRED)
        }

    /** 회복: 전송되지 않은 PREPARED Attempt를 fencing으로 포기(같은 행 version 조건부 갱신 — TXd와 먼저 커밋한 쪽만 이긴다, C23). */
    fun fence(authorizationId: AuthorizationId, attemptId: AttemptId, causation: EventCausation): ResolutionOutcome =
        apply(authorizationId, causation) { c ->
            val attempt = c.authorization.attempt(attemptId)
            if (attempt.state != AttemptState.PREPARED) ResolutionOutcome.NoChange
            else AuthorizationResolution.abandonBeforeDispatch(c.payment, c.authorization, c.usage, attemptId, AbandonCause.FENCED_BY_RECOVERY, c.at)
        }

    private fun observe(c: Context, observation: InstitutionObservation): ResolutionOutcome =
        AuthorizationResolution.applyObservation(
            c.control, c.payment, c.authorization, c.usage, observation, ids.transactionId(c.at), history.policyOf(c.authorization), c.at,
        )

    private class Context(
        val control: OrderPaymentControl, val payment: Payment, val authorization: PaymentAuthorization, val session: AuthenticationSession?, val usage: DailyLimitUsage,
        val at: Instant,
    )

    private fun apply(
        authorizationId: AuthorizationId,
        causation: EventCausation,
        inquired: AttemptId? = null,
        decide: (Context) -> ResolutionOutcome,
    ): ResolutionOutcome {
        val row = views.find(authorizationId) ?: error("the authorization is not stored")
        return transactions.execute(::isRetryable) {
            val at = clock.now().truncatedTo(ChronoUnit.MILLIS)
            val control = controls.lock(row.orderKey) ?: error("a stored authorization has its order control")
            val payment = payments.lock(row.paymentId) ?: error("a stored authorization has its payment")
            val authorization = authorizations.lock(authorizationId) ?: error("the authorization is not stored")
            // §4.7: payment_authorization → (attempt) → 인증 세션 → 사용량. 세션은 승인 확정 이벤트의 발급사·매입사 코드를 읽는다(변경 없음).
            val session = sessions.lock(authorization.value.authenticationSessionId)
            val usage = usages.lockOrCreate(authorization.value.limitKey, at)
            val holdsBefore = control.value.incidentHolds.size
            val c = Context(control.value, payment.value, authorization.value, session?.value, usage.value, at)
            val outcome = decide(c)
            controls.update(c.control, control.version, at)
            payments.update(c.payment, payment.version)
            val version = authorizations.update(c.authorization, authorization.version)
            usages.update(c.usage, usage.version, at)
            publish(c, outcome, holdsBefore, version, causation)
            reschedule(c, at, inquired)
            outcome
        }
    }

    private fun publish(c: Context, outcome: ResolutionOutcome, holdsBefore: Int, version: Long, causation: EventCausation) {
        val a = c.authorization
        when (outcome) {
            is ResolutionOutcome.Authorized -> {
                val evidence = checkNotNull(c.session?.verified) { "a bound session keeps its evidence" }
                val site = checkNotNull(payments.findCreationRecord(a.paymentId)).attributes.merchantSiteId.value
                outbox.append(AuthorizationEvents.authorized(a, outcome.transaction, site, evidence, version, eventIds.newEventId(), causation))
            }
            is ResolutionOutcome.Failed -> outbox.append(AuthorizationEvents.failed(a, version, c.at, eventIds.newEventId(), causation))
            else -> {}
        }
        // F-1·AU-17: 이번 TX에서 append된 hold마다 사건 이벤트 하나(같은 활성 hold에 흡수된 재보고는 이벤트 없음). Authorized 뒤에 둔다(§4.10 순서).
        c.control.incidentHolds.drop(holdsBefore).forEach { hold ->
            val opening = hold.sourceObservation?.let { ref -> a.observationRefs.firstOrNull { it.second == ref }?.first }
            outbox.append(AuthorizationEvents.incident(a, hold, opening, version, c.at, eventIds.newEventId(), causation))
        }
    }

    private fun reschedule(c: Context, at: Instant, inquired: AttemptId?) {
        c.authorization.attempts.forEach { attempt ->
            val schedule = schedules.lock(attempt.id) ?: return@forEach
            if (schedule.closedAt != null) return@forEach
            val counted = if (attempt.id == inquired) schedule.copy(inquiryCount = schedule.inquiryCount + 1) else schedule
            val due = at.plus(if (counted.escalatedAt != null) timings.slowInquiryInterval else timings.inquiryInterval)
            val next = when (attempt.state) {
                AttemptState.RESOLVED, AttemptState.ABANDONED_BEFORE_DISPATCH -> counted.copy(closedAt = at)
                // 결과 불명확: 이번 조회 뒤면 다음 조회는 간격 뒤, 그 밖(응답·lease 만료 표시)은 다음 조회를 간격 뒤로 당긴다(이미 더 이른 시각이면 그대로).
                AttemptState.OUTCOME_UNKNOWN -> if (attempt.id == inquired || counted.nextCheckAt.isAfter(due)) counted.copy(nextCheckAt = due) else counted
                else -> counted
            }
            if (next != schedule) schedules.update(next, at)
        }
    }

    private fun isRetryable(e: RuntimeException): Boolean = e is PersistenceFailure.Deadlock || e is PersistenceFailure.OptimisticConflict

    private companion object {
        const val MAX_ATTEMPTS = 3
        val DEFAULT_CAUSATION = EventCausation("authorization-result", "authorization-result")
    }
}

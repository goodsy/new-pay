package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.required.AdmissionResult
import com.paycore.payment.payment.application.required.AuthenticationSessionRepository
import com.paycore.payment.payment.application.required.AuthorizationAdmissionPort
import com.paycore.payment.payment.application.required.AuthorizationCall
import com.paycore.payment.payment.application.required.AuthorizationEvidenceRef
import com.paycore.payment.payment.application.required.AuthorizationPort
import com.paycore.payment.payment.application.required.AuthorizationRecoveryScheduleStore
import com.paycore.payment.payment.application.required.AuthorizationTimings
import com.paycore.payment.payment.application.required.AuthorizationViewReader
import com.paycore.payment.payment.application.required.DailyLimitUsageRepository
import com.paycore.payment.payment.application.required.EntitlementHeadLockPort
import com.paycore.payment.payment.application.required.InstitutionRouteResolver
import com.paycore.payment.payment.application.required.IntegrationEventIdGenerator
import com.paycore.payment.payment.application.required.OrderPaymentControlRepository
import com.paycore.payment.payment.application.required.OutboxStore
import com.paycore.payment.payment.application.required.PaymentAuthorizationRepository
import com.paycore.payment.payment.application.required.PaymentClock
import com.paycore.payment.payment.application.required.PaymentRepository
import com.paycore.payment.payment.application.required.PersistenceFailure
import com.paycore.payment.payment.domain.AbandonCause
import com.paycore.payment.payment.domain.AttemptState
import com.paycore.payment.payment.domain.AuthorizationId
import com.paycore.payment.payment.domain.AuthorizationResolution
import com.paycore.payment.payment.domain.AuthorizationStatus
import com.paycore.payment.payment.domain.OrderKey
import com.paycore.payment.payment.domain.PaymentId
import com.paycore.payment.payment.domain.PaymentMethod
import com.paycore.payment.payment.domain.ResolutionOutcome
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import java.time.temporal.ChronoUnit

/**
 * 전송 게이트 TXd + TX 밖 기관 호출 + TX2(AUTH-001 §4.4·§4.5). TXd는 head(S) → control → payment → payment_authorization(→ attempt) → (인증 세션 읽기) → (게이트 거절 때만 사용량)
 * → 회복 일정 순으로 잠그고 A0~A2만 지금 시각으로 재판정한다(한도·정책 Assignment는 TX1 고정 — D-AUTH-007; 인증 증거 TTL은 보지 않는다 — D-AUTH1-004).
 * - 통과: Attempt PREPARED → DISPATCHING + 이번 전송의 Route 프로파일 버전 고정(D-AUTH1-007)(version 조건부 — 회복 fencing과 먼저 커밋한 쪽만 이긴다, C23) + 회복 일정에 dispatch lease. **이 커밋이 확인될 때만** connect한다
 *   (AU-28: commit 불명확이면 보내지 않는다 — 회복이 PREPARED/DISPATCHING 어느 쪽이든 정리).
 * - 일시 Unavailable은 `preparedDeadline`까지 재시도, 넘으면 GATE_REFUSED(AU-33). Refused·NotFound는 즉시 GATE_REFUSED → FAILED(PRE_TRANSMISSION), 한도 해제, PaymentAuthorizationFailed.
 * - 호출 직전 자기 deadline(`lease − safetyMargin`)을 다시 보고, 지났으면 connect하지 않는다(상태 불변 — 회복이 lease 만료로 처리, AU-07). 호출 중에는 TX·잠금이 없다(AC 2).
 * - 결과는 [AuthorizationResultService.applyCall](TX2). TX2가 실패하면 결과는 회복이 원 Attempt 조회로 반영한다.
 */
@Service
class AuthorizationDispatchService(
    transactionManager: PlatformTransactionManager,
    private val views: AuthorizationViewReader,
    private val headLock: EntitlementHeadLockPort,
    private val controls: OrderPaymentControlRepository,
    private val payments: PaymentRepository,
    private val authorizations: PaymentAuthorizationRepository,
    private val sessions: AuthenticationSessionRepository,
    private val usages: DailyLimitUsageRepository,
    private val admission: AuthorizationAdmissionPort,
    private val schedules: AuthorizationRecoveryScheduleStore,
    private val outbox: OutboxStore,
    private val eventIds: IntegrationEventIdGenerator,
    private val institution: AuthorizationPort,
    private val routes: InstitutionRouteResolver,
    private val results: AuthorizationResultService,
    private val timings: AuthorizationTimings,
    private val clock: PaymentClock,
) {
    private val log = LoggerFactory.getLogger(AuthorizationDispatchService::class.java)
    private val transactions = LocalTransactions(transactionManager, MAX_ATTEMPTS)

    /** 게이트 판정 결과. */
    private sealed interface Gate {
        data class Dispatched(val call: AuthorizationCall) : Gate
        data object Refused : Gate
        data object Retry : Gate
        data object NotApplicable : Gate
    }

    /** @return 전송·반영까지 이 호출 안에서 끝났으면 true(결과는 현재 상태 조회로 확인), 회복에 맡겼으면 false. */
    fun dispatch(authorizationId: AuthorizationId, causation: EventCausation): Boolean {
        val row = views.find(authorizationId) ?: error("the authorization is not stored")
        // AU-33 재시도 상한: 업무 시각(preparedAt + preparedDeadline)과 함께 경과 단조 시간으로도 끊는다(시계가 멈춘 경우에도 무한 대기하지 않게).
        val retryUntil = System.nanoTime() + timings.preparedDeadline.toNanos()
        while (true) {
            val giveUp = System.nanoTime() >= retryUntil
            val gate = try {
                transactions.execute(::isRetryable) { gate(authorizationId, row.orderKey, row.paymentId, giveUp, causation) }
            } catch (e: CommitOutcomeUnknownException) {
                log.warn("operation=AuthorizationDispatch result=COMMIT_UNKNOWN authorizationId={} — not sent, left to recovery", authorizationId.value)
                return false
            } catch (e: RuntimeException) {
                log.warn("operation=AuthorizationDispatch result=GATE_FAILED authorizationId={} failure={}", authorizationId.value, e::class.simpleName)
                return false
            }
            when (gate) {
                is Gate.Dispatched -> return send(authorizationId, gate.call, causation)
                Gate.Retry -> Thread.sleep(timings.gateRetryInterval)
                Gate.Refused, Gate.NotApplicable -> return true
            }
        }
    }

    private fun send(authorizationId: AuthorizationId, call: AuthorizationCall, causation: EventCausation): Boolean {
        if (!clock.now().isBefore(call.connectDeadline)) return false // AU-07: deadline 경과 — connect하지 않는다(회복이 lease 만료로 처리)
        // 검토 #3 n-2: 전송자 결함(Port 예외)은 202 — DISPATCHING이 커밋됐으므로 회복이 lease 만료로 UNKNOWN 처리한다(보냈는지 모른다고 본다).
        // 쓰기 시작 뒤 실패는 Adapter가 OutcomeUnknown으로 돌려준다(검토 #2 n-5).
        val result = try {
            institution.authorize(call)
        } catch (e: RuntimeException) {
            log.warn("operation=AuthorizationDispatch result=SENDER_FAILED authorizationId={} failure={} — left to recovery", authorizationId.value, e::class.simpleName)
            return false
        }
        return try {
            results.applyCall(authorizationId, call.attemptId, result, causation)
            true
        } catch (e: RuntimeException) {
            log.warn("operation=AuthorizationResult result=TX2_FAILED authorizationId={} failure={} — left to recovery", authorizationId.value, e::class.simpleName)
            false
        }
    }

    private fun gate(authorizationId: AuthorizationId, orderKey: OrderKey, paymentId: PaymentId, giveUp: Boolean, causation: EventCausation): Gate {
        headLock.lockForShare(orderKey.merchantAccountId)
        // 검토 #3 n-5: 판정 시각은 head(S)를 얻은 뒤 — 차단 Snapshot(head X)을 기다렸다면 그 커밋 뒤 시각으로 판정한다(C26).
        val now = clock.now().truncatedTo(ChronoUnit.MILLIS)
        val control = controls.lock(orderKey) ?: error("a stored authorization has its order control")
        val payment = payments.lock(paymentId) ?: error("a stored authorization has its payment")
        val authorization = authorizations.lock(authorizationId) ?: error("the authorization is not stored")
        val a = authorization.value
        val attempt = a.attempts.lastOrNull { it.state == AttemptState.PREPARED }
        if (a.status != AuthorizationStatus.OPEN || attempt == null) return Gate.NotApplicable
        // 검토 #3 m-1: preparedAt + preparedDeadline 이후에는 통과시키지 않는다 — 회복 fencing에 맡긴다(fencing과 TXd가 같은 기한을 본다).
        if (!now.isBefore(attempt.preparedAt.plus(timings.preparedDeadline))) return Gate.NotApplicable
        // 검토 #3 n-1: 회복 일정 행이 없으면 결함 — rollback, 보내지 않는다.
        val schedule = schedules.lock(attempt.id) ?: error("a prepared attempt has its recovery schedule")
        val session = sessions.lock(a.authenticationSessionId)?.value ?: error("an authorization has its authentication session")
        val evidence = checkNotNull(session.verified) { "a bound session keeps its evidence" }
        val creation = checkNotNull(payments.findCreationRecord(a.paymentId))
        val verdict = admission.evaluate(orderKey.merchantAccountId, creation.attributes.merchantSiteId, a.money.currency, PaymentMethod.CARD, evidence.acquirerCode, now)
        val refuse = when (verdict) {
            is AdmissionResult.Admitted -> false
            is AdmissionResult.Unavailable -> {
                if (!giveUp && now.isBefore(attempt.preparedAt.plus(timings.preparedDeadline))) return Gate.Retry
                true
            }
            is AdmissionResult.Refused, AdmissionResult.NotFoundOrForbidden -> true
        }
        // D-AUTH1-007: 이번 전송의 Route 프로파일 버전을 Attempt에 고정한다. TX1 뒤 등록부에서 Route가 사라졌으면(재기동 설정 변경) 보내지 않는다 — 전송 전 거절(fail-closed).
        val profile = routes.resolve(a.admission.routeProfile)
        if (refuse || profile == null) {
            // 최종 검토 m-1: Route 소실은 설정 결함 신호 — 진단 로그(내부 ID만, 기관 요청 ID·계정·키 없음).
            if (profile == null) log.warn("operation=AuthorizationDispatch result=GATE_REFUSED reason=ROUTE_NOT_REGISTERED authorizationId={}", authorizationId.value)
            val usage = usages.lockOrCreate(a.limitKey, now)
            val outcome = AuthorizationResolution.abandonBeforeDispatch(payment.value, a, usage.value, attempt.id, AbandonCause.GATE_REFUSED, now)
            payments.update(payment.value, payment.version)
            val version = authorizations.update(a, authorization.version)
            usages.update(usage.value, usage.version, now)
            if (outcome is ResolutionOutcome.Failed) outbox.append(AuthorizationEvents.failed(a, version, now, eventIds.newEventId(), causation))
            schedules.update(schedule.copy(closedAt = now), now)
            return Gate.Refused
        }
        a.beginDispatch(attempt.id, profile.profileVersion)
        authorizations.update(a, authorization.version)
        val leaseUntil = now.plus(timings.dispatchLease)
        schedules.update(schedule.copy(dispatchLeaseUntil = leaseUntil, nextCheckAt = leaseUntil), now)
        return Gate.Dispatched(
            AuthorizationCall(
                attemptId = attempt.id,
                providerRequest = attempt.providerRequest,
                routeRef = a.admission.routeProfile,
                merchantRef = orderKey.merchantAccountId.value,
                money = a.money,
                plan = a.plan,
                evidence = AuthorizationEvidenceRef(evidence.evidenceRef, evidence.providerCode, evidence.issuerCode, evidence.acquirerCode),
                connectDeadline = leaseUntil.minus(timings.safetyMargin),
            ),
        )
    }

    private fun isRetryable(e: RuntimeException): Boolean = e is PersistenceFailure.Deadlock || e is PersistenceFailure.OptimisticConflict

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}

package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.required.CancellationCall
import com.paycore.payment.payment.application.required.CancellationCallResult
import com.paycore.payment.payment.application.required.CancellationPort
import com.paycore.payment.payment.application.required.CancellationRecoveryScheduleStore
import com.paycore.payment.payment.application.required.CancellationTimings
import com.paycore.payment.payment.application.required.CancellationViewReader
import com.paycore.payment.payment.application.required.InstitutionRouteResolver
import com.paycore.payment.payment.application.required.IntegrationEventIdGenerator
import com.paycore.payment.payment.application.required.OrderPaymentControlRepository
import com.paycore.payment.payment.application.required.OriginalAuthorizationRef
import com.paycore.payment.payment.application.required.OutboxStore
import com.paycore.payment.payment.application.required.PaymentAuthorizationRepository
import com.paycore.payment.payment.application.required.PaymentCancellationRepository
import com.paycore.payment.payment.application.required.PaymentClock
import com.paycore.payment.payment.application.required.PaymentRepository
import com.paycore.payment.payment.application.required.PersistenceFailure
import com.paycore.payment.payment.application.required.ReturnBalanceRepository
import com.paycore.payment.payment.domain.AbandonCause
import com.paycore.payment.payment.domain.AttemptState
import com.paycore.payment.payment.domain.CancelCapabilityResolution
import com.paycore.payment.payment.domain.CancelResolutionOutcome
import com.paycore.payment.payment.domain.CancellationId
import com.paycore.payment.payment.domain.CancellationResolution
import com.paycore.payment.payment.domain.CancellationStatus
import com.paycore.payment.payment.domain.OrderKey
import com.paycore.payment.payment.domain.PaymentId
import com.paycore.payment.payment.domain.UnknownCause
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import java.time.temporal.ChronoUnit

/**
 * 취소 전송 게이트 TXd + TX 밖 기관 취소 + TX2(CANCEL-001 §4.4·§4.5). TXd 잠금 순서 §4.7: control → payment → payment_authorization → return_balance → payment_cancellation(→ attempt)
 * → 회복 일정. **신규 승인 권리·Credential은 재판정하지 않는다**(K-3). 보는 것: preparedDeadline 전인가(지났으면 보내지 않음 — 회복 fencing), 원 경로 Route가 등록돼 있고 환경·기관이
 * 원 승인 Attempt와 같으며(rev 0.3 m-4) 취소 Capability(sim-van-2)가 있는가(없으면 GATE_REFUSED → FAILED(PRE_TRANSMISSION)·예약 RELEASED·PaymentCancelFailed).
 * - 통과: Attempt PREPARED → DISPATCHING + Route 프로파일 버전 고정(D-CANCEL1-004) + 일정 dispatch lease. **이 커밋이 확인될 때만** connect한다(CN-08 — commit 불명확이면 보내지 않는다).
 * - 호출 직전 자기 deadline(`lease − safetyMargin`)을 다시 보고, 지났으면 connect하지 않는다(회복이 lease 만료로 처리). 호출 중에는 TX·잠금이 없다(K-13).
 * - Port가 예외를 던지면(전송자 결함 — 보냈는지 모른다) OutcomeUnknown(CONNECTION_LOST)으로 반영한다(검토 #2 n-4). 결과 반영은 [CancellationResultService.applyCall](TX2) — 실패하면
 *   결과는 회복이 원 취소 요청 ID 조회로 반영한다.
 */
@Service
class CancellationDispatchService(
    transactionManager: PlatformTransactionManager,
    private val views: CancellationViewReader,
    private val controls: OrderPaymentControlRepository,
    private val payments: PaymentRepository,
    private val authorizations: PaymentAuthorizationRepository,
    private val balances: ReturnBalanceRepository,
    private val cancellations: PaymentCancellationRepository,
    private val schedules: CancellationRecoveryScheduleStore,
    private val outbox: OutboxStore,
    private val eventIds: IntegrationEventIdGenerator,
    private val institution: CancellationPort,
    private val routes: InstitutionRouteResolver,
    private val results: CancellationResultService,
    private val timings: CancellationTimings,
    private val clock: PaymentClock,
) {
    private val log = LoggerFactory.getLogger(CancellationDispatchService::class.java)
    private val transactions = LocalTransactions(transactionManager, MAX_ATTEMPTS)

    private sealed interface Gate {
        data class Dispatched(val call: CancellationCall) : Gate
        data object Refused : Gate
        data object NotApplicable : Gate
    }

    /** @return 전송·반영까지 이 호출 안에서 끝났으면 true(결과는 현재 상태 조회로 확인), 회복에 맡겼으면 false. */
    fun dispatch(cancellationId: CancellationId, causation: EventCausation): Boolean {
        val row = views.find(cancellationId) ?: error("the cancellation is not stored")
        val gate = try {
            transactions.execute(::isRetryable) { gate(cancellationId, row.orderKey, row.paymentId, causation) }
        } catch (e: CommitOutcomeUnknownException) {
            log.warn("operation=CancellationDispatch result=COMMIT_UNKNOWN cancellationId={} — not sent, left to recovery", cancellationId.value)
            return false
        } catch (e: RuntimeException) {
            log.warn("operation=CancellationDispatch result=GATE_FAILED cancellationId={} failure={}", cancellationId.value, e::class.simpleName)
            return false
        }
        return when (gate) {
            is Gate.Dispatched -> send(cancellationId, gate.call, causation)
            Gate.Refused, Gate.NotApplicable -> true
        }
    }

    private fun send(cancellationId: CancellationId, call: CancellationCall, causation: EventCausation): Boolean {
        if (!clock.now().isBefore(call.connectDeadline)) return false // deadline 경과 — connect하지 않는다(회복이 lease 만료로 처리)
        val result = try {
            institution.cancel(call)
        } catch (e: RuntimeException) {
            // 검토 #2 n-4: DISPATCHING이 커밋된 뒤의 전송자 결함 — 보냈는지 모른다. OutcomeUnknown으로 반영하고 회복이 원 취소 요청 ID 조회로 확정한다.
            log.warn("operation=CancellationDispatch result=SENDER_FAILED cancellationId={} failure={} — outcome unknown", cancellationId.value, e::class.simpleName)
            CancellationCallResult.OutcomeUnknown(UnknownCause.CONNECTION_LOST)
        }
        return try {
            results.applyCall(cancellationId, call.attemptId, result, causation)
            true
        } catch (e: RuntimeException) {
            log.warn("operation=CancellationResult result=TX2_FAILED cancellationId={} failure={} — left to recovery", cancellationId.value, e::class.simpleName)
            false
        }
    }

    private fun gate(cancellationId: CancellationId, orderKey: OrderKey, paymentId: PaymentId, causation: EventCausation): Gate {
        val now = clock.now().truncatedTo(ChronoUnit.MILLIS)
        controls.lock(orderKey) ?: error("a stored cancellation has its order control")
        payments.lock(paymentId) ?: error("a stored cancellation has its payment")
        val authorization = (authorizations.lockByPaymentId(paymentId) ?: error("a stored cancellation has its authorization")).value
        val balance = balances.lockByPaymentId(paymentId) ?: error("a stored cancellation has its return balance")
        val cancellation = cancellations.lock(cancellationId) ?: error("the cancellation is not stored")
        val x = cancellation.value
        val attempt = x.attempts.lastOrNull { it.state == AttemptState.PREPARED }
        if (x.status != CancellationStatus.OPEN || attempt == null) return Gate.NotApplicable
        // preparedAt + preparedDeadline 이후에는 통과시키지 않는다 — 회복 fencing에 맡긴다(fencing과 TXd가 같은 기한을 본다, K7).
        if (!now.isBefore(attempt.preparedAt.plus(timings.preparedDeadline))) return Gate.NotApplicable
        val schedule = schedules.lock(attempt.id) ?: error("a prepared cancel attempt has its recovery schedule")
        val original = authorization.attempts.first { it.state == AttemptState.RESOLVED }.providerRequest
        val profile = routes.resolve(authorization.admission.routeProfile)
        val refusal = when {
            profile == null -> "ROUTE_NOT_REGISTERED"
            profile.environment != orderKey.environment || !profile.servesOriginal(original) || !profile.servesOriginal(attempt.providerRequest) -> "ROUTE_NOT_ORIGINAL"
            profile.cancelCapability() is CancelCapabilityResolution.Unavailable -> "CANCEL_CAPABILITY_UNAVAILABLE"
            else -> null
        }
        if (refusal != null) {
            log.warn("operation=CancellationDispatch result=GATE_REFUSED reason={} cancellationId={}", refusal, cancellationId.value)
            val outcome = CancellationResolution.abandonBeforeDispatch(x, balance.value, attempt.id, AbandonCause.GATE_REFUSED)
            balances.update(balance.value, balance.version, now)
            val version = cancellations.update(x, cancellation.version)
            if (outcome is CancelResolutionOutcome.Failed) outbox.append(CancellationEvents.failed(x, version, now, eventIds.newEventId(), causation))
            schedules.update(schedule.copy(closedAt = now), now)
            return Gate.Refused
        }
        x.beginDispatch(attempt.id, checkNotNull(profile).profileVersion)
        cancellations.update(x, cancellation.version)
        val leaseUntil = now.plus(timings.dispatchLease)
        schedules.update(schedule.copy(dispatchLeaseUntil = leaseUntil, nextCheckAt = leaseUntil), now)
        val originalTransaction = checkNotNull(authorization.transaction) { "a cancellation targets a confirmed authorization transaction" }
        return Gate.Dispatched(
            CancellationCall(
                attemptId = attempt.id,
                providerRequest = attempt.providerRequest,
                routeRef = authorization.admission.routeProfile,
                merchantRef = orderKey.merchantAccountId.value,
                original = OriginalAuthorizationRef(
                    providerRequest = original,
                    providerTransactionId = originalTransaction.terms.providerTransactionId,
                    approvalNumber = originalTransaction.terms.approvalNumber,
                    transactionDate = originalTransaction.terms.institutionTransactionDate,
                ),
                money = x.money,
                scope = x.scope,
                connectDeadline = leaseUntil.minus(timings.safetyMargin),
            ),
        )
    }

    private fun isRetryable(e: RuntimeException): Boolean = e is PersistenceFailure.Deadlock || e is PersistenceFailure.OptimisticConflict

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}

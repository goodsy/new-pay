package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.provided.FinancialFactRelayReport
import com.paycore.payment.payment.application.provided.RelayFinancialFactsCommand
import com.paycore.payment.payment.application.provided.RelayFinancialFactsUseCase
import com.paycore.payment.payment.application.required.FinancialFactAck
import com.paycore.payment.payment.application.required.FinancialFactDispatchOutcome
import com.paycore.payment.payment.application.required.FinancialFactDispatcher
import com.paycore.payment.payment.application.required.FinancialFactRelayMark
import com.paycore.payment.payment.application.required.FinancialFactRelayStore
import com.paycore.payment.payment.application.required.PaymentClock
import com.paycore.payment.payment.application.required.PersistenceFailure
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

/**
 * PAYMENT → FINANCIAL 결제 사실 Relay(FIN-FACT-RELAY-001 §4.4 — MASTER→PAYMENT 권리 Relay와 같은 모양, D-ACQ-001). 한 회차:
 * ```
 * TXa(discover): 미전달(publish_status = PENDING) FINANCIAL_FACT Outbox 행 중 전달 행 없는 것 → PENDING 전달 행(Outbox는 잠그지 않는다)
 * TXb(claim, FOR UPDATE SKIP LOCKED): PENDING·next_attempt_at ≤ now·claim 없음/만료 → Outbox id 순 → claim 표시 → COMMIT
 * 행마다: 남은 lease(claim_until − now) > 전송 예산 + 여유(1초)일 때만 보낸다. 모자라면 남은 행 전부 claim을 풀고(TXr) 이번 회차를 끝낸다(검토 M-1)
 *   (TX 밖) Dispatcher(서명 HTTP — 전송 예산 안에 반드시 반환) — DB 잠금·TX 없음
 *   TXc: ACK → DELIVERED + ACK 기록 + Outbox PUBLISHED(D-FFR-001) / 재시도 가능 → attempts+1, next_attempt_at = now + backoff, claim 해제 / 내용 거절 → QUARANTINED
 * ```
 * 그래서 한 인스턴스의 전송은 항상 자기 lease 안에서 끝나고, lease가 끝난 행을 다른 인스턴스가 다시 집어도 겹쳐 보내지 않는다(인스턴스 간 시계 차이는 여유 1초 안 가정 —
 * 명세 §9 L-9). 기동 검사(claim lease > 전송 예산 + 1초)는 최소 조건일 뿐 진행 보장이 아니다 — discover·claim TX 시간이 여유를 잠식하면 한 행도 못 보내고
 * 미루기만 할 수 있다(재검토 m-A). 그 회차는 WARN(result=STARVED)으로 드러내고, 운영값은 lease ≫ 예산으로 둔다(Demo 30s vs 6s, 명세 §9 L-10).
 * 재시도 상한 없음(금융 사실을 포기하지 않는다 — 대기 연령 gauge로 경보). Relay crash 뒤에는 claim 만료 후 다른 인스턴스가 같은 eventId·같은 바이트로 다시 보내고
 * FINANCIAL은 DUPLICATE로 응답한다(at-least-once + 멱등 Inbox). 응답 유실(FINANCIAL 커밋 뒤 연결 끊김)도 같은 경로로 수렴한다.
 * 한 행의 예상 밖 예외는 그 행만 실패로 세고 나머지를 계속 처리한다(그 행은 claim 만료 뒤 다시 집힌다).
 */
@Service
class FinancialFactRelayService(
    transactionManager: PlatformTransactionManager,
    private val store: FinancialFactRelayStore,
    private val dispatcher: FinancialFactDispatcher,
    private val clock: PaymentClock,
) : RelayFinancialFactsUseCase {

    private val transactions = LocalTransactions(transactionManager, MAX_ATTEMPTS)
    private val log = LoggerFactory.getLogger(FinancialFactRelayService::class.java)

    /** 이 프로세스의 Relay 소유자 이름(claim_owner). 인스턴스마다 다르다. */
    val owner: String = "fact-relay-" + UUID.randomUUID()

    override fun relayOnce(command: RelayFinancialFactsCommand): FinancialFactRelayReport {
        val start = now()
        val discovered = transactions.execute(::isRetryable) { store.discover(start, command.batchSize) }
        val claimed = transactions.execute(::isRetryable) { store.claim(owner, start, start.plus(command.claimLease), command.batchSize) }
        var delivered = 0
        var retried = 0
        var quarantined = 0
        var lost = 0
        var failed = 0
        var deferred = 0
        val claimUntil = start.plus(command.claimLease)
        for ((index, envelope) in claimed.withIndex()) {
            if (now().plus(dispatcher.sendBudget).plus(LEASE_MARGIN) > claimUntil) {
                val rest = claimed.subList(index, claimed.size)
                deferred = rest.size
                runCatching { transactions.execute(::isRetryable) { store.releaseClaims(rest.map { it.outboxId }, owner, now()) } }
                    .onFailure { log.warn("operation=FinancialFactRelay result=RELEASE_FAILED rows={} failure={}", rest.size, it::class.simpleName) }
                if (index == 0) {
                    log.warn("operation=FinancialFactRelay result=STARVED rows={} (no row sent this round: claim lease too short for discover/claim + send budget + margin)", rest.size)
                } else {
                    log.info("operation=FinancialFactRelay result=DEFERRED rows={} (claim lease shorter than send budget + margin)", rest.size)
                }
                break
            }
            val outcome: FinancialFactDispatchOutcome
            val mark: FinancialFactRelayMark
            try {
                outcome = dispatcher.dispatch(envelope)
                val at = now()
                mark = transactions.execute(::isRetryable) {
                    when (outcome) {
                        is FinancialFactDispatchOutcome.Acknowledged ->
                            store.markDelivered(envelope.outboxId, owner, FinancialFactAck(outcome.consumer, outcome.outcome, outcome.originalOutcome, at))
                        is FinancialFactDispatchOutcome.Retryable ->
                            store.markRetry(envelope.outboxId, owner, at.plus(backoff(envelope.attempts, command)), outcome.code, at)
                        is FinancialFactDispatchOutcome.Rejected -> store.markQuarantined(envelope.outboxId, owner, outcome.code, at)
                    }
                }
            } catch (e: RuntimeException) {
                failed++
                log.error("operation=FinancialFactRelay outboxId={} eventId={} result=FAILED failure={}", envelope.outboxId, envelope.eventId, e::class.simpleName)
                continue
            }
            when {
                mark == FinancialFactRelayMark.CLAIM_LOST -> lost++
                outcome is FinancialFactDispatchOutcome.Acknowledged -> delivered++
                outcome is FinancialFactDispatchOutcome.Retryable -> retried++
                else -> quarantined++
            }
            logOutcome(envelope.outboxId, envelope.eventId, envelope.attempts, outcome, mark)
        }
        return FinancialFactRelayReport(discovered, claimed.size, delivered, retried, quarantined, lost, failed, deferred)
    }

    private fun logOutcome(outboxId: Long, eventId: String, attempts: Int, outcome: FinancialFactDispatchOutcome, mark: FinancialFactRelayMark) {
        when (outcome) {
            is FinancialFactDispatchOutcome.Acknowledged -> {
                val first = outcome.originalOutcome ?: outcome.outcome
                if (first == UNSUPPORTED) {
                    log.error("operation=FinancialFactRelay outboxId={} eventId={} result=DELIVERED_UNSUPPORTED mark={}", outboxId, eventId, mark)
                } else {
                    log.info("operation=FinancialFactRelay outboxId={} eventId={} result=DELIVERED outcome={} mark={}", outboxId, eventId, outcome.outcome, mark)
                }
            }
            is FinancialFactDispatchOutcome.Retryable -> log.warn(
                "operation=FinancialFactRelay outboxId={} eventId={} result=RETRY attempt={} failure={} mark={}", outboxId, eventId, attempts + 1, outcome.code, mark,
            )
            is FinancialFactDispatchOutcome.Rejected -> log.error(
                "operation=FinancialFactRelay outboxId={} eventId={} result=QUARANTINED failure={} mark={}", outboxId, eventId, outcome.code, mark,
            )
        }
    }

    /** 지수 backoff(attempts 0 → initial, 이후 ×2, 상한 max) + 선택 jitter(설정 비율 이내). 업무 정책이 아니라 전달 기술 설정이다. */
    internal fun backoff(attempts: Int, command: RelayFinancialFactsCommand): Duration {
        val initial = command.backoffInitial.toMillis()
        val max = command.backoffMax.toMillis()
        val shift = attempts.coerceIn(0, MAX_SHIFT)
        val base = (initial shl shift).coerceAtMost(max).coerceAtLeast(initial)
        val ratio = command.backoffJitterRatio
        val jitter = if (ratio > 0.0) (base * ratio * ThreadLocalRandom.current().nextDouble()).toLong() else 0L
        return Duration.ofMillis((base + jitter).coerceAtMost(max.coerceAtLeast(base)))
    }

    private fun now(): Instant = clock.now().truncatedTo(ChronoUnit.MILLIS)

    private fun isRetryable(e: RuntimeException): Boolean =
        e is PersistenceFailure.Deadlock || e is PersistenceFailure.OptimisticConflict || e is PersistenceFailure.LockTimeout

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val MAX_SHIFT = 20
        const val UNSUPPORTED = "UNSUPPORTED"

        /** lease 판단 여유(인스턴스 간 시계 차이·mark TX 시간). 기동 검사 FinancialFactRelayTimingConsistency의 여유와 같다. */
        val LEASE_MARGIN: Duration = Duration.ofSeconds(1)
    }
}

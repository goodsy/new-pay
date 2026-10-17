package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.provided.ExpireElapsedRequestedPaymentsCommand
import com.paycore.payment.payment.application.provided.ExpireElapsedRequestedPaymentsUseCase
import com.paycore.payment.payment.application.provided.ExpiryRunResult
import com.paycore.payment.payment.application.required.ElapsedRequestedCandidate
import com.paycore.payment.payment.application.required.ElapsedRequestedPaymentReader
import com.paycore.payment.payment.application.required.OrderPaymentControlRepository
import com.paycore.payment.payment.application.required.PaymentClock
import com.paycore.payment.payment.application.required.PaymentRepository
import com.paycore.payment.payment.application.required.PersistenceFailure
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import java.time.temporal.ChronoUnit

/**
 * 결제창 만료 주기 작업(AUTHN-001 §7.5.4, D-AUTHN-005·008, AN-28·AN-30). 한 회차:
 * 1. 후보 쪽(page)을 jOOQ로 읽는다(비권위, 키셋 — 후보가 없을 때까지).
 * 2. 후보마다 로컬 TX 하나: OrderKey로 control(X) → payment(X) → [RequestedPaymentClosing](세션 → 결제창 세션 → 토큰 닫기, Payment EXPIRED, PaymentExpired 1건).
 *    잠금 아래 다시 보면 이미 EXPIRED·승인 시작·시각 전일 수 있다 → 변경 없음 커밋(NoChange). 다중 인스턴스는 control·payment X에서 직렬화된다(분산 lock 불필요).
 * 3. 후보 하나의 실패(교착 재시도 소진·잠금 timeout·장애·결함)는 그 건만 rollback하고 로그·결과에 세며 다음 후보를 계속한다(다음 회차에 다시 후보 — 멱등).
 * 회차 시각은 시작 때 한 번 정한다(같은 회차 안 판정 기준 고정). 인과 값: correlationId = 회차 ID, causationId = `checkout-expiry-job:<회차 ID>`.
 */
@Service
class RequestedPaymentExpiryService(
    transactionManager: PlatformTransactionManager,
    private val clock: PaymentClock,
    private val reader: ElapsedRequestedPaymentReader,
    private val controls: OrderPaymentControlRepository,
    private val payments: PaymentRepository,
    private val closing: RequestedPaymentClosing,
) : ExpireElapsedRequestedPaymentsUseCase {

    private val log = LoggerFactory.getLogger(RequestedPaymentExpiryService::class.java)
    private val transactions = LocalTransactions(transactionManager, MAX_ATTEMPTS)

    override fun expireElapsed(command: ExpireElapsedRequestedPaymentsCommand): ExpiryRunResult {
        val now = clock.now().truncatedTo(ChronoUnit.MILLIS)
        val causation = ExpiryCausation(command.runId, CAUSATION_PREFIX + command.runId)
        var expired = 0
        var noChange = 0
        var failed = 0
        var defects = 0
        var after: ElapsedRequestedCandidate? = null
        while (true) {
            val page = try {
                reader.findElapsed(now, after, command.batchSize)
            } catch (e: PersistenceFailure) {
                log.warn("operation=CheckoutExpiryJob correlationId={} result=CANDIDATES_UNAVAILABLE exception={}", command.runId, e.javaClass.name)
                break
            }
            page.forEach { candidate ->
                when (expireOne(candidate, now, causation)) {
                    Outcome.EXPIRED -> expired++
                    Outcome.NO_CHANGE -> noChange++
                    Outcome.FAILED -> failed++
                    Outcome.DEFECT -> defects++
                }
            }
            if (page.size < command.batchSize) break
            after = page.last()
        }
        log.info("operation=CheckoutExpiryJob correlationId={} expired={} noChange={} failed={} defects={}", command.runId, expired, noChange, failed, defects)
        return ExpiryRunResult(expired, noChange, failed, defects)
    }

    private enum class Outcome { EXPIRED, NO_CHANGE, FAILED, DEFECT }

    /** 후보 하나(그 건만 TX). 저장소 일시 실패는 FAILED(WARN), 그 밖의 예외는 DEFECT(ERROR — 같은 후보가 매 회차 막히면 경보로 드러난다, 검토 #4 n-3). */
    private fun expireOne(candidate: ElapsedRequestedCandidate, now: java.time.Instant, causation: ExpiryCausation): Outcome = try {
        val closed = transactions.execute({ it is PersistenceFailure.Deadlock || it is PersistenceFailure.OptimisticConflict }) {
            val control = controls.lock(candidate.orderKey) ?: error("a payment has an order control")
            val payment = payments.lock(candidate.paymentId) ?: error("a candidate payment exists")
            closing.closeIfElapsed(control, payment, now, causation) is ClosingOutcome.Closed
        }
        if (closed) Outcome.EXPIRED else Outcome.NO_CHANGE
    } catch (e: RuntimeException) {
        val transient = e is PersistenceFailure || e is CommitOutcomeUnknownException ||
            e is org.springframework.transaction.CannotCreateTransactionException || e is org.springframework.transaction.UnexpectedRollbackException
        val message = "operation=CheckoutExpiryJob correlationId={} result={} paymentId={} exception={}"
        if (transient) {
            log.warn(message, causation.correlationId, "FAILED", prefix(candidate), e.javaClass.name)
            Outcome.FAILED
        } else {
            log.error(message, causation.correlationId, "DEFECT", prefix(candidate), SafeThrowableNames.summary(e))
            Outcome.DEFECT
        }
    }

    /** 로그에는 식별자 접두만(AUTHN-001 §12.6). */
    private fun prefix(candidate: ElapsedRequestedCandidate): String = candidate.paymentId.value.take(PAYMENT_ID_LOG_PREFIX)

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val CAUSATION_PREFIX = "checkout-expiry-job:"
        const val PAYMENT_ID_LOG_PREFIX = 12
    }
}

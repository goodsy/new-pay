package com.paycore.financial.paymentfact.application

import com.paycore.financial.paymentfact.application.provided.HoldReleaseSweepResult
import com.paycore.financial.paymentfact.application.provided.SweepOriginalUnknownHoldsUseCase
import com.paycore.financial.paymentfact.application.required.PaymentFactClock
import com.paycore.financial.paymentfact.application.required.PaymentFactPersistenceFailure
import com.paycore.financial.paymentfact.application.required.PaymentFactProjectionStore
import com.paycore.financial.paymentfact.domain.HoldReleaseTrigger
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import java.time.temporal.ChronoUnit

/**
 * [SweepOriginalUnknownHoldsUseCase] 구현. Payment마다 새 FINANCIAL 로컬 TX(REQUIRES_NEW, READ COMMITTED): 직렬화 행 잠금 → 이력 → 그 Payment의 원승인마다
 * [OriginalUnknownHoldRelease](Inbox와 같은 절차·규칙). 잠금 아래에서 다시 읽으므로 대상 목록을 읽은 뒤 Inbox가 먼저 판정한 보류는 건너뛴다(빈 목록).
 */
@Service
class PaymentFactHoldReleaseSweepService(
    transactionManager: PlatformTransactionManager,
    private val projection: PaymentFactProjectionStore,
    private val clock: PaymentFactClock,
) : SweepOriginalUnknownHoldsUseCase {

    private val transactions = PaymentFactLocalTransactions(transactionManager, MAX_ATTEMPTS, independentOfCaller = true)
    private val holdRelease = OriginalUnknownHoldRelease(projection)

    override fun sweep(maxPayments: Int): HoldReleaseSweepResult {
        val paymentIds = transactions.execute(::isRetryable) { projection.paymentsWithReleasableHolds(maxPayments) }
        var released = 0
        var review = 0
        paymentIds.forEach { paymentId ->
            val (r, v) = transactions.execute(::isRetryable) { once(paymentId) }
            released += r
            review += v
        }
        return HoldReleaseSweepResult(paymentIds.size, released, review)
    }

    private fun once(paymentId: String): Pair<Int, Int> {
        val now = clock.now().truncatedTo(ChronoUnit.MILLIS)
        val owner = projection.lockExistingStream(paymentId) ?: return 0 to 0
        val history = projection.history(paymentId, owner)
        var released = 0
        var review = 0
        history.authorizations.forEach { authorization ->
            val (r, v) = holdRelease.apply(history, authorization, projection.hasHolds(authorization.transactionId), HoldReleaseTrigger.SWEEP, now)
            released += r
            review += v
        }
        return released to review
    }

    private fun isRetryable(e: RuntimeException): Boolean =
        e is PaymentFactPersistenceFailure.Deadlock || e is PaymentFactPersistenceFailure.OptimisticConflict ||
            e is PaymentFactPersistenceFailure.UniqueConflict

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}

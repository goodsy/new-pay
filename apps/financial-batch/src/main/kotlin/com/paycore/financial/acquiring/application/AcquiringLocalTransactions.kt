package com.paycore.financial.acquiring.application

import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.UnexpectedRollbackException
import org.springframework.transaction.support.DefaultTransactionDefinition

/**
 * acquiring 모듈의 FINANCIAL 로컬 TX 실행기. **항상 새 TX(REQUIRES_NEW)·READ COMMITTED**: Spring Batch Tasklet step TX 안에서 불려도 업무 효과는 따로 커밋되고
 * (step 재시작은 같은 의도를 멱등하게 다시 실행 — 봉인은 저장 바이트 대조로 AlreadySealed), 잠금 뒤 읽기가 최신 커밋 값을 본다(REPEATABLE READ의 snapshot이면
 * 잠금 직전에 커밋된 취소를 놓쳐 D-ACQ-012 상계 경계가 깨진다).
 * commit 전 실패는 rollback 후 원래 예외, commit 중 실패는 [AcquiringCommitOutcomeUnknownException](재실행이 저장 상태로 수렴), [isRetryable] 실패만 새 TX로 재시도.
 */
internal class AcquiringLocalTransactions(private val transactionManager: PlatformTransactionManager, private val maxAttempts: Int) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be positive" }
    }

    private val definition = DefaultTransactionDefinition().apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
    }

    fun <T> execute(isRetryable: (RuntimeException) -> Boolean, block: (TransactionStatus) -> T): T {
        var attempt = 1
        while (true) {
            try {
                return once(block)
            } catch (e: AcquiringCommitOutcomeUnknownException) {
                throw e
            } catch (e: RuntimeException) {
                if (attempt >= maxAttempts || !isRetryable(e)) throw e
                attempt++
            }
        }
    }

    private fun <T> once(block: (TransactionStatus) -> T): T {
        val status = transactionManager.getTransaction(definition)
        val value = try {
            block(status)
        } catch (e: Throwable) {
            try {
                transactionManager.rollback(status)
            } catch (rollbackFailure: RuntimeException) {
                e.addSuppressed(rollbackFailure)
            }
            throw e
        }
        try {
            transactionManager.commit(status)
        } catch (e: UnexpectedRollbackException) {
            throw e
        } catch (e: RuntimeException) {
            throw AcquiringCommitOutcomeUnknownException(e)
        }
        return value
    }
}

/** commit 단계 실패: 커밋됐는지 알 수 없다. 같은 봉인을 다시 부르면 저장 상태(AlreadySealed 또는 처음 봉인)로 수렴한다. */
class AcquiringCommitOutcomeUnknownException(cause: Throwable) : RuntimeException("local transaction commit outcome unknown", cause)

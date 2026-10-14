package com.paycore.payment.entitlement.application

import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.UnexpectedRollbackException
import org.springframework.transaction.support.DefaultTransactionDefinition
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * entitlement 모듈의 PAYMENT 로컬 TX 실행기(payment 모듈 `LocalTransactions`와 같은 관례 — 모듈 내부 타입이라 공유하지 않는다, Modulith 공개 범위).
 * 호출 시점에 TX가 있으면 거부, READ COMMITTED, commit 전 실패는 rollback 후 원래 예외(rollback 실패는 suppressed), commit 중 실패는 결과 불명확
 * ([EntitlementCommitOutcomeUnknownException]), [isRetryable] 실패만 새 TX로 처음부터 다시(최대 [maxAttempts]회).
 */
internal class EntitlementLocalTransactions(private val transactionManager: PlatformTransactionManager, private val maxAttempts: Int) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be positive" }
    }

    private val definition = DefaultTransactionDefinition().apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRED
        isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
    }

    fun <T> execute(isRetryable: (RuntimeException) -> Boolean, block: (TransactionStatus) -> T): T {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) {
            "this use case owns its local transaction and must be called outside a transaction"
        }
        var attempt = 1
        while (true) {
            try {
                return once(block)
            } catch (e: EntitlementCommitOutcomeUnknownException) {
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
            throw EntitlementCommitOutcomeUnknownException(e)
        }
        return value
    }
}

/** commit 단계 실패: 커밋됐는지 알 수 없다(Inbox는 응답하지 않는다 → MASTER 재전송 → DUPLICATE/APPLIED로 수렴). */
internal class EntitlementCommitOutcomeUnknownException(cause: Throwable) : RuntimeException("local transaction commit outcome unknown", cause)

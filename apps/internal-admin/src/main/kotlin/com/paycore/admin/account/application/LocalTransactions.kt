package com.paycore.admin.account.application

import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.UnexpectedRollbackException
import org.springframework.transaction.support.DefaultTransactionDefinition
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * account 모듈 로컬 TX 실행기(master-api·audit와 같은 관례 — 모듈 내부 구현이라 모듈마다 둔다: Modulith는 다른 모듈 internal 공유를 막는다).
 * 이미 TX 안이면 거부, READ COMMITTED, commit 전 실패는 원래 예외 보존, commit 중 실패는 [CommitOutcomeUnknownException].
 */
internal class LocalTransactions(private val transactionManager: PlatformTransactionManager, private val maxAttempts: Int) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be positive" }
    }

    private val definition = DefaultTransactionDefinition().apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRED
        isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
    }

    fun <T> execute(isRetryable: (RuntimeException) -> Boolean, block: (TransactionStatus) -> T): T {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) { "this use case owns its local transaction and must be called outside a transaction" }
        var attempt = 1
        while (true) {
            try {
                return once(block)
            } catch (e: CommitOutcomeUnknownException) {
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
            throw CommitOutcomeUnknownException(e)
        }
        return value
    }
}

internal class CommitOutcomeUnknownException(cause: Throwable) : RuntimeException("local transaction commit outcome unknown", cause)

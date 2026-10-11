package com.paycore.master.merchant.application

import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.UnexpectedRollbackException
import org.springframework.transaction.support.DefaultTransactionDefinition
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * Use Case가 소유하는 MASTER 로컬 TX 실행기(CP-DB-001 관례).
 *
 * - 호출 시점에 이미 TX가 있으면 거부한다: Use Case가 TX 경계를 소유해야 재시도·rollback 판단이 성립한다.
 * - 격리 수준 READ COMMITTED: 판정에 쓰는 행은 잠금 읽기(FOR UPDATE)로 최신 커밋 값을 보고, 비잠금 읽기도 TX 시작 시점 스냅샷이 아니라
 *   최신 커밋을 본다. 갭 잠금을 줄여 insert-first 경합의 교착 여지를 줄인다.
 * - **commit 전 실패**(블록 예외): rollback하고 원래 예외를 그대로 던진다. rollback 자체가 실패해도(연결 끊김) 원래 예외를 잃지 않는다
 *   (rollback 실패는 suppressed로 붙인다). commit 요청을 보내지 않았으므로 이 TX는 커밋되지 않았다.
 *   TransactionTemplate은 rollback 실패 예외로 원래 예외를 덮어써 분류가 불가능해지므로 쓰지 않는다.
 * - **commit 중 실패**: [UnexpectedRollbackException](rollback 확정)을 빼고는 커밋 여부를 알 수 없다고 보고 [CommitOutcomeUnknownException]으로
 *   감싼다(연결이 commit 도중 끊기면 서버가 반영했는지 알 수 없다). 호출자는 새 키가 아니라 같은 키로 다시 확인해야 한다.
 * - [isRetryable]이 참인 commit 전 실패(DB가 rollback을 확정한 교착 등)만 **새 TX로 처음부터 같은 의도**로 다시 실행한다. 최대 [maxAttempts]회.
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
        check(!TransactionSynchronizationManager.isActualTransactionActive()) {
            "this use case owns its local transaction and must be called outside a transaction"
        }
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

/** commit 단계 실패: 커밋됐는지 알 수 없다. 같은 키 재요청/조회로 확인한다(새 키 금지, 초안 §5). */
internal class CommitOutcomeUnknownException(cause: Throwable) : RuntimeException("local transaction commit outcome unknown", cause)

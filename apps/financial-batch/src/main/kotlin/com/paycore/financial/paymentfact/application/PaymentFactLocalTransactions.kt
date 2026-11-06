package com.paycore.financial.paymentfact.application

import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.UnexpectedRollbackException
import org.springframework.transaction.support.DefaultTransactionDefinition
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * paymentfact 모듈의 FINANCIAL 로컬 TX 실행기(payment-api `LocalTransactions`·`EntitlementLocalTransactions`와 같은 관례 — 앱이 달라 공유하지 않는다).
 * 호출 시점에 TX가 있으면 거부, READ COMMITTED, commit 전 실패는 rollback 후 원래 예외(rollback 실패는 suppressed), commit 중 실패는 결과 불명확
 * ([PaymentFactCommitOutcomeUnknownException] — 응답하지 않는다 → PAYMENT 재전송 → DUPLICATE/처음 처리로 수렴), [isRetryable] 실패만 새 TX로 처음부터 다시(최대 [maxAttempts]회).
 * 블록이 `status.setRollbackOnly()`를 부르면 commit은 rollback으로 끝난다(값은 그대로 돌려준다 — 충돌·중복 판정이 아무것도 남기지 않게).
 */
internal class PaymentFactLocalTransactions(
    private val transactionManager: PlatformTransactionManager,
    private val maxAttempts: Int,
    /**
     * true = 바깥 TX가 있어도 따로 새 TX(REQUIRES_NEW)로 실행한다(ACQUIRING-001 — Spring Batch Tasklet step TX 안에서 부르는 일괄 처리용. 업무 효과는 step metadata와
     * 별도로 커밋되고, step 재시작 시 같은 의도를 멱등하게 다시 실행한다). false(기본) = 바깥 TX가 있으면 거부(Inbox 수신 — FIN-FACT-RELAY-001과 같다).
     */
    private val independentOfCaller: Boolean = false,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be positive" }
    }

    private val definition = DefaultTransactionDefinition().apply {
        propagationBehavior = if (independentOfCaller) TransactionDefinition.PROPAGATION_REQUIRES_NEW else TransactionDefinition.PROPAGATION_REQUIRED
        isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
    }

    fun <T> execute(isRetryable: (RuntimeException) -> Boolean, block: (TransactionStatus) -> T): T {
        check(independentOfCaller || !TransactionSynchronizationManager.isActualTransactionActive()) {
            "this use case owns its local transaction and must be called outside a transaction"
        }
        var attempt = 1
        while (true) {
            try {
                return once(block)
            } catch (e: PaymentFactCommitOutcomeUnknownException) {
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
            throw PaymentFactCommitOutcomeUnknownException(e)
        }
        return value
    }
}

/** commit 단계 실패: 커밋됐는지 알 수 없다(Inbox는 응답하지 않는다 → PAYMENT 재전송 → DUPLICATE/처음 처리로 수렴). */
internal class PaymentFactCommitOutcomeUnknownException(cause: Throwable) : RuntimeException("local transaction commit outcome unknown", cause)

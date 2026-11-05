package com.paycore.financial.paymentfact.adapter.persistence

import com.paycore.financial.paymentfact.application.required.PaymentFactPersistenceFailure
import org.hibernate.StaleStateException
import org.springframework.dao.OptimisticLockingFailureException
import java.sql.SQLException
import java.sql.SQLNonTransientConnectionException
import java.sql.SQLRecoverableException
import java.sql.SQLTransientConnectionException

/**
 * DB/JPA 예외 → [PaymentFactPersistenceFailure](payment-api 관례와 같다: MySQL vendor 코드·SQLState로만 판단, 메시지 원문·키 값을 넘기지 않는다).
 * 분류할 수 없는 예외는 그대로 둔다(결함 — 상위에서 500/503).
 */
internal object PaymentFactPersistenceFailures {

    fun <T> translate(entity: String, block: () -> T): T =
        try {
            block()
        } catch (e: PaymentFactPersistenceFailure) {
            throw e
        } catch (e: RuntimeException) {
            throw classify(entity, e) ?: e
        }

    private fun classify(entity: String, failure: Throwable): PaymentFactPersistenceFailure? {
        val chain = generateSequence(failure) { t -> t.cause?.takeIf { it !== t } }.take(MAX_CAUSE_DEPTH).toList()
        if (chain.any { it is OptimisticLockingFailureException || it is jakarta.persistence.OptimisticLockException || it is StaleStateException }) {
            return PaymentFactPersistenceFailure.OptimisticConflict(entity)
        }
        val sql = chain.filterIsInstance<SQLException>().firstOrNull() ?: return null
        return when {
            sql.errorCode == DUPLICATE_KEY -> PaymentFactPersistenceFailure.UniqueConflict(KEY_NAME.find(sql.message ?: "")?.groupValues?.get(1) ?: "unknown")
            sql.errorCode == DEADLOCK -> PaymentFactPersistenceFailure.Deadlock(failure, sql.errorCode, sql.sqlState)
            sql.errorCode == LOCK_WAIT_TIMEOUT -> PaymentFactPersistenceFailure.LockTimeout(failure, sql.errorCode, sql.sqlState)
            sql.sqlState?.startsWith("08") == true || sql is SQLTransientConnectionException || sql is SQLNonTransientConnectionException ||
                sql is SQLRecoverableException || sql.errorCode in CONNECTION_LOST -> PaymentFactPersistenceFailure.Unavailable(failure, sql.errorCode, sql.sqlState)
            else -> null
        }
    }

    private const val MAX_CAUSE_DEPTH = 20
    private const val DUPLICATE_KEY = 1062
    private const val DEADLOCK = 1213
    private const val LOCK_WAIT_TIMEOUT = 1205
    private val CONNECTION_LOST = setOf(2006, 2013)
    private val KEY_NAME = Regex("for key '([^']+)'\\s*$")
}

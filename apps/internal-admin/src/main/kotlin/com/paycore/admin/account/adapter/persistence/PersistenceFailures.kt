package com.paycore.admin.account.adapter.persistence

import com.paycore.admin.account.application.required.PersistenceFailure
import com.paycore.admin.account.application.required.StoreConstraint
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.hibernate.StaleStateException
import org.springframework.dao.OptimisticLockingFailureException
import java.sql.SQLException
import java.sql.SQLNonTransientConnectionException
import java.sql.SQLRecoverableException
import java.sql.SQLTransientConnectionException

/**
 * DB/JPA 예외 → [PersistenceFailure](master-api와 같은 관례). 원인 사슬의 MySQL vendor 코드로 판단: 1062 → UniqueConflict(키 이름만, 값 버림),
 * 1213 → Deadlock, 1205 → LockTimeout, 08xxx/연결 예외 → Unavailable, JPA version 충돌 → OptimisticConflict. 그 밖(CHECK 3819·열 권한 1143 등)은 결함 — 그대로 던진다.
 */
internal object PersistenceFailures {

    fun <T> translate(entity: String, block: () -> T): T =
        try {
            block()
        } catch (e: PersistenceFailure) {
            throw e
        } catch (e: RuntimeException) {
            throw classify(entity, e) ?: e
        }

    fun classify(entity: String, failure: Throwable): PersistenceFailure? {
        val chain = generateSequence(failure) { t -> t.cause?.takeIf { it !== t } }.take(MAX_CAUSE_DEPTH).toList()
        if (chain.any { it is OptimisticLockingFailureException || it is jakarta.persistence.OptimisticLockException || it is StaleStateException }) {
            return PersistenceFailure.OptimisticConflict(entity)
        }
        val sql = chain.filterIsInstance<SQLException>().firstOrNull() ?: return null
        return when {
            sql.errorCode == MYSQL_DUPLICATE_KEY -> PersistenceFailure.UniqueConflict(constraintOf(sql.message))
            sql.errorCode == MYSQL_DEADLOCK -> PersistenceFailure.Deadlock(failure)
            sql.errorCode == MYSQL_LOCK_WAIT_TIMEOUT -> PersistenceFailure.LockTimeout(failure)
            isConnectionFailure(sql) -> PersistenceFailure.Unavailable(failure)
            else -> null
        }
    }

    private fun isConnectionFailure(sql: SQLException): Boolean =
        sql.sqlState?.startsWith("08") == true || sql is SQLTransientConnectionException || sql is SQLNonTransientConnectionException ||
            sql is SQLRecoverableException || sql.errorCode in MYSQL_CONNECTION_LOST

    /** `Duplicate entry '<값>' for key '<table>.<index>'`에서 key 이름만 읽는다(값은 쓰지 않는다 — login_name 비노출). */
    fun constraintOf(message: String?): StoreConstraint {
        val key = message?.let { KEY_NAME.find(it)?.groupValues?.get(1) } ?: return StoreConstraint.OTHER
        return CONSTRAINTS[key] ?: StoreConstraint.OTHER
    }

    private const val MAX_CAUSE_DEPTH = 20
    private const val MYSQL_DUPLICATE_KEY = 1062
    private const val MYSQL_DEADLOCK = 1213
    private const val MYSQL_LOCK_WAIT_TIMEOUT = 1205
    private val MYSQL_CONNECTION_LOST = setOf(2006, 2013)
    private val KEY_NAME = Regex("for key '([^']+)'\\s*$")
    private val CONSTRAINTS = mapOf(
        "admin_account.uk_admin_account_login_name" to StoreConstraint.LOGIN_NAME,
        "admin_account.PRIMARY" to StoreConstraint.ACTOR_ID,
        "admin_enrollment_code.PRIMARY" to StoreConstraint.ENROLLMENT_CODE,
        "admin_bootstrap.PRIMARY" to StoreConstraint.BOOTSTRAP,
        "admin_recovery_episode.PRIMARY" to StoreConstraint.RECOVERY_EPISODE,
        "admin_recovery_episode.uk_admin_recovery_episode_open" to StoreConstraint.RECOVERY_EPISODE,
    )
}

/**
 * 잠금 읽기: `find` 뒤 `refresh(PESSIMISTIC_WRITE)` = `SELECT … FOR UPDATE`로 최신 커밋 값을 다시 읽는다(이미 영속성 컨텍스트에 있던 Entity도 덮음 — master-api 관례).
 * JPQL `@Lock` 조회는 쓰지 않는다(관례 §9 — 영속성 컨텍스트의 오래된 Entity를 다시 읽지 않는 문제). runtime은 열 단위 UPDATE만으로 잠글 수 있다(S0 m-4).
 */
internal fun <T : Any> EntityManager.lockRow(type: Class<T>, id: Any): T? {
    val row = find(type, id) ?: return null
    refresh(row, LockModeType.PESSIMISTIC_WRITE)
    return row
}

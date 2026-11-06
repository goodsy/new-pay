package com.paycore.simulator.acquirer.adapter.persistence

import com.paycore.simulator.acquirer.application.required.DuplicateAcquirerRecord
import jakarta.persistence.EntityManager
import jakarta.persistence.OptimisticLockException
import org.hibernate.StaleStateException
import java.sql.SQLException

/**
 * 가상 매입사 영속 실패 분류(adapter.persistence 전용). JPA 선택: persist 뒤 **즉시 flush** — UNIQUE 위반(1062)·version 충돌을 이 Port 호출 안에서 드러내
 * [DuplicateAcquirerRecord]로 분류한다(flush ≠ commit: 커밋은 호출자 TX가 한다. 실패한 TX는 호출자가 버린다). JDBC 오류 원문(키 값 포함)은 로그에 남기지 않는다.
 */
object AcquirerPersistenceFailures {

    fun <T> flushing(entityManager: EntityManager, block: () -> T): T = try {
        val result = block()
        entityManager.flush()
        result
    } catch (e: RuntimeException) {
        if (isConcurrentWrite(e)) throw DuplicateAcquirerRecord()
        throw e
    }

    private fun isConcurrentWrite(e: Throwable): Boolean = generateSequence(e) { c -> c.cause?.takeIf { it !== c } }.take(MAX_CAUSE_DEPTH).any { c ->
        (c is SQLException && c.errorCode == MYSQL_DUPLICATE_KEY) || c is OptimisticLockException || c is StaleStateException
    }

    private const val MAX_CAUSE_DEPTH = 20
    private const val MYSQL_DUPLICATE_KEY = 1062
}

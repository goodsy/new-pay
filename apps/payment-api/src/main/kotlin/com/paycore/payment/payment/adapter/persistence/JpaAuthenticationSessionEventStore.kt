package com.paycore.payment.payment.adapter.persistence

import com.paycore.payment.payment.adapter.persistence.PersistenceFailures.translate
import com.paycore.payment.payment.application.required.AuthenticationSessionEventRecord
import com.paycore.payment.payment.application.required.AuthenticationSessionEventStore
import com.paycore.payment.payment.application.required.EventAppendResult
import com.paycore.payment.payment.domain.AuthenticationEventDetail
import com.paycore.payment.payment.domain.AuthenticationEventKind
import com.paycore.payment.payment.domain.AuthenticationSessionId
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * [AuthenticationSessionEventStore] JPA 구현(append-only, `@Immutable` Entity). 호출자가 payment → 세션 잠금을 쥔 상태라 `max(seq) + 1`과 반복 종류 존재 확인이
 * 직렬화된다 — 정상 경로에서 1062가 나지 않는다(UNIQUE는 DB 방어선, §7.6).
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaAuthenticationSessionEventStore(
    private val rows: AuthenticationSessionEventJpaRepository,
    private val entityManager: EntityManager,
) : AuthenticationSessionEventStore {

    override fun append(event: AuthenticationSessionEventRecord): EventAppendResult = translate(ENTITY) {
        val sessionId = event.sessionId.value
        if (event.kind in AuthenticationSessionEventStore.REPEATABLE_KINDS && rows.existsRecorded(sessionId, event.kind.name, event.detail?.name)) {
            return@translate EventAppendResult.ALREADY_RECORDED
        }
        val seq = (rows.maxSeq(sessionId) ?: -1) + 1
        entityManager.persist(JpaAuthenticationSessionEventEntity.newRow(sessionId, seq, event.kind.name, event.detail?.name, event.occurredAt, event.correlationId))
        entityManager.flush()
        EventAppendResult.APPENDED
    }

    override fun findBySession(sessionId: AuthenticationSessionId): List<AuthenticationSessionEventRecord> = translate(ENTITY) {
        rows.findBySessionOrdered(sessionId.value).map {
            AuthenticationSessionEventRecord(
                sessionId = AuthenticationSessionId(it.key.authenticationSessionId),
                kind = AuthenticationEventKind.valueOf(it.kind),
                detail = it.detailCode?.let(AuthenticationEventDetail::valueOf),
                occurredAt = it.occurredAt,
                correlationId = it.correlationId,
            )
        }
    }

    private companion object {
        const val ENTITY = "authentication_session_event"
    }
}

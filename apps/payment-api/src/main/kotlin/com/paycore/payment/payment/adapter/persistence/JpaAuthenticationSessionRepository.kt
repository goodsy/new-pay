package com.paycore.payment.payment.adapter.persistence

import com.paycore.payment.payment.adapter.persistence.PersistenceFailures.translate
import com.paycore.payment.payment.application.required.AuthenticationSessionAttributes
import com.paycore.payment.payment.application.required.AuthenticationSessionRepository
import com.paycore.payment.payment.application.required.PersistenceFailure
import com.paycore.payment.payment.application.required.Versioned
import com.paycore.payment.payment.domain.AuthenticationRef
import com.paycore.payment.payment.domain.AuthenticationSession
import com.paycore.payment.payment.domain.AuthenticationSessionId
import com.paycore.payment.payment.domain.CheckoutSessionId
import com.paycore.payment.payment.domain.PaymentId
import com.paycore.payment.payment.domain.SecretDigest
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * [AuthenticationSessionRepository] JPA 구현(AUTHN-001 §11.2). 공통 관례는 [JpaPaymentRepository] 설명과 같다: MANDATORY TX, 쓰기 끝 flush(제약 위반·version
 * 충돌을 Port 안에서 [PersistenceFailure]로), 새 행은 persist만, 갱신은 잠가 읽은 Entity에 Mapper로 옮겨 변경 감지(`UPDATE … WHERE version = ?`).
 * "Payment당 비종료 1·귀속 1"은 서로 다른 행 사이 불변식이라 @Version이 못 지킨다 — 생성 컬럼 UNIQUE가 지키고 1062는 UniqueConflict로 나간다.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaAuthenticationSessionRepository(
    private val rows: AuthenticationSessionJpaRepository,
    private val entityManager: EntityManager,
) : AuthenticationSessionRepository {

    override fun insertNew(session: AuthenticationSession, attributes: AuthenticationSessionAttributes): Long = translate(ENTITY) {
        val row = AuthenticationSessionPersistenceMapper.newRow(session, attributes)
        entityManager.persist(row)
        entityManager.flush()
        checkNotNull(row.version)
    }

    override fun lock(id: AuthenticationSessionId): Versioned<AuthenticationSession>? = translate(ENTITY) {
        entityManager.lockRow(JpaAuthenticationSessionEntity::class.java, id.value)?.let { Versioned(AuthenticationSessionPersistenceMapper.toSession(it), checkNotNull(it.version)) }
    }

    override fun lockAllOfPayment(paymentId: PaymentId): List<Versioned<AuthenticationSession>> = translate(ENTITY) {
        entityManager.lockRows(JpaAuthenticationSessionEntity::class.java, rows.lockIdsByPayment(paymentId.value)).map { Versioned(AuthenticationSessionPersistenceMapper.toSession(it), checkNotNull(it.version)) }
    }

    override fun findIdByState(checkoutSessionId: CheckoutSessionId, stateDigest: SecretDigest): AuthenticationSessionId? = translate(ENTITY) {
        rows.findIdByCheckoutSessionAndState(checkoutSessionId.value, stateDigest.value)?.let(::AuthenticationSessionId)
    }

    override fun findIdByAuthenticationRef(authenticationRef: AuthenticationRef): AuthenticationSessionId? = translate(ENTITY) {
        rows.findIdByAuthenticationRef(authenticationRef.value)?.let(::AuthenticationSessionId)
    }

    override fun update(session: AuthenticationSession, expectedVersion: Long): Long = translate(ENTITY) {
        val row = entityManager.find(JpaAuthenticationSessionEntity::class.java, session.id.value) ?: error("authentication session ${session.id.value} is not stored")
        if (row.version != expectedVersion) throw PersistenceFailure.OptimisticConflict(ENTITY)
        AuthenticationSessionPersistenceMapper.apply(session, row)
        entityManager.flush()
        checkNotNull(row.version)
    }

    private companion object {
        const val ENTITY = "authentication_session"
    }
}

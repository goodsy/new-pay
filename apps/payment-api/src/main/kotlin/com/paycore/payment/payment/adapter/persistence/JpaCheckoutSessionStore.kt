package com.paycore.payment.payment.adapter.persistence

import com.paycore.payment.payment.adapter.persistence.PersistenceFailures.translate
import com.paycore.payment.payment.application.required.CheckoutSessionRecord
import com.paycore.payment.payment.application.required.CheckoutSessionStore
import com.paycore.payment.payment.application.required.PersistenceFailure
import com.paycore.payment.payment.application.required.Versioned
import com.paycore.payment.payment.domain.CheckoutHandle
import com.paycore.payment.payment.domain.CheckoutSessionId
import com.paycore.payment.payment.domain.PaymentId
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/** [CheckoutSessionStore] JPA 구현(AUTHN-001 §11.2). 관례는 [JpaPaymentRepository]와 같다. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaCheckoutSessionStore(
    private val rows: CheckoutSessionJpaRepository,
    private val entityManager: EntityManager,
) : CheckoutSessionStore {

    override fun insert(session: CheckoutSessionRecord): Long = translate(ENTITY) {
        val row = CheckoutPersistenceMapper.newSessionRow(session)
        entityManager.persist(row)
        entityManager.flush()
        checkNotNull(row.version)
    }

    override fun findByHandle(handle: CheckoutHandle): CheckoutSessionRecord? = translate(ENTITY) {
        rows.findByHandle(handle.value)?.let(CheckoutPersistenceMapper::toSession)
    }

    override fun lock(id: CheckoutSessionId): Versioned<CheckoutSessionRecord>? = translate(ENTITY) {
        entityManager.lockRow(JpaCheckoutSessionEntity::class.java, id.value)?.let { Versioned(CheckoutPersistenceMapper.toSession(it), checkNotNull(it.version)) }
    }

    override fun lockActiveOfPayment(paymentId: PaymentId): Versioned<CheckoutSessionRecord>? = translate(ENTITY) {
        entityManager.lockRows(JpaCheckoutSessionEntity::class.java, rows.lockActiveIdsByPayment(paymentId.value)).let { found ->
            check(found.size <= 1) { "at most one ACTIVE checkout session per payment" }
            found.singleOrNull()?.let { Versioned(CheckoutPersistenceMapper.toSession(it), checkNotNull(it.version)) }
        }
    }

    override fun update(session: CheckoutSessionRecord, expectedVersion: Long): Long = translate(ENTITY) {
        val row = entityManager.find(JpaCheckoutSessionEntity::class.java, session.id.value) ?: error("checkout session ${session.id.value} is not stored")
        if (row.version != expectedVersion) throw PersistenceFailure.OptimisticConflict(ENTITY)
        CheckoutPersistenceMapper.applySession(session, row)
        entityManager.flush()
        checkNotNull(row.version)
    }

    private companion object {
        const val ENTITY = "checkout_session"
    }
}

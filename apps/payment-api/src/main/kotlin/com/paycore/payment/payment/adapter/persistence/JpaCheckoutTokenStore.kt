package com.paycore.payment.payment.adapter.persistence

import com.paycore.payment.payment.adapter.persistence.PersistenceFailures.translate
import com.paycore.payment.payment.application.required.CheckoutTokenRecord
import com.paycore.payment.payment.application.required.CheckoutTokenStore
import com.paycore.payment.payment.application.required.PersistenceFailure
import com.paycore.payment.payment.application.required.Versioned
import com.paycore.payment.payment.domain.CheckoutTokenId
import com.paycore.payment.payment.domain.PaymentId
import com.paycore.payment.payment.domain.SecretDigest
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/** [CheckoutTokenStore] JPA 구현(AUTHN-001 §11.2). 관례는 [JpaPaymentRepository]와 같다(MANDATORY, persist만, 끝 flush, 잠가 읽은 Entity 변경 감지 + @Version). */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaCheckoutTokenStore(
    private val rows: CheckoutTokenJpaRepository,
    private val entityManager: EntityManager,
) : CheckoutTokenStore {

    override fun insert(token: CheckoutTokenRecord): Long = translate(ENTITY) {
        val row = CheckoutPersistenceMapper.newTokenRow(token)
        entityManager.persist(row)
        entityManager.flush()
        checkNotNull(row.version)
    }

    override fun findByDigest(digest: SecretDigest): CheckoutTokenRecord? = translate(ENTITY) {
        rows.findByTokenDigest(digest.value)?.let(CheckoutPersistenceMapper::toToken)
    }

    override fun lock(id: CheckoutTokenId): Versioned<CheckoutTokenRecord>? = translate(ENTITY) {
        entityManager.lockRow(JpaCheckoutTokenEntity::class.java, id.value)?.let { Versioned(CheckoutPersistenceMapper.toToken(it), checkNotNull(it.version)) }
    }

    override fun lockIssuedOfPayment(paymentId: PaymentId): Versioned<CheckoutTokenRecord>? = translate(ENTITY) {
        entityManager.lockRows(JpaCheckoutTokenEntity::class.java, rows.lockIssuedIdsByPayment(paymentId.value)).let { found ->
            check(found.size <= 1) { "at most one ISSUED token per payment" }
            found.singleOrNull()?.let { Versioned(CheckoutPersistenceMapper.toToken(it), checkNotNull(it.version)) }
        }
    }

    override fun update(token: CheckoutTokenRecord, expectedVersion: Long): Long = translate(ENTITY) {
        val row = entityManager.find(JpaCheckoutTokenEntity::class.java, token.id.value) ?: error("checkout token ${token.id.value} is not stored")
        if (row.version != expectedVersion) throw PersistenceFailure.OptimisticConflict(ENTITY)
        CheckoutPersistenceMapper.applyToken(token, row)
        entityManager.flush()
        checkNotNull(row.version)
    }

    private companion object {
        const val ENTITY = "checkout_token"
    }
}

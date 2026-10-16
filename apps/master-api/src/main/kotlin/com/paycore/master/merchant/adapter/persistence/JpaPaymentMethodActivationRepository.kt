package com.paycore.master.merchant.adapter.persistence

import com.paycore.master.merchant.adapter.persistence.PersistenceFailures.translate
import com.paycore.master.merchant.application.required.PaymentMethodActivationRepository
import com.paycore.master.merchant.application.required.PersistenceFailure
import com.paycore.master.merchant.application.required.Versioned
import com.paycore.master.merchant.domain.ActivationTransition
import com.paycore.master.merchant.domain.EvidenceRef
import com.paycore.master.merchant.domain.MerchantAccountId
import com.paycore.master.merchant.domain.PaymentMethod
import com.paycore.master.merchant.domain.PaymentMethodActivation
import com.paycore.master.merchant.domain.PaymentMethodActivationId
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/*
 * MASTER-SNAPSHOT-001 저장 Port 구현(JPA). 공통 관례는 `PessimisticRowLock.kt` 머리말과 같다(MANDATORY TX, 쓰기 끝 flush, append-only는 persist만,
 * 잠금 읽기는 refresh(PESSIMISTIC_WRITE)).
 */

@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaPaymentMethodActivationRepository(
    private val activations: PaymentMethodActivationJpaRepository,
    private val events: ActivationEventJpaRepository,
    private val entityManager: EntityManager,
) : PaymentMethodActivationRepository {

    override fun findOpen(accountId: MerchantAccountId, method: PaymentMethod): Versioned<PaymentMethodActivation>? = translate(ENTITY) {
        activations.findOpen(accountId.value, method.name)?.let(::versioned)
    }

    override fun find(id: PaymentMethodActivationId): Versioned<PaymentMethodActivation>? = translate(ENTITY) {
        entityManager.find(JpaPaymentMethodActivationEntity::class.java, id.value)?.let(::versioned)
    }

    override fun lock(id: PaymentMethodActivationId): Versioned<PaymentMethodActivation>? = translate(ENTITY) {
        entityManager.lockRow(JpaPaymentMethodActivationEntity::class.java, id.value)?.let(::versioned)
    }

    override fun listByAccount(accountId: MerchantAccountId): List<PaymentMethodActivation> = translate(ENTITY) {
        activations.findByAccount(accountId.value).map(EntitlementPersistenceMapper::toActivation)
    }

    override fun evidenceHistory(id: PaymentMethodActivationId): List<EvidenceRef> = translate(ENTITY) {
        events.findByActivation(id.value).mapNotNull { it.evidenceRef }.distinct().map(::EvidenceRef)
    }

    override fun insert(activation: PaymentMethodActivation, transition: ActivationTransition, requestRecordId: String): Unit = translate(ENTITY) {
        require(transition.from == null && transition.to == activation.status) { "an insert records the registration transition" }
        entityManager.persist(
            JpaPaymentMethodActivationEntity.newRow(
                activation.id.value, activation.merchantAccountId.value, activation.paymentMethod.name, activation.registeredAt,
                activation.registeredBy.value, activation.status.name, activation.evidenceRef?.value,
            ),
        )
        entityManager.flush()
        appendEvent(activation.id, transition, requestRecordId)
    }

    override fun update(activation: PaymentMethodActivation, expectedVersion: Long, transition: ActivationTransition, requestRecordId: String): Unit =
        translate(ENTITY) {
            val row = entityManager.find(JpaPaymentMethodActivationEntity::class.java, activation.id.value) ?: error("activation is not stored")
            if (row.version != expectedVersion) throw PersistenceFailure.OptimisticConflict(ENTITY)
            check(row.merchantAccountId == activation.merchantAccountId.value && row.paymentMethod == activation.paymentMethod.name) {
                "an activation never changes its MID or method"
            }
            check(transition.from?.name == row.status && transition.to == activation.status) { "the transition must start from the stored status" }
            // 변경 감지: 관리되는 Entity의 필드만 바꾸면 flush 때 `UPDATE … WHERE version = ?`가 나간다(save 불필요).
            row.status = activation.status.name
            row.evidenceRef = activation.evidenceRef?.value
            row.statusChangedAt = activation.statusChangedAt
            entityManager.flush()
            appendEvent(activation.id, transition, requestRecordId)
        }

    private fun appendEvent(id: PaymentMethodActivationId, t: ActivationTransition, requestRecordId: String) {
        val seq = events.maxSeq(id.value) + 1
        entityManager.persist(
            JpaActivationEventEntity.newRow(
                ActivationEventKey.of(id.value, seq), t.from?.name, t.to.name, t.evidenceRef?.value, t.reason?.value, t.by.value, requestRecordId, t.at,
            ),
        )
        entityManager.flush()
    }

    private fun versioned(row: JpaPaymentMethodActivationEntity) =
        Versioned(EntitlementPersistenceMapper.toActivation(row), checkNotNull(row.version))

    private companion object {
        const val ENTITY = "payment_method_activation"
    }
}

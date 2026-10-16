package com.paycore.master.merchant.adapter.persistence

import com.paycore.master.merchant.adapter.persistence.PersistenceFailures.translate
import com.paycore.master.merchant.application.required.CurrencyEnablementRepository
import com.paycore.master.merchant.application.required.PersistenceFailure
import com.paycore.master.merchant.application.required.Versioned
import com.paycore.master.merchant.domain.CurrencyCode
import com.paycore.master.merchant.domain.CurrencyEnablement
import com.paycore.master.merchant.domain.CurrencyEnablementId
import com.paycore.master.merchant.domain.CurrencyUnit
import com.paycore.master.merchant.domain.MerchantAccountId
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaCurrencyEnablementRepository(
    private val units: CurrencyUnitJpaRepository,
    private val enablements: CurrencyEnablementJpaRepository,
    private val entityManager: EntityManager,
) : CurrencyEnablementRepository {

    override fun findUnit(code: CurrencyCode): CurrencyUnit? = translate(ENTITY) {
        units.findById(code.value).orElse(null)?.let(EntitlementPersistenceMapper::toUnit)
    }

    override fun find(id: CurrencyEnablementId): Versioned<CurrencyEnablement>? = translate(ENTITY) {
        entityManager.find(JpaCurrencyEnablementEntity::class.java, id.value)?.let(::versioned)
    }

    override fun lock(id: CurrencyEnablementId): Versioned<CurrencyEnablement>? = translate(ENTITY) {
        entityManager.lockRow(JpaCurrencyEnablementEntity::class.java, id.value)?.let(::versioned)
    }

    override fun listByAccount(accountId: MerchantAccountId): List<CurrencyEnablement> = translate(ENTITY) {
        enablements.findByAccount(accountId.value).map { EntitlementPersistenceMapper.toEnablement(it, unitOf(it.currencyCode)) }
    }

    override fun insert(enablement: CurrencyEnablement, requestRecordId: String): Unit = translate(ENTITY) {
        entityManager.persist(
            JpaCurrencyEnablementEntity.newRow(
                enablement.id.value, enablement.merchantAccountId.value, enablement.unit.code.value, enablement.period.from, enablement.period.to,
                enablement.evidenceRef.value, enablement.createdAt, enablement.createdBy.value, requestRecordId,
            ),
        )
        entityManager.flush()
    }

    override fun update(enablement: CurrencyEnablement, expectedVersion: Long): Unit = translate(ENTITY) {
        val row = entityManager.find(JpaCurrencyEnablementEntity::class.java, enablement.id.value) ?: error("enablement is not stored")
        if (row.version != expectedVersion) throw PersistenceFailure.OptimisticConflict(ENTITY)
        check(row.effectiveFrom == enablement.period.from && row.currencyCode == enablement.unit.code.value) { "only the end can change" }
        row.effectiveTo = enablement.period.to
        row.endedAt = enablement.endedAt
        row.endedBy = enablement.endedBy?.value
        entityManager.flush()
    }

    private fun unitOf(code: String): CurrencyUnit =
        EntitlementPersistenceMapper.toUnit(checkNotNull(entityManager.find(JpaCurrencyUnitEntity::class.java, code)) { "currency unit must exist (FK)" })

    private fun versioned(row: JpaCurrencyEnablementEntity) =
        Versioned(EntitlementPersistenceMapper.toEnablement(row, unitOf(row.currencyCode)), checkNotNull(row.version))

    private companion object {
        const val ENTITY = "currency_enablement"
    }
}

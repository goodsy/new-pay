package com.paycore.master.merchant.adapter.persistence

import com.paycore.master.merchant.adapter.persistence.PersistenceFailures.translate
import com.paycore.master.merchant.application.required.MerchantRepository
import com.paycore.master.merchant.application.required.PersistenceFailure
import com.paycore.master.merchant.application.required.Versioned
import com.paycore.master.merchant.domain.Merchant
import com.paycore.master.merchant.domain.MerchantId
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaMerchantRepository(private val entityManager: EntityManager) : MerchantRepository {

    override fun insert(merchant: Merchant): Unit = translate(ENTITY) {
        entityManager.persist(MasterPersistenceMapper.merchantRow(merchant))
        entityManager.flush()
    }

    override fun lock(id: MerchantId): Versioned<Merchant>? = translate(ENTITY) {
        entityManager.lockRow(JpaMerchantEntity::class.java, id.value)?.let {
            Versioned(MasterPersistenceMapper.toMerchant(it), checkNotNull(it.version))
        }
    }

    override fun find(id: MerchantId): Merchant? = translate(ENTITY) {
        entityManager.find(JpaMerchantEntity::class.java, id.value)?.let(MasterPersistenceMapper::toMerchant)
    }

    override fun update(merchant: Merchant, expectedVersion: Long): Long = translate(ENTITY) {
        val row = entityManager.find(JpaMerchantEntity::class.java, merchant.id.value) ?: error("merchant is not stored")
        if (row.version != expectedVersion) throw PersistenceFailure.OptimisticConflict(ENTITY)
        check(row.businessId == merchant.businessId.value) { "a merchant never changes its business" }
        check(merchant.entitlementRevision >= row.entitlementRevision) { "entitlement revision never decreases" }
        // 변경 감지: 관리되는 Entity의 필드만 바꾸면 flush 때 `UPDATE … WHERE version = ?`가 나간다(save 불필요).
        row.entitlementRevision = merchant.entitlementRevision
        entityManager.flush()
        checkNotNull(row.version)
    }

    private companion object {
        const val ENTITY = "merchant"
    }
}

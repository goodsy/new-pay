package com.paycore.master.merchant.adapter.persistence

import com.paycore.master.merchant.adapter.persistence.PersistenceFailures.translate
import com.paycore.master.merchant.application.required.BusinessRepository
import com.paycore.master.merchant.domain.Business
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.BusinessRegistrationNumber
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaBusinessRepository(private val businesses: BusinessJpaRepository, private val entityManager: EntityManager) : BusinessRepository {

    override fun insert(business: Business): Unit = translate(ENTITY) {
        entityManager.persist(MasterPersistenceMapper.businessRow(business))
        entityManager.flush()
    }

    override fun lock(id: BusinessId): Business? = translate(ENTITY) {
        entityManager.lockRow(JpaBusinessEntity::class.java, id.value)?.let(MasterPersistenceMapper::toBusiness)
    }

    override fun findIdByRegistrationNumber(number: BusinessRegistrationNumber): BusinessId? = translate(ENTITY) {
        businesses.findIdByRegistrationNumber(number.value)?.let(::BusinessId)
    }

    override fun find(id: BusinessId): Business? = translate(ENTITY) {
        entityManager.find(JpaBusinessEntity::class.java, id.value)?.let(MasterPersistenceMapper::toBusiness)
    }

    private companion object {
        const val ENTITY = "business"
    }
}

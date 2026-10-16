package com.paycore.master.merchant.adapter.persistence

import com.paycore.master.merchant.adapter.persistence.PersistenceFailures.translate
import com.paycore.master.merchant.application.required.MerchantAccountRepository
import com.paycore.master.merchant.domain.FirstDefaultIssuance
import com.paycore.master.merchant.domain.MerchantAccount
import com.paycore.master.merchant.domain.MerchantAccountId
import com.paycore.master.merchant.domain.MerchantId
import com.paycore.master.merchant.domain.SiteAssignment
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaMerchantAccountRepository(
    private val accounts: MerchantAccountJpaRepository,
    private val assignments: SiteAssignmentJpaRepository,
    private val entityManager: EntityManager,
) : MerchantAccountRepository {

    override fun countByMerchant(merchantId: MerchantId): Long = translate(ENTITY) { accounts.countByMerchant(merchantId.value) }

    override fun findDefault(merchantId: MerchantId): MerchantAccountId? = translate(ENTITY) {
        entityManager.find(JpaDefaultDesignationEntity::class.java, merchantId.value)?.let { MerchantAccountId(it.merchantAccountId) }
    }

    override fun insertFirstDefault(issuance: FirstDefaultIssuance): Unit = translate(ENTITY) {
        val account = issuance.account
        check(issuance.designation.merchantAccountId == account.id && issuance.primaryAssignment.merchantAccountId == account.id) {
            "the issuance parts refer to one MID"
        }
        // FK 순서: MID → Default 지정 → PRIMARY 연결. 하나라도 실패하면 호출자의 TX 전체가 rollback된다(잔존 MID·부분 지정 없음).
        entityManager.persist(MasterPersistenceMapper.accountRow(account))
        entityManager.flush()
        val d = issuance.designation
        entityManager.persist(JpaDefaultDesignationEntity.newRow(d.merchantId.value, d.merchantAccountId.value, d.designatedAt, d.designatedBy.value))
        entityManager.persist(MasterPersistenceMapper.assignmentRow(issuance.primaryAssignment))
        entityManager.flush()
    }

    override fun find(id: MerchantAccountId): MerchantAccount? = translate(ENTITY) {
        entityManager.find(JpaMerchantAccountEntity::class.java, id.value)?.let(MasterPersistenceMapper::toAccount)
    }

    override fun lock(id: MerchantAccountId): MerchantAccount? = translate(ENTITY) {
        entityManager.lockRow(JpaMerchantAccountEntity::class.java, id.value)?.let(MasterPersistenceMapper::toAccount)
    }

    override fun assignmentsOf(id: MerchantAccountId): List<SiteAssignment> = translate(ENTITY) {
        assignments.findByAccount(id.value).map(MasterPersistenceMapper::toAssignment)
    }

    override fun listByMerchant(merchantId: MerchantId): List<MerchantAccount> = translate(ENTITY) {
        accounts.findByMerchant(merchantId.value).map(MasterPersistenceMapper::toAccount)
    }

    private companion object {
        const val ENTITY = "merchant_account"
    }
}

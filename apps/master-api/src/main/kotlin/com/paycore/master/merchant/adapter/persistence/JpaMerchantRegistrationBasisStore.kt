package com.paycore.master.merchant.adapter.persistence

import com.paycore.master.merchant.adapter.persistence.PersistenceFailures.translate
import com.paycore.master.merchant.application.required.MerchantRegistrationBasisStore
import com.paycore.master.merchant.domain.MerchantRegistrationBasis
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/** 가맹점 등록 근거 INSERT 전용 저장(FLOW-3 — BR-BIZ-337). persist + flush(PK·FK 위반이 Port 안에서 드러난다). 등록 TX 안에서만(MANDATORY). */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaMerchantRegistrationBasisStore(private val entityManager: EntityManager) : MerchantRegistrationBasisStore {
    override fun insert(basis: MerchantRegistrationBasis): Unit = translate(ENTITY) {
        entityManager.persist(
            JpaMerchantRegistrationBasisEntity.newRow(
                basis.merchantId.value, basis.contractId.businessId.value, basis.contractId.contractSeq, basis.versionNo, basis.recordedAt, basis.recordedBy.value,
            ),
        )
        entityManager.flush()
    }

    private companion object {
        const val ENTITY = "MerchantRegistrationBasis"
    }
}

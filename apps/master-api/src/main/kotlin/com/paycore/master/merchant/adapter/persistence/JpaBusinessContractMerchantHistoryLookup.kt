package com.paycore.master.merchant.adapter.persistence

import com.paycore.master.merchant.adapter.persistence.PersistenceFailures.translate
import com.paycore.master.merchant.application.required.BusinessContractMerchantHistoryLookup
import com.paycore.master.merchant.domain.BusinessContractMerchantHistory
import com.paycore.master.merchant.domain.BusinessId
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 계약·가맹점 이력 존재(BIZ-CRIT-APPROVAL-001 §3.6 — BR-BIZ-386). 사업자 잠금 뒤 같은 TX(MANDATORY)에서 JPQL **스칼라 존재 질의** 두 개(사업자 계약 행·가맹점 행 — 상태 무관, 접수 건 제외).
 * Entity를 돌려받지 않으므로 영속성 컨텍스트의 오래된 값이 아니라 READ COMMITTED 최신 커밋을 본다(선례 `JpaBusinessContractRepository.latest`).
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaBusinessContractMerchantHistoryLookup(private val entityManager: EntityManager) : BusinessContractMerchantHistoryLookup {
    override fun of(businessId: BusinessId): BusinessContractMerchantHistory = translate(ENTITY) {
        fun exists(jpql: String) = entityManager.createQuery(jpql, Int::class.javaObjectType).setParameter("businessId", businessId.value).setMaxResults(1).resultList.isNotEmpty()
        BusinessContractMerchantHistory(
            contractEver = exists("select 1 from JpaBusinessContractEntity c where c.businessId = :businessId"),
            merchantEver = exists("select 1 from JpaMerchantEntity m where m.businessId = :businessId"),
        )
    }

    private companion object {
        const val ENTITY = "BusinessContractMerchantHistory"
    }
}

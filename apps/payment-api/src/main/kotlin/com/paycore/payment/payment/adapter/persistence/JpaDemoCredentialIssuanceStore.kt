package com.paycore.payment.payment.adapter.persistence

import com.paycore.payment.payment.adapter.persistence.PersistenceFailures.translate
import com.paycore.payment.payment.application.required.DemoCredentialIssuance
import com.paycore.payment.payment.application.required.DemoCredentialIssuanceStore
import com.paycore.payment.payment.domain.MerchantAccountId
import jakarta.persistence.EntityManager
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * [DemoCredentialIssuanceStore] JPA 구현(DEMO-SEED-001 §4.5). **명령 프로세스에만 빈**(`paycore.payment.command=demo-credential` — I-23). INSERT는
 * `persist` + `flush`(merge 금지 — C5)로 PK 1062를 이 메서드 안에서 [PersistenceFailures.translate]가 `UniqueConflict(DEMO_CREDENTIAL_ISSUANCE)`로 바꾼다.
 */
@Component
@ConditionalOnProperty(prefix = "paycore.payment", name = ["command"], havingValue = "demo-credential")
@Transactional(propagation = Propagation.MANDATORY)
class JpaDemoCredentialIssuanceStore(private val entityManager: EntityManager) : DemoCredentialIssuanceStore {

    override fun find(): DemoCredentialIssuance? = translate(ENTITY) {
        entityManager.find(JpaDemoCredentialIssuanceEntity::class.java, JpaDemoCredentialIssuanceEntity.SINGLE_ID)?.let {
            DemoCredentialIssuance(it.keyId, MerchantAccountId(it.merchantAccountId), it.issuedAt, it.actor, it.correlationId)
        }
    }

    override fun insertOnce(issuance: DemoCredentialIssuance): Unit = translate(ENTITY) {
        entityManager.persist(
            JpaDemoCredentialIssuanceEntity.issued(
                issuance.keyId, issuance.merchantAccountId.value, issuance.issuedAt, issuance.actor, issuance.correlationId,
            ),
        )
        entityManager.flush()
    }

    private companion object {
        const val ENTITY = "demo_credential_issuance"
    }
}

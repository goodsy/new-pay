package com.paycore.payment.payment.adapter.persistence

import com.paycore.payment.payment.adapter.persistence.PersistenceFailures.translate
import com.paycore.payment.payment.application.required.DemoCredentialProjection
import com.paycore.payment.payment.application.required.DemoCredentialProjectionWriter
import jakarta.persistence.EntityManager
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * [DemoCredentialProjectionWriter] JPA 구현(DEMO-SEED-001 §4.5). **명령 프로세스에만 빈**(I-23). `persist` + `flush`(merge 금지 — C5), PK 1062는
 * [PersistenceFailures.translate]가 `UniqueConflict(MERCHANT_API_CREDENTIAL_KEY_ID)`로 바꾼다(MySQL 예외를 여기서 직접 해석하지 않는다 — rev 0.3).
 */
@Component
@ConditionalOnProperty(prefix = "paycore.payment", name = ["command"], havingValue = "demo-credential")
@Transactional(propagation = Propagation.MANDATORY)
class JpaDemoCredentialProjectionWriter(private val entityManager: EntityManager) : DemoCredentialProjectionWriter {

    override fun insert(row: DemoCredentialProjection): Unit = translate(ENTITY) {
        entityManager.persist(
            JpaDemoCredentialProjectionEntity.demoTestKey(
                row.keyId, row.merchantAccountId.value, row.secretSha256Hex, row.validFrom, row.validUntil, row.leaseUntil,
            ),
        )
        entityManager.flush()
    }

    private companion object {
        const val ENTITY = "merchant_api_credential"
    }
}

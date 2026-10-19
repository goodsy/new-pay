package com.paycore.simulator.van.adapter.persistence

import com.paycore.simulator.van.application.required.VanEvidenceUseStore
import com.paycore.simulator.van.domain.VanNamespace
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** [VanEvidenceUseStore] JPA 구현(MANDATORY TX, persist만). PK 충돌(다른 요청 ID의 동시 사용) = DuplicateVanRecord. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaVanEvidenceUseStore(
    private val rows: VanEvidenceUseJpaRepository,
    private val entityManager: EntityManager,
) : VanEvidenceUseStore {

    override fun namespaceOf(evidenceRef: String): VanNamespace? =
        rows.findById(evidenceRef).orElse(null)?.let { VanNamespace(it.clientId, it.environment, it.institutionAccount, it.operation, it.requestId) }

    override fun insert(evidenceRef: String, namespace: VanNamespace, at: Instant) = VanPersistence.persistOrDuplicate(
        entityManager,
        JpaVanEvidenceUseEntity.newRow(evidenceRef, namespace.clientId, namespace.environment, namespace.institutionAccount, namespace.operation, namespace.requestId, at),
    )
}

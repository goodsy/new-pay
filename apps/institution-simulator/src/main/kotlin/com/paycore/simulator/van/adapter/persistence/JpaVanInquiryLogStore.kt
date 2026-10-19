package com.paycore.simulator.van.adapter.persistence

import com.paycore.simulator.van.application.required.VanInquiryLogStore
import com.paycore.simulator.van.domain.VanNamespace
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** [VanInquiryLogStore] JPA 구현(MANDATORY TX, persist만 — append-only). */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaVanInquiryLogStore(
    private val rows: VanInquiryLogJpaRepository,
    private val entityManager: EntityManager,
) : VanInquiryLogStore {

    override fun count(namespace: VanNamespace): Long =
        rows.countByClientIdAndEnvironmentAndInstitutionAccountAndOriginalRequestId(namespace.clientId, namespace.environment, namespace.institutionAccount, namespace.requestId)

    override fun append(namespace: VanNamespace, inquiryId: String, closeIfAbsent: Boolean, result: String, at: Instant) {
        entityManager.persist(
            JpaVanInquiryLogEntity.newRow(
                namespace.clientId, namespace.environment, namespace.institutionAccount, namespace.requestId, inquiryId,
                if (closeIfAbsent) "CLOSE_IF_ABSENT" else "LOOKUP", result, at,
            ),
        )
        entityManager.flush()
    }
}

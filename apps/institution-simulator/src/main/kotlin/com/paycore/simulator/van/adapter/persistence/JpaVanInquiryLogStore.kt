package com.paycore.simulator.van.adapter.persistence

import com.paycore.simulator.van.application.required.VanInquiryLogStore
import com.paycore.simulator.van.domain.VanNamespace
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * [VanInquiryLogStore] JPA 구현(MANDATORY TX, persist만 — append-only). 조회 operation(AUTHORIZE·CANCEL)을 함께 남긴다(CANCEL-001 CN-26). 조회 수는 원 요청 ID로 센다 —
 * 승인(`ar_`)과 취소(`cr_`) 요청 ID는 접두가 달라 섞이지 않는다.
 */
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
                if (closeIfAbsent) "CLOSE_IF_ABSENT" else "LOOKUP", result, namespace.operation, at,
            ),
        )
        entityManager.flush()
    }
}

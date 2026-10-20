package com.paycore.simulator.van.adapter.persistence

import com.paycore.simulator.van.application.required.VanCancelLedgerStore
import com.paycore.simulator.van.domain.VanApprovedCancels
import com.paycore.simulator.van.domain.VanCancelEntry
import com.paycore.simulator.van.domain.VanNamespace
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * [VanCancelLedgerStore] JPA 구현(MANDATORY TX, persist만 — 수정·삭제 연산 없음, `@Immutable` Entity, DB 권한 SELECT·INSERT). 승인된 취소 n·Σ는 COUNT와 SUM을 **한 JPQL
 * SELECT**로 읽는다(m-6 — 두 문장이면 그 사이 다른 TX 커밋이 끼어 n과 Σ가 다른 시점일 수 있다. MySQL REPEATABLE READ에서도 한 문장으로 고정해 둔다).
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaVanCancelLedgerStore(
    private val rows: VanCancelEntryJpaRepository,
    private val entityManager: EntityManager,
) : VanCancelLedgerStore {

    override fun find(namespace: VanNamespace): VanCancelEntry? = rows.findByClientIdAndEnvironmentAndInstitutionAccountAndOperationAndRequestId(
        namespace.clientId, namespace.environment, namespace.institutionAccount, namespace.operation, namespace.requestId,
    )?.let(VanPersistence::toCancelEntry)

    override fun approvedTotals(clientId: String, environment: String, institutionAccount: String, originalRequestId: String): VanApprovedCancels {
        val row = entityManager.createQuery(
            "SELECT COUNT(e), COALESCE(SUM(e.cancelledAmountMinor), 0) FROM JpaVanCancelEntryEntity e " +
                "WHERE e.clientId = :client AND e.environment = :environment AND e.institutionAccount = :account " +
                "AND e.originalRequestId = :original AND e.outcome = 'APPROVED'",
            Array<Any>::class.java,
        ).setParameter("client", clientId).setParameter("environment", environment).setParameter("account", institutionAccount)
            .setParameter("original", originalRequestId).singleResult
        return VanApprovedCancels(count = (row[0] as Number).toInt(), sumMinor = (row[1] as Number).toLong())
    }

    override fun insert(entry: VanCancelEntry) = VanPersistence.persistOrDuplicate(entityManager, VanPersistence.newCancelRow(entry))
}

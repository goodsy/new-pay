package com.paycore.simulator.van.adapter.persistence

import com.paycore.simulator.van.application.required.DuplicateVanRecord
import com.paycore.simulator.van.application.required.VanPendingDecisionStore
import com.paycore.simulator.van.domain.VanNamespace
import com.paycore.simulator.van.domain.VanPendingDecision
import com.paycore.simulator.van.domain.VanPendingState
import jakarta.persistence.EntityManager
import java.time.Instant
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/** [VanPendingDecisionStore] JPA 구현(MANDATORY TX). 확정 반영은 `state = 'PENDING'` 조건부 UPDATE — 0행이면 다른 TX가 먼저 확정했다([DuplicateVanRecord] → 재판정). */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaVanPendingDecisionStore(
    private val rows: VanPendingDecisionJpaRepository,
    private val entityManager: EntityManager,
) : VanPendingDecisionStore {

    override fun find(namespace: VanNamespace): VanPendingDecision? = rows.findByClientIdAndEnvironmentAndInstitutionAccountAndOperationAndRequestId(
        namespace.clientId, namespace.environment, namespace.institutionAccount, namespace.operation, namespace.requestId,
    )?.let(VanPersistence::toPending)

    override fun insert(pending: VanPendingDecision) = VanPersistence.persistOrDuplicate(entityManager, VanPersistence.newPendingRow(pending))

    override fun markResolved(pending: VanPendingDecision, at: Instant) {
        check(pending.state == VanPendingState.RESOLVED) { "only a resolved reservation is marked" }
        val updated = rows.markResolved(pending.pendingId, checkNotNull(pending.resolvedEntryId), at)
        if (updated != 1) throw DuplicateVanRecord()
    }
}

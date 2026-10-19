package com.paycore.simulator.van.adapter.persistence

import com.paycore.simulator.van.application.required.VanLedgerStore
import com.paycore.simulator.van.domain.VanLedgerEntry
import com.paycore.simulator.van.domain.VanNamespace
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/** [VanLedgerStore] JPA 구현(MANDATORY TX, persist만 — 수정·삭제 연산 없음, `@Immutable` Entity, DB 권한 SELECT·INSERT). */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaVanLedgerStore(
    private val rows: VanLedgerEntryJpaRepository,
    private val entityManager: EntityManager,
) : VanLedgerStore {

    override fun find(namespace: VanNamespace): VanLedgerEntry? = rows.findByClientIdAndEnvironmentAndInstitutionAccountAndOperationAndRequestId(
        namespace.clientId, namespace.environment, namespace.institutionAccount, namespace.operation, namespace.requestId,
    )?.let(VanPersistence::toEntry)

    override fun insert(entry: VanLedgerEntry) = VanPersistence.persistOrDuplicate(entityManager, VanPersistence.newEntryRow(entry))
}

package com.paycore.simulator.van.adapter.persistence

import com.paycore.simulator.van.application.required.VanAcquiringEvidenceStore
import com.paycore.simulator.van.domain.VanCancelEntry
import com.paycore.simulator.van.domain.VanLedgerEntry
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * [VanAcquiringEvidenceStore] JPA 구현(MANDATORY TX, JPQL 읽기만 — 색인 V11 `(client_id, environment, provider_transaction_id)`). `@Immutable` 원장 Entity를
 * 기존 Mapper(VanPersistence)로 Domain 원장 행으로 바꾼다.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaVanAcquiringEvidenceStore(private val entityManager: EntityManager) : VanAcquiringEvidenceStore {

    override fun approvedAuthorizations(clientId: String, providerTransactionId: String): List<VanLedgerEntry> = entityManager.createQuery(
        "select e from JpaVanLedgerEntryEntity e where e.clientId = :client and e.environment = :env and e.providerTransactionId = :ptx " +
            "and e.kind = 'DECISION' and e.outcome = 'APPROVED' order by e.entryId",
        JpaVanLedgerEntryEntity::class.java,
    ).setParameter("client", clientId).setParameter("env", TEST).setParameter("ptx", providerTransactionId).setMaxResults(MAX_ROWS)
        .resultList.map(VanPersistence::toEntry)

    override fun approvedCancellations(clientId: String, providerTransactionId: String): List<VanCancelEntry> = entityManager.createQuery(
        "select e from JpaVanCancelEntryEntity e where e.clientId = :client and e.environment = :env and e.providerTransactionId = :ptx " +
            "and e.kind = 'DECISION' and e.outcome = 'APPROVED' order by e.entryId",
        JpaVanCancelEntryEntity::class.java,
    ).setParameter("client", clientId).setParameter("env", TEST).setParameter("ptx", providerTransactionId).setMaxResults(MAX_ROWS)
        .resultList.map(VanPersistence::toCancelEntry)

    private companion object {
        const val TEST = "TEST"

        /** 둘 이상이면 호출자가 "찾지 못함"으로 다룬다 — 두 행만 읽으면 충분하다. */
        const val MAX_ROWS = 2
    }
}

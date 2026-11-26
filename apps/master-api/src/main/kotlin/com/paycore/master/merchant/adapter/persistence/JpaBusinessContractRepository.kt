package com.paycore.master.merchant.adapter.persistence

import com.paycore.master.merchant.adapter.persistence.PersistenceFailures.translate
import com.paycore.master.merchant.application.required.BusinessContractRepository
import com.paycore.master.merchant.domain.BusinessContract
import com.paycore.master.merchant.domain.BusinessContractChange
import com.paycore.master.merchant.domain.BusinessId
import jakarta.persistence.EntityManager
import jakarta.persistence.Tuple
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 사업자 계약 저장(MERCHANT-FLOW-001 §4B.2·§4B.12 — JPA 선택의 역할·예·이유·주의는 [JpaBusinessContractEntity]).
 *
 * - `@Transactional(MANDATORY)`: 사업자 행을 잠근 Application TX(READ COMMITTED — `LocalTransactions`) 안에서만 동작한다(잠금 순서 사업자 → 접수 건 → 사업자 계약, §4B.9).
 * - [latest] — **Aggregate 한 번에 load, 잠금 뒤 DB 직접 읽기**: 계약 머리 1 + 하위 표 4(버전·범위·닫기·사건) = SQL 5문장 고정(N+1 없음 — 시험이 문장 수를 고정한다).
 *   JPQL **스칼라 투영**(`Tuple`)으로 읽는다 — Entity를 돌려받지 않으므로 같은 TX 영속성 컨텍스트에 이미 있던 관리 Entity를 재사용하지 않고 항상 DB 행을 본다
 *   (`em.find`·Entity 조회 금지 — §4B.8 MINOR-5, 선례 `JpaContractRepository.hasApprovedRevisionFor`). 예: 같은 TX에서 앞서 load한 뒤 다른 TX가 해지를 커밋했고 이 TX가
 *   사업자 행 잠금을 얻었다면, 다시 부른 [latest]는 그 해지 사건을 본다(READ COMMITTED 최신 커밋).
 *   주의: 사업자 행 잠금 **뒤에** 불러야 판정과 쓰기 사이에 다른 TX가 끼지 않는다.
 * - [insert] — **새 행만 persist**(UPDATE 0): 계약 머리(신규) → 버전 → 범위 → 닫기 / 사건. FK 순서를 위해 부모 뒤에 flush한다. 1062는 Port 안에서
 *   [com.paycore.master.merchant.application.required.PersistenceFailure.UniqueConflict]([com.paycore.master.merchant.application.required.StoreConstraint.BUSINESS_CONTRACT_ROW])로 드러난다.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaBusinessContractRepository(private val entityManager: EntityManager) : BusinessContractRepository {

    override fun latest(businessId: BusinessId): BusinessContract? = translate(ENTITY) {
        val id = businessId.value
        val head = entityManager.createQuery(
            "select c.contractSeq as contractSeq, c.originIntakeSeq as originIntakeSeq, c.migrated as migrated, c.createdAt as createdAt, c.createdBy as createdBy " +
                "from JpaBusinessContractEntity c where c.businessId = :businessId order by c.contractSeq desc",
            Tuple::class.java,
        ).setParameter("businessId", id).setMaxResults(1).resultList.firstOrNull() ?: return@translate null
        val seq = (head.get("contractSeq") as Number).toInt()
        BusinessContractPersistenceMapper.toContract(
            businessId, head,
            children(
                "select v.versionNo as versionNo, v.originIntakeSeq as originIntakeSeq, v.migrated as migrated, v.startsAt as startsAt, v.startDate as startDate, " +
                    "v.endDate as endDate, v.documentRef as documentRef, v.reason as reason, v.createdAt as createdAt, v.createdBy as createdBy " +
                    "from JpaBusinessContractVersionEntity v where v.businessId = :businessId and v.contractSeq = :contractSeq order by v.versionNo",
                id, seq,
            ),
            children(
                "select s.versionNo as versionNo, s.serviceKind as serviceKind from JpaBusinessContractVersionServiceEntity s " +
                    "where s.businessId = :businessId and s.contractSeq = :contractSeq order by s.versionNo, s.serviceKind",
                id, seq,
            ),
            children(
                "select x.versionNo as versionNo, x.closedAt as closedAt, x.closedByVersionNo as closedByVersionNo, x.createdAt as createdAt, x.createdBy as createdBy " +
                    "from JpaBusinessContractVersionCloseEntity x where x.businessId = :businessId and x.contractSeq = :contractSeq order by x.versionNo",
                id, seq,
            ),
            children(
                "select e.eventSeq as eventSeq, e.kind as kind, e.reason as reason, e.evidenceRef as evidenceRef, e.actorRef as actorRef, e.occurredAt as occurredAt " +
                    "from JpaBusinessContractEventEntity e where e.businessId = :businessId and e.contractSeq = :contractSeq order by e.eventSeq",
                id, seq,
            ),
        )
    }

    override fun insert(change: BusinessContractChange): Unit = translate(ENTITY) {
        BusinessContractPersistenceMapper.newRows(change).forEach { batch ->
            batch.forEach(entityManager::persist)
            entityManager.flush()
        }
    }

    private fun children(jpql: String, businessId: String, contractSeq: Int): List<Tuple> =
        entityManager.createQuery(jpql, Tuple::class.java).setParameter("businessId", businessId).setParameter("contractSeq", contractSeq).resultList

    private companion object {
        const val ENTITY = "BusinessContract"
    }
}

package com.paycore.master.merchant.adapter.persistence

import com.paycore.master.merchant.adapter.persistence.PersistenceFailures.translate
import com.paycore.master.merchant.application.required.ContractRepository
import com.paycore.master.merchant.application.required.PersistenceFailure
import com.paycore.master.merchant.application.required.Versioned
import com.paycore.master.merchant.domain.ActorRef
import com.paycore.master.merchant.domain.Contract
import com.paycore.master.merchant.domain.ContractCode
import com.paycore.master.merchant.domain.ContractId
import com.paycore.master.merchant.domain.MerchantId
import jakarta.persistence.EntityManager
import java.time.Instant
import jakarta.persistence.LockModeType
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaContractRepository(
    private val revisions: ContractRevisionJpaRepository,
    private val scopeSites: ContractRevisionScopeSiteJpaRepository,
    private val approvals: ContractRevisionApprovalJpaRepository,
    private val agreements: ContractServiceAgreementJpaRepository,
    private val entityManager: EntityManager,
) : ContractRepository {

    override fun insertNew(contract: Contract): Long = translate(ENTITY) {
        require(contract.revisions.size == 1 && !contract.revisions.single().isApproved && contract.agreements.isEmpty()) {
            "only a freshly registered contract can be inserted"
        }
        val row = JpaContractEntity.newRow(
            contract.id.value, contract.businessId.value, contract.merchantId.value, contract.createdAt, contract.createdBy.value,
        )
        entityManager.persist(row)
        appendRevision(contract, contract.revisions.single())
        entityManager.flush()
        checkNotNull(row.version)
    }

    override fun find(id: ContractId): Contract? = translate(ENTITY) {
        entityManager.find(JpaContractEntity::class.java, id.value)?.let(::toDomain)
    }

    override fun lock(id: ContractId): Versioned<Contract>? = translate(ENTITY) {
        entityManager.lockRow(JpaContractEntity::class.java, id.value)?.let { Versioned(toDomain(it), checkNotNull(it.version)) }
    }

    override fun update(contract: Contract, expectedVersion: Long): Long = translate(ENTITY) {
        val row = entityManager.find(JpaContractEntity::class.java, contract.id.value) ?: error("contract is not stored")
        if (row.version != expectedVersion) throw PersistenceFailure.OptimisticConflict(ENTITY)
        check(row.businessId == contract.businessId.value) { "a contract never changes its party" }
        check(row.merchantId == contract.merchantId.value) { "a contract never changes its merchant (D-MD-013)" }
        val stored = toDomain(row)

        // append-only: 저장된 Revision·승인·CSA는 바꿀 수 없고 새 것만 추가한다(D-MD-004).
        val storedNos = stored.revisions.map { it.revisionNo }
        check(contract.revisions.map { it.revisionNo }.take(storedNos.size) == storedNos) { "revisions are append-only" }
        stored.revisions.forEach { old ->
            val now = checkNotNull(contract.revision(old.revisionNo))
            check(now.scope == old.scope && now.effectivePeriod == old.effectivePeriod) { "revision content is immutable" }
            check(old.approval == null || old.approval == now.approval) { "an approval is never changed" }
        }
        check(contract.agreements.take(stored.agreements.size) == stored.agreements) { "agreements are append-only" }

        contract.revisions.drop(storedNos.size).forEach { appendRevision(contract, it) }
        contract.revisions.filter { r -> r.approval != null && stored.revision(r.revisionNo)?.approval == null }.forEach { r ->
            val approval = checkNotNull(r.approval)
            entityManager.persist(
                JpaContractRevisionApprovalEntity.newRow(RevisionKey.of(contract.id.value, r.revisionNo), approval.approvedAt, approval.approvedBy.value),
            )
        }
        contract.agreements.drop(stored.agreements.size).forEach {
            entityManager.persist(
                JpaContractServiceAgreementEntity.newRow(it.id.value, contract.id.value, it.serviceKind.name, it.addedAt, it.addedBy.value),
            )
        }
        // 자식 행만 늘어도 계약 version을 올린다(동시에 자식을 덧붙이는 다른 TX가 version으로 충돌하게).
        entityManager.lock(row, LockModeType.PESSIMISTIC_FORCE_INCREMENT)
        entityManager.flush()
        checkNotNull(row.version)
    }

    override fun hasApprovedRevisionFor(merchantId: MerchantId): Boolean = translate(ENTITY) {
        // JPQL은 영속성 컨텍스트를 거치지 않고 DB를 읽는다(잠금 뒤 호출 — READ COMMITTED 최신 커밋). 승인 사실 행은 append-only라 읽은 뒤 사라지지 않는다.
        entityManager.createQuery(
            "select count(a) from JpaContractRevisionApprovalEntity a, JpaContractEntity c where c.contractId = a.key.contractId and c.merchantId = :merchantId",
            Long::class.javaObjectType,
        ).setParameter("merchantId", merchantId.value).singleResult > 0
    }

    /**
     * SITE-CONTRACT-CODE-001 §3.4·§7 주의 ②(C-12·C-13): 등록 경로 전용 — [find]·[lock]·[update]는 코드 표를 읽거나 쓰지 않는다. JPQL `max`는 DB를 읽고(영속성 컨텍스트 캐시 아님),
     * Scope merchant `lockRow` 뒤·`beginWrites` 전에 불린다(READ COMMITTED 최신 커밋 — 직렬화는 merchant 행 잠금).
     */
    override fun nextContractCodeSeq(merchantId: MerchantId): Int = translate(CODE_ENTITY) {
        val max = entityManager.createQuery(
            "select max(c.codeSeq) from JpaContractCodeEntity c where c.merchantId = :merchantId", Int::class.javaObjectType,
        ).setParameter("merchantId", merchantId.value).singleResult
        (max ?: 0) + 1
    }

    /** 주의 ①·④: 독립 persist + flush — 계약 행([insertNew]가 이미 flush)이 먼저라 FK `fk_cc_contract` 만족. 1062는 결함 분류(C-3). */
    override fun insertCode(contractId: ContractId, merchantId: MerchantId, code: ContractCode, assignedAt: Instant, assignedBy: ActorRef): Unit = translate(CODE_ENTITY) {
        entityManager.persist(MasterPersistenceMapper.contractCodeRow(contractId, merchantId, code, assignedAt, assignedBy))
        entityManager.flush()
    }

    override fun findCode(contractId: ContractId): ContractCode? = translate(CODE_ENTITY) {
        entityManager.find(JpaContractCodeEntity::class.java, contractId.value)?.let(MasterPersistenceMapper::toContractCode)
    }

    private fun appendRevision(contract: Contract, revision: com.paycore.master.merchant.domain.ContractRevision) {
        entityManager.persist(MasterPersistenceMapper.revisionRow(contract, revision))
        // FK(scope site → revision)를 위해 Revision 행을 먼저 보낸다.
        entityManager.flush()
        MasterPersistenceMapper.scopeSiteRows(contract, revision).forEach(entityManager::persist)
        check(revision.approval == null) { "a new revision is registered before approval" }
    }

    private fun toDomain(row: JpaContractEntity): Contract = MasterPersistenceMapper.toContract(
        row,
        revisions.findByContract(row.contractId),
        scopeSites.findByContract(row.contractId),
        approvals.findByContract(row.contractId),
        agreements.findByContract(row.contractId),
    )

    private companion object {
        const val ENTITY = "contract"
        const val CODE_ENTITY = "contract_code"
    }
}

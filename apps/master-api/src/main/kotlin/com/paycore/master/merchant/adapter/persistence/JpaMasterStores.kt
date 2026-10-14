package com.paycore.master.merchant.adapter.persistence

import com.paycore.master.merchant.adapter.persistence.PersistenceFailures.translate
import com.paycore.master.merchant.application.required.BusinessStore
import com.paycore.master.merchant.application.required.ContractStore
import com.paycore.master.merchant.application.required.MerchantAccountStore
import com.paycore.master.merchant.application.required.MerchantSiteStore
import com.paycore.master.merchant.application.required.MerchantStore
import com.paycore.master.merchant.application.required.PersistenceFailure
import com.paycore.master.merchant.application.required.Versioned
import com.paycore.master.merchant.domain.Business
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.BusinessRegistrationNumber
import com.paycore.master.merchant.domain.Contract
import com.paycore.master.merchant.domain.ContractId
import com.paycore.master.merchant.domain.FirstDefaultIssuance
import com.paycore.master.merchant.domain.Merchant
import com.paycore.master.merchant.domain.MerchantAccount
import com.paycore.master.merchant.domain.MerchantAccountId
import com.paycore.master.merchant.domain.MerchantId
import com.paycore.master.merchant.domain.MerchantSite
import com.paycore.master.merchant.domain.MerchantSiteId
import com.paycore.master.merchant.domain.SiteAssignment
import com.paycore.master.merchant.domain.SiteChannel
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/*
 * application.required 저장 Port 구현(JPA). 공통 관례(payment-api §9와 같다):
 * - `@Transactional(MANDATORY)`: application이 연 로컬 TX 안에서만 동작한다.
 * - 쓰기 끝에 flush해 제약 위반·version 충돌을 Port 안에서 [PersistenceFailure]로 드러낸다(flush ≠ commit: rollback되면 사라진다).
 * - 새 행은 persist만(Spring Data save는 merge가 될 수 있다 — append-only·insert-only 표에서 UPDATE 권한 없음 1142).
 * - 잠금 읽기 [lockRow]: `EntityManager.refresh(row, PESSIMISTIC_WRITE)` = `SELECT … FOR UPDATE`로 다시 읽어 **이미 영속성 컨텍스트에 있던
 *   Entity도 최신 커밋 값**으로 덮는다(같은 TX의 앞선 비잠금 읽기가 오래된 값을 남기지 않게 — 오래된 version으로 갱신하면 낙관적 충돌이 난다).
 */

internal fun <T : Any> EntityManager.lockRow(type: Class<T>, id: Any): T? {
    val row = find(type, id) ?: return null
    refresh(row, LockModeType.PESSIMISTIC_WRITE)
    return row
}

@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaBusinessStore(private val businesses: BusinessJpaRepository, private val entityManager: EntityManager) : BusinessStore {

    override fun insert(business: Business): Unit = translate(ENTITY) {
        entityManager.persist(MasterPersistenceMapper.businessRow(business))
        entityManager.flush()
    }

    override fun lock(id: BusinessId): Business? = translate(ENTITY) {
        entityManager.lockRow(JpaBusinessEntity::class.java, id.value)?.let(MasterPersistenceMapper::toBusiness)
    }

    override fun findIdByRegistrationNumber(number: BusinessRegistrationNumber): BusinessId? = translate(ENTITY) {
        businesses.findIdByRegistrationNumber(number.value)?.let(::BusinessId)
    }

    override fun find(id: BusinessId): Business? = translate(ENTITY) {
        entityManager.find(JpaBusinessEntity::class.java, id.value)?.let(MasterPersistenceMapper::toBusiness)
    }

    private companion object {
        const val ENTITY = "business"
    }
}

@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaMerchantStore(private val entityManager: EntityManager) : MerchantStore {

    override fun insert(merchant: Merchant): Unit = translate(ENTITY) {
        entityManager.persist(MasterPersistenceMapper.merchantRow(merchant))
        entityManager.flush()
    }

    override fun lock(id: MerchantId): Versioned<Merchant>? = translate(ENTITY) {
        entityManager.lockRow(JpaMerchantEntity::class.java, id.value)?.let {
            Versioned(MasterPersistenceMapper.toMerchant(it), checkNotNull(it.version))
        }
    }

    override fun find(id: MerchantId): Merchant? = translate(ENTITY) {
        entityManager.find(JpaMerchantEntity::class.java, id.value)?.let(MasterPersistenceMapper::toMerchant)
    }

    override fun update(merchant: Merchant, expectedVersion: Long): Long = translate(ENTITY) {
        val row = entityManager.find(JpaMerchantEntity::class.java, merchant.id.value) ?: error("merchant is not stored")
        if (row.version != expectedVersion) throw PersistenceFailure.OptimisticConflict(ENTITY)
        check(row.businessId == merchant.businessId.value) { "a merchant never changes its business" }
        check(merchant.entitlementRevision >= row.entitlementRevision) { "entitlement revision never decreases" }
        // 변경 감지: 관리되는 Entity의 필드만 바꾸면 flush 때 `UPDATE … WHERE version = ?`가 나간다(save 불필요).
        row.entitlementRevision = merchant.entitlementRevision
        entityManager.flush()
        checkNotNull(row.version)
    }

    private companion object {
        const val ENTITY = "merchant"
    }
}

@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaMerchantSiteStore(private val sites: MerchantSiteJpaRepository, private val entityManager: EntityManager) : MerchantSiteStore {

    override fun insert(site: MerchantSite): Unit = translate(ENTITY) {
        entityManager.persist(MasterPersistenceMapper.siteRow(site))
        entityManager.flush()
    }

    override fun find(id: MerchantSiteId): MerchantSite? = translate(ENTITY) {
        entityManager.find(JpaMerchantSiteEntity::class.java, id.value)?.let(MasterPersistenceMapper::toSite)
    }

    override fun findActiveByChannel(channel: SiteChannel): MerchantSiteId? = translate(ENTITY) {
        sites.findActiveByChannel(channel.type.name, channel.identifier)?.let(::MerchantSiteId)
    }

    override fun listByMerchant(merchantId: MerchantId): List<MerchantSite> = translate(ENTITY) {
        sites.findByMerchant(merchantId.value).map(MasterPersistenceMapper::toSite)
    }

    private companion object {
        const val ENTITY = "merchant_site"
    }
}

@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaContractStore(
    private val revisions: ContractRevisionJpaRepository,
    private val scopeSites: ContractRevisionScopeSiteJpaRepository,
    private val approvals: ContractRevisionApprovalJpaRepository,
    private val agreements: ContractServiceAgreementJpaRepository,
    private val entityManager: EntityManager,
) : ContractStore {

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
    }
}

@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaMerchantAccountStore(
    private val accounts: MerchantAccountJpaRepository,
    private val assignments: SiteAssignmentJpaRepository,
    private val entityManager: EntityManager,
) : MerchantAccountStore {

    override fun countByMerchant(merchantId: MerchantId): Long = translate(ENTITY) { accounts.countByMerchant(merchantId.value) }

    override fun findDefault(merchantId: MerchantId): MerchantAccountId? = translate(ENTITY) {
        entityManager.find(JpaDefaultDesignationEntity::class.java, merchantId.value)?.let { MerchantAccountId(it.merchantAccountId) }
    }

    override fun insertFirstDefault(issuance: FirstDefaultIssuance): Unit = translate(ENTITY) {
        val account = issuance.account
        check(issuance.designation.merchantAccountId == account.id && issuance.primaryAssignment.merchantAccountId == account.id) {
            "the issuance parts refer to one MID"
        }
        // FK 순서: MID → Default 지정 → PRIMARY 연결. 하나라도 실패하면 호출자의 TX 전체가 rollback된다(잔존 MID·부분 지정 없음).
        entityManager.persist(MasterPersistenceMapper.accountRow(account))
        entityManager.flush()
        val d = issuance.designation
        entityManager.persist(JpaDefaultDesignationEntity.newRow(d.merchantId.value, d.merchantAccountId.value, d.designatedAt, d.designatedBy.value))
        entityManager.persist(MasterPersistenceMapper.assignmentRow(issuance.primaryAssignment))
        entityManager.flush()
    }

    override fun find(id: MerchantAccountId): MerchantAccount? = translate(ENTITY) {
        entityManager.find(JpaMerchantAccountEntity::class.java, id.value)?.let(MasterPersistenceMapper::toAccount)
    }

    override fun lock(id: MerchantAccountId): MerchantAccount? = translate(ENTITY) {
        entityManager.lockRow(JpaMerchantAccountEntity::class.java, id.value)?.let(MasterPersistenceMapper::toAccount)
    }

    override fun assignmentsOf(id: MerchantAccountId): List<SiteAssignment> = translate(ENTITY) {
        assignments.findByAccount(id.value).map(MasterPersistenceMapper::toAssignment)
    }

    override fun listByMerchant(merchantId: MerchantId): List<MerchantAccount> = translate(ENTITY) {
        accounts.findByMerchant(merchantId.value).map(MasterPersistenceMapper::toAccount)
    }

    private companion object {
        const val ENTITY = "merchant_account"
    }
}

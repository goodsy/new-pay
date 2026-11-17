package com.paycore.master.merchant.adapter.persistence

import com.paycore.master.merchant.adapter.persistence.PersistenceFailures.translate
import com.paycore.master.merchant.application.required.MerchantRepository
import com.paycore.master.merchant.application.required.PersistenceFailure
import com.paycore.master.merchant.application.required.Versioned
import com.paycore.master.merchant.domain.Merchant
import com.paycore.master.merchant.domain.MerchantCode
import com.paycore.master.merchant.domain.MerchantId
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * MERCHANT-CODE-001 MC-1(§3.7.4): 가맹점 코드 배정 행은 `JpaMerchantEntity`의 컬렉션 매핑이 아니라 이 Repository가 따로 읽어(seq 오름차순) Mapper로 조립한다
 * (`JpaContractRepository`의 Revision·승인 행과 같은 방식 — merchant 갱신이 자식 행을 다시 저장하지 않는다). 등록은 merchant 행과 seq 1 행을 같은 TX에 persist하고 flush한다.
 * MC-1b: [update]가 새 배정 행(seq 2 — 1회 변경)만 append하고 merchant version을 올린다(저장된 행은 바꾸지 않는다). PK·UNIQUE 1062는 flush에서 `StoreConstraint`로 번역된다.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaMerchantRepository(private val entityManager: EntityManager) : MerchantRepository {

    override fun insert(merchant: Merchant): Unit = translate(ENTITY) {
        entityManager.persist(MasterPersistenceMapper.merchantRow(merchant))
        merchant.codeAssignments.forEach { entityManager.persist(MasterPersistenceMapper.codeAssignmentRow(merchant.id, it)) }
        entityManager.flush()
    }

    override fun lock(id: MerchantId): Versioned<Merchant>? = translate(ENTITY) {
        entityManager.lockRow(JpaMerchantEntity::class.java, id.value)?.let {
            Versioned(MasterPersistenceMapper.toMerchant(it, assignments(id)), checkNotNull(it.version))
        }
    }

    override fun find(id: MerchantId): Merchant? = translate(ENTITY) {
        entityManager.find(JpaMerchantEntity::class.java, id.value)?.let { MasterPersistenceMapper.toMerchant(it, assignments(id)) }
    }

    override fun findCodeOwner(code: MerchantCode): MerchantId? = translate(ENTITY) {
        entityManager.createQuery(
            "select a.merchantId from JpaMerchantCodeAssignmentEntity a where a.merchantCode = :code", String::class.java,
        ).setParameter("code", code.value).resultList.singleOrNull()?.let(::MerchantId)
    }

    override fun update(merchant: Merchant, expectedVersion: Long): Long = translate(ENTITY) {
        val row = entityManager.find(JpaMerchantEntity::class.java, merchant.id.value) ?: error("merchant is not stored")
        if (row.version != expectedVersion) throw PersistenceFailure.OptimisticConflict(ENTITY)
        check(row.businessId == merchant.businessId.value) { "a merchant never changes its business" }
        check(merchant.entitlementRevision >= row.entitlementRevision) { "entitlement revision never decreases" }
        // MC-1b(§3.7.3): 배정 행은 append-only — 저장된 행은 그대로(접두 일치)이고 새 행(seq 2)만 persist한다.
        val stored = assignments(merchant.id).map(MasterPersistenceMapper::toCodeAssignment)
        val now = merchant.codeAssignments
        check(now.size >= stored.size && now.take(stored.size).map { it.seq to it.code } == stored.map { it.seq to it.code }) { "code assignments are append-only" }
        val appended = now.drop(stored.size)
        appended.forEach { entityManager.persist(MasterPersistenceMapper.codeAssignmentRow(merchant.id, it)) }
        // 변경 감지: 관리되는 Entity의 필드만 바꾸면 flush 때 `UPDATE … WHERE version = ?`가 나간다(save 불필요).
        val revisionChanged = row.entitlementRevision != merchant.entitlementRevision
        row.entitlementRevision = merchant.entitlementRevision
        // 자식 행만 늘어도 merchant version을 올린다(같은 가맹점의 다른 쓰기와 직렬화 증거 — §3.7.3, JpaContractRepository와 같은 방식).
        // MERCHANT-CODE-001 MC-4(리뷰 수정 1 — MINOR-4 DB 시험에서 발견): 코드 변경이 같은 TX에서 entitlement_revision도 올리면 그 변경 감지 UPDATE가 이미 version + 1을 한다.
        // 그때 FORCE_INCREMENT를 더하면 version이 +2가 된다(MC-1b-3 "merchant version +1" 위반) → 열 변경이 없을 때만 강제 증가한다(어느 경우든 정확히 +1).
        if (appended.isNotEmpty() && !revisionChanged) entityManager.lock(row, LockModeType.PESSIMISTIC_FORCE_INCREMENT)
        entityManager.flush()
        checkNotNull(row.version)
    }

    /** 배정 행(seq 오름차순). 잠금 뒤 호출이면 READ COMMITTED의 최신 커밋 값이다. */
    private fun assignments(id: MerchantId): List<JpaMerchantCodeAssignmentEntity> =
        entityManager.createQuery(
            "select a from JpaMerchantCodeAssignmentEntity a where a.merchantId = :id order by a.assignmentSeq", JpaMerchantCodeAssignmentEntity::class.java,
        ).setParameter("id", id.value).resultList

    private companion object {
        const val ENTITY = "merchant"
    }
}

package com.paycore.master.merchant.adapter.persistence

import com.paycore.master.merchant.adapter.persistence.PersistenceFailures.translate
import com.paycore.master.merchant.application.required.ContractIntakeRepository
import com.paycore.master.merchant.application.required.PersistenceFailure
import com.paycore.master.merchant.domain.DesiredService
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.ContractIntake
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 계약 접수 건 저장(MERCHANT-FLOW-001 §4A.15 — JPA 선택의 역할·예·이유·주의는 [JpaContractIntakeEntity]).
 *
 * - `@Transactional(MANDATORY)`: 사업자 행을 잠근 Application TX 안에서만 동작한다(잠금 순서 사업자 → 접수 건).
 * - [latest]: 잠금 없는 일반 SELECT(순번 내림차순 첫 행). 같은 사업자의 접수 건을 바꾸는 모든 경로가 사업자 행을 먼저 잠그므로 잠금 뒤 읽은 값이 최신 커밋이다.
 * - [insert]: persist + flush — 열린 건 중복(uk_cin_open — V21)·순번 중복(PK) 1062가 Port 안에서 [PersistenceFailure.UniqueConflict]로 드러난다. 종류·대상 열은 INSERT 때만 쓴다.
 * - [update]: 저장된 version = 전이 전 version인지 확인(결함 방어 — 잠금 우회 시 덮어쓰지 않는다) → 변경 감지 UPDATE(`@Version` +1) → 저장 뒤 version 확인.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaContractIntakeRepository(
    private val intakes: ContractIntakeJpaRepository,
    private val entityManager: EntityManager,
) : ContractIntakeRepository {

    override fun latest(businessId: BusinessId): ContractIntake? = translate(ENTITY) {
        intakes.findFirstByBusinessIdOrderByIntakeSeqDesc(businessId.value)?.let(ContractIntakePersistenceMapper::toIntake)
    }

    override fun find(businessId: BusinessId, seq: Int): ContractIntake? = translate(ENTITY) {
        entityManager.find(JpaContractIntakeEntity::class.java, JpaContractIntakeEntity.Key(businessId.value, seq))
            // 같은 TX에 앞서 관리되던 행이면 최신 커밋으로 다시 읽는다(ARCHITECTURE-CONVENTIONS '잠금 읽기' — 사업자 행 잠금 뒤 호출).
            ?.also { entityManager.refresh(it) }
            ?.let(ContractIntakePersistenceMapper::toIntake)
    }

    override fun insert(intake: ContractIntake): Unit = translate(ENTITY) {
        check(intake.version == 0L) { "a new intake starts at version 0" }
        val row = ContractIntakePersistenceMapper.newRow(intake)
        entityManager.persist(row)
        entityManager.flush()
        check(row.version == intake.version) { "a new intake starts at its domain version" }
    }

    override fun update(intake: ContractIntake): Unit = translate(ENTITY) {
        val key = JpaContractIntakeEntity.Key(intake.businessId.value, intake.seq)
        val row = entityManager.find(JpaContractIntakeEntity::class.java, key) ?: error("intake is not stored")
        if (row.version != intake.version - 1) throw PersistenceFailure.OptimisticConflict(ENTITY)
        check(row.migrated == intake.migrated) { "the migration flag never changes" }
        check(row.kind == intake.kind.name && row.targetContractSeq == intake.targetContractSeq) { "kind and target never change" }
        row.applyTransition(intake.status.name, intake.updatedAt, intake.updatedBy.value)
        entityManager.flush()
        check(row.version == intake.version) { "intake version must advance exactly once per transition" }
    }

    override fun updateItems(intake: ContractIntake): Unit = translate(ENTITY) {
        val key = JpaContractIntakeEntity.Key(intake.businessId.value, intake.seq)
        val row = entityManager.find(JpaContractIntakeEntity::class.java, key) ?: error("intake is not stored")
        if (row.version != intake.version - 1) throw PersistenceFailure.OptimisticConflict(ENTITY)
        check(!row.migrated && !intake.migrated) { "a migrated intake has no intake items to update" }
        check(row.status == intake.status.name) { "an item update never changes the status" }
        check(row.kind == intake.kind.name && row.targetContractSeq == intake.targetContractSeq) { "kind and target never change" }
        val items = checkNotNull(intake.items) { "a non-migrated intake has intake items" }
        row.applyItems(
            items.receivedOn, items.channel.name, items.contractManager.value, DesiredService.CARD_AUTH in items.desiredServices, DesiredService.CARD_KEYIN in items.desiredServices,
            intake.updatedAt, intake.updatedBy.value,
        )
        entityManager.flush()
        check(row.version == intake.version) { "intake version must advance exactly once per item update" }
    }

    private companion object {
        const val ENTITY = "ContractIntake"
    }
}

package com.paycore.admin.approval.adapter.persistence

import com.paycore.admin.approval.application.required.ApprovalPersistenceFailure
import com.paycore.admin.approval.application.required.OwnerExecutionRepository
import com.paycore.admin.approval.application.required.VersionedOwnerExecution
import com.paycore.admin.approval.domain.OwnerExecution
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * [OwnerExecutionRepository] JPA 구현(ADMIN-PAYMENT-OPS-001 §5): 새 행 persist + flush, 잠금 `find` + `refresh(PESSIMISTIC_WRITE)`(`@Lock` JPQL 재조회 대신 —
 * 승인 모듈 관례), 갱신은 잠근 managed Entity의 **변경 열만** 옮기고 flush(dirty checking — Hibernate는 updatable 열만 SET, 불변 열은 `updatable = false` + 열 권한 1143).
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaOwnerExecutionRepository(private val entityManager: EntityManager) : OwnerExecutionRepository {

    override fun insert(execution: OwnerExecution): VersionedOwnerExecution = translate {
        val row = JpaOwnerExecutionEntity.newRow(
            execution.approvalId, execution.requestBody(), execution.fingerprint, execution.approvedAt, execution.executeBefore, execution.executorActorId,
        )
        copyMutable(execution, row)
        entityManager.persist(row)
        entityManager.flush()
        versioned(row)
    }

    override fun find(approvalId: String): OwnerExecution? = translate { entityManager.find(JpaOwnerExecutionEntity::class.java, approvalId)?.let(::toDomain) }

    override fun lock(approvalId: String): VersionedOwnerExecution? = translate {
        val row = entityManager.find(JpaOwnerExecutionEntity::class.java, approvalId) ?: return@translate null
        entityManager.refresh(row, LockModeType.PESSIMISTIC_WRITE)
        versioned(row)
    }

    override fun update(current: VersionedOwnerExecution, next: OwnerExecution): VersionedOwnerExecution = translate {
        require(current.value.approvalId == next.approvalId) { "an update keeps the approval id" }
        val row = entityManager.find(JpaOwnerExecutionEntity::class.java, next.approvalId) ?: throw ApprovalPersistenceFailure.OptimisticConflict()
        if (row.version != current.version) throw ApprovalPersistenceFailure.OptimisticConflict()
        copyMutable(next, row)
        entityManager.flush()
        versioned(row)
    }

    /**
     * 회복 선점(§4.16, 관례 §16): native `FOR UPDATE SKIP LOCKED` — 승인 행(EXECUTING·EXECUTION_UNKNOWN) ⋈ 실행 행(`next_attempt_at ≤ now`)을 다음 시도 순으로 잠그고,
     * 다른 인스턴스가 잡은 행은 기다리지 않고 건너뛴다. 잠근 행은 이 TX 안에서 다시 읽어 `Versioned`로 돌려준다(`@Version`이 두 번째 방어).
     * `now`는 `Instant` 그대로 바인딩한다(TZ-BIND-001): `LocalDateTime` 바인딩은 JVM 기본 시간대만큼 밀려 KST에선 늦게, 음수 offset에선 일찍 선점된다.
     */
    override fun lockDue(now: java.time.Instant, limit: Int): List<VersionedOwnerExecution> = translate {
        @Suppress("UNCHECKED_CAST")
        val ids = entityManager.createNativeQuery(
            "SELECT e.approval_id FROM admin_owner_execution e JOIN admin_approval_request r ON r.approval_id = e.approval_id " +
                "WHERE r.status IN ('EXECUTING', 'EXECUTION_UNKNOWN') AND e.next_attempt_at <= ? ORDER BY e.next_attempt_at, e.approval_id LIMIT ? FOR UPDATE SKIP LOCKED",
        ).setParameter(1, now).setParameter(2, limit).resultList as List<String>
        ids.map { id ->
            val row = requireNotNull(entityManager.find(JpaOwnerExecutionEntity::class.java, id)) { "claimed execution row" }
            entityManager.refresh(row)
            versioned(row)
        }
    }

    private fun copyMutable(e: OwnerExecution, row: JpaOwnerExecutionEntity) {
        row.attemptCount = e.attemptCount
        row.nextAttemptAt = e.nextAttemptAt
        row.lastAttemptAt = e.lastAttemptAt
        row.lastResponseAt = e.lastResponseAt
        row.lastFailure = e.lastFailure
        row.integrityFlag = e.integrityFlag
        row.resendHaltedReason = e.resendHaltedReason
        row.ownerResultCode = e.ownerResultCode
        row.ownerReference = e.ownerReference
    }

    private fun versioned(row: JpaOwnerExecutionEntity) = VersionedOwnerExecution(toDomain(row), requireNotNull(row.version))

    private fun toDomain(r: JpaOwnerExecutionEntity) = OwnerExecution.reconstitute(
        r.approvalId, r.requestBody, r.fingerprint, r.approvedAt, r.executeBefore, r.executorActorId, r.attemptCount, r.nextAttemptAt, r.lastAttemptAt,
        r.lastResponseAt, r.lastFailure, r.integrityFlag, r.resendHaltedReason, r.ownerResultCode, r.ownerReference,
    )

    private fun <T> translate(block: () -> T): T = ApprovalPersistenceTranslation.translate(block)
}

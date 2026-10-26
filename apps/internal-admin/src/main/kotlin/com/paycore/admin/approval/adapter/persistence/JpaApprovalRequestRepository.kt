package com.paycore.admin.approval.adapter.persistence

import com.paycore.admin.approval.application.required.ApprovalPersistenceFailure
import com.paycore.admin.approval.application.required.ApprovalRequestRepository
import com.paycore.admin.approval.application.required.ImmutableApprovalColumns
import com.paycore.admin.approval.application.required.VersionedApproval
import com.paycore.admin.approval.domain.ApprovalKind
import com.paycore.admin.approval.domain.ApprovalRequest
import com.paycore.admin.approval.domain.ApprovalStatus
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.hibernate.StaleStateException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.SQLException
import java.time.Instant

/**
 * [ApprovalRequestRepository] JPA 구현(account와 같은 관례): 새 행은 persist + flush(1062 `pending_key` → DuplicatePending), 잠금은 `find` + `refresh(PESSIMISTIC_WRITE)`,
 * 갱신은 잠근 managed Entity의 결정 열만(dirty checking, `@Version` 조건) + flush. MySQL 1213·1205·version 충돌은 Port 안에서 분류한다.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaApprovalRequestRepository(private val entityManager: EntityManager) : ApprovalRequestRepository {

    override fun insert(request: ApprovalRequest): VersionedApproval = translate {
        val row = JpaApprovalRequestEntity.newRow(
            request.id, request.kind.name, request.targetActorId, request.targetSecurityVersion, request.targetSnapshot, request.grantedRole,
            request.causeApprovalId, request.requestedBy, request.requestedByDisplayName, request.requestedAt, request.expiresAt,
        )
        copyDecision(request, row)
        entityManager.persist(row)
        entityManager.flush()
        versioned(row)
    }

    override fun find(id: String): ApprovalRequest? = translate { entityManager.find(JpaApprovalRequestEntity::class.java, id)?.let(::toDomain) }

    override fun lock(id: String): VersionedApproval? = translate {
        val row = entityManager.find(JpaApprovalRequestEntity::class.java, id) ?: return@translate null
        entityManager.refresh(row, LockModeType.PESSIMISTIC_WRITE)
        versioned(row)
    }

    override fun update(current: VersionedApproval, next: ApprovalRequest): VersionedApproval = translate {
        require(current.value.id == next.id) { "an update keeps the approval id" }
        val row = entityManager.find(JpaApprovalRequestEntity::class.java, next.id) ?: throw ApprovalPersistenceFailure.OptimisticConflict()
        if (row.version != current.version) throw ApprovalPersistenceFailure.OptimisticConflict()
        copyDecision(next, row)
        entityManager.flush()
        versioned(row)
    }

    override fun immutableColumns(id: String): ImmutableApprovalColumns? = translate {
        // 스칼라 JPQL — Entity를 영속성 컨텍스트에 올리지 않으므로 뒤의 lock(find + refresh FOR UPDATE)이 최신 커밋 값을 다시 읽는다(0단계 ↔ 1단계 대조가 의미 있게).
        entityManager.createQuery(
            "SELECT r.kind, r.targetActorId, r.grantedRole FROM JpaApprovalRequestEntity r WHERE r.approvalId = :id", Array<Any?>::class.java,
        ).setParameter("id", id).resultList.firstOrNull()?.let { ImmutableApprovalColumns(it[0] as String, it[1] as String, it[2] as String?) }
    }

    override fun dueForExpiry(now: Instant, limit: Int): List<String> = translate {
        entityManager.createQuery(
            "SELECT r.approvalId FROM JpaApprovalRequestEntity r WHERE r.status = :pending AND r.expiresAt <= :now ORDER BY r.expiresAt, r.approvalId", String::class.java,
        ).setParameter("pending", ApprovalStatus.PENDING.name).setParameter("now", now).setMaxResults(limit).resultList
    }

    private fun copyDecision(request: ApprovalRequest, row: JpaApprovalRequestEntity) {
        row.status = request.status.name
        row.decidedBy = request.decidedBy
        row.decidedByDisplayName = request.decidedByDisplayName
        row.decidedAt = request.decidedAt
        row.resultCode = request.resultCode
    }

    private fun versioned(row: JpaApprovalRequestEntity) = VersionedApproval(toDomain(row), requireNotNull(row.version))

    private fun toDomain(r: JpaApprovalRequestEntity) = ApprovalRequest.reconstitute(
        r.approvalId, ApprovalKind.valueOf(r.kind), r.targetActorId, r.targetSecurityVersion, r.targetSnapshot, r.grantedRole, r.causeApprovalId,
        r.requestedBy, r.requestedByDisplayName, r.requestedAt, r.expiresAt, ApprovalStatus.valueOf(r.status), r.decidedBy, r.decidedByDisplayName,
        r.decidedAt, r.resultCode,
    )

    private fun <T> translate(block: () -> T): T = try {
        block()
    } catch (e: ApprovalPersistenceFailure) {
        throw e
    } catch (e: RuntimeException) {
        val chain = generateSequence<Throwable>(e) { t -> t.cause?.takeIf { it !== t } }.take(MAX_CAUSE_DEPTH).toList()
        if (chain.any { it is OptimisticLockingFailureException || it is jakarta.persistence.OptimisticLockException || it is StaleStateException }) {
            throw ApprovalPersistenceFailure.OptimisticConflict()
        }
        val sql = chain.filterIsInstance<SQLException>().firstOrNull() ?: throw e
        throw when (sql.errorCode) {
            // 키 값은 쓰지 않는다 — 제약 이름만 본다(`pending_key` UNIQUE = 같은 종류·대상 PENDING).
            1062 -> if (sql.message.orEmpty().contains("uk_admin_approval_request_pending")) ApprovalPersistenceFailure.DuplicatePending() else e
            1213 -> ApprovalPersistenceFailure.Deadlock(e)
            1205 -> ApprovalPersistenceFailure.LockTimeout(e)
            else -> if (sql.sqlState?.startsWith("08") == true) ApprovalPersistenceFailure.Unavailable(e) else e
        }
    }

    private companion object {
        const val MAX_CAUSE_DEPTH = 20
    }
}

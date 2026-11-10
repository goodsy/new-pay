package com.paycore.admin.approval.adapter.persistence

import com.paycore.admin.approval.application.required.ApprovalPersistenceFailure
import com.paycore.admin.approval.application.required.ApprovalRequestRepository
import com.paycore.admin.approval.application.required.ImmutableApprovalColumns
import com.paycore.admin.approval.application.required.VersionedApproval
import com.paycore.admin.approval.domain.ApprovalKind
import com.paycore.admin.approval.domain.ApprovalRequest
import com.paycore.admin.approval.domain.ApprovalStatus
import com.paycore.admin.approval.domain.ApprovalTarget
import com.paycore.admin.approval.domain.ApprovalTargetKind
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * [ApprovalRequestRepository] JPA 구현(account와 같은 관례): 새 행은 persist + flush(1062 `pending_key` → DuplicatePending, `unresolved_key` → DuplicateUnresolved — V5),
 * 잠금은 `find` + `refresh(PESSIMISTIC_WRITE)`,
 * 갱신은 잠근 managed Entity의 결정 열만(dirty checking, `@Version` 조건) + flush. MySQL 1213·1205·version 충돌은 Port 안에서 분류한다.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaApprovalRequestRepository(private val entityManager: EntityManager) : ApprovalRequestRepository {

    override fun insert(request: ApprovalRequest): VersionedApproval = translate {
        val target = request.target
        val row = JpaApprovalRequestEntity.newRow(
            request.id, request.kind.name, target.kind.name, (target as? ApprovalTarget.AdminAccount)?.actorId, target.ref.takeIf { target !is ApprovalTarget.AdminAccount },
            target.version, request.targetSnapshot, request.grantedRole, request.causeApprovalId, request.requestedBy, request.requestedByDisplayName,
            request.requestedAt, request.expiresAt,
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
            "SELECT r.kind, r.targetKind, r.targetActorId, r.targetRef, r.grantedRole FROM JpaApprovalRequestEntity r WHERE r.approvalId = :id", Array<Any?>::class.java,
        ).setParameter("id", id).resultList.firstOrNull()?.let {
            ImmutableApprovalColumns(it[0] as String, it[1] as String, requireNotNull((it[2] ?: it[3]) as String?) { "target reference" }, it[4] as String?)
        }
    }

    override fun dueForExpiry(now: Instant, limit: Int): List<String> = translate {
        entityManager.createQuery(
            "SELECT r.approvalId FROM JpaApprovalRequestEntity r WHERE r.status = :pending AND r.expiresAt <= :now ORDER BY r.expiresAt, r.approvalId", String::class.java,
        ).setParameter("pending", ApprovalStatus.PENDING.name).setParameter("now", now).setMaxResults(limit).resultList
    }

    override fun unresolvedCounts(): Map<String, Long> = translate {
        val counts = entityManager.createQuery(
            "SELECT r.status, COUNT(r) FROM JpaApprovalRequestEntity r WHERE r.status IN :statuses GROUP BY r.status", Array<Any?>::class.java,
        ).setParameter("statuses", UNRESOLVED_OWNER).resultList.associate { it[0] as String to (it[1] as Number).toLong() }
        UNRESOLVED_OWNER.associateWith { counts[it] ?: 0L }
    }

    override fun oldestUnknownDecidedAt(): Instant? = translate {
        entityManager.createQuery("SELECT MIN(r.decidedAt) FROM JpaApprovalRequestEntity r WHERE r.status = :unknown", Instant::class.java)
            .setParameter("unknown", ApprovalStatus.EXECUTION_UNKNOWN.name).singleResult
    }

    override fun terminalOwnerApprovals(respondedBefore: Instant): List<ApprovalRequest> = translate {
        entityManager.createQuery(
            "SELECT r FROM JpaApprovalRequestEntity r, JpaOwnerExecutionEntity e WHERE e.approvalId = r.approvalId AND r.kind = :kind AND r.status IN :statuses " +
                "AND e.lastResponseAt < :respondedBefore",
            JpaApprovalRequestEntity::class.java,
        )
            .setParameter("kind", ApprovalKind.OPERATOR_CORRECTION_CANCEL.name)
            .setParameter("respondedBefore", respondedBefore)
            .setParameter("statuses", listOf(ApprovalStatus.EXECUTED.name, ApprovalStatus.OWNER_REJECTED.name))
            .resultList.map(::toDomain)
    }

    private fun copyDecision(request: ApprovalRequest, row: JpaApprovalRequestEntity) {
        row.status = request.status.name
        row.decidedBy = request.decidedBy
        row.decidedByDisplayName = request.decidedByDisplayName
        row.decidedAt = request.decidedAt
        row.resultCode = request.resultCode
    }

    private fun versioned(row: JpaApprovalRequestEntity) = VersionedApproval(toDomain(row), requireNotNull(row.version))

    /** Mapper: 대상 종류별 열 → sealed 대상. 재구성 불변식(ADMIN 소유 종류인데 EXECUTING 등)은 domain이 거부한다(§5). */
    private fun toDomain(r: JpaApprovalRequestEntity) = ApprovalRequest.reconstitute(
        r.approvalId, ApprovalKind.valueOf(r.kind), target(r), r.targetSnapshot, r.grantedRole, r.causeApprovalId,
        r.requestedBy, r.requestedByDisplayName, r.requestedAt, r.expiresAt, ApprovalStatus.valueOf(r.status), r.decidedBy, r.decidedByDisplayName,
        r.decidedAt, r.resultCode,
    )

    private fun target(r: JpaApprovalRequestEntity): ApprovalTarget = when (ApprovalTargetKind.valueOf(r.targetKind)) {
        ApprovalTargetKind.ADMIN_ACCOUNT -> ApprovalTarget.AdminAccount(requireNotNull(r.targetActorId) { "account target" }, r.targetSecurityVersion)
        ApprovalTargetKind.PAYMENT -> ApprovalTarget.Payment(requireNotNull(r.targetRef) { "payment target" }, r.targetSecurityVersion)
        // ACQ-ADMIN-001 B1-A: 파일 대상의 대상 버전 열은 0 고정(권위 대상 버전은 명령 행 pinned_target) — 0이 아니면 재구성 실패(fail-closed).
        ApprovalTargetKind.ACQUISITION_FILE -> ApprovalTarget.AcquisitionFile(requireNotNull(r.targetRef) { "acquisition file target" }).also {
            require(r.targetSecurityVersion == 0L) { "acquisition file target version column is 0" }
        }
        // MERCHANT-OPS-002 B2-2: 가맹점 대상도 대상 버전 0 고정(C-16 — pinned 비교 + MASTER 재판정).
        ApprovalTargetKind.MERCHANT -> ApprovalTarget.Merchant(requireNotNull(r.targetRef) { "merchant target" }).also {
            require(r.targetSecurityVersion == 0L) { "merchant target version column is 0" }
        }
    }

    private fun <T> translate(block: () -> T): T = ApprovalPersistenceTranslation.translate(block)

    private companion object {
        val UNRESOLVED_OWNER = listOf(ApprovalStatus.EXECUTING.name, ApprovalStatus.EXECUTION_UNKNOWN.name)
    }
}

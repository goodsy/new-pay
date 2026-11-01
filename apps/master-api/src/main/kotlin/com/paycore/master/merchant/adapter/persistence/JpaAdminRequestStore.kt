package com.paycore.master.merchant.adapter.persistence

import com.paycore.master.merchant.adapter.persistence.PersistenceFailures.translate
import com.paycore.master.merchant.application.required.AdminRequestScope
import com.paycore.master.merchant.application.required.AdminRequestStore
import com.paycore.master.merchant.application.required.AdminReservation
import com.paycore.master.merchant.application.required.AdminReservationOutcome
import com.paycore.master.merchant.application.required.StoredAdminRequest
import com.paycore.master.merchant.application.required.StoredAdminResult
import com.paycore.master.merchant.domain.MasterRejection
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaAdminRequestStore(
    private val requests: AdminRequestJpaRepository,
    private val entityManager: EntityManager,
) : AdminRequestStore {

    override fun reserveOrLock(reservation: AdminReservation): AdminReservationOutcome = translate(ENTITY) {
        val s = reservation.scope
        // 1) 범위 UNIQUE에 먼저 INSERT(없으면 PENDING 삽입, 있으면 그 행의 잠금 대기) 2) 잠가 읽기. SELECT 후 INSERT가 아니다.
        requests.insertPendingIfAbsent(
            s.operation.code, s.key.value, reservation.requestRecordId, reservation.requestFingerprint, reservation.actor.value, reservation.at,
        )
        val row = lockedRow(s)
        if (row.requestRecordId == reservation.requestRecordId && row.resultKind == PENDING) {
            AdminReservationOutcome.Reserved
        } else {
            AdminReservationOutcome.Existing(stored(row))
        }
    }

    private fun stored(row: JpaAdminRequestEntity) = StoredAdminRequest(
        row.requestRecordId,
        row.requestFingerprint,
        when (row.resultKind) {
            PENDING -> null
            SUCCESS -> StoredAdminResult.Succeeded(checkNotNull(row.resultReference))
            REJECTED -> StoredAdminResult.Rejected(MasterRejection.valueOf(checkNotNull(row.resultCode)), row.resultReference)
            else -> error("unknown admin request result kind")
        },
    )

    override fun complete(scope: AdminRequestScope, result: StoredAdminResult, at: Instant): Unit = translate(ENTITY) {
        val row = lockedRow(scope)
        check(row.resultKind == PENDING) { "admin request already has a result" }
        when (result) {
            is StoredAdminResult.Succeeded -> {
                row.resultKind = SUCCESS
                row.resultReference = result.reference
            }
            is StoredAdminResult.Rejected -> {
                row.resultKind = REJECTED
                row.resultCode = result.rejection.name
                row.resultReference = result.reference
            }
        }
        row.updatedAt = at
        entityManager.flush()
    }

    /** 비잠금 읽기(DEMO-SEED-001 진행 판정). 쓰지 않는다 — 호출자의 읽기 전용 TX 안에서. */
    override fun find(scope: AdminRequestScope): StoredAdminRequest? = translate(ENTITY) {
        requests.findByOperationAndIdempotencyKey(scope.operation.code, scope.key.value)?.let(::stored)
    }

    /** 같은 TX에서 이미 잠갔으면 영속성 컨텍스트의 같은 Entity가 돌아온다(추가 SQL 잠금은 재진입). */
    private fun lockedRow(s: AdminRequestScope): JpaAdminRequestEntity =
        requests.lockByScope(s.operation.code, s.key.value) ?: error("admin request must exist after insert-if-absent")

    private companion object {
        const val ENTITY = "admin_request"
        const val PENDING = "PENDING"
        const val SUCCESS = "SUCCESS"
        const val REJECTED = "REJECTED"
    }
}

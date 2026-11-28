package com.paycore.admin.approval.adapter.persistence

import com.paycore.admin.approval.application.required.ApprovalPersistenceFailure
import org.hibernate.StaleStateException
import org.springframework.dao.OptimisticLockingFailureException
import java.sql.SQLException

/**
 * approval 저장 Port 구현들의 기술 실패 분류(한 곳 — `JpaApprovalRequestRepository`·`JpaOwnerExecutionRepository`·`JpaOperatorCancelCommandStore`).
 * Port 안에서 flush한 뒤 MySQL 오류 번호·제약 이름으로 분류한다(키 값은 쓰지 않는다): 1062 `pending_key` → DuplicatePending, `unresolved_key` → DuplicateUnresolved, `uk_admin_business_change_command_proposal` → DuplicateProposal
 * (ADMIN-PAYMENT-OPS-001 I-14), 1213 → Deadlock, 1205 → LockTimeout, version 충돌 → OptimisticConflict, SQLSTATE 08 → Unavailable. 그 밖은 그대로 전파한다.
 */
internal object ApprovalPersistenceTranslation {
    private const val MAX_CAUSE_DEPTH = 20

    fun <T> translate(block: () -> T): T = try {
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
            1062 -> when {
                sql.message.orEmpty().contains("uk_admin_approval_request_pending") -> ApprovalPersistenceFailure.DuplicatePending()
                sql.message.orEmpty().contains("uk_admin_approval_request_unresolved") -> ApprovalPersistenceFailure.DuplicateUnresolved()
                sql.message.orEmpty().contains("uk_admin_ops_reconciliation_finding") -> ApprovalPersistenceFailure.DuplicateFinding()
                sql.message.orEmpty().contains("uk_admin_business_change_command_proposal") -> ApprovalPersistenceFailure.DuplicateProposal()
                else -> e
            }
            1213 -> ApprovalPersistenceFailure.Deadlock(e)
            1205 -> ApprovalPersistenceFailure.LockTimeout(e)
            else -> if (sql.sqlState?.startsWith("08") == true) ApprovalPersistenceFailure.Unavailable(e) else e
        }
    }
}

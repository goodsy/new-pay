package com.paycore.master.merchant.application

import com.paycore.master.merchant.application.provided.ApprovalDecision
import com.paycore.master.merchant.application.provided.ApprovalDecisionUseCase
import com.paycore.master.merchant.application.provided.ApprovedCommand
import com.paycore.master.merchant.application.provided.DeadlineConfirmationResult
import com.paycore.master.merchant.application.required.AdminRequestStore
import com.paycore.master.merchant.application.required.ApprovalEvidenceStore
import com.paycore.master.merchant.application.required.MasterChangeLog
import com.paycore.master.merchant.application.required.MasterClock
import com.paycore.master.merchant.application.required.MasterIdGenerator
import com.paycore.master.merchant.application.required.MasterOperationalMetrics
import com.paycore.master.merchant.application.required.OutboxStore
import com.paycore.master.merchant.domain.ApprovalEvidence
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.DefaultTransactionDefinition
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * 결정 조회·기한 확인(MERCHANT-OPS-002 §9.4 — C-18 개정). 둘 다 업무 본문을 부르지 않는다.
 *
 * - [decision]: 짧은 **읽기 전용** TX에서 근거 행 PK 한 행을 읽는다(JPA Store — 화면용 복합 조회가 아니라 jOOQ Reader를 두지 않는다, §9.9). 쓰기 0.
 * - [confirmDeadline]: [AdminCommandExecutor.confirmDeadline] — 실행과 같은 operation·키·지문([ApprovedCommands.fingerprintFields]).
 */
@Service
class ApprovalDecisionService(
    private val transactionManager: PlatformTransactionManager,
    requests: AdminRequestStore,
    changeLog: MasterChangeLog,
    outbox: OutboxStore,
    ids: MasterIdGenerator,
    clock: MasterClock,
    metrics: MasterOperationalMetrics,
    publisher: EntitlementSnapshotPublisher,
    private val evidences: ApprovalEvidenceStore,
) : ApprovalDecisionUseCase {

    private val executor = AdminCommandExecutor(transactionManager, requests, changeLog, outbox, ids, clock, metrics, publisher, evidences)

    private val readOnly = DefaultTransactionDefinition().apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRED
        isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
        isReadOnly = true
    }

    override fun decision(approvalId: String): ApprovalDecision {
        require(ApprovalEvidence.APPROVAL_ID.matches(approvalId)) { "approvalId must match apr_<ULID>" }
        val record = readOnly { evidences.find(approvalId) } ?: return ApprovalDecision.None
        return ApprovalDecision.Decided(
            record.operation, record.outcome, record.resultReference, record.resultCode, record.evidence.fingerprint, record.decidedVia,
        )
    }

    override fun confirmDeadline(command: ApprovedCommand): DeadlineConfirmationResult =
        executor.confirmDeadline(command.operation, command.request, command.evidence, command.window, ApprovedCommands.fingerprintFields(command))

    private fun <T> readOnly(block: () -> T): T {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) { "the decision query owns its read-only transaction" }
        val status = transactionManager.getTransaction(readOnly)
        val value = try {
            block()
        } catch (e: Throwable) {
            runCatching { transactionManager.rollback(status) }.exceptionOrNull()?.let(e::addSuppressed)
            throw e
        }
        transactionManager.commit(status)
        return value
    }
}

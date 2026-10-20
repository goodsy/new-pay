package com.paycore.simulator.van.adapter.persistence

import com.paycore.simulator.van.application.required.DuplicateVanRecord
import com.paycore.simulator.van.domain.VanApproval
import com.paycore.simulator.van.domain.VanCancelDeclineCategory
import com.paycore.simulator.van.domain.VanCancelEntry
import com.paycore.simulator.van.domain.VanCancelTerms
import com.paycore.simulator.van.domain.VanDecisionOutcome
import com.paycore.simulator.van.domain.VanDeclineCategory
import com.paycore.simulator.van.domain.VanEntryKind
import com.paycore.simulator.van.domain.VanLedgerEntry
import com.paycore.simulator.van.domain.VanNamespace
import com.paycore.simulator.van.domain.VanPendingDecision
import com.paycore.simulator.van.domain.VanPendingState
import com.paycore.simulator.van.domain.VanPlan
import com.paycore.simulator.van.domain.VanPlanKind
import jakarta.persistence.EntityManager
import java.sql.SQLException

/** 가상 VAN 행 ↔ domain 변환과 저장 공통 처리(adapter.persistence 내부). */
internal object VanPersistence {

    fun toEntry(row: JpaVanLedgerEntryEntity): VanLedgerEntry {
        val namespace = VanNamespace(row.clientId, row.environment, row.institutionAccount, row.operation, row.requestId)
        val approval = row.approvalNumber?.let {
            VanApproval(
                approvalNumber = it, providerTransactionId = checkNotNull(row.providerTransactionId), amountMinor = checkNotNull(row.approvedAmountMinor),
                currency = checkNotNull(row.currency), plan = VanPlan(VanPlanKind.valueOf(checkNotNull(row.planKind)), row.installmentMonths),
                cardType = checkNotNull(row.cardType), institutionTransactionDate = checkNotNull(row.institutionTransactionDate),
                institutionBusinessDate = checkNotNull(row.institutionBusinessDate),
            )
        }
        return VanLedgerEntry.reconstitute(
            entryId = row.entryId, namespace = namespace, kind = VanEntryKind.valueOf(row.kind), payloadHash = row.payloadHash, evidenceRef = row.evidenceRef,
            cardRef = row.cardRef, outcome = row.outcome?.let(VanDecisionOutcome::valueOf), approval = approval,
            declineCategory = row.declineCategory?.let(VanDeclineCategory::valueOf), createdAt = row.createdAt,
        )
    }

    fun newEntryRow(e: VanLedgerEntry): JpaVanLedgerEntryEntity {
        val n = e.namespace
        val a = e.approval
        return JpaVanLedgerEntryEntity.newRow(
            entryId = e.entryId, clientId = n.clientId, environment = n.environment, institutionAccount = n.institutionAccount, operation = n.operation,
            requestId = n.requestId, kind = e.kind.name, payloadHash = e.payloadHash, evidenceRef = e.evidenceRef, cardRef = e.cardRef, outcome = e.outcome?.name,
            approvalNumber = a?.approvalNumber, providerTransactionId = a?.providerTransactionId, approvedAmountMinor = a?.amountMinor, currency = a?.currency,
            planKind = a?.plan?.kind?.name, installmentMonths = a?.plan?.months, cardType = a?.cardType, institutionTransactionDate = a?.institutionTransactionDate,
            institutionBusinessDate = a?.institutionBusinessDate, declineCategory = e.declineCategory?.name, createdAt = e.createdAt,
        )
    }

    fun toCancelEntry(row: JpaVanCancelEntryEntity): VanCancelEntry {
        val namespace = VanNamespace(row.clientId, row.environment, row.institutionAccount, row.operation, row.requestId)
        val terms = row.providerTransactionId?.let {
            VanCancelTerms(
                cancelApprovalNumber = row.cancelApprovalNumber, providerTransactionId = it,
                originalProviderTransactionId = checkNotNull(row.originalProviderTransactionId), cancelledAmountMinor = checkNotNull(row.cancelledAmountMinor),
                currency = checkNotNull(row.currency), institutionTransactionDate = checkNotNull(row.institutionTransactionDate),
                institutionBusinessDate = checkNotNull(row.institutionBusinessDate),
            )
        }
        return VanCancelEntry.reconstitute(
            entryId = row.entryId, namespace = namespace, kind = VanEntryKind.valueOf(row.kind), payloadHash = row.payloadHash, originalRequestId = row.originalRequestId,
            cardRef = row.cardRef, outcome = row.outcome?.let(VanDecisionOutcome::valueOf), terms = terms, approvedSequence = row.approvedSequence,
            declineCategory = row.declineCategory?.let(VanCancelDeclineCategory::valueOf), createdAt = row.createdAt,
        )
    }

    fun newCancelRow(e: VanCancelEntry): JpaVanCancelEntryEntity {
        val n = e.namespace
        val t = e.terms
        return JpaVanCancelEntryEntity.newRow(
            entryId = e.entryId, clientId = n.clientId, environment = n.environment, institutionAccount = n.institutionAccount, operation = n.operation,
            requestId = n.requestId, kind = e.kind.name, payloadHash = e.payloadHash, originalRequestId = e.originalRequestId, cardRef = e.cardRef,
            outcome = e.outcome?.name, cancelApprovalNumber = t?.cancelApprovalNumber, providerTransactionId = t?.providerTransactionId,
            originalProviderTransactionId = t?.originalProviderTransactionId, cancelledAmountMinor = t?.cancelledAmountMinor, currency = t?.currency,
            institutionTransactionDate = t?.institutionTransactionDate, institutionBusinessDate = t?.institutionBusinessDate, approvedSequence = e.approvedSequence,
            declineCategory = e.declineCategory?.name, createdAt = e.createdAt,
        )
    }

    fun toPending(row: JpaVanPendingDecisionEntity): VanPendingDecision = VanPendingDecision.reconstitute(
        pendingId = row.pendingId, namespace = VanNamespace(row.clientId, row.environment, row.institutionAccount, row.operation, row.requestId),
        payloadHash = row.payloadHash, evidenceRef = row.evidenceRef, cardRef = row.cardRef, amountMinor = row.amountMinor, currency = row.currency,
        plan = VanPlan(VanPlanKind.valueOf(row.planKind), row.installmentMonths), cardType = row.cardType, resolveAfter = row.resolveAfter, createdAt = row.createdAt,
        state = VanPendingState.valueOf(row.state), resolvedEntryId = row.resolvedEntryId,
    )

    fun newPendingRow(p: VanPendingDecision): JpaVanPendingDecisionEntity {
        val n = p.namespace
        return JpaVanPendingDecisionEntity.newRow(
            pendingId = p.pendingId, clientId = n.clientId, environment = n.environment, institutionAccount = n.institutionAccount, operation = n.operation,
            requestId = n.requestId, payloadHash = p.payloadHash, evidenceRef = p.evidenceRef, cardRef = p.cardRef, amountMinor = p.amountMinor, currency = p.currency,
            planKind = p.plan.kind.name, installmentMonths = p.plan.months, cardType = p.cardType, resolveAfter = p.resolveAfter, createdAt = p.createdAt,
        )
    }

    /** persist + flush. MySQL 1062(UNIQUE·PK 경합)는 [DuplicateVanRecord] — 호출자가 새 TX에서 재판정한다. */
    fun persistOrDuplicate(entityManager: EntityManager, row: Any) {
        try {
            entityManager.persist(row)
            entityManager.flush()
        } catch (e: RuntimeException) {
            if (isDuplicate(e)) throw DuplicateVanRecord()
            throw e
        }
    }

    private fun isDuplicate(e: Throwable): Boolean =
        generateSequence(e) { cause -> cause.cause?.takeIf { it !== cause } }.take(MAX_CAUSE_DEPTH).filterIsInstance<SQLException>().any { it.errorCode == MYSQL_DUPLICATE_KEY }

    private const val MAX_CAUSE_DEPTH = 20
    private const val MYSQL_DUPLICATE_KEY = 1062
}

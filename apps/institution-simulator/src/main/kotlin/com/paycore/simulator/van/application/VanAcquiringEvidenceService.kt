package com.paycore.simulator.van.application

import com.paycore.simulator.van.application.provided.VanAcquiringEvidenceQuery
import com.paycore.simulator.van.application.provided.VanAuthorizationFact
import com.paycore.simulator.van.application.provided.VanCancellationFact
import com.paycore.simulator.van.application.required.VanAcquiringEvidenceStore
import com.paycore.simulator.van.application.required.VanCancelLedgerStore
import com.paycore.simulator.van.application.required.VanLedgerStore
import com.paycore.simulator.van.domain.VanNamespace
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/** [VanAcquiringEvidenceQuery] 구현(읽기만 — 원장 기록 없음). 같은 참조가 둘 이상이면 null(근거를 골라 쓰지 않는다). */
@Service
@Transactional(propagation = Propagation.MANDATORY)
class VanAcquiringEvidenceService(
    private val evidence: VanAcquiringEvidenceStore,
    private val cancels: VanCancelLedgerStore,
    private val ledger: VanLedgerStore,
) : VanAcquiringEvidenceQuery {

    override fun authorization(clientId: String, providerTransactionId: String): VanAuthorizationFact? {
        val entry = evidence.approvedAuthorizations(clientId, providerTransactionId).singleOrNull() ?: return null
        val approval = checkNotNull(entry.approval) { "an APPROVED decision carries its approval terms" }
        val ns = entry.namespace
        val totals = cancels.approvedTotals(ns.clientId, ns.environment, ns.institutionAccount, ns.requestId)
        return VanAuthorizationFact(
            entry.entryId, approval.providerTransactionId, approval.approvalNumber, approval.amountMinor, approval.currency, approval.institutionTransactionDate,
            totals.sumMinor,
        )
    }

    override fun cancellation(clientId: String, providerTransactionId: String): VanCancellationFact? {
        val entry = evidence.approvedCancellations(clientId, providerTransactionId).singleOrNull() ?: return null
        val terms = checkNotNull(entry.terms) { "an APPROVED cancel decision carries its terms" }
        val ns = entry.namespace
        val original = ledger.find(VanNamespace(ns.clientId, ns.environment, ns.institutionAccount, VanNamespace.AUTHORIZE, checkNotNull(entry.originalRequestId)))
        return VanCancellationFact(
            entry.entryId, original?.entryId, terms.providerTransactionId, terms.originalProviderTransactionId, terms.cancelledAmountMinor, terms.currency,
            terms.institutionTransactionDate,
        )
    }
}

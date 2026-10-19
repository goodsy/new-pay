package com.paycore.simulator.authentication.application

import com.paycore.simulator.authentication.application.provided.AuthenticationEvidenceLookup
import com.paycore.simulator.authentication.application.provided.AuthenticationEvidenceView
import com.paycore.simulator.authentication.application.required.AuthenticationTransactionRepository
import com.paycore.simulator.authentication.domain.TransactionStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** [AuthenticationEvidenceLookup] 구현(읽기 전용 — 호출자 TX에 참여, 없으면 읽기 TX). 증거 참조가 정확히 한 거래에 있을 때만 사본을 준다. */
@Service
class EvidenceLookupService(private val transactions: AuthenticationTransactionRepository) : AuthenticationEvidenceLookup {

    @Transactional(readOnly = true)
    override fun find(evidenceRef: String): AuthenticationEvidenceView? {
        val t = transactions.findByEvidenceRef(evidenceRef).singleOrNull() ?: return null
        val card = t.card
        val r = t.request
        return AuthenticationEvidenceView(
            evidenceRef = evidenceRef,
            clientId = t.clientId,
            authenticated = t.status == TransactionStatus.AUTHENTICATED,
            environment = r.environment,
            merchantRef = r.merchantRef,
            amountMinor = r.amountMinor,
            currency = r.currency,
            planKind = r.plan.kind,
            planMonths = r.plan.months,
            cardRef = card?.ref,
            cardType = card?.cardType,
            issuerCode = card?.issuerCode,
            acquirerCode = card?.acquirerCode,
            providerCode = card?.providerCode,
            validUntil = checkNotNull(t.validUntil) { "an evidence belongs to a completed transaction" },
        )
    }
}

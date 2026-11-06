package com.paycore.financial.acquiring.domain

import java.time.Instant
import java.time.LocalDate

/**
 * 매입 후보 승인 한 건(FINANCIAL 승인 투영 값 — 원승인 고정값)과 판정 시점에 이미 받은 그 원승인의 취소.
 * [held] = 이 승인 자신에게 미해제 보류가 있는가, [paymentHeld] = 같은 Payment의 어느 거래에든 미해제 보류가 있는가(리뷰 MJ-2 — Coordinator 결정).
 * 미해제 = D-FFR-003 RELEASED 해제 기록이 없는 보류.
 */
data class SaleCandidate(
    val transactionId: String,
    val paymentId: String,
    val environment: AcquisitionEnvironment,
    val merchantAccountId: String,
    val acquirerCode: String,
    val amountMinor: Long,
    val currency: String,
    val planKind: String,
    val installmentMonths: Int?,
    val cardType: String,
    val issuerCode: String,
    val approvalNumber: String,
    val providerTransactionId: String,
    val institutionTransactionDate: LocalDate,
    val approvedAt: Instant,
    val held: Boolean,
    val paymentHeld: Boolean,
    val cancellations: List<KnownCancellation>,
) {
    init {
        if (amountMinor < 1) throw AcquisitionContractViolation("an authorization amount is a positive minor-unit integer")
        if (held && !paymentHeld) throw AcquisitionContractViolation("a held authorization makes its payment held")
        if (cancellations.any { it.held } && !paymentHeld) throw AcquisitionContractViolation("a held cancellation makes its payment held")
    }
}

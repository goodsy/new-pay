package com.paycore.financial.acquiring.domain

/** 판정 시점에 FINANCIAL이 받은 취소 한 건(원승인 [SaleCandidate]의 것). [held] = 미해제 보류가 있는가. */
data class KnownCancellation(val transactionId: String, val amountMinor: Long, val currency: String, val held: Boolean) {
    init {
        if (amountMinor < 1) throw AcquisitionContractViolation("a cancellation amount is a positive minor-unit integer")
    }
}

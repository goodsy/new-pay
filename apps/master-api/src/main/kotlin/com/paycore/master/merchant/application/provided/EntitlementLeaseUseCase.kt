package com.paycore.master.merchant.application.provided

/** lease 갱신 한 회차(명세 §3.3): 잔여가 renewBefore보다 짧은 Merchant마다 현재 상태로 재발행(같은 revision, leaseSeq+1, priority LOW). */
interface EntitlementLeaseUseCase {
    fun renewDueLeases(): RenewalReport
}

data class RenewalReport(val candidates: Int, val renewed: Int, val skipped: Int)

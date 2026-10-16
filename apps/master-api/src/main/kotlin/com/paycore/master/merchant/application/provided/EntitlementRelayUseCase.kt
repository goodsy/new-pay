package com.paycore.master.merchant.application.provided

/** Outbox Relay 한 회차(명세 §4.7). 스케줄 adapter와 시험이 부른다. 외부 호출은 DB TX 밖. */
interface EntitlementRelayUseCase {
    fun relayOnce(): RelayReport
}

data class RelayReport(val claimed: Int, val acknowledged: Int, val retried: Int, val quarantined: Int, val claimLost: Int, val failed: Int = 0)

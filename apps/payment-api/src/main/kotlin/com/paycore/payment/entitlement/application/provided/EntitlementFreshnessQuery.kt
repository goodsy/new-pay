package com.paycore.payment.entitlement.application.provided

/** 권리 투영 신선도(운영 조회·지표, jOOQ). */
interface EntitlementFreshnessQuery {
    fun overview(): FreshnessOverview
}

data class FreshnessOverview(
    val heads: Long,
    val blockedHeads: Long,
    val expiredHeads: Long,
    val minLeaseRemainingMillis: Long?,
)

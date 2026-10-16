package com.paycore.master.merchant.domain

import java.time.Instant

/** 결제 채널(웹·앱). Merchant 1:N. 같은 Merchant 소속 MID와만 연결된다(구조 §8). */
class MerchantSite private constructor(
    val id: MerchantSiteId,
    val merchantId: MerchantId,
    val channel: SiteChannel,
    val lifecycle: Lifecycle,
    val createdAt: Instant,
    val createdBy: ActorRef,
) {
    fun requireActive() {
        if (lifecycle != Lifecycle.ACTIVE) throw MasterRuleViolation(MasterRejection.SITE_NOT_ACTIVE)
    }

    override fun toString(): String = "MerchantSite(id=${id.value}, merchantId=${merchantId.value}, lifecycle=$lifecycle)"

    companion object {
        fun register(id: MerchantSiteId, merchant: Merchant, channel: SiteChannel, at: Instant, by: ActorRef): MerchantSite {
            merchant.requireActive()
            return MerchantSite(id, merchant.id, channel, Lifecycle.ACTIVE, at, by)
        }

        fun reconstitute(
            id: MerchantSiteId,
            merchantId: MerchantId,
            channel: SiteChannel,
            lifecycle: Lifecycle,
            createdAt: Instant,
            createdBy: ActorRef,
        ) = MerchantSite(id, merchantId, channel, lifecycle, createdAt, createdBy)
    }
}

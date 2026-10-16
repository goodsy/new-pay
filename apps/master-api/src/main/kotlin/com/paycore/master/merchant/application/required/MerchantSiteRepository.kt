package com.paycore.master.merchant.application.required

import com.paycore.master.merchant.domain.MerchantId
import com.paycore.master.merchant.domain.MerchantSite
import com.paycore.master.merchant.domain.MerchantSiteId
import com.paycore.master.merchant.domain.SiteChannel

interface MerchantSiteRepository {
    fun insert(site: MerchantSite)

    fun find(id: MerchantSiteId): MerchantSite?

    /** 같은 채널 식별값의 활성 Site(어느 Merchant든, D-MD-008). 잠금 없음 — 경합은 UNIQUE 충돌 → 재판정. */
    fun findActiveByChannel(channel: SiteChannel): MerchantSiteId?

    fun listByMerchant(merchantId: MerchantId): List<MerchantSite>
}

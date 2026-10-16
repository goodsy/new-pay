package com.paycore.master.merchant.application.required

import com.paycore.master.merchant.domain.MerchantSiteId
import com.paycore.master.merchant.domain.SiteReviewDecision

/** Site 심사 결정(append-only). */
interface SiteReviewStore {
    fun history(siteId: MerchantSiteId): List<SiteReviewDecision>

    fun append(decision: SiteReviewDecision, requestRecordId: String)
}

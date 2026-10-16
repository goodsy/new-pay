package com.paycore.master.merchant.application.required

import com.paycore.master.merchant.application.provided.MerchantPropagationView
import com.paycore.master.merchant.application.provided.PropagationOverview
import com.paycore.master.merchant.domain.MerchantId
import java.time.Instant

/** 전파 조회(CQRS 조회 측, jOOQ adapter.query — 명세 §4.8). */
interface EntitlementPropagationReader {
    fun merchant(merchantId: MerchantId, now: Instant): MerchantPropagationView?

    fun overview(now: Instant): PropagationOverview
}

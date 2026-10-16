package com.paycore.payment.entitlement.application.required

import com.paycore.payment.entitlement.application.provided.FreshnessOverview
import java.time.Instant

/** 신선도 조회(jOOQ adapter.query). */
interface EntitlementFreshnessReader {
    fun overview(now: Instant): FreshnessOverview
}

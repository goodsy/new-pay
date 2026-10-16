package com.paycore.payment.entitlement.application

import com.paycore.payment.entitlement.application.provided.EntitlementFreshnessQuery
import com.paycore.payment.entitlement.application.provided.FreshnessOverview
import com.paycore.payment.entitlement.application.required.EntitlementClock
import com.paycore.payment.entitlement.application.required.EntitlementFreshnessReader
import org.springframework.stereotype.Service
import java.time.temporal.ChronoUnit

/** 신선도 조회 use case(jOOQ 읽기 Port에 위임, 시각은 서버 clock). */
@Service
class EntitlementFreshnessService(private val reader: EntitlementFreshnessReader, private val clock: EntitlementClock) : EntitlementFreshnessQuery {
    override fun overview(): FreshnessOverview = reader.overview(clock.now().truncatedTo(ChronoUnit.MILLIS))
}

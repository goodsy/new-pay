package com.paycore.master.merchant.application

import com.paycore.master.merchant.application.provided.EntitlementPropagationQuery
import com.paycore.master.merchant.application.provided.MerchantPropagationView
import com.paycore.master.merchant.application.provided.PropagationOverview
import com.paycore.master.merchant.application.required.EntitlementPropagationReader
import com.paycore.master.merchant.application.required.MasterClock
import com.paycore.master.merchant.domain.MerchantId
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.temporal.ChronoUnit

/** 전파 조회 use case(jOOQ 읽기 Port에 위임, 시각은 서버 clock). */
@Service
class EntitlementPropagationQueryService(
    private val reader: EntitlementPropagationReader,
    private val clock: MasterClock,
) : EntitlementPropagationQuery {
    override fun merchant(merchantId: MerchantId): MerchantPropagationView? = reader.merchant(merchantId, now())

    override fun overview(): PropagationOverview = reader.overview(now())

    private fun now(): Instant = clock.now().truncatedTo(ChronoUnit.MILLIS)
}

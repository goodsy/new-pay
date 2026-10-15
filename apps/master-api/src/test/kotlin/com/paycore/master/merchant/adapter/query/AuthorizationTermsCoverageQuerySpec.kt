package com.paycore.master.merchant.adapter.query

import com.paycore.master.MasterDbTest
import com.paycore.master.merchant.adapter.integration.MicrometerMasterOperationalMetrics
import com.paycore.master.merchant.application.AuthorizationTermsCommandFixture
import com.paycore.master.merchant.application.EntitlementCommandFixture
import com.paycore.master.merchant.application.MutableMasterClock
import com.paycore.master.merchant.application.provided.AuthorizationTermsCoverageQuery
import com.paycore.master.merchant.application.provided.MerchantAuthorizationTermsUseCase
import com.paycore.master.merchant.application.provided.MerchantEntitlementUseCase
import com.paycore.master.merchant.application.provided.MerchantOnboardingUseCase
import com.paycore.master.merchant.application.provided.TermsCoverageOverview
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry

/**
 * 승인 시작 조건 누락 점검(MASTER-SNAPSHOT-002 명세 §9, CQRS jOOQ 조회)을 실제 MySQL에서 확인한다. 같은 DB를 다른 시험과 공유하므로 Merchant 하나를
 * 만들기 전후의 **차이**로 본다(같은 시계 시각). 결과는 비권위 점검값이다.
 */
@MasterDbTest
class AuthorizationTermsCoverageQuerySpec(
    private val onboarding: MerchantOnboardingUseCase,
    private val entitlement: MerchantEntitlementUseCase,
    private val terms: MerchantAuthorizationTermsUseCase,
    private val query: AuthorizationTermsCoverageQuery,
    private val clock: MutableMasterClock,
    private val registry: MeterRegistry,
) : BehaviorSpec({

    afterEach { clock.reset() }

    fun delta(a: TermsCoverageOverview, b: TermsCoverageOverview) = listOf(
        a.enabledCurrenciesWithoutLimitPolicy - b.enabledCurrenciesWithoutLimitPolicy,
        a.enabledCurrenciesWithoutFeeAssignment - b.enabledCurrenciesWithoutFeeAssignment,
        a.enabledCurrenciesWithoutSettlementAssignment - b.enabledCurrenciesWithoutSettlementAssignment,
        a.activeMethodsWithoutCardRoute - b.activeMethodsWithoutCardRoute,
    )

    Given("a newly entitled merchant (KRW enabled, CARD active) without authorization terms") {
        Then("it adds one missing limit, fee, settlement and card route; after the terms only the limit (effective from the next Seoul midnight) is still missing now") {
            val before = query.overview()
            val e = EntitlementCommandFixture.entitled(onboarding, entitlement)
            delta(query.overview(), before) shouldBe listOf(1L, 1L, 1L, 1L)
            AuthorizationTermsCommandFixture.ready(terms, e)
            delta(query.overview(), before) shouldBe listOf(1L, 0L, 0L, 0L)
        }

        Then("the gauges are registered by missing kind only (no MID or currency label)") {
            listOf("LIMIT_POLICY", "FEE_ASSIGNMENT", "SETTLEMENT_ASSIGNMENT", "CARD_ROUTE").map { kind ->
                registry.find(MicrometerMasterOperationalMetrics.TERMS_MISSING).tags("kind", kind).gauge()?.id?.tags?.map { it.key }
            } shouldBe List(4) { listOf("kind") }
        }
    }
})

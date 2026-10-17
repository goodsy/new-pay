package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.required.SiteWebHostPort
import com.paycore.payment.payment.application.required.SiteWebHostResult
import com.paycore.payment.payment.domain.MerchantAccountId
import com.paycore.payment.payment.domain.MerchantSiteId
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * **시험 전용** Site WEB host Port 대역(운영 구현은 `payment.adapter.integration.EntitlementSiteWebHostAdapter` — 실 Snapshot v3 시험은 `SiteWebHostQuerySpec`).
 * 기본으로 Demo host `shop.example.test`(Snapshot 버전 Demo)를 준다. 시험은 자기 MID(시험마다 고유)에 대해서만 [respondFor]로 응답을 바꾼다(공유 컨텍스트에서 섞이지 않게).
 */
class ScriptedSiteWebHostPort : SiteWebHostPort {
    private val answers = ConcurrentHashMap<MerchantAccountId, (MerchantSiteId, Instant) -> SiteWebHostResult>()

    override fun resolve(authenticatedMid: MerchantAccountId, merchantSiteId: MerchantSiteId, evaluatedAt: Instant): SiteWebHostResult =
        (answers[authenticatedMid] ?: ::demo)(merchantSiteId, evaluatedAt)

    fun respondFor(mid: MerchantAccountId, answer: (MerchantSiteId, Instant) -> SiteWebHostResult) {
        answers[mid] = answer
    }

    companion object {
        const val DEMO_HOST = "shop.example.test"
        const val DEMO_SNAPSHOT_VERSION = "es1:demo-merchant:3"

        fun demo(@Suppress("UNUSED_PARAMETER") site: MerchantSiteId, at: Instant): SiteWebHostResult =
            SiteWebHostResult.WebHost(DEMO_HOST, DEMO_SNAPSHOT_VERSION, at.plus(Duration.ofMinutes(5)))
    }
}

@TestConfiguration(proxyBeanMethods = false)
class SiteWebHostTestConfig {
    @Bean
    @Primary
    fun scriptedSiteWebHostPort(): ScriptedSiteWebHostPort = ScriptedSiteWebHostPort()
}

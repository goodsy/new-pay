package com.paycore.payment.payment.adapter.integration

import com.paycore.payment.payment.application.provided.MerchantNotificationBacklogQuery
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/**
 * fan-out·delivery 적체 gauge(WEBHOOK-001 §11·n-3·S5): 비종결 실패 행 수(> 0이면 경보 — 같은 Payment의 뒤 통지가 막혀 있다)와 최고 연령, PENDING 행 수와 최고 연령(fan-out 지연).
 * label 없음(ID·MID 없음). 조회 시점마다 provided 조회로 계산한다(취소 적체 gauge와 같은 방식). 경보 임계값은 운영값(LIVE Gate).
 */
@Component
class MerchantNotificationBacklogGauges(registry: MeterRegistry, private val query: MerchantNotificationBacklogQuery) {
    init {
        Gauge.builder(FAILED) { query.backlog().failedRows.toDouble() }.register(registry)
        Gauge.builder(FAILED_OLDEST_AGE) { query.backlog().oldestFailedAgeSeconds.toDouble() }.register(registry)
        Gauge.builder(PENDING) { query.backlog().pendingRows.toDouble() }.register(registry)
        Gauge.builder(PENDING_OLDEST_AGE) { query.backlog().oldestPendingAgeSeconds.toDouble() }.register(registry)
        // S5(§11): 전달 적체·지연·포기(포기 수는 종결 누적 — 증가가 경보 근거, 임계값은 운영값).
        Gauge.builder(DELIVERY_PENDING) { query.backlog().deliveryPendingRows.toDouble() }.register(registry)
        Gauge.builder(DELIVERY_OLDEST_DUE_AGE) { query.backlog().oldestDeliveryDueAgeSeconds.toDouble() }.register(registry)
        Gauge.builder(DELIVERY_GAVE_UP) { query.backlog().gaveUpRows.toDouble() }.register(registry)
    }

    private companion object {
        const val FAILED = "paycore.webhook.fanout.failed"
        const val FAILED_OLDEST_AGE = "paycore.webhook.fanout.failed.oldest_age_seconds"
        const val PENDING = "paycore.webhook.fanout.pending"
        const val PENDING_OLDEST_AGE = "paycore.webhook.fanout.pending.oldest_age_seconds"
        const val DELIVERY_PENDING = "paycore.webhook.delivery.pending"
        const val DELIVERY_OLDEST_DUE_AGE = "paycore.webhook.delivery.pending.oldest_due_age_seconds"
        const val DELIVERY_GAVE_UP = "paycore.webhook.delivery.gave_up"
    }
}

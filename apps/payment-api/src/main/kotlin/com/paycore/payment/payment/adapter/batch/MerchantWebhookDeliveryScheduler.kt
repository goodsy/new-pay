package com.paycore.payment.payment.adapter.batch

import com.paycore.payment.payment.application.provided.DeliverMerchantWebhooksCommand
import com.paycore.payment.payment.application.provided.DeliverMerchantWebhooksUseCase
import com.paycore.payment.payment.application.provided.MerchantWebhookDeliveryReport
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * delivery trigger(WEBHOOK-001 §5.0 — 다른 주기 작업과 같은 관례: `@Scheduled`, provided Use Case만). `enabled: false`면 무동작(시험은 Use Case를 직접 부른다).
 * counter `paycore.webhook.delivery{result=delivered·already_delivered·retry·gave_up·claim_lost·record_failed·released_lease_short·blocked}`(label은 result 하나 — ID·URL·MID 없음),
 * timer `paycore.webhook.delivery.duration`(보낸 시도마다, label 없음). `gave_up`은 경보 대상(D-WH-006). `blocked`는 retry·gave_up과 겹치는 원인 축이다(최종 검토 m-1).
 * `released_lease_short`는 실제로 되돌린 것만, 되돌림이 false면 `claim_lost`, 되돌림 저장소 실패는 `record_failed`에 센다(최종 검토 #3 n-1 — report 필드를 그대로 쓴다).
 */
@Component
class MerchantWebhookDeliveryScheduler(
    private val useCase: DeliverMerchantWebhooksUseCase,
    private val settings: MerchantWebhookDeliveryJobSettings,
    private val registry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(MerchantWebhookDeliveryScheduler::class.java)

    @Scheduled(fixedDelayString = "\${paycore.webhook.delivery.job.fixed-delay}")
    fun onSchedule() {
        if (!settings.enabled) return
        runCatching { runOnce() }.onFailure { log.warn("operation=MerchantWebhookDelivery result=ROUND_FAILED failure={}", it::class.simpleName) }
    }

    fun runOnce(): MerchantWebhookDeliveryReport {
        val r = useCase.deliver(DeliverMerchantWebhooksCommand(settings.batchSize))
        count("delivered", r.delivered)
        count("already_delivered", r.alreadyDelivered)
        count("retry", r.retried)
        count("gave_up", r.gaveUp)
        count("claim_lost", r.claimLost)
        count("record_failed", r.recordFailed)
        count("released_lease_short", r.released)
        count("blocked", r.blocked)
        val timer = registry.timer(DURATION)
        r.attemptDurations.forEach { timer.record(it) }
        return r
    }

    private fun count(result: String, n: Int) {
        if (n > 0) registry.counter(METRIC, "result", result).increment(n.toDouble())
    }

    private companion object {
        const val METRIC = "paycore.webhook.delivery"
        const val DURATION = "paycore.webhook.delivery.duration"
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MerchantWebhookDeliveryJobSettings::class)
class MerchantWebhookDeliveryJobConfiguration

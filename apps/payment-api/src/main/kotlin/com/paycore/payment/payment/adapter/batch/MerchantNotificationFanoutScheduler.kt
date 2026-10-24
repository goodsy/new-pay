package com.paycore.payment.payment.adapter.batch

import com.paycore.payment.payment.application.provided.FanOutMerchantNotificationsCommand
import com.paycore.payment.payment.application.provided.FanOutMerchantNotificationsUseCase
import com.paycore.payment.payment.application.provided.MerchantNotificationFanoutReport
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * fan-out trigger(WEBHOOK-001 §5.0 — 다른 주기 작업과 같은 관례: `@Scheduled`, provided Use Case만 부른다). `enabled: false`면 아무것도 하지 않는다(시험은 Use Case를 직접 부른다).
 * 결과를 counter `paycore.webhook.fanout{result=notified·no_endpoint·suppressed·not_applicable·failed·deferred}`로 센다(label에 ID 없음). `failed`는 경보 대상이다(m-3).
 */
@Component
class MerchantNotificationFanoutScheduler(
    private val useCase: FanOutMerchantNotificationsUseCase,
    private val settings: MerchantNotificationFanoutJobSettings,
    private val registry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(MerchantNotificationFanoutScheduler::class.java)

    @Scheduled(fixedDelayString = "\${paycore.webhook.fanout.fixed-delay}")
    fun onSchedule() {
        if (!settings.enabled) return
        runCatching { runOnce() }.onFailure { log.warn("operation=MerchantNotificationFanout result=ROUND_FAILED failure={}", it::class.simpleName) }
    }

    fun runOnce(): MerchantNotificationFanoutReport {
        val r = useCase.fanOut(FanOutMerchantNotificationsCommand(settings.batchSize, settings.failedRetryInterval))
        count("notified", r.notified - r.noEndpoint)
        count("no_endpoint", r.noEndpoint)
        count("suppressed", r.suppressed)
        count("not_applicable", r.notApplicable)
        count("failed", r.failed)
        count("deferred", r.deferred)
        return r
    }

    private fun count(result: String, n: Int) {
        if (n > 0) registry.counter(METRIC, "result", result).increment(n.toDouble())
    }

    private companion object {
        const val METRIC = "paycore.webhook.fanout"
    }
}

/** 설정 바인딩(스케줄링 활성화는 기존 `@EnableScheduling` 구성). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MerchantNotificationFanoutJobSettings::class)
class MerchantNotificationFanoutJobConfiguration

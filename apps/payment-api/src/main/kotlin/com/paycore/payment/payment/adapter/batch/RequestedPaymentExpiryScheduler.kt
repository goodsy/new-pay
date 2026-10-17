package com.paycore.payment.payment.adapter.batch

import com.paycore.payment.payment.application.provided.ExpireElapsedRequestedPaymentsCommand
import com.paycore.payment.payment.application.provided.ExpireElapsedRequestedPaymentsUseCase
import com.paycore.payment.payment.application.provided.ExpiryRunResult
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.MDC
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * 결제창 만료 주기 작업 trigger(AUTHN-001 §7.5.4, AN-28 — Spring `@Scheduled`, Spring Batch 아님). provided Use Case만 부른다(ArchUnit `batchAdapterUsesOnlyApplication`).
 * 회차마다 새 회차 ID(UUID — 로그 MDC `correlationId`)와 설정 batch 크기로 부르고 결과를 counter `paycore.payment.checkout_expiry{result=expired·no_change·failed·defect}`(defect = 저장소 일시 실패가 아닌 예외 — 경보 대상, 검토 #4 n-3)로 센다
 * (label에 ID 없음). `enabled: false`면 일정이 와도 아무것도 하지 않는다(시험은 Use Case를 직접 부른다).
 */
@Component
class RequestedPaymentExpiryScheduler(
    private val useCase: ExpireElapsedRequestedPaymentsUseCase,
    private val settings: CheckoutExpiryJobSettings,
    private val registry: MeterRegistry,
) {

    @Scheduled(fixedDelayString = "\${paycore.payment.checkout-expiry.job.fixed-delay}")
    fun onSchedule() {
        if (settings.enabled) runOnce()
    }

    fun runOnce(): ExpiryRunResult {
        val runId = UUID.randomUUID().toString()
        MDC.put(CORRELATION_ID_MDC_KEY, runId)
        try {
            val result = useCase.expireElapsed(ExpireElapsedRequestedPaymentsCommand(runId, settings.batchSize))
            count("expired", result.expired)
            count("no_change", result.noChange)
            count("failed", result.failed)
            count("defect", result.defects)
            return result
        } finally {
            MDC.remove(CORRELATION_ID_MDC_KEY)
        }
    }

    private fun count(result: String, n: Int) {
        registry.counter(METRIC, "result", result).increment(n.toDouble())
    }

    private companion object {
        const val METRIC = "paycore.payment.checkout_expiry"
        const val CORRELATION_ID_MDC_KEY = "correlationId"
    }
}

/** 설정 바인딩과 스케줄링(만료 작업 trigger만 쓴다). */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(CheckoutExpiryJobSettings::class)
class CheckoutExpiryJobConfiguration

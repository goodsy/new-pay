package com.paycore.payment.payment.adapter.batch

import com.paycore.payment.payment.application.provided.RecoverCancellationsCommand
import com.paycore.payment.payment.application.provided.RecoverCancellationsUseCase
import com.paycore.payment.payment.application.provided.RecoveryRunResult
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.MDC
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * 취소 회복 주기 작업 trigger(CANCEL-001 §7 — 승인 회복 trigger와 같은 관례: Spring `@Scheduled`, provided Use Case만 부른다 — ArchUnit `batchAdapterUsesOnlyApplication`).
 * 회차마다 새 회차 ID(UUID — 로그 MDC `correlationId`)와 설정 batch 크기로 부르고 결과를 counter
 * `paycore.payment.cancellation_recovery{result=fenced·lease_expired·inquiry·concluded·escalated·failed}`로 센다(label에 ID 없음). `enabled: false`면 아무것도 하지 않는다(시험은 Use Case를
 * 직접 부른다). 다중 인스턴스 직렬화는 DB `SKIP LOCKED` 선점이다(분산 lock·Redis 없음 — K14).
 */
@Component
class CancellationRecoveryScheduler(
    private val useCase: RecoverCancellationsUseCase,
    private val settings: CancellationRecoveryJobSettings,
    private val registry: MeterRegistry,
) {

    @Scheduled(fixedDelayString = "\${paycore.cancellation.timing.recovery.fixed-delay}")
    fun onSchedule() {
        if (settings.enabled) runOnce()
    }

    fun runOnce(): RecoveryRunResult {
        val runId = UUID.randomUUID().toString()
        MDC.put(CORRELATION_ID_MDC_KEY, runId)
        try {
            val result = useCase.recover(RecoverCancellationsCommand(runId, settings.batchSize))
            count("fenced", result.fenced)
            count("lease_expired", result.leaseExpired)
            count("inquiry", result.inquiries)
            count("concluded", result.concluded)
            count("escalated", result.escalated)
            count("failed", result.failed)
            return result
        } finally {
            MDC.remove(CORRELATION_ID_MDC_KEY)
        }
    }

    private fun count(result: String, n: Int) {
        registry.counter(METRIC, "result", result).increment(n.toDouble())
    }

    private companion object {
        const val METRIC = "paycore.payment.cancellation_recovery"
        const val CORRELATION_ID_MDC_KEY = "correlationId"
    }
}

/** 설정 바인딩(스케줄링 활성화는 [CheckoutExpiryJobConfiguration]의 `@EnableScheduling`). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CancellationRecoveryJobSettings::class)
class CancellationRecoveryJobConfiguration

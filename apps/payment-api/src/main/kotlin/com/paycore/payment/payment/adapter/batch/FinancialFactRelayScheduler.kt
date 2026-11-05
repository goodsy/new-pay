package com.paycore.payment.payment.adapter.batch

import com.paycore.payment.payment.application.provided.FinancialFactRelayReport
import com.paycore.payment.payment.application.provided.RelayFinancialFactsCommand
import com.paycore.payment.payment.application.provided.RelayFinancialFactsUseCase
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * PAYMENT → FINANCIAL 결제 사실 Relay trigger(FIN-FACT-RELAY-001 §4.4 — 다른 주기 작업과 같은 관례: `@Scheduled`, provided Use Case만 부른다, Spring Batch 아님).
 * `enabled: false`면 아무것도 하지 않는다(시험은 Use Case를 직접 부른다). 한 회차의 예외는 로그만 남기고 다음 주기에 다시 시도한다(전달 상태는 DB에 있어 놓친 회차가 유실을
 * 만들지 않는다). 결과를 counter `paycore.financial.relay{result=delivered·retried·quarantined·claim_lost·failed·deferred}`로 센다(label에 ID 없음).
 */
@Component
class FinancialFactRelayScheduler(
    private val useCase: RelayFinancialFactsUseCase,
    private val settings: FinancialFactRelayJobSettings,
    private val registry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(FinancialFactRelayScheduler::class.java)

    @Scheduled(fixedDelayString = "\${paycore.financial-relay.job.fixed-delay}")
    fun onSchedule() {
        if (!settings.enabled) return
        runCatching { runOnce() }.onFailure { log.warn("operation=FinancialFactRelay result=ROUND_FAILED failure={}", it::class.simpleName) }
    }

    fun runOnce(): FinancialFactRelayReport {
        val r = useCase.relayOnce(
            RelayFinancialFactsCommand(settings.batchSize, settings.claimLease, settings.backoffInitial, settings.backoffMax, settings.backoffJitterRatio),
        )
        count("delivered", r.delivered)
        count("retried", r.retried)
        count("quarantined", r.quarantined)
        count("claim_lost", r.claimLost)
        count("failed", r.failed)
        count("deferred", r.deferred)
        return r
    }

    private fun count(result: String, n: Int) {
        if (n > 0) registry.counter(METRIC, "result", result).increment(n.toDouble())
    }

    private companion object {
        const val METRIC = "paycore.financial.relay"
    }
}

/** 설정 바인딩(스케줄링 활성화는 기존 `@EnableScheduling` 구성). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FinancialFactRelayJobSettings::class)
class FinancialFactRelayJobConfiguration

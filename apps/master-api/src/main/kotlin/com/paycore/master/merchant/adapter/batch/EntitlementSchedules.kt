package com.paycore.master.merchant.adapter.batch

import com.paycore.master.merchant.application.provided.EntitlementLeaseUseCase
import com.paycore.master.merchant.application.provided.EntitlementRelayUseCase
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Relay·lease 갱신 주기 실행(명세 §10 "스케줄은 adapter → application use case, 업무 규칙은 application"). 실행 조정만 하고 판단은 use case가 한다
 * (batchAdapterUsesOnlyApplication — application.provided만 쓴다).
 *
 * 주기와 실행 여부에 **기본값이 없다**(`paycore.master.entitlement.scheduling-enabled`, `…relay.poll-interval`, `…renew-scan-interval`) —
 * 값이 없으면 기동이 실패한다. 시험 컨텍스트는 실행을 끄고 use case를 직접 부른다. 한 회차의 예외는 로그만 남기고 다음 주기에 다시 시도한다
 * (Outbox가 상태를 들고 있으므로 놓친 회차가 유실을 만들지 않는다).
 */
@Component
class EntitlementSchedules(
    private val relay: EntitlementRelayUseCase,
    private val leases: EntitlementLeaseUseCase,
    @param:Value("\${paycore.master.entitlement.scheduling-enabled}") private val enabled: Boolean,
) {
    private val log = LoggerFactory.getLogger(EntitlementSchedules::class.java)

    @Scheduled(fixedDelayString = "\${paycore.master.entitlement.relay.poll-interval}")
    fun relay() {
        if (!enabled) return
        runCatching { relay.relayOnce() }.onFailure { log.warn("entitlement relay round failed failure={}", it::class.simpleName) }
    }

    @Scheduled(fixedDelayString = "\${paycore.master.entitlement.renew-scan-interval}")
    fun renew() {
        if (!enabled) return
        runCatching { leases.renewDueLeases() }.onFailure { log.warn("entitlement lease renewal round failed failure={}", it::class.simpleName) }
    }
}

@Configuration(proxyBeanMethods = false)
@EnableScheduling
class EntitlementSchedulingConfiguration

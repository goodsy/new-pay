package com.paycore.payment.payment.adapter.web.admin

import com.paycore.payment.payment.adapter.web.PaymentApiSettings
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * ADMIN 채널 업무 구성(ADMIN-PAYMENT-OPS-001 §4.7): 설정([AdminOpsSettings] — 기본값 없음), bulkhead([AdminChannelPermits]). 기동 검사(review L-2): 관리 permit 합은 내부 connector 작업 스레드
 * 상한보다 **작아야** 한다(`readPermits + commandPermits < internalMaxThreads`) — 관리 요청이 모두 permit을 쥐어도 Entitlement Inbox·busy 응답을 처리할 스레드가 남는다. 풀 대비 permit 검사는
 * DB 연결 풀을 아는 persistence 구성(`AdminChannelPoolBudget`)에 있다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AdminOpsSettings::class)
class AdminOpsConfiguration {

    @Bean
    fun adminChannelPermits(settings: AdminOpsSettings, api: PaymentApiSettings, registry: MeterRegistry): AdminChannelPermits {
        check(settings.readPermits + settings.commandPermits < api.internalMaxThreads) {
            "admin-ops read-permits + command-permits must be below paycore.payment.api.internal-max-threads"
        }
        return AdminChannelPermits(settings, registry)
    }
}

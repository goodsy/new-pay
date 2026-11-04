package com.paycore.master.merchant.adapter.web.admin

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

/**
 * ADMIN 채널 설정·기동 검사(MERCHANT-OPS-001 §3): 서명 키 설정([AdminChannelSettings] — 기본값 없음), context path 금지([AdminChannelContextPathGuard]),
 * 키 공간 분리([AdminChannelKeySeparation]), 조회 permit. 어느 것이든 실패하면 기동하지 않는다. 보안 체인은 [AdminChannelSecurityConfiguration].
 * 서블릿 웹 컨텍스트에서만 구성한다 — DEMO 1회성 명령(비웹, DEMO-SEED-001)은 HTTP를 받지 않으므로 admin-channel 키를 요구하지 않는다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(AdminChannelSettings::class)
class AdminChannelConfiguration {

    @Bean
    fun adminChannelContextPathGuard(@Value("\${server.servlet.context-path:}") contextPath: String): AdminChannelContextPathGuard =
        AdminChannelContextPathGuard(contextPath)

    @Bean
    fun adminChannelKeySeparation(settings: AdminChannelSettings, environment: Environment): AdminChannelKeySeparation =
        AdminChannelKeySeparation(settings, Binder.get(environment))

    @Bean
    fun adminChannelPermits(settings: AdminChannelSettings, registry: MeterRegistry): AdminChannelPermits = AdminChannelPermits(settings, registry)
}

package com.paycore.financial.acquiring.adapter.web.admin

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

/**
 * ADMIN 채널 설정·기동 검사(ACQ-VIEW-001 §3.1–§3.2): 서명 키 설정([AdminChannelSettings] — 기본값 없음, I-8), context path 금지([AdminChannelContextPathGuard]),
 * 키 공간 분리([AdminChannelKeySeparation]), 조회 permit. 어느 것이든 실패하면 기동하지 않는다. 보안 체인은 [AdminChannelSecurityConfiguration].
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

/**
 * context path 금지(ADMIN-PAYMENT-OPS-001 L-3와 같은 이유): ADMIN은 요청 URI 경로를 서명하고 FINANCIAL은 `servletPath + pathInfo`로 검증하므로 둘이 같아야 한다.
 * `server.servlet.context-path`가 비어 있지 않으면(루트 `/` 포함) 기동을 거부한다.
 */
class AdminChannelContextPathGuard(contextPath: String) {
    init {
        check(contextPath.isEmpty()) { "server.servlet.context-path must be empty: the admin call signature covers the request path" }
    }
}

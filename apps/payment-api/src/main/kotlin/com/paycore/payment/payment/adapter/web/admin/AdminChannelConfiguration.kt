package com.paycore.payment.payment.adapter.web.admin

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

/**
 * ADMIN 채널 설정·기동 검사(ADMIN-PAYMENT-OPS-001 §4.2): 서명 키 설정([AdminChannelSettings] — 기본값 없음), context path 금지([AdminChannelContextPathGuard]),
 * 키 공간 분리([AdminChannelKeySeparation]). 어느 것이든 실패하면 기동하지 않는다. 보안 체인은 [AdminChannelSecurityConfiguration].
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AdminChannelSettings::class)
class AdminChannelConfiguration {

    @Bean
    fun adminChannelContextPathGuard(@Value("\${server.servlet.context-path:}") contextPath: String): AdminChannelContextPathGuard =
        AdminChannelContextPathGuard(contextPath)

    @Bean
    fun adminChannelKeySeparation(settings: AdminChannelSettings, environment: Environment): AdminChannelKeySeparation =
        AdminChannelKeySeparation(settings, Binder.get(environment))
}

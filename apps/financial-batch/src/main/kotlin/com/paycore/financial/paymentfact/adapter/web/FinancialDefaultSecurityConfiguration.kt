package com.paycore.financial.paymentfact.adapter.web

import jakarta.servlet.DispatcherType
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler

/**
 * financial-batch 기본 보안 체인(ACQ-VIEW-001 §3.2 R-2 A — 마지막 순서). ADMIN 채널(`/internal/v1/admin/` — 앞선 전용 체인) 밖에서는:
 * - `POST /internal/v1/payment-facts`(정확 경로)만 무인증 통과 — 인증은 기존대로 컨트롤러의 결제 사실 HMAC(FIN-FACT-RELAY-001 §4.1, 동작 불변).
 * - `GET /actuator/health`(와 하위)만 허용. ERROR dispatch(Spring 오류 응답 — 415 등)는 허용해 원래 상태 코드를 지킨다(payment-api와 같다).
 * - 나머지는 거부 — 404 빈 본문(진입 필터 [PaymentFactInboxEntryFilter]와 같은 존재 비노출. 진입 필터가 먼저 404로 막으므로 이 규칙은 이중 방어다).
 * 세션·로그인 화면·`WWW-Authenticate` 없음. 사용자·비밀번호 인증 없음(`UserDetailsServiceAutoConfiguration` 제외 — FinancialBatchApplication).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class FinancialDefaultSecurityConfiguration {

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    fun financialDefaultSecurityFilterChain(http: HttpSecurity): SecurityFilterChain = http
        .securityMatcher("/**")
        .csrf { it.disable() }
        .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        .httpBasic { it.disable() }
        .formLogin { it.disable() }
        .logout { it.disable() }
        .requestCache { it.disable() }
        .exceptionHandling { it.authenticationEntryPoint(notFound()).accessDeniedHandler(denied()) }
        .authorizeHttpRequests {
            it.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
            it.requestMatchers(HttpMethod.POST, PaymentFactSignature.PATH).permitAll()
            it.requestMatchers(HttpMethod.GET, PaymentFactInboxEntryFilter.HEALTH_PATH, "${PaymentFactInboxEntryFilter.HEALTH_PATH}/**").permitAll()
            it.anyRequest().denyAll()
        }
        .build()

    private fun notFound() = AuthenticationEntryPoint { _, response, _ -> response.status = NOT_FOUND }

    private fun denied() = AccessDeniedHandler { _, response, _ -> response.status = NOT_FOUND }

    private companion object {
        const val NOT_FOUND = 404
    }
}

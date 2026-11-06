package com.paycore.financial.acquiring.adapter.web.admin

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter

/**
 * ADMIN → FINANCIAL 채널 보안 체인(ACQ-VIEW-001 §3.2 — Coordinator 결정 R-2 A, master-api·payment-api 수신 측과 같은 구조). `/internal/v1/admin/` 아래만:
 * STATELESS, CSRF·form·basic·logout·request cache·anonymous 끔, 서명 필터([AdminChannelAuthenticationFilter]) → `ADMIN_SERVICE`만. 응답은 기본 보안 헤더
 * (`Cache-Control: no-store` 포함)를 유지한다. 그 밖 경로의 기본 거부 체인은 `paymentfact.adapter.web.FinancialDefaultSecurityConfiguration`(결제 사실 Inbox 정확 경로·health만).
 * mTLS는 LIVE Gate.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class AdminChannelSecurityConfiguration {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    fun adminChannelSecurityFilterChain(http: HttpSecurity, settings: AdminChannelSettings, registry: MeterRegistry): SecurityFilterChain = http
        .securityMatcher(ADMIN_PATHS)
        .csrf { it.disable() }
        .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        .httpBasic { it.disable() }
        .formLogin { it.disable() }
        .logout { it.disable() }
        .requestCache { it.disable() }
        .anonymous { it.disable() }
        .exceptionHandling { it.authenticationEntryPoint(unauthenticated()).accessDeniedHandler(denied()) }
        .addFilterBefore(AdminChannelAuthenticationFilter(settings, registry), AnonymousAuthenticationFilter::class.java)
        .authorizeHttpRequests { it.anyRequest().hasAuthority(AdminChannelAuthenticationFilter.ADMIN_SERVICE) }
        .build()

    /** 인증 없음(서명 필터를 거치지 않은 경우 포함): 원인 비구별 401. */
    private fun unauthenticated() = AuthenticationEntryPoint { request, response, _ ->
        AdminChannelResponses.write(request, response, UNAUTHORIZED, AdminChannelResponses.UNAUTHENTICATED)
    }

    /** 거부: 같은 401 — 경로 존재 여부를 구별해 주지 않는다. */
    private fun denied() = AccessDeniedHandler { request, response, _ ->
        AdminChannelResponses.write(request, response, UNAUTHORIZED, AdminChannelResponses.UNAUTHENTICATED)
    }

    companion object {
        const val ADMIN_PATHS = "/internal/v1/admin/**"
        private const val UNAUTHORIZED = 401
    }
}

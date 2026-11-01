package com.paycore.payment.payment.adapter.web.admin

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
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter

/**
 * ADMIN 채널 전용 보안 체인(ADMIN-PAYMENT-OPS-001 §4.2 M-3, I-02): `securityMatcher("/internal/v1/admin/" + "**")`, 결제창 체인 다음(`HIGHEST_PRECEDENCE + 1`)·가맹점 API 체인 앞.
 * STATELESS, CSRF 끔(쿠키·세션 없는 서버 간 서명 호출), Bearer 필터 없음, 서명 필터([AdminChannelAuthenticationFilter]) → `ADMIN_SERVICE`만 허용. 내부 connector에서만 닿는다
 * (공개 포트의 `/internal` 아래는 `ApiEntryFilter`가 보안 체인보다 먼저 404). 응답은 기본 보안 헤더(`Cache-Control: no-store` 포함)를 유지한다.
 */
/* DEMO-SEED-001(Coordinator 결정 2026-11-01): 서블릿 웹 컨텍스트에서만 구성한다 — 비웹 1회성 명령 프로세스에는 `HttpSecurity`가 없고 HTTP를 받지 않는다.
 * 가드가 filter chain을 지우는 방식(분류 오류 시 fail-open)은 쓰지 않는다. */
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
        .exceptionHandling { it.authenticationEntryPoint(unauthenticated()) }
        .addFilterBefore(AdminChannelAuthenticationFilter(settings, registry), AnonymousAuthenticationFilter::class.java)
        .authorizeHttpRequests { it.anyRequest().hasAuthority(AdminChannelAuthenticationFilter.ADMIN_SERVICE) }
        .build()

    /** 서명 필터를 거치지 않은 경우(방어): 같은 원인 비구별 401. */
    private fun unauthenticated() = AuthenticationEntryPoint { request, response, _ ->
        AdminChannelResponses.write(request, response, UNAUTHORIZED, AdminChannelResponses.UNAUTHENTICATED)
    }

    private companion object {
        const val ADMIN_PATHS = "/internal/v1/admin/**"
        const val UNAUTHORIZED = 401
    }
}

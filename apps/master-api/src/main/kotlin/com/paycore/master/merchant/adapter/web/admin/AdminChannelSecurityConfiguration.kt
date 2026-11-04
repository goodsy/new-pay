package com.paycore.master.merchant.adapter.web.admin

import io.micrometer.core.instrument.MeterRegistry
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
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter

/**
 * master-api 보안 체인(MERCHANT-OPS-001 §3). master-api의 HTTP 표면은 이 둘뿐이다:
 * 1. `/internal/v1/admin/` 아래: STATELESS, CSRF·form·basic·logout·request cache·anonymous 끔, 서명 필터([AdminChannelAuthenticationFilter]) → `ADMIN_SERVICE`만.
 * 2. 그 밖 전부(마지막 순서): `GET /actuator/health`(와 하위)만 허용, 나머지는 거부 — 401 `{"error":"UNAUTHENTICATED"}`(로그인 화면·`WWW-Authenticate`·세션 없음).
 * 응답은 기본 보안 헤더(`Cache-Control: no-store` 포함)를 유지한다. 서블릿 웹 컨텍스트에서만(비웹 1회성 명령에는 `HttpSecurity`가 없다). mTLS는 LIVE Gate.
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

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    fun denyByDefaultSecurityFilterChain(http: HttpSecurity): SecurityFilterChain = http
        .securityMatcher("/**")
        .csrf { it.disable() }
        .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        .httpBasic { it.disable() }
        .formLogin { it.disable() }
        .logout { it.disable() }
        .requestCache { it.disable() }
        .exceptionHandling { it.authenticationEntryPoint(unauthenticated()).accessDeniedHandler(denied()) }
        .authorizeHttpRequests {
            it.requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
            it.anyRequest().denyAll()
        }
        .build()

    /** 인증 없음(서명 필터를 거치지 않은 경우 포함): 원인 비구별 401. */
    private fun unauthenticated() = AuthenticationEntryPoint { request, response, _ ->
        AdminChannelResponses.write(request, response, UNAUTHORIZED, AdminChannelResponses.UNAUTHENTICATED)
    }

    /** 거부(기본 체인의 denyAll 등): 같은 401 — 경로 존재 여부를 구별해 주지 않는다. */
    private fun denied() = AccessDeniedHandler { request, response, _ ->
        AdminChannelResponses.write(request, response, UNAUTHORIZED, AdminChannelResponses.UNAUTHENTICATED)
    }

    private companion object {
        const val ADMIN_PATHS = "/internal/v1/admin/**"
        const val UNAUTHORIZED = 401
    }
}

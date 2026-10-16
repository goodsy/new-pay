package com.paycore.payment.payment.adapter.web

import com.paycore.payment.payment.application.provided.MerchantAuthenticator
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.DispatcherType
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.ProviderNotFoundException
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter

/**
 * 가맹점 공개 API 보안 체인(D-CPA-001, CP-API-001 명세 §5.2).
 *
 * - stateless(세션 없음), CSRF 끔: 쿠키·세션이 없는 헤더(Bearer) 인증 JSON API라 브라우저가 자격 증명을 자동으로 보내지 않는다 — CSRF 대상이 아니다.
 * - 기본 거부: `/internal` 아래(내부 connector 전용 — 인증은 봉투 HMAC)과 `/actuator/health`만 무인증, 나머지는 인증 필요(인증 뒤 없는 경로는 404).
 * - 401은 원인을 구분하지 않는다(`UNAUTHENTICATED`, `WWW-Authenticate: Bearer realm="paycore"`). 원인은 지표·WARN 로그(keyId 없음)에만.
 * - 사용자·비밀번호 기반 인증은 없다: 모든 것을 거절하는 [AuthenticationManager]를 두어 Boot의 기본 사용자(생성 비밀번호) 구성을 끈다.
 * - 인증 정보 읽기는 [MerchantAuthenticator](PAYMENT 로컬 투영, 캐시 없음). 저장소 일시 실패는 401이 아니라 503(결과 미정이 아니라
 *   검증 불가 — 같은 키로 재시도).
 */
@Configuration(proxyBeanMethods = false)
class PaymentApiSecurityConfiguration {

    @Bean
    fun paymentApiSecurityFilterChain(
        http: HttpSecurity,
        authenticate: MerchantAuthenticator,
        settings: PaymentApiSettings,
        registry: MeterRegistry,
    ): SecurityFilterChain {
        val entryPoint = UnauthenticatedEntryPoint(registry)
        return http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .requestCache { it.disable() }
            .exceptionHandling { it.authenticationEntryPoint(entryPoint) }
            .addFilterBefore(MerchantBearerAuthenticationFilter(authenticate, settings), AnonymousAuthenticationFilter::class.java)
            .authorizeHttpRequests {
                it.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                    .requestMatchers("/internal/**", "/actuator/health").permitAll()
                    .anyRequest().authenticated()
            }
            .build()
    }

    /** 사용자·비밀번호 인증 없음(Boot 기본 사용자 생성 방지). 어떤 인증 요청도 처리하지 않는다. */
    @Bean
    fun noPasswordAuthenticationManager(): AuthenticationManager =
        AuthenticationManager { throw ProviderNotFoundException("no username/password authentication") }
}

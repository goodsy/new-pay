package com.paycore.payment.payment.adapter.web

import com.paycore.payment.payment.application.provided.CheckoutPageQuery
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.security.web.context.request.async.WebAsyncManagerIntegrationFilter
import java.nio.charset.StandardCharsets

/**
 * 결제창 보안 체인(AUTHN-001 §12.1·§12.3, D-AUTHN-006 승인 편차): `securityMatcher`(`/checkout` 아래), API 체인보다 앞(가장 높은 순서). Bearer 필터 없음, HttpSession 없음
 * (STATELESS), CSRF **켬**(쿠키 비밀에서 파생한 토큰 — [CheckoutCsrfTokenRepository]), `permitAll` — 접근 통제는 handle 쿠키로 application이 한다. 응답 헤더는
 * [CheckoutResponseHeadersFilter]가 체인 맨 앞에서 쓴다(Spring 기본 헤더 writer는 끈다 — 값이 다르다). CSRF 거절은 403 고정 문구 화면이며 상태를 바꾸지 않는다.
 * API 체인(`PaymentApiSecurityConfiguration`)은 그대로 — 이 체인은 `/v1` 아래에 걸리지 않고 쿠키로 `/v1` 아래을 인증할 수 없다.
 */
/* DEMO-SEED-001(Coordinator 결정 2026-11-01): 서블릿 웹 컨텍스트에서만 구성한다 — 비웹 1회성 명령 프로세스에는 `HttpSecurity`가 없고 HTTP를 받지 않는다.
 * 가드가 filter chain을 지우는 방식(분류 오류 시 fail-open)은 쓰지 않는다. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class CheckoutSecurityConfiguration {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    fun checkoutSecurityFilterChain(http: HttpSecurity, pages: CheckoutPageQuery): SecurityFilterChain = http
        .securityMatcher("/checkout/**")
        .csrf { it.csrfTokenRepository(CheckoutCsrfTokenRepository()) }
        .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        .httpBasic { it.disable() }
        .formLogin { it.disable() }
        .logout { it.disable() }
        .requestCache { it.disable() }
        .headers { it.disable() }
        .exceptionHandling { it.accessDeniedHandler(csrfRejected()) }
        .addFilterBefore(CheckoutResponseHeadersFilter(pages::defaultFormActionOrigins), WebAsyncManagerIntegrationFilter::class.java)
        .authorizeHttpRequests { it.anyRequest().permitAll() }
        .build()

    /** CSRF 불일치(403, §6.3): 고정 문구, 상태 불변. 보안 이벤트 로그(분류만 — 토큰·쿠키 원문 없음, §12.6). */
    private fun csrfRejected() = AccessDeniedHandler { request, response, _ ->
        LoggerFactory.getLogger(CheckoutSecurityConfiguration::class.java)
            .warn("operation=Checkout correlationId={} security=CSRF_REJECTED", ApiResponses.requestId(request))
        response.status = 403
        response.contentType = MediaType("text", "html", StandardCharsets.UTF_8).toString()
        response.outputStream.write(CheckoutPages.staticPage(CheckoutPages.REQUEST_REJECTED).toByteArray(StandardCharsets.UTF_8))
    }
}

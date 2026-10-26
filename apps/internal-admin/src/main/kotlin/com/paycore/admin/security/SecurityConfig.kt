package com.paycore.admin.security

import com.paycore.admin.account.application.provided.AdminSessionQuery
import com.paycore.admin.audit.application.provided.AdminSecurityEventRecorder
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.DispatcherType
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.core.Ordered
import org.springframework.core.env.Environment
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy
import org.springframework.security.web.csrf.CsrfFilter
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
import org.springframework.security.web.header.writers.StaticHeadersWriter
import org.springframework.security.web.util.matcher.AnyRequestMatcher

/**
 * internal-admin 보안 골격(ADMIN-BOOT-001 S1 — React SPA 같은 출처 서빙 + JSON API).
 *
 * - `/api/` 아래는 인증 필요 — 인증은 2단계 로그인(S4, `/api/auth/csrf`·`/api/auth/password`·`/api/auth/totp`만 익명 허용, 상태 변경은 CSRF 필수).
 *   `SecurityContext`는 세션에 저장하지 않고 요청마다 세션 문자열 상태 + 계정 현재 행으로 만든다([AdminSecurityContextRepository]). 미인증 401·거부 403은 JSON 고정 code만.
 * - IP 허용 목록 filter가 모든 filter(Spring Session 포함)보다 먼저다([IpAllowlistFilter]).
 * - 익명 GET은 SPA 문서 허용 목록([SpaDocumentRequestMatcher])과 `/assets/`만. 그 밖의 경로·상태 변경 요청은 거부한다.
 * - CSRF 토큰은 세션이 이미 있을 때만, 문자열로 저장한다([StringSessionCsrfTokenRepository]) — 익명 상태 변경 요청이 세션을 만들지 않는다.
 * - CSRF는 **세션 저장 동기화 토큰**(D-10): 저장소는 세션, 쿠키 `__Host-PCADMIN-XSRF`는 전달만([XsrfCookieFilter]), 상태 변경 요청은
 *   `X-XSRF-TOKEN` 헤더 값이 세션 토큰과 같아야 한다. Security `csrf.spa()`는 쿠키 저장(double-submit)이라 쓰지 않는다(S0 §17.5).
 * - 요청 캐시(저장 요청)를 끈다 — 401 응답마다 익명 세션이 생기지 않게(n-1).
 * - `/assets/` 아래는 별도 체인: 해시 파일명 자원의 장기 캐시를 위해 Security cache 헤더를 끄고 세션을 쓰지 않는다(n-3).
 * - (S8) actuator는 별도 management 포트(D-15 — 기본 loopback 바인딩, 포트 설정이 없으면 HTTP actuator 없음). 그 포트는 전용 체인([ManagementServerRequestMatcher]):
 *   `GET /actuator/health`만 익명 허용(상세 없음), 그 밖은 거부. 웹 포트의 `/actuator/` 아래는 지금처럼 허용 목록 밖(401·403).
 * - (S8) HSTS는 모든 응답에(`max-age=31536000; includeSubDomains`) — TLS는 앞단 프록시에서 끝나고 `forward-headers-strategy=none`이라 요청이 https로 보이지 않기
 *   때문이다. 평문 응답의 HSTS는 브라우저가 무시하므로(RFC 6797 §8.1) 해가 없다.
 * - formLogin/httpBasic/기본 logout filter를 켜지 않는다(로그인·로그아웃은 JSON API). Boot 기본 사용자도 만들지 않는다(InternalAdminApplication).
 *   세션 고정 방지는 로그인 각 단계에서 웹 adapter가 세션 ID를 재발급한다(Security의 sessionManagement는 세션을 만들지 않게 NEVER).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AdminNetworkProperties::class)
class SecurityConfig {

    @Bean
    fun ipAllowlistFilterRegistration(
        properties: AdminNetworkProperties,
        events: ObjectProvider<AdminSecurityEventRecorder>,
        meters: ObjectProvider<MeterRegistry>,
        environment: Environment,
    ): FilterRegistrationBean<IpAllowlistFilter> {
        // LIVE 배치에서 모든 주소 허용(0.0.0.0/0·::/0)은 기동 거부(S4 리뷰 m-5). 모드 값은 account의 배치 모드 설정과 같은 키.
        if (environment.getProperty("paycore.admin.deployment-mode") == "LIVE") {
            val found = properties.ipAllowlist.liveViolations()
            check(found.isEmpty()) { "LIVE deployment refused: " + found.joinToString("; ") }
        }
        // 감사 기록기는 처음 거부할 때 얻는다(lazy) — filter 등록은 웹 서버 시작 때 만들어지므로 DataSource를 그 시점에 끌어오지 않게 한다.
        return FilterRegistrationBean(IpAllowlistFilter(properties.ipAllowlist, { events.getObject() }, { meters.ifAvailable })).apply {
            order = Ordered.HIGHEST_PRECEDENCE
            addUrlPatterns("/*")
        }
    }

    @Bean
    fun csrfTokenRepository(): StringSessionCsrfTokenRepository = StringSessionCsrfTokenRepository()

    @Bean
    fun adminSecurityContextRepository(sessions: AdminSessionQuery): AdminSecurityContextRepository = AdminSecurityContextRepository(sessions)


    @Bean
    @Order(0)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    fun managementSecurityFilterChain(http: HttpSecurity): SecurityFilterChain =
        http
            .securityMatcher(ManagementServerRequestMatcher)
            .authorizeHttpRequests {
                it.requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                    .anyRequest().denyAll()
            }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            // GET health 외에는 denyAll이라 CSRF 토큰이 필요 없다(세션도 만들지 않는다).
            .csrf { it.disable() }
            .requestCache { it.disable() }
            .headers { commonHeaders(it) }
            .exceptionHandling {
                it.authenticationEntryPoint(JsonErrorResponses.unauthenticated()).accessDeniedHandler(JsonErrorResponses.forbidden())
            }
            .build()

    @Bean
    @Order(1)
    // S6: 1회성 명령 모드(비웹 — §4.9)에는 HttpSecurity가 없다 — 필터 체인만 웹에서 만든다(웹 모드 동작은 그대로).
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    fun assetsSecurityFilterChain(http: HttpSecurity): SecurityFilterChain =
        http
            .securityMatcher("/assets/**")
            .authorizeHttpRequests {
                it.requestMatchers(HttpMethod.GET, "/assets/**").permitAll()
                    .anyRequest().denyAll()
            }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            // GET 외 요청은 denyAll이라 CSRF 토큰이 필요 없다. 켜 두면 기본 HttpSession 저장소가 익명 PUT마다 세션을 만든다(S1 리뷰 M1).
            .csrf { it.disable() }
            .requestCache { it.disable() }
            .headers { headers ->
                commonHeaders(headers)
                headers.cacheControl { it.disable() }
            }
            .exceptionHandling {
                it.authenticationEntryPoint(JsonErrorResponses.unauthenticated()).accessDeniedHandler(JsonErrorResponses.forbidden())
            }
            .build()

    @Bean
    @Order(2)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    fun appSecurityFilterChain(
        http: HttpSecurity,
        csrfTokenRepository: StringSessionCsrfTokenRepository,
        contextRepository: AdminSecurityContextRepository,
    ): SecurityFilterChain =
        http
            .authorizeHttpRequests {
                it.dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.FORWARD).permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/auth/csrf").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/auth/password", "/api/auth/totp").permitAll()
                    // S7: 등록 코드 흐름(미인증 — 익명 세션·CSRF 필요, 진행 상태는 세션 문자열 속성).
                    .requestMatchers(HttpMethod.POST, "/api/enroll/start", "/api/enroll/password", "/api/enroll/totp", "/api/enroll/complete").permitAll()
                    .requestMatchers("/api/**").authenticated()
                    // 익명 GET은 SPA 문서 허용 목록만(S1 리뷰 m3 — actuator 등 그 밖의 경로는 거부). 이전의 GET /** 전체 허용을 대체.
                    .requestMatchers(SpaDocumentRequestMatcher).permitAll()
                    .anyRequest().denyAll()
            }
            .securityContext { it.securityContextRepository(contextRepository) }
            // 세션 ID·CSRF 토큰 재발급은 로그인 각 단계에서 웹 adapter가 직접 한다. Security의 인증 시점 전략(세션 고정 방지·CsrfAuthenticationStrategy)은
            // 끈다 — 세션에 SecurityContext를 두지 않으므로 Security가 "새 인증"으로 오판해 세션의 CSRF 토큰을 지우는 일을 막는다.
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.NEVER).sessionAuthenticationStrategy(NullAuthenticatedSessionStrategy()) }
            .csrf {
                it.csrfTokenRepository(csrfTokenRepository).csrfTokenRequestHandler(SpaCsrfTokenRequestHandler())
                    .sessionAuthenticationStrategy(NullAuthenticatedSessionStrategy())
            }
            .addFilterAfter(XsrfCookieFilter(), CsrfFilter::class.java)
            .requestCache { it.disable() }
            .logout { it.disable() }
            .headers { commonHeaders(it) }
            .exceptionHandling {
                it.authenticationEntryPoint(JsonErrorResponses.unauthenticated()).accessDeniedHandler(JsonErrorResponses.forbidden())
            }
            .build()

    private fun commonHeaders(headers: HeadersConfigurer<HttpSecurity>) {
        headers
            .contentSecurityPolicy { it.policyDirectives(CONTENT_SECURITY_POLICY) }
            .referrerPolicy { it.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER) }
            .frameOptions { it.deny() }
            .addHeaderWriter(StaticHeadersWriter("Permissions-Policy", PERMISSIONS_POLICY))
            .httpStrictTransportSecurity { it.requestMatcher(AnyRequestMatcher.INSTANCE).maxAgeInSeconds(HSTS_MAX_AGE_SECONDS).includeSubDomains(true) }
    }

    companion object {
        const val XSRF_HEADER = "X-XSRF-TOKEN"
        const val HSTS_MAX_AGE_SECONDS = 31_536_000L
        const val CONTENT_SECURITY_POLICY =
            "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; " +
                "font-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'"
        const val PERMISSIONS_POLICY =
            "camera=(), microphone=(), geolocation=(), payment=(), usb=(), clipboard-read=(), display-capture=()"
    }
}

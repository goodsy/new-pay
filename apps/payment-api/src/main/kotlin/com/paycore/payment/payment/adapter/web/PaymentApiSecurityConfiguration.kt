package com.paycore.payment.payment.adapter.web

import com.paycore.payment.payment.application.provided.AuthenticateMerchantUseCase
import com.paycore.payment.payment.application.provided.AuthenticationFailureReason
import com.paycore.payment.payment.application.provided.MerchantAuthentication
import com.paycore.payment.payment.application.provided.MerchantAuthenticationResult
import com.paycore.payment.payment.application.provided.MerchantAuthenticationUnavailable
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.DispatcherType
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.transaction.TransactionException
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.ProviderNotFoundException
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter
import org.springframework.web.filter.OncePerRequestFilter

/**
 * 가맹점 공개 API 보안 체인(D-CPA-001, CP-API-001 명세 §5.2).
 *
 * - stateless(세션 없음), CSRF 끔: 쿠키·세션이 없는 헤더(Bearer) 인증 JSON API라 브라우저가 자격 증명을 자동으로 보내지 않는다 — CSRF 대상이 아니다.
 * - 기본 거부: `/internal` 아래(내부 connector 전용 — 인증은 봉투 HMAC)과 `/actuator/health`만 무인증, 나머지는 인증 필요(인증 뒤 없는 경로는 404).
 * - 401은 원인을 구분하지 않는다(`UNAUTHENTICATED`, `WWW-Authenticate: Bearer realm="paycore"`). 원인은 지표·WARN 로그(keyId 없음)에만.
 * - 사용자·비밀번호 기반 인증은 없다: 모든 것을 거절하는 [AuthenticationManager]를 두어 Boot의 기본 사용자(생성 비밀번호) 구성을 끈다.
 * - 인증 정보 읽기는 [AuthenticateMerchantUseCase](PAYMENT 로컬 투영, 캐시 없음). 저장소 일시 실패는 401이 아니라 503(결과 미정이 아니라
 *   검증 불가 — 같은 키로 재시도).
 */
@Configuration(proxyBeanMethods = false)
class PaymentApiSecurityConfiguration {

    @Bean
    fun paymentApiSecurityFilterChain(
        http: HttpSecurity,
        authenticate: AuthenticateMerchantUseCase,
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

/** Spring Security 인증 객체. principal = [MerchantAuthentication](MID·environment·keyId). 자격 증명(키 원문)은 담지 않는다. */
class MerchantAuthenticationToken(private val merchant: MerchantAuthentication) : AbstractAuthenticationToken(emptyList()) {
    init {
        isAuthenticated = true
    }

    override fun getCredentials(): Any? = null

    override fun getPrincipal(): MerchantAuthentication = merchant

    override fun toString(): String = "MerchantAuthenticationToken(keyId=${merchant.credentialKeyId.value})"
}

/**
 * `Authorization: Bearer <key>` 검증 필터. 실패하면 원인을 요청 속성에 남기고 다음으로 넘긴다(보호 경로면 진입점이 401). 내부 경로는 검사하지
 * 않는다. 키 원문은 어디에도 남기지 않는다.
 */
class MerchantBearerAuthenticationFilter(
    private val authenticate: AuthenticateMerchantUseCase,
    private val settings: PaymentApiSettings,
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(MerchantBearerAuthenticationFilter::class.java)

    override fun shouldNotFilter(request: HttpServletRequest): Boolean = ApiEntryFilter.isInternalPath(request)

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val headers = request.getHeaders(HttpHeaders.AUTHORIZATION).toList()
        val presented: String? = when {
            headers.isEmpty() -> null
            // 인증 scheme 이름은 대소문자를 구분하지 않는다(RFC 7235 §2.1). 자격 증명(키) 자체는 그대로 비교한다.
            headers.size > 1 || !headers[0].regionMatches(0, BEARER_PREFIX, 0, BEARER_PREFIX.length, ignoreCase = true) -> MALFORMED_MARKER
            else -> headers[0].substring(BEARER_PREFIX.length)
        }
        val result = try {
            authenticate.authenticate(presented)
        } catch (e: TransactionException) {
            // 방어(review round2 m-1 (a)): application이 TX 실패를 MerchantAuthenticationUnavailable로 옮기지만, TX 기반 실패가 그대로 올라와도
            // (연결 없음·rollback/commit 실패) 결함이 아니라 검증 불가 503이다.
            val requestId = ApiResponses.requestId(request)
            log.warn("operation=AuthenticateMerchant correlationId={} result=TEMPORARILY_UNAVAILABLE failure=TRANSACTION_UNAVAILABLE", requestId)
            ApiResponses.write(
                response, ApiError(ApiErrorCode.TEMPORARILY_UNAVAILABLE), requestId,
                mapOf(HttpHeaders.RETRY_AFTER to settings.retryAfter.temporarilyUnavailable.toRetryAfterSeconds()),
            )
            return
        } catch (e: MerchantAuthenticationUnavailable) {
            // 검증 불가(투영 저장소 일시 실패: 교착·잠금 대기·연결) — 401로 거짓 판정하지 않는다. 같은 키로 재시도.
            val requestId = ApiResponses.requestId(request)
            log.warn(
                "operation=AuthenticateMerchant correlationId={} result=TEMPORARILY_UNAVAILABLE failure={} vendorCode={} sqlState={}",
                requestId, e.kind, e.diagnostic?.vendorCode, e.diagnostic?.sqlState,
            )
            ApiResponses.write(
                response, ApiError(ApiErrorCode.TEMPORARILY_UNAVAILABLE), requestId,
                mapOf(HttpHeaders.RETRY_AFTER to settings.retryAfter.temporarilyUnavailable.toRetryAfterSeconds()),
            )
            return
        } catch (e: RuntimeException) {
            // 분류되지 않은 예외는 결함이다(n-7): 500, 메시지 원문 없이 클래스 이름 사슬만 남긴다.
            val requestId = ApiResponses.requestId(request)
            log.error("operation=AuthenticateMerchant correlationId={} result=INTERNAL_ERROR failure=DEFECT exception={}", requestId, SafeThrowable.summary(e))
            ApiResponses.write(response, ApiError(ApiErrorCode.INTERNAL_ERROR), requestId)
            return
        }
        when (result) {
            is MerchantAuthenticationResult.Authenticated ->
                SecurityContextHolder.setContext(SecurityContextImpl(MerchantAuthenticationToken(result.authentication)))
            is MerchantAuthenticationResult.Rejected -> request.setAttribute(FAILURE_ATTRIBUTE, result.reason)
        }
        chain.doFilter(request, response)
    }

    companion object {
        const val FAILURE_ATTRIBUTE = "paycore.authenticationFailure"
        private const val BEARER_PREFIX = "Bearer "

        /** 키 형식 검사에서 반드시 MALFORMED가 되는 값(Bearer 아님·헤더 여러 개). */
        private const val MALFORMED_MARKER = "\u0000"
    }
}

/** 401 진입점(원인 비구분 응답 + 원인별 지표). */
class UnauthenticatedEntryPoint(private val registry: MeterRegistry) : AuthenticationEntryPoint {
    private val log = LoggerFactory.getLogger(UnauthenticatedEntryPoint::class.java)

    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: org.springframework.security.core.AuthenticationException,
    ) {
        val reason = request.getAttribute(MerchantBearerAuthenticationFilter.FAILURE_ATTRIBUTE) as? AuthenticationFailureReason
            ?: AuthenticationFailureReason.MISSING
        val requestId = ApiResponses.requestId(request)
        registry.counter(AUTH_FAILURES, "reason", reason.name).increment()
        log.info("operation=AuthenticateMerchant correlationId={} result=UNAUTHENTICATED reason={}", requestId, reason)
        ApiResponses.write(response, ApiError(ApiErrorCode.UNAUTHENTICATED), requestId, mapOf(HttpHeaders.WWW_AUTHENTICATE to "Bearer realm=\"paycore\""))
    }

    companion object {
        const val AUTH_FAILURES = "paycore.payment.api.auth.failures"
    }
}

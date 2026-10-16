package com.paycore.payment.payment.adapter.web

import com.paycore.payment.payment.application.provided.AuthenticationFailureReason
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.security.web.AuthenticationEntryPoint

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

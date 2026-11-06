package com.paycore.financial.paymentfact.adapter.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.filter.OncePerRequestFilter

/**
 * financial-batch HTTP 진입 제한(FIN-FACT-RELAY-001 §4.1). 이 앱의 HTTP는 **내부 전용**이다(공개 API 없음 — 내부망 바인딩·mTLS는 배포 작업, LIVE Gate):
 * - `POST /internal/v1/payment-facts`만 통과. query 문자열이 있으면 400(서명 canonical에 query가 없다), Content-Length가 없으면 411, 상한 초과면 413 — 본문을 읽기 전에 거절한다.
 * - `GET /actuator/health`(세부 없음)만 통과.
 * - (ACQ-VIEW-001 §3.2) `/internal/v1/admin/` 아래 GET·POST는 ADMIN 서명 채널 보안 체인으로 넘긴다(크기·query·서명 판정은 그 체인의 서명 필터 몫 — 이 필터는 경로·method만 본다).
 * - 그 밖(같은 경로의 다른 method 포함)은 404 빈 본문(존재 비노출).
 * `server.servlet.context-path`가 비어 있지 않으면 기동을 거부한다(서명 경로 = 요청 경로).
 */
class PaymentFactInboxEntryFilter(private val maxBodyBytes: Int) : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val path = request.requestURI
        when {
            path == PaymentFactSignature.PATH && request.method == PaymentFactSignature.METHOD -> {
                when {
                    request.queryString != null -> deny(response, HttpStatus.BAD_REQUEST, "QUERY_NOT_ALLOWED")
                    request.contentLengthLong < 0 -> deny(response, HttpStatus.LENGTH_REQUIRED, "LENGTH_REQUIRED")
                    request.contentLengthLong > maxBodyBytes -> deny(response, HttpStatus.CONTENT_TOO_LARGE, "BODY_TOO_LARGE")
                    else -> chain.doFilter(request, response)
                }
            }
            path == HEALTH_PATH && request.method == "GET" -> chain.doFilter(request, response)
            path.startsWith(ADMIN_CHANNEL_PREFIX) && request.method in ADMIN_CHANNEL_METHODS -> chain.doFilter(request, response)
            else -> response.status = HttpStatus.NOT_FOUND.value()
        }
    }

    private fun deny(response: HttpServletResponse, status: HttpStatus, error: String) {
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.writer.write("{\"error\":\"$error\"}")
    }

    companion object {
        const val HEALTH_PATH = "/actuator/health"

        /** ADMIN → FINANCIAL 서명 채널 경로 접두(ACQ-VIEW-001 — acquiring.adapter.web.admin 보안 체인이 처리). */
        const val ADMIN_CHANNEL_PREFIX = "/internal/v1/admin/"
        private val ADMIN_CHANNEL_METHODS = setOf("GET", "POST")
    }
}

@Configuration(proxyBeanMethods = false)
class PaymentFactInboxEntryConfiguration {

    /** 가장 먼저 실행된다. */
    @Bean
    fun paymentFactInboxEntryFilter(
        settings: PaymentFactInboxSettings,
        @Value("\${server.servlet.context-path:}") contextPath: String,
    ): FilterRegistrationBean<PaymentFactInboxEntryFilter> {
        check(contextPath.isEmpty()) { "server.servlet.context-path must be empty: the signed path is the request path" }
        return FilterRegistrationBean(PaymentFactInboxEntryFilter(settings.maxBodyBytes)).apply { order = Ordered.HIGHEST_PRECEDENCE }
    }
}

package com.paycore.financial.acquiring.adapter.web.admin

import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Instant

/**
 * ADMIN 채널 서명 필터(ACQ-VIEW-001 §3.1 — `/internal/v1/admin` 아래 전용 체인에만). 판정 순서: context path → 본문 크기(16 KiB — `Content-Length`와 스트림을 상한 + 1바이트까지만,
 * 초과 413) → query 문자열(있으면 400) → 헤더 문법 → 시각 창 → kid → 서명([AdminCallSignature]). 통과하면 권한 `ADMIN_SERVICE`로 인증하고 **서명한 본문 바이트 그대로** 넘긴다.
 * 실패는 원인 비구별 401 `UNAUTHENTICATED` + 지표 `paycore.financial.admin_channel.auth{result,reason}` + WARN(분류만 — 헤더·서명·본문·kid 없음).
 */
class AdminChannelAuthenticationFilter(
    private val settings: AdminChannelSettings,
    private val registry: MeterRegistry,
    private val clock: () -> Instant = Instant::now,
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(AdminChannelAuthenticationFilter::class.java)

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        if (!request.contextPath.isNullOrEmpty()) return reject(request, response, AdminCallRejection.CONTEXT_PATH_PRESENT)
        if (request.contentLengthLong > MAX_BODY_BYTES) return reject(request, response, AdminCallRejection.BODY_TOO_LARGE)
        val body = try {
            request.inputStream.readNBytes(MAX_BODY_BYTES + 1)
        } catch (e: java.io.IOException) {
            return reject(request, response, AdminCallRejection.BODY_UNREADABLE)
        }
        if (body.size > MAX_BODY_BYTES) return reject(request, response, AdminCallRejection.BODY_TOO_LARGE)
        if (request.queryString != null) return reject(request, response, AdminCallRejection.QUERY_NOT_ALLOWED)
        val header = request.getHeaders(AdminCallSignature.HEADER)?.toList().orEmpty().singleOrNull()
        val path = request.servletPath + (request.pathInfo ?: "")
        when (val result = AdminCallSignature.verify(header, request.method, path, body, clock().epochSecond, settings.clockWindow, settings::key)) {
            is AdminCallVerification.Rejected -> reject(request, response, result.reason)
            is AdminCallVerification.Accepted -> {
                registry.counter(AUTH_METRIC, "result", "ACCEPTED", "reason", "NONE").increment()
                SecurityContextHolder.setContext(SecurityContextImpl(AdminServiceAuthenticationToken(result.keyId)))
                chain.doFilter(AdminChannelCachedBodyRequest(request, body), response)
            }
        }
    }

    private fun reject(request: HttpServletRequest, response: HttpServletResponse, reason: AdminCallRejection) {
        registry.counter(AUTH_METRIC, "result", "REJECTED", "reason", reason.name).increment()
        log.warn("operation=AdminChannelAuthenticate correlationId={} result=REJECTED reason={}", AdminChannelResponses.requestId(request), reason)
        when (reason) {
            AdminCallRejection.BODY_TOO_LARGE -> AdminChannelResponses.write(request, response, PAYLOAD_TOO_LARGE, AdminChannelResponses.REQUEST_TOO_LARGE)
            AdminCallRejection.QUERY_NOT_ALLOWED -> AdminChannelResponses.write(request, response, BAD_REQUEST, AdminChannelResponses.QUERY_NOT_ALLOWED)
            AdminCallRejection.BODY_UNREADABLE -> AdminChannelResponses.write(request, response, SERVICE_UNAVAILABLE, AdminChannelResponses.REQUEST_UNREADABLE)
            else -> AdminChannelResponses.write(request, response, UNAUTHORIZED, AdminChannelResponses.UNAUTHENTICATED)
        }
    }

    companion object {
        const val ADMIN_SERVICE = "ADMIN_SERVICE"
        const val AUTH_METRIC = "paycore.financial.admin_channel.auth"

        /** 본문 상한 16 KiB(서명 전). */
        const val MAX_BODY_BYTES = 16 * 1024
        private const val BAD_REQUEST = 400
        private const val UNAUTHORIZED = 401
        private const val PAYLOAD_TOO_LARGE = 413
        private const val SERVICE_UNAVAILABLE = 503
    }
}

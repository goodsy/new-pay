package com.paycore.admin.security

import com.paycore.admin.audit.application.provided.AdminSecurityEventRecorder
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * 모든 경로 앞의 IP 허용 목록 filter(§4.8.1 — Spring Session·Security filter보다 먼저, 세션을 만들지 않는다). `remoteAddr`만 본다(`server.forward-headers-strategy=none`).
 * 거부는 403 `IP_DENIED`. 인증 경로(`/api/auth/`) 거부는 익명 감사 1행(자기 TX). 거부는 안전 방향이라 감사 기록이 실패해도 거부 응답은 그대로(실패는 로그 분류만).
 * Spring bean이 아니다(SecurityConfig의 registration이 순서를 고정한다).
 */
class IpAllowlistFilter(
    private val allowlist: IpAllowlist,
    private val events: () -> AdminSecurityEventRecorder,
    private val meters: () -> MeterRegistry? = { null },
) : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        if (allowlist.allows(request.remoteAddr)) {
            filterChain.doFilter(request, response)
            return
        }
        val area = when {
            request.requestURI.startsWith("/api/auth/") -> "AUTH"
            request.requestURI.startsWith("/api/enroll/") -> "ENROLL"
            else -> null
        }
        // S8: 거부는 경로와 무관하게 모두 센다(`paycore.admin.ip_denied{area}` — 감사는 미인증 경로만). 지표 실패는 거부 응답을 바꾸지 않는다.
        runCatching { meters()?.counter(IP_DENIED, "area", area ?: "OTHER")?.increment() }
        if (area != null) {
            try {
                events().recordIpDenied(request.remoteAddr, UUID.randomUUID().toString(), area)
            } catch (e: RuntimeException) {
                log.warn("operation=ip-denied-audit result=failed failure={}", e.javaClass.simpleName)
            }
        }
        JsonErrorResponses.write(response, HttpServletResponse.SC_FORBIDDEN, "IP_DENIED")
    }

    private companion object {
        val log = LoggerFactory.getLogger(IpAllowlistFilter::class.java)
        const val IP_DENIED = "paycore.admin.ip_denied"
    }
}

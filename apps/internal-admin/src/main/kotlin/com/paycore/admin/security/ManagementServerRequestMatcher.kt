package com.paycore.admin.security

import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.security.web.util.matcher.RequestMatcher
import org.springframework.web.context.support.WebApplicationContextUtils

/**
 * 별도 management 포트(D-15)로 들어온 요청인가 — 그 servlet context의 애플리케이션 컨텍스트가 `management` 서버 namespace(Boot의 management 자식 컨텍스트)이면 참.
 * 웹 포트·MockMvc 요청은 거짓(경로가 `/actuator/`여도 웹 체인이 허용 목록 밖으로 거부한다).
 */
object ManagementServerRequestMatcher : RequestMatcher {
    override fun matches(request: HttpServletRequest): Boolean {
        val context = WebApplicationContextUtils.getWebApplicationContext(request.servletContext) ?: return false
        return WebServerApplicationContext.hasServerNamespace(context, MANAGEMENT_NAMESPACE)
    }

    private const val MANAGEMENT_NAMESPACE = "management"
}

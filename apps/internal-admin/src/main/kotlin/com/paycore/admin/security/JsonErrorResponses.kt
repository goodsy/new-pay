package com.paycore.admin.security

import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.access.AccessDeniedHandler

/**
 * 보안 거부 응답: JSON `{"error": <고정 code>}`만(§4.10 — stack·메시지·입력 echo 없음), `no-store`. SPA는 이 code로 오류 화면을 고른다.
 * 401 code: 세션 검사가 남긴 사유(`SESSION_REPLACED` — 다른 곳 로그인·세대 변경, `SESSION_EXPIRED` — 만료·손상·절대 한도) 또는 `UNAUTHENTICATED`.
 */
object JsonErrorResponses {

    fun unauthenticated(): AuthenticationEntryPoint = AuthenticationEntryPoint { request, response, _ ->
        val reason = request.getAttribute(AdminSecurityContextRepository.FAILURE_ATTRIBUTE) as? String
        write(response, HttpServletResponse.SC_UNAUTHORIZED, reason ?: "UNAUTHENTICATED")
    }

    fun forbidden(): AccessDeniedHandler =
        AccessDeniedHandler { _, response, _ -> write(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN") }

    fun write(response: HttpServletResponse, status: Int, code: String) {
        response.status = status
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        response.setHeader("Cache-Control", "no-store")
        response.writer.write("""{"error":"$code"}""")
    }
}

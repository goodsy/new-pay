package com.paycore.financial.acquiring.adapter.web.admin

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.core.Ordered
import org.springframework.stereotype.Component
import org.springframework.web.HttpMediaTypeNotAcceptableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.servlet.HandlerExceptionResolver
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.NoHandlerFoundException
import org.springframework.web.servlet.resource.NoResourceFoundException

/**
 * ADMIN 채널 경로의 Spring MVC 경로·method 오류를 고정 code 본문으로 바꾼다(리뷰 MINOR-1 — 명세 §3.3 "오류 본문 `{"error":"<CODE>"}`만"). 서명된 요청이 없는 경로·다른 method에
 * 닿으면 Spring은 405/404를 ERROR dispatch로 넘겨 Boot 기본 오류 본문(timestamp·path)을 만든다 — 이 resolver가 그 전에 `{"error":…}`로 끝낸다.
 *
 * `@RestControllerAdvice`가 아니라 resolver인 이유: 경로·method 불일치 예외는 handler가 정해지기 **전**에 나므로 패키지 범위 advice는 적용되지 않고, 범위 없는 advice는 결제 사실
 * Inbox 경로의 Spring 오류 응답(415 등 — FIN-FACT-RELAY-001 동작)까지 바꾼다. 여기서는 요청 경로가 `/internal/v1/admin/` 아래일 때만 처리하고 그 밖은 null(다음 resolver — 기존 동작
 * 그대로)을 돌려준다. 순서는 Spring 기본 resolver(composite, order 0)보다 앞이다.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class AdminChannelErrorResolver : HandlerExceptionResolver, Ordered {

    override fun resolveException(request: HttpServletRequest, response: HttpServletResponse, handler: Any?, ex: Exception): ModelAndView? {
        val path = request.servletPath + (request.pathInfo ?: "")
        if (!path.startsWith(PREFIX)) return null
        val (status, code) = when (ex) {
            is HttpRequestMethodNotSupportedException -> METHOD_NOT_ALLOWED to "METHOD_NOT_ALLOWED"
            is NoResourceFoundException, is NoHandlerFoundException -> NOT_FOUND to "NOT_FOUND"
            is HttpMediaTypeNotSupportedException -> UNSUPPORTED_MEDIA_TYPE to "UNSUPPORTED_MEDIA_TYPE"
            is HttpMediaTypeNotAcceptableException -> NOT_ACCEPTABLE to "NOT_ACCEPTABLE"
            else -> return null
        }
        AdminChannelResponses.write(request, response, status, code)
        return ModelAndView()
    }

    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE + 1

    private companion object {
        const val PREFIX = "/internal/v1/admin/"
        const val NOT_FOUND = 404
        const val METHOD_NOT_ALLOWED = 405
        const val NOT_ACCEPTABLE = 406
        const val UNSUPPORTED_MEDIA_TYPE = 415
    }
}

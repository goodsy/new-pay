package com.paycore.payment.payment.adapter.web

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.resource.NoResourceFoundException

/**
 * 라우팅 오류(없는 경로 404, 허용되지 않은 방법 405)를 공개 API 오류 형식으로 쓴다(CP-API-001 명세 §7.2 — 업무 표 밖 code). 인증은 보안 필터가
 * 먼저 한다(인증 없는 요청은 경로와 무관하게 401). 업무 오류는 각 Controller가 직접 매핑한다.
 */
@RestControllerAdvice
class ApiRoutingAdvice {

    @ExceptionHandler(NoResourceFoundException::class)
    fun notFound(request: HttpServletRequest): ResponseEntity<ByteArray> =
        ApiResponses.error(ApiError(ApiErrorCode.NOT_FOUND), ApiResponses.requestId(request))

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun methodNotAllowed(request: HttpServletRequest): ResponseEntity<ByteArray> =
        ApiResponses.error(ApiError(ApiErrorCode.METHOD_NOT_ALLOWED), ApiResponses.requestId(request))
}

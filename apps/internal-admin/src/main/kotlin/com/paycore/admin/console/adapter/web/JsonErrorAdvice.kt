package com.paycore.admin.console.adapter.web

import org.slf4j.LoggerFactory
import org.springframework.beans.TypeMismatchException
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageConversionException
import org.springframework.security.access.AccessDeniedException
import org.springframework.http.ResponseEntity
import org.springframework.web.ErrorResponse
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * MVC 안 오류의 공통 JSON 응답(§4.10 — `ErrorNonDisclosureSpec`): Spring MVC 표준 오류(없는 경로 404·메서드 405·형식 400 등)는 상태별 고정 code,
 * 그 밖의 예외는 500 `INTERNAL_ERROR`. 메시지·예외 이름·경로·stack을 내지 않고 로그에는 예외 클래스 이름만(§6). 모듈별 advice가 먼저다(가장 낮은 순서).
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
class JsonErrorAdvice {

    @ExceptionHandler(Exception::class)
    fun handle(e: Exception): ResponseEntity<String> {
        // 권한 거부는 Security(ExceptionTranslationFilter → 403 FORBIDDEN)가 처리한다 — 여기서 500으로 흡수하지 않는다(S4 리뷰 m-2).
        if (e is AccessDeniedException) throw e
        // 형식 오류(읽을 수 없는 본문·형 변환 실패)는 400(S4 리뷰 m-2).
        if (e is HttpMessageConversionException || e is TypeMismatchException) return JsonErrors.response(HttpStatus.BAD_REQUEST.value())
        if (e is ErrorResponse) return JsonErrors.response(e.statusCode.value())
        log.error("operation=web result=failed failure={}", e.javaClass.name)
        return JsonErrors.response(HttpStatus.INTERNAL_SERVER_ERROR.value())
    }

    private companion object {
        val log = LoggerFactory.getLogger(JsonErrorAdvice::class.java)
    }
}

/** 상태 → 고정 code. */
internal object JsonErrors {
    fun code(status: Int): String = when (status) {
        400 -> "BAD_REQUEST"
        401 -> "UNAUTHENTICATED"
        403 -> "FORBIDDEN"
        404 -> "NOT_FOUND"
        405 -> "METHOD_NOT_ALLOWED"
        409 -> "CONFLICT"
        415 -> "UNSUPPORTED_MEDIA_TYPE"
        in 400..499 -> "BAD_REQUEST"
        503 -> "UNAVAILABLE"
        else -> "INTERNAL_ERROR"
    }

    /** 본문은 고정 JSON 문자열, Content-Type을 미리 정해 Accept 협상(406)을 타지 않는다(브라우저 Accept: text/html에도 같은 JSON). */
    fun response(status: Int): ResponseEntity<String> =
        ResponseEntity.status(status).cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON).body("{\"error\":\"" + code(status) + "\"}")
}

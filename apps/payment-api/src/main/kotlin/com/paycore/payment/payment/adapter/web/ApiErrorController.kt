package com.paycore.payment.payment.adapter.web

import jakarta.servlet.RequestDispatcher
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.boot.webmvc.error.ErrorController
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 컨테이너 오류 dispatch(`/error`)를 공개 API 오류 형식으로 쓴다(review round1 n-3). Boot 기본 `BasicErrorController`(경로·timestamp·오류 이름을
 * 담는 JSON)를 대신한다 — 요청 경로를 echo하지 않는다. 상태 → code: 400 INVALID_REQUEST(그 밖 4xx 포함), 401 UNAUTHENTICATED, 404 NOT_FOUND,
 * 405 METHOD_NOT_ALLOWED, 5xx INTERNAL_ERROR.
 */
@RestController
class ApiErrorController : ErrorController {
    private val log = LoggerFactory.getLogger(ApiErrorController::class.java)

    @RequestMapping("\${server.error.path:\${error.path:/error}}")
    fun error(request: HttpServletRequest): ResponseEntity<ByteArray> {
        val status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) as? Int ?: STATUS_WHEN_CALLED_DIRECTLY
        val code = codeFor(status)
        if (code == ApiErrorCode.INTERNAL_ERROR) {
            val exception = request.getAttribute(RequestDispatcher.ERROR_EXCEPTION) as? Throwable
            log.error("operation=ErrorDispatch correlationId={} result=INTERNAL_ERROR status={} exception={}",
                ApiResponses.requestId(request), status, exception?.let(SafeThrowable::summary))
        }
        return ApiResponses.error(ApiError(code), ApiResponses.requestId(request))
    }

    companion object {
        /** 오류 dispatch 없이 `/error`를 직접 부른 경우(인증된 요청만 여기까지 온다) — 없는 자원으로 본다. */
        private const val STATUS_WHEN_CALLED_DIRECTLY = 404

        fun codeFor(status: Int): ApiErrorCode = when {
            status == 401 -> ApiErrorCode.UNAUTHENTICATED
            status == 404 -> ApiErrorCode.NOT_FOUND
            status == 405 -> ApiErrorCode.METHOD_NOT_ALLOWED
            status in 400..499 -> ApiErrorCode.INVALID_REQUEST
            else -> ApiErrorCode.INTERNAL_ERROR
        }
    }
}

package com.paycore.payment.payment.adapter.web

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** 요청 상관 ID와 JSON 응답 작성(필터·컨트롤러 공통). */
object ApiResponses {
    const val REQUEST_ID_HEADER = "PayCore-Request-Id"
    const val REPLAYED_HEADER = "Idempotent-Replayed"

    /** 로그 MDC 키(요청 상관 ID). entitlement 모듈의 같은 이름 상수와 값이 같아야 한다(시험이 HTTP→entitlement 로그로 확인). */
    const val CORRELATION_ID_MDC_KEY = "correlationId"
    private const val REQUEST_ID_ATTRIBUTE = "paycore.requestId"

    val mapper: JsonMapper = JsonMapper.builder().build()

    /** 이 요청의 서버 생성 requestId(진입 필터가 정한다. 필터를 거치지 않은 경로에서는 여기서 만든다). */
    fun requestId(request: HttpServletRequest): String =
        (request.getAttribute(REQUEST_ID_ATTRIBUTE) as? String) ?: UUID.randomUUID().toString().also {
            request.setAttribute(REQUEST_ID_ATTRIBUTE, it)
        }

    fun errorBody(error: ApiError, requestId: String): ByteArray {
        val body = linkedMapOf<String, Any>(
            "code" to error.code.wireCode,
            "message" to error.code.message,
            "retryable" to error.code.retryable,
            "requestId" to requestId,
        )
        error.field?.let { body["field"] = it }
        error.reason?.let { body["reason"] = it }
        error.referencePaymentId?.let { body["referencePaymentId"] = it }
        return mapper.writeValueAsBytes(mapOf("error" to body))
    }

    fun error(error: ApiError, requestId: String, headers: HttpHeaders = HttpHeaders()): ResponseEntity<ByteArray> =
        ResponseEntity.status(error.code.status).headers(headers).header(REQUEST_ID_HEADER, requestId)
            .contentType(MediaType.APPLICATION_JSON).body(errorBody(error, requestId))

    /** 필터 단계(컨트롤러 밖)에서 오류를 쓴다. */
    fun write(response: HttpServletResponse, error: ApiError, requestId: String, extraHeaders: Map<String, String> = emptyMap()) {
        response.status = error.code.status.value()
        response.setHeader(REQUEST_ID_HEADER, requestId)
        extraHeaders.forEach(response::setHeader)
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.outputStream.write(errorBody(error, requestId))
    }
}

package com.paycore.master.merchant.adapter.web.admin

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * ADMIN 채널 응답(MERCHANT-OPS-001 §3.1): 오류 본문 `{"error":"<CODE>"}`(고정 code만 — 요청 값 없음, 특히 사업자등록번호를 되돌려 주지 않는다), 요청 ID는 헤더
 * `PayCore-Request-Id`로만(요청마다 새 UUID — 상관용, 비밀 아님), 시각은 `…T…Z` ms 3자리. `Cache-Control: no-store`는 보안 체인 기본 헤더가 붙인다.
 * 값이 없는 필드도 `null`로 내보낸다(응답 모양 고정 — ADMIN의 허용 필드 검사가 모양을 본다).
 */
object AdminChannelResponses {
    const val REQUEST_ID_HEADER = "PayCore-Request-Id"
    const val UNAUTHENTICATED = "UNAUTHENTICATED"
    const val QUERY_NOT_ALLOWED = "QUERY_NOT_ALLOWED"
    const val REQUEST_TOO_LARGE = "REQUEST_TOO_LARGE"
    const val REQUEST_UNREADABLE = "REQUEST_UNREADABLE"
    const val REQUEST_MALFORMED = "REQUEST_MALFORMED"
    const val MERCHANT_NOT_FOUND = "MERCHANT_NOT_FOUND"
    const val TEMPORARILY_UNAVAILABLE = "TEMPORARILY_UNAVAILABLE"
    const val ADMIN_READ_BUSY = "ADMIN_READ_BUSY"

    // MERCHANT-OPS-002 §3.3 명령 응답 code(요청 값 없음).
    const val ADMIN_WRITE_BUSY = "ADMIN_WRITE_BUSY"
    const val IDEMPOTENCY_KEY_REUSED = "IDEMPOTENCY_KEY_REUSED"
    const val REQUEST_IN_PROGRESS = "REQUEST_IN_PROGRESS"
    const val OUTCOME_UNKNOWN = "OUTCOME_UNKNOWN"

    private const val REQUEST_ID_ATTRIBUTE = "paycore.master.admin.requestId"
    private val mapper: JsonMapper = JsonMapper.builder().build()
    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    fun requestId(request: HttpServletRequest): String =
        (request.getAttribute(REQUEST_ID_ATTRIBUTE) as? String) ?: UUID.randomUUID().toString().also { request.setAttribute(REQUEST_ID_ATTRIBUTE, it) }

    fun write(request: HttpServletRequest, response: HttpServletResponse, status: Int, code: String) {
        response.status = status
        response.setHeader(REQUEST_ID_HEADER, requestId(request))
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.outputStream.write(mapper.writeValueAsBytes(mapOf("error" to code)))
    }

    fun json(request: HttpServletRequest, status: Int, body: Any): ResponseEntity<ByteArray> =
        ResponseEntity.status(status).header(REQUEST_ID_HEADER, requestId(request)).contentType(MediaType.APPLICATION_JSON).body(mapper.writeValueAsBytes(body))

    fun error(request: HttpServletRequest, status: Int, code: String): ResponseEntity<ByteArray> = json(request, status, mapOf("error" to code))

    fun time(at: Instant): String = TIME.format(at)
}

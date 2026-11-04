package com.paycore.admin.paymentops.adapter.web

import com.paycore.admin.account.application.provided.AdminRequestContext
import com.paycore.admin.paymentops.application.provided.TransactionLookupResult
import com.paycore.admin.paymentops.application.provided.TransactionLookupUseCase
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 화면 거래 조회(TXN-OPS-001 §4.1). 모두 `no-store`. 검색은 POST 본문(R-2 — MID·주문번호가 URL·접근 로그·브라우저 기록에 남지 않게, SPA CSRF 규칙 적용), 감사 없음.
 * 상세는 Use Case가 `PAYMENT_VIEWED` 감사를 기록한 뒤에만 200. 응답: 200 / 400 `INVALID_INPUT` / 403 `FORBIDDEN` / 404 `PAYMENT_NOT_FOUND` / 413 `REQUEST_TOO_LARGE`·
 * 415 `UNSUPPORTED_MEDIA_TYPE`(검색만 — 본문 해석 전, 감사 없음, 지표만) / 429 `RATE_LIMITED` / 503 `PAYMENT_UNAVAILABLE`·`AUDIT_UNAVAILABLE`(원인 분류는 응답에 넣지 않는다).
 * 오류 응답에 입력 값을 넣지 않는다.
 */
@RestController
class TransactionController(private val lookup: TransactionLookupUseCase, private val registry: MeterRegistry) {

    /**
     * 검색 본문은 바이트로 받아 [TransactionSearchBodyParser]로 엄격 해석한다(Spring 기본 Jackson의 중복 key·모르는 필드·자료형 강제 변환을 받지 않는다). 읽기 전 거절(MERCHANT-OPS-001
     * Q-MOPS-08과 같은 방식): Content-Type이 JSON이 아니면 415(본문을 읽지 않는다), 선언 길이 또는 실제 읽은 바이트가 [MAX_SEARCH_BODY_BYTES]를 넘으면 413(상한 + 1바이트까지만 읽는다).
     */
    @PostMapping("/api/payment-searches")
    fun search(authentication: Authentication, request: HttpServletRequest): ResponseEntity<out Any> {
        if (!isJson(request.contentType)) return rejectBody(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE")
        if (request.contentLengthLong > MAX_SEARCH_BODY_BYTES) return rejectBody(HttpStatus.CONTENT_TOO_LARGE, "REQUEST_TOO_LARGE")
        val body = request.inputStream.readNBytes(MAX_SEARCH_BODY_BYTES + 1)
        if (body.size > MAX_SEARCH_BODY_BYTES) return rejectBody(HttpStatus.CONTENT_TOO_LARGE, "REQUEST_TOO_LARGE")
        val criteria = TransactionSearchBodyParser.parse(body)
        return respond(if (criteria == null) lookup.rejectMalformedSearch(authentication.name) else lookup.search(authentication.name, criteria))
    }

    @GetMapping("/api/payments/{paymentId}")
    fun detail(@PathVariable paymentId: String, authentication: Authentication, request: HttpServletRequest): ResponseEntity<out Any> =
        respond(lookup.detail(authentication.name, paymentId, AdminRequestContext(UUID.randomUUID().toString(), request.remoteAddr?.take(REMOTE_IP_MAX))))

    private fun isJson(contentType: String?): Boolean =
        contentType != null && runCatching { MediaType.APPLICATION_JSON.equalsTypeAndSubtype(MediaType.parseMediaType(contentType)) }.getOrDefault(false)

    private fun rejectBody(status: HttpStatus, code: String): ResponseEntity<Any> {
        registry.counter(SEARCH_BODY_REJECTED_METRIC, "reason", if (status == HttpStatus.UNSUPPORTED_MEDIA_TYPE) code else "TOO_LARGE").increment()
        return error(status, code)
    }

    private fun respond(result: TransactionLookupResult): ResponseEntity<out Any> = when (result) {
        is TransactionLookupResult.Found -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result.document)
        TransactionLookupResult.InvalidInput -> error(HttpStatus.BAD_REQUEST, "INVALID_INPUT")
        TransactionLookupResult.Forbidden -> error(HttpStatus.FORBIDDEN, "FORBIDDEN")
        TransactionLookupResult.NotFound -> error(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND")
        TransactionLookupResult.RateLimited -> error(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED")
        TransactionLookupResult.AuditUnavailable -> error(HttpStatus.SERVICE_UNAVAILABLE, "AUDIT_UNAVAILABLE")
        is TransactionLookupResult.Unavailable -> error(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENT_UNAVAILABLE")
    }

    private fun error(status: HttpStatus, code: String): ResponseEntity<Any> = ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(mapOf("error" to code))

    companion object {
        /** 검색 본문 상한(MERCHANT-OPS-001 Q-MOPS-08과 같은 값 — Demo 값, 운영값 아님). */
        const val MAX_SEARCH_BODY_BYTES = 16 * 1024

        /** 내용을 읽지 않고 거절한 검색 본문 수(감사 없음). label `reason` = TOO_LARGE·UNSUPPORTED_MEDIA_TYPE. */
        const val SEARCH_BODY_REJECTED_METRIC = "paycore.admin.ops.transaction_search_body_rejected"
        private const val REMOTE_IP_MAX = 45
    }
}

package com.paycore.admin.financialops.adapter.web

import com.paycore.admin.account.application.provided.AdminRequestContext
import com.paycore.admin.financialops.application.provided.AcquisitionLookupResult
import com.paycore.admin.financialops.application.provided.AcquisitionLookupUseCase
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
 * 화면 매입 현황 조회(ACQ-VIEW-001 §4.6 — 읽기 전용). 모두 `no-store`. 검색·레코드 쪽은 POST 본문(R-4 — SPA CSRF 규칙 적용). 해석되지 않은 본문은 컨트롤러가 400으로 끊지 않고
 * Use Case `rejectMalformedSearch`에 넘긴다(역할 판정이 먼저 — 조회 역할 없는 계정은 형식 오류여도 403, B2 리뷰 MINOR-2·TXN과 같은 규칙).
 * 200 / 400 `INVALID_INPUT` / 403 `FORBIDDEN` / 404 `ACQUISITION_FILE_NOT_FOUND` / 413 `REQUEST_TOO_LARGE`·415 `UNSUPPORTED_MEDIA_TYPE`(POST — 본문을 읽기 전, 감사 없음) /
 * 429 `RATE_LIMITED` / 503 `FINANCIAL_UNAVAILABLE`·`AUDIT_UNAVAILABLE`(원인 분류는 응답에 넣지 않는다). 오류 응답에 입력 값을 넣지 않는다.
 */
@RestController
class AcquisitionController(private val lookup: AcquisitionLookupUseCase, private val registry: MeterRegistry) {

    @PostMapping("/api/acquisition-file-searches")
    fun search(authentication: Authentication, request: HttpServletRequest): ResponseEntity<out Any> {
        val body = readBody(request) ?: return rejected(request)
        val criteria = AcquisitionSearchBodyParser.files(body)
        return respond(if (criteria == null) lookup.rejectMalformedSearch(authentication.name) else lookup.search(authentication.name, criteria))
    }

    @GetMapping("/api/acquisition-files/{fileId}")
    fun detail(@PathVariable fileId: String, authentication: Authentication, request: HttpServletRequest): ResponseEntity<out Any> =
        respond(lookup.detail(authentication.name, fileId, context(request)))

    @PostMapping("/api/acquisition-files/{fileId}/record-searches")
    fun records(@PathVariable fileId: String, authentication: Authentication, request: HttpServletRequest): ResponseEntity<out Any> {
        val body = readBody(request) ?: return rejected(request)
        val criteria = AcquisitionSearchBodyParser.records(body)
        return respond(if (criteria == null) lookup.rejectMalformedSearch(authentication.name) else lookup.records(authentication.name, fileId, criteria, context(request)))
    }

    /** 읽기 전 거절(MERCHANT-OPS-001 Q-MOPS-08과 같은 규칙): JSON이 아니면 읽지 않고, 선언 길이·실제 바이트가 상한을 넘으면 상한 + 1바이트까지만 읽고 거절(null). */
    private fun readBody(request: HttpServletRequest): ByteArray? {
        if (!isJson(request.contentType) || request.contentLengthLong > MAX_BODY_BYTES) return null
        return request.inputStream.readNBytes(MAX_BODY_BYTES + 1).takeIf { it.size <= MAX_BODY_BYTES }
    }

    private fun rejected(request: HttpServletRequest): ResponseEntity<Any> {
        val (status, code) = if (isJson(request.contentType)) HttpStatus.CONTENT_TOO_LARGE to "REQUEST_TOO_LARGE" else HttpStatus.UNSUPPORTED_MEDIA_TYPE to "UNSUPPORTED_MEDIA_TYPE"
        registry.counter(BODY_REJECTED_METRIC, "reason", if (status == HttpStatus.UNSUPPORTED_MEDIA_TYPE) code else "TOO_LARGE").increment()
        return error(status, code)
    }

    private fun isJson(contentType: String?): Boolean =
        contentType != null && runCatching { MediaType.APPLICATION_JSON.equalsTypeAndSubtype(MediaType.parseMediaType(contentType)) }.getOrDefault(false)

    private fun respond(result: AcquisitionLookupResult): ResponseEntity<out Any> = when (result) {
        is AcquisitionLookupResult.Found -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result.document)
        AcquisitionLookupResult.InvalidInput -> error(HttpStatus.BAD_REQUEST, "INVALID_INPUT")
        AcquisitionLookupResult.Forbidden -> error(HttpStatus.FORBIDDEN, "FORBIDDEN")
        AcquisitionLookupResult.NotFound -> error(HttpStatus.NOT_FOUND, "ACQUISITION_FILE_NOT_FOUND")
        AcquisitionLookupResult.RateLimited -> error(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED")
        AcquisitionLookupResult.AuditUnavailable -> error(HttpStatus.SERVICE_UNAVAILABLE, "AUDIT_UNAVAILABLE")
        is AcquisitionLookupResult.Unavailable -> error(HttpStatus.SERVICE_UNAVAILABLE, "FINANCIAL_UNAVAILABLE")
    }

    private fun error(status: HttpStatus, code: String): ResponseEntity<Any> = ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(mapOf("error" to code))

    private fun context(request: HttpServletRequest) = AdminRequestContext(UUID.randomUUID().toString(), request.remoteAddr?.take(REMOTE_IP_MAX))

    companion object {
        /** 화면 본문 상한 — Demo 값(FINANCIAL 채널 16 KiB와 같다, 운영값 아님). */
        const val MAX_BODY_BYTES = 16 * 1024

        /** 내용을 읽지 않고 거절한 본문 수(감사 없음). label `reason` = TOO_LARGE·UNSUPPORTED_MEDIA_TYPE. */
        const val BODY_REJECTED_METRIC = "paycore.admin.financial_ops.body_rejected"
        private const val REMOTE_IP_MAX = 45
    }
}

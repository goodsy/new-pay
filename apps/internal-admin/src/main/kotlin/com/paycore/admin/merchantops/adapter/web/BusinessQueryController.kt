package com.paycore.admin.merchantops.adapter.web

import com.paycore.admin.account.application.provided.AdminRequestContext
import com.paycore.admin.merchantops.application.provided.BusinessLookupUseCase
import com.paycore.admin.merchantops.application.provided.BusinessQueryResult
import com.paycore.admin.merchantops.application.provided.BusinessQueryUseCase
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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 화면 사업자 조회(BUSINESS-PROFILE-001 §7.1 — BIZ-3). 세션 + CSRF(보안 체인), 모두 `no-store`, 오류 본문 `{"error":"<CODE>"}`만(입력 값 없음).
 * 검색·번호 조회는 POST 본문(번호·상호 접두가 URL·접근 로그·브라우저 기록에 남지 않게 — C-6). 읽기 전 거절(감사 없음 — Q-MOPS-08): JSON이 아닌 Content-Type 415
 * `UNSUPPORTED_MEDIA_TYPE`(본문을 읽지 않는다), 16 KiB 초과 413 `REQUEST_TOO_LARGE`(상한 + 1바이트까지만 읽는다).
 * 200 = MASTER 문서(모양 검사 통과분 — 화면은 `businessId`를 그리지 않는다) / 400 `INVALID_INPUT` / 403 `FORBIDDEN` / 404 `BUSINESS_NOT_FOUND` / 429 `RATE_LIMITED` /
 * 503 `MASTER_UNAVAILABLE`·`AUDIT_UNAVAILABLE`(원인 분류는 응답에 넣지 않는다).
 */
@RestController
class BusinessQueryController(private val query: BusinessQueryUseCase, private val lookups: BusinessLookupUseCase, private val registry: MeterRegistry) {

    @PostMapping("/api/businesses/searches")
    fun search(authentication: Authentication, request: HttpServletRequest): ResponseEntity<out Any> {
        val body = readBody(request) ?: return rejectedBody(request)
        val criteria = BusinessSearchBodyParser.parse(body) ?: return respond(query.rejectMalformedSearch(authentication.name))
        return respond(query.search(authentication.name, criteria, context(request)))
    }

    /** 합친 MERCHANT-BIZ-LOOKUP-001: 단일 목적 endpoint — 413·415 뒤 모든 본문이 시도(해석 실패도 감사 `brn=INVALID`, BIZ-LOOKUP C-5). */
    @PostMapping("/api/businesses/registration-number-lookups")
    fun lookup(authentication: Authentication, request: HttpServletRequest): ResponseEntity<out Any> {
        val body = readBody(request) ?: return rejectedBody(request)
        return respond(lookups.lookup(authentication.name, BusinessSearchBodyParser.lookupNumber(body), context(request)))
    }

    @GetMapping("/api/businesses/{businessId}")
    fun detail(@PathVariable businessId: String, authentication: Authentication, request: HttpServletRequest): ResponseEntity<out Any> =
        respond(query.detail(authentication.name, businessId, context(request)))

    @GetMapping("/api/businesses/{businessId}/history")
    fun history(@PathVariable businessId: String, @RequestParam(required = false) after: String?, authentication: Authentication): ResponseEntity<out Any> {
        val afterSeq = when {
            after == null -> null
            SEQ.matches(after) -> after.toLongOrNull() ?: return error(HttpStatus.BAD_REQUEST, "INVALID_INPUT")
            else -> return error(HttpStatus.BAD_REQUEST, "INVALID_INPUT")
        }
        return respond(query.history(authentication.name, businessId, afterSeq))
    }

    /** 읽기 전 거절(415·413)이면 null — 호출자가 [rejectedBody]로 응답한다. */
    private fun readBody(request: HttpServletRequest): ByteArray? {
        if (!isJson(request.contentType) || request.contentLengthLong > MAX_BODY_BYTES) return null
        val body = request.inputStream.readNBytes(MAX_BODY_BYTES + 1)
        return body.takeIf { it.size <= MAX_BODY_BYTES }
    }

    private fun rejectedBody(request: HttpServletRequest): ResponseEntity<Any> {
        val unsupported = !isJson(request.contentType)
        registry.counter(BODY_REJECTED_METRIC, "reason", if (unsupported) "UNSUPPORTED_MEDIA_TYPE" else "TOO_LARGE").increment()
        return if (unsupported) error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE") else error(HttpStatus.CONTENT_TOO_LARGE, "REQUEST_TOO_LARGE")
    }

    private fun respond(result: BusinessQueryResult): ResponseEntity<out Any> = when (result) {
        is BusinessQueryResult.Found -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result.document)
        BusinessQueryResult.InvalidInput -> error(HttpStatus.BAD_REQUEST, "INVALID_INPUT")
        BusinessQueryResult.Forbidden -> error(HttpStatus.FORBIDDEN, "FORBIDDEN")
        BusinessQueryResult.NotFound -> error(HttpStatus.NOT_FOUND, "BUSINESS_NOT_FOUND")
        BusinessQueryResult.RateLimited -> error(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED")
        BusinessQueryResult.AuditUnavailable -> error(HttpStatus.SERVICE_UNAVAILABLE, "AUDIT_UNAVAILABLE")
        is BusinessQueryResult.Unavailable -> error(HttpStatus.SERVICE_UNAVAILABLE, "MASTER_UNAVAILABLE")
    }

    private fun isJson(contentType: String?): Boolean =
        contentType != null && runCatching { MediaType.APPLICATION_JSON.equalsTypeAndSubtype(MediaType.parseMediaType(contentType)) }.getOrDefault(false)

    private fun error(status: HttpStatus, code: String): ResponseEntity<Any> = ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(mapOf("error" to code))

    private fun context(request: HttpServletRequest) = AdminRequestContext(UUID.randomUUID().toString(), request.remoteAddr?.take(REMOTE_IP_MAX))

    companion object {
        /** 검색·번호 조회 본문 상한 — Demo 값(MASTER 채널 16 KiB와 같다, 운영값 아님). */
        const val MAX_BODY_BYTES = 16 * 1024

        /** 내용을 읽지 않고 거절한 사업자 본문 수(감사 없음). label `reason` = TOO_LARGE·UNSUPPORTED_MEDIA_TYPE. */
        const val BODY_REJECTED_METRIC = "paycore.admin.merchant_ops.business_body_rejected"
        private val SEQ = Regex("^[0-9]{1,19}$")
        private const val REMOTE_IP_MAX = 45
    }
}

package com.paycore.admin.merchantops.adapter.web

import com.paycore.admin.account.application.provided.AdminRequestContext
import com.paycore.admin.merchantops.application.provided.MerchantLookupResult
import com.paycore.admin.merchantops.application.provided.MerchantLookupUseCase
import com.paycore.admin.merchantops.application.provided.MerchantPendingApprovals
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
 * 화면 가맹점 조회(MERCHANT-OPS-001 §4.1). 모두 `no-store`. 검색은 POST 본문(등록번호가 URL·접근 로그·브라우저 기록에 남지 않게 — R-3, SPA CSRF 규칙 적용).
 * 200 / 400 `INVALID_INPUT` / 403 `FORBIDDEN` / 404 `MERCHANT_NOT_FOUND` / 413 `REQUEST_TOO_LARGE`·415 `UNSUPPORTED_MEDIA_TYPE`(검색만) / 429 `RATE_LIMITED` /
 * 503 `MASTER_UNAVAILABLE`·`AUDIT_UNAVAILABLE`(원인 분류는 응답에 넣지 않는다).
 * 오류 응답에 입력 값(특히 등록번호)을 넣지 않는다.
 */
@RestController
class MerchantController(private val lookup: MerchantLookupUseCase, private val registry: MeterRegistry, private val pending: MerchantPendingApprovals) {

    /**
     * 검색 본문은 바이트로 받아 [MerchantSearchBodyParser]로 엄격 해석한다(Q-MOPS-07 — Spring 기본 Jackson의 중복 key·모르는 필드·자료형 강제 변환을 받지 않는다).
     * 해석 실패는 본문 원문 없이 "등록번호 검색 시도 여부"만 Use Case에 넘긴다(시도면 §5.2 순서로 감사, 아니면 감사 없는 400).
     * 읽기 전 거절(Q-MOPS-08 — 사용자 답변 "거절만, 감사 없음"): Content-Type이 JSON이 아니면 415 `UNSUPPORTED_MEDIA_TYPE`(본문을 읽지 않는다), 선언 길이 또는 실제로 읽은 바이트가
     * [MAX_SEARCH_BODY_BYTES]를 넘으면 413 `REQUEST_TOO_LARGE`(상한 + 1바이트까지만 읽는다 — 해석하지 않으니 시도 판정·감사 없음). 둘 다 지표 [SEARCH_BODY_REJECTED_METRIC]만 센다.
     * Content-Type 검사를 매핑 `consumes`가 아니라 여기서 하는 이유: 다른 오류와 같은 `{"error":CODE}` + no-store 응답을 쓰기 위해서.
     */
    @PostMapping("/api/merchant-searches")
    fun search(authentication: Authentication, request: HttpServletRequest): ResponseEntity<out Any> {
        if (!isJson(request.contentType)) return rejectBody(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE")
        if (request.contentLengthLong > MAX_SEARCH_BODY_BYTES) return rejectBody(HttpStatus.CONTENT_TOO_LARGE, "REQUEST_TOO_LARGE")
        val body = request.inputStream.readNBytes(MAX_SEARCH_BODY_BYTES + 1)
        if (body.size > MAX_SEARCH_BODY_BYTES) return rejectBody(HttpStatus.CONTENT_TOO_LARGE, "REQUEST_TOO_LARGE")
        return respond(
            when (val parsed = MerchantSearchBodyParser.parse(body)) {
                is MerchantSearchBodyParser.Parsed.Criteria -> lookup.search(authentication.name, parsed.criteria, context(request))
                is MerchantSearchBodyParser.Parsed.Malformed -> lookup.rejectMalformedSearch(authentication.name, parsed.registrationNumberAttempt, context(request))
            },
        )
    }

    private fun isJson(contentType: String?): Boolean =
        contentType != null && runCatching { MediaType.APPLICATION_JSON.equalsTypeAndSubtype(MediaType.parseMediaType(contentType)) }.getOrDefault(false)

    private fun rejectBody(status: HttpStatus, code: String): ResponseEntity<Any> {
        registry.counter(SEARCH_BODY_REJECTED_METRIC, "reason", if (status == HttpStatus.UNSUPPORTED_MEDIA_TYPE) code else "TOO_LARGE").increment()
        return error(status, code)
    }

    @GetMapping("/api/merchants/{merchantId}")
    fun detail(@PathVariable merchantId: String, authentication: Authentication, request: HttpServletRequest): ResponseEntity<out Any> =
        when (val result = lookup.detail(authentication.name, merchantId, context(request))) {
            // MERCHANT-OPS-002 C-24: ADMIN DB의 미해결 승인(승인 목록 권한 역할에게만 — 그 밖은 빈 배열). MASTER 문서 뒤에 붙인다(MASTER 응답 모양은 그대로).
            is MerchantLookupResult.Found -> respond(
                MerchantLookupResult.Found(
                    LinkedHashMap(result.document).apply {
                        put("pendingApprovals", pending.pendingFor(authentication.name, merchantId).map { linkedMapOf("approvalId" to it.approvalId, "kind" to it.kind, "status" to it.status, "revisionNo" to it.revisionNo) })
                    },
                ),
            )
            else -> respond(result)
        }

    @GetMapping("/api/merchants/{merchantId}/history")
    fun history(@PathVariable merchantId: String, @RequestParam(required = false) after: String?, authentication: Authentication): ResponseEntity<out Any> {
        val afterSeq = when {
            after == null -> null
            SEQ.matches(after) -> after.toLongOrNull() ?: return error(HttpStatus.BAD_REQUEST, "INVALID_INPUT")
            else -> return error(HttpStatus.BAD_REQUEST, "INVALID_INPUT")
        }
        return respond(lookup.history(authentication.name, merchantId, afterSeq))
    }

    private fun respond(result: MerchantLookupResult): ResponseEntity<out Any> = when (result) {
        is MerchantLookupResult.Found -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result.document)
        MerchantLookupResult.InvalidInput -> error(HttpStatus.BAD_REQUEST, "INVALID_INPUT")
        MerchantLookupResult.Forbidden -> error(HttpStatus.FORBIDDEN, "FORBIDDEN")
        MerchantLookupResult.NotFound -> error(HttpStatus.NOT_FOUND, "MERCHANT_NOT_FOUND")
        MerchantLookupResult.RateLimited -> error(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED")
        MerchantLookupResult.AuditUnavailable -> error(HttpStatus.SERVICE_UNAVAILABLE, "AUDIT_UNAVAILABLE")
        is MerchantLookupResult.Unavailable -> error(HttpStatus.SERVICE_UNAVAILABLE, "MASTER_UNAVAILABLE")
    }

    private fun error(status: HttpStatus, code: String): ResponseEntity<Any> = ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(mapOf("error" to code))

    private fun context(request: HttpServletRequest) = AdminRequestContext(UUID.randomUUID().toString(), request.remoteAddr?.take(REMOTE_IP_MAX))

    companion object {
        /** 검색 본문 상한(Q-MOPS-08) — Demo 값(MASTER 채널 16 KiB와 같다, 운영값 아님). */
        const val MAX_SEARCH_BODY_BYTES = 16 * 1024

        /** 내용을 읽지 않고 거절한 검색 본문 수(감사 없음 — Q-MOPS-08). label `reason` = TOO_LARGE·UNSUPPORTED_MEDIA_TYPE. */
        const val SEARCH_BODY_REJECTED_METRIC = "paycore.admin.merchant_ops.search_body_rejected"
        private val SEQ = Regex("^[0-9]{1,19}$")
        private const val REMOTE_IP_MAX = 45
    }
}

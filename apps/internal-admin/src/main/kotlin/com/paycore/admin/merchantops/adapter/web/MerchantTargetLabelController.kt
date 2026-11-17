package com.paycore.admin.merchantops.adapter.web

import com.paycore.admin.merchantops.application.provided.MerchantTargetLabelResult
import com.paycore.admin.merchantops.application.provided.MerchantTargetLabelUseCase
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 화면 가맹점 대상 라벨(MERCHANT-CODE-001 §5.6.3 MC-3b — `POST /api/merchant-labels`, 세션 + CSRF는 SPA 보안 체인, 모두 `no-store`, 감사 없음).
 * 읽기 전 거절: JSON이 아니면 415 `UNSUPPORTED_MEDIA_TYPE`, 16 KiB 초과 413 `REQUEST_TOO_LARGE`(상한 + 1바이트까지만 읽는다).
 * 200 `{"items":[{"merchantId","name","code"}],"labels":"OK"|"UNAVAILABLE"}`(merchantId 오름차순 — 화면 대응용·그리지 않는다, UNAVAILABLE이면 items `[]`, **HTTP 200**) /
 * 400 `INVALID_INPUT` / 403 `FORBIDDEN` / 429 `RATE_LIMITED`(라벨 전용 분당 창 초과 — 화면은 UNAVAILABLE과 같게 다룬다, C-35). 오류 응답에 입력 값을 넣지 않는다.
 */
@RestController
class MerchantTargetLabelController(private val useCase: MerchantTargetLabelUseCase) {

    @PostMapping("/api/merchant-labels")
    fun labels(authentication: Authentication, request: HttpServletRequest): ResponseEntity<out Any> {
        if (!isJson(request.contentType)) return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE")
        if (request.contentLengthLong > MAX_BODY_BYTES) return error(HttpStatus.CONTENT_TOO_LARGE, "REQUEST_TOO_LARGE")
        val body = request.inputStream.readNBytes(MAX_BODY_BYTES + 1)
        if (body.size > MAX_BODY_BYTES) return error(HttpStatus.CONTENT_TOO_LARGE, "REQUEST_TOO_LARGE")
        return when (val result = useCase.labels(authentication.name, MerchantLabelBodyParser.parse(body))) {
            is MerchantTargetLabelResult.Found -> ok(
                result.labels.entries.sortedBy { it.key }.map { (merchantId, label) -> linkedMapOf("merchantId" to merchantId, "name" to label.name, "code" to label.code) },
                OK,
            )
            is MerchantTargetLabelResult.Unavailable -> ok(emptyList(), UNAVAILABLE)
            MerchantTargetLabelResult.Forbidden -> error(HttpStatus.FORBIDDEN, "FORBIDDEN")
            MerchantTargetLabelResult.InvalidInput -> error(HttpStatus.BAD_REQUEST, "INVALID_INPUT")
            MerchantTargetLabelResult.RateLimited -> error(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED")
        }
    }

    private fun ok(items: List<Map<String, String>>, labels: String): ResponseEntity<Any> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(linkedMapOf("items" to items, "labels" to labels))

    private fun isJson(contentType: String?): Boolean =
        contentType != null && runCatching { MediaType.APPLICATION_JSON.equalsTypeAndSubtype(MediaType.parseMediaType(contentType)) }.getOrDefault(false)

    private fun error(status: HttpStatus, code: String): ResponseEntity<Any> = ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(mapOf("error" to code))

    companion object {
        /** 본문 상한(§5.6.3 — MASTER 채널 16 KiB와 같다, Demo 값). */
        const val MAX_BODY_BYTES = 16 * 1024
        private const val OK = "OK"
        private const val UNAVAILABLE = "UNAVAILABLE"
    }
}

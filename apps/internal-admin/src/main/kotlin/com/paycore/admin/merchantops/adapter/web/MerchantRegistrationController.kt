package com.paycore.admin.merchantops.adapter.web

import com.paycore.admin.account.application.provided.AdminRequestContext
import com.paycore.admin.merchantops.application.provided.MerchantRegistrationResult
import com.paycore.admin.merchantops.application.provided.MerchantRegistrationUseCase
import com.paycore.admin.merchantops.application.provided.RegistrationKind
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 화면 가맹점 등록 API(MERCHANT-OPS-002 §4.3 — B1-1: 사업자·가맹점·Site, B1-2: 계약 초안·CSA, B2-1: 계약 Revision §9.2). 세션 + CSRF(보안 체인), 모두 `no-store`, 오류 본문 `{"error":"<CODE>"}`만(입력 값 없음).
 * 읽기 전 거절(감사 없음 — MERCHANT-OPS-001 Q-MOPS-08과 같은 규칙): JSON이 아닌 Content-Type 415 `UNSUPPORTED_MEDIA_TYPE`(본문을 읽지 않는다), 16 KiB 초과 413 `REQUEST_TOO_LARGE`
 * (상한 + 1바이트까지만 읽는다). 그 뒤 [MerchantRegistrationBodyParser] 엄격 해석 → Use Case.
 *
 * 200 `{outcome, reference?(성공만), rejection?(거절만), replayed}` — 거절에 reference 없음(D-MOPS-006 ③). 400 `INVALID_INPUT`, 403 `FORBIDDEN`, 409 `IDEMPOTENCY_KEY_REUSED`,
 * 429 `RATE_LIMITED`, 503 `MASTER_BUSY`·`MASTER_RETRY_SAME_KEY`·`MASTER_OUTCOME_UNKNOWN`·`AUDIT_UNAVAILABLE`.
 */
@RestController
class MerchantRegistrationController(private val registrations: MerchantRegistrationUseCase, private val registry: MeterRegistry) {

    @PostMapping("/api/merchant-registrations/businesses")
    fun registerBusiness(authentication: Authentication, request: HttpServletRequest): ResponseEntity<out Any> = handle(RegistrationKind.BUSINESS, authentication, request)

    @PostMapping("/api/merchant-registrations/merchants")
    fun registerMerchant(authentication: Authentication, request: HttpServletRequest): ResponseEntity<out Any> = handle(RegistrationKind.MERCHANT, authentication, request)

    @PostMapping("/api/merchant-registrations/merchant-sites")
    fun registerMerchantSite(authentication: Authentication, request: HttpServletRequest): ResponseEntity<out Any> =
        handle(RegistrationKind.MERCHANT_SITE, authentication, request)

    /** B1-2 계약 초안(Revision 1 — 미승인). 날짜는 Asia/Seoul `YYYY-MM-DD`(C-7), 시작 소급 판정은 MASTER(§3.4). */
    @PostMapping("/api/merchant-registrations/contracts")
    fun registerContract(authentication: Authentication, request: HttpServletRequest): ResponseEntity<out Any> = handle(RegistrationKind.CONTRACT, authentication, request)

    /** B2-1 계약 Revision 등록(1인 — MERCHANT-OPS-002 §9.2, 미승인). 날짜 규칙은 계약 초안과 같다(C-7), 시작 소급 판정은 MASTER(D-MOPS-007 ②). */
    @PostMapping("/api/merchant-registrations/contract-revisions")
    fun registerContractRevision(authentication: Authentication, request: HttpServletRequest): ResponseEntity<out Any> =
        handle(RegistrationKind.CONTRACT_REVISION, authentication, request)

    /** B1-2 서비스 계약(CSA) 추가. */
    @PostMapping("/api/merchant-registrations/service-agreements")
    fun addServiceAgreement(authentication: Authentication, request: HttpServletRequest): ResponseEntity<out Any> =
        handle(RegistrationKind.SERVICE_AGREEMENT, authentication, request)

    /** B3-1 결제수단 개통 등록(1인 — §9.14, UNDER_REVIEW). 개통 승인은 2인(`/api/approvals/merchant-payment-method-activation`). */
    @PostMapping("/api/merchant-registrations/payment-method-activations")
    fun registerPaymentMethodActivation(authentication: Authentication, request: HttpServletRequest): ResponseEntity<out Any> =
        handle(RegistrationKind.PAYMENT_METHOD_ACTIVATION, authentication, request)

    /** B3-1 Site 심사 거절(1인 — §9.14, REJECTED 고정·되돌릴 수 없음 D-MS-005). 심사 승인은 2인(`/api/approvals/merchant-site-review-approval`). */
    @PostMapping("/api/merchant-registrations/site-review-rejections")
    fun rejectSiteReview(authentication: Authentication, request: HttpServletRequest): ResponseEntity<out Any> =
        handle(RegistrationKind.SITE_REVIEW_REJECTION, authentication, request)

    private fun handle(kind: RegistrationKind, authentication: Authentication, request: HttpServletRequest): ResponseEntity<out Any> {
        if (!isJson(request.contentType)) return rejectBody(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE")
        if (request.contentLengthLong > MAX_BODY_BYTES) return rejectBody(HttpStatus.CONTENT_TOO_LARGE, "REQUEST_TOO_LARGE")
        val body = request.inputStream.readNBytes(MAX_BODY_BYTES + 1)
        if (body.size > MAX_BODY_BYTES) return rejectBody(HttpStatus.CONTENT_TOO_LARGE, "REQUEST_TOO_LARGE")
        val context = AdminRequestContext(UUID.randomUUID().toString(), request.remoteAddr?.take(REMOTE_IP_MAX))
        return respond(
            when (val parsed = MerchantRegistrationBodyParser.parse(kind, body)) {
                is MerchantRegistrationBodyParser.Parsed.Request -> registrations.register(authentication.name, parsed.request, context)
                is MerchantRegistrationBodyParser.Parsed.Malformed -> registrations.rejectMalformed(authentication.name, kind, parsed.registrationNumberAttempt, context)
            },
        )
    }

    private fun respond(result: MerchantRegistrationResult): ResponseEntity<out Any> = when (result) {
        is MerchantRegistrationResult.Decided -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            linkedMapOf<String, Any>("outcome" to result.outcome).apply {
                result.reference?.let { put("reference", it) }
                result.rejection?.let { put("rejection", it) }
                put("replayed", result.replayed)
            },
        )
        MerchantRegistrationResult.InvalidInput -> error(HttpStatus.BAD_REQUEST, "INVALID_INPUT")
        MerchantRegistrationResult.Forbidden -> error(HttpStatus.FORBIDDEN, "FORBIDDEN")
        MerchantRegistrationResult.RateLimited -> error(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED")
        MerchantRegistrationResult.KeyReused -> error(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED")
        MerchantRegistrationResult.MasterBusy -> error(HttpStatus.SERVICE_UNAVAILABLE, "MASTER_BUSY")
        MerchantRegistrationResult.RetrySameKey -> error(HttpStatus.SERVICE_UNAVAILABLE, "MASTER_RETRY_SAME_KEY")
        MerchantRegistrationResult.OutcomeUnknown -> error(HttpStatus.SERVICE_UNAVAILABLE, "MASTER_OUTCOME_UNKNOWN")
        MerchantRegistrationResult.AuditUnavailable -> error(HttpStatus.SERVICE_UNAVAILABLE, "AUDIT_UNAVAILABLE")
    }

    private fun isJson(contentType: String?): Boolean =
        contentType != null && runCatching { MediaType.APPLICATION_JSON.equalsTypeAndSubtype(MediaType.parseMediaType(contentType)) }.getOrDefault(false)

    private fun rejectBody(status: HttpStatus, code: String): ResponseEntity<Any> {
        registry.counter(BODY_REJECTED_METRIC, "reason", if (status == HttpStatus.UNSUPPORTED_MEDIA_TYPE) code else "TOO_LARGE").increment()
        return error(status, code)
    }

    private fun error(status: HttpStatus, code: String): ResponseEntity<Any> = ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(mapOf("error" to code))

    companion object {
        /** 등록 본문 상한 — Demo 값(MASTER 채널 16 KiB와 같다, 운영값 아님). */
        const val MAX_BODY_BYTES = 16 * 1024

        /** 내용을 읽지 않고 거절한 등록 본문 수(감사 없음). label `reason` = TOO_LARGE·UNSUPPORTED_MEDIA_TYPE. */
        const val BODY_REJECTED_METRIC = "paycore.admin.merchant_ops.registration_body_rejected"
        private const val REMOTE_IP_MAX = 45
    }
}

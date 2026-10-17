package com.paycore.payment.payment.adapter.web

import com.paycore.payment.payment.application.provided.CheckoutTokenRejection
import com.paycore.payment.payment.application.provided.IssueCheckoutTokenCommand
import com.paycore.payment.payment.application.provided.IssueCheckoutTokenResult
import com.paycore.payment.payment.application.provided.IssueCheckoutTokenUseCase
import com.paycore.payment.payment.application.provided.MerchantAuthentication
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.InvalidMediaTypeException
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.StandardCharsets
import java.nio.charset.UnsupportedCharsetException
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 결제창 토큰 발급 공개 API(AUTHN-001 §5, D-AUTHN-001). `POST /v1/payments/{paymentId}/checkout`(공개 connector, Bearer 체인 그대로).
 *
 * 순서: 인증(보안 필터 — 401) → `Idempotency-Key` 있음·media type·본문 형식(400) → Use Case(URL 문법 400 → 소유 404 → … → 커밋) → 응답(커밋 뒤 결과로).
 * 201 본문은 닫힌 키 3개(paymentId·checkoutUrl·expiresAt), `Cache-Control: no-store`(토큰 원문이 들어 있다). 오류는 고정 code·문구 — 요청 URL·host·내부 사유를 echo하지
 * 않는다(§5.3). 로그에는 토큰·URL 원문을 남기지 않는다(§12.6 — operation·correlationId·result만). provided Use Case만 쓴다(D-AUTHN-006, ArchUnit).
 */
@RestController
class CheckoutTokenController(
    private val issue: IssueCheckoutTokenUseCase,
    private val settings: PaymentApiSettings,
    private val registry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(CheckoutTokenController::class.java)

    @PostMapping("/v1/payments/{paymentId}/checkout")
    fun issue(@PathVariable("paymentId") paymentId: String, request: HttpServletRequest): ResponseEntity<ByteArray> {
        val requestId = ApiResponses.requestId(request)
        val authentication = (SecurityContextHolder.getContext().authentication?.principal as? MerchantAuthentication)
            ?: return error(ApiError(ApiErrorCode.UNAUTHENTICATED), requestId)
        return try {
            CheckoutTokenRequestParser.rejectIdempotencyKey(request.getHeaders(IDEMPOTENCY_KEY_HEADER).toList())
            checkMediaTypes(request)
            val body = CheckoutTokenRequestParser.parse(request.inputStream.readNBytes(CheckoutTokenRequestParser.MAX_BODY_BYTES + 1))
            respond(issue.issue(IssueCheckoutTokenCommand(authentication, paymentId, body.successUrl, body.failUrl, requestId)), requestId)
        } catch (e: RequestRejected) {
            error(e.error, requestId)
        } catch (e: RuntimeException) {
            record("INTERNAL_ERROR")
            log.error("operation=IssueCheckoutToken correlationId={} result=INTERNAL_ERROR failure=DEFECT exception={}", requestId, SafeThrowable.summary(e))
            ApiResponses.error(ApiError(ApiErrorCode.INTERNAL_ERROR), requestId)
        }
    }

    private fun respond(result: IssueCheckoutTokenResult, requestId: String): ResponseEntity<ByteArray> = when (result) {
        is IssueCheckoutTokenResult.Issued -> {
            record("ISSUED")
            log.info("operation=IssueCheckoutToken correlationId={} result=ISSUED", requestId)
            ResponseEntity.status(HttpStatus.CREATED)
                .header(ApiResponses.REQUEST_ID_HEADER, requestId)
                .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                    ApiResponses.mapper.writeValueAsBytes(
                        linkedMapOf("paymentId" to result.paymentId, "checkoutUrl" to result.checkoutUrl, "expiresAt" to FORMATTER.format(result.expiresAt)),
                    ),
                )
        }
        is IssueCheckoutTokenResult.Rejected -> error(ApiError(codeOf(result.reason), field = result.field), requestId)
    }

    private fun codeOf(reason: CheckoutTokenRejection): ApiErrorCode = when (reason) {
        CheckoutTokenRejection.INVALID_RETURN_URL -> ApiErrorCode.INVALID_RETURN_URL
        CheckoutTokenRejection.PAYMENT_NOT_FOUND -> ApiErrorCode.PAYMENT_NOT_FOUND
        CheckoutTokenRejection.CHECKOUT_NOT_SUPPORTED -> ApiErrorCode.CHECKOUT_NOT_SUPPORTED
        CheckoutTokenRejection.RETURN_URL_NOT_ALLOWED -> ApiErrorCode.RETURN_URL_NOT_ALLOWED
        CheckoutTokenRejection.ORDER_ON_HOLD -> ApiErrorCode.ORDER_ON_HOLD
        CheckoutTokenRejection.PAYMENT_TERMINATED -> ApiErrorCode.PAYMENT_TERMINATED
        CheckoutTokenRejection.PAYMENT_AUTHORIZATION_STARTED -> ApiErrorCode.PAYMENT_AUTHORIZATION_STARTED
        CheckoutTokenRejection.CHECKOUT_UNAVAILABLE -> ApiErrorCode.CHECKOUT_UNAVAILABLE
    }

    private fun error(error: ApiError, requestId: String): ResponseEntity<ByteArray> {
        record(error.code.wireCode)
        log.debug("operation=IssueCheckoutToken correlationId={} result={} field={}", requestId, error.code, error.field)
        val headers = HttpHeaders()
        headers.set(HttpHeaders.CACHE_CONTROL, NO_STORE)
        if (error.code.retryable) headers.set(HttpHeaders.RETRY_AFTER, settings.retryAfter.temporarilyUnavailable.toRetryAfterSeconds())
        return ApiResponses.error(error, requestId, headers)
    }

    /** Content-Type은 application/json(charset이 있으면 UTF-8만), Accept는 없음 또는 JSON 호환만 — CreatePayment와 같은 규칙(CP-API §4.1). */
    private fun checkMediaTypes(request: HttpServletRequest) {
        val ok = try {
            val contentType = request.contentType?.let(MediaType::parseMediaType)
            val contentOk = contentType != null && contentType.isCompatibleWith(MediaType.APPLICATION_JSON) && !contentType.isWildcardSubtype &&
                (contentType.charset == null || contentType.charset == StandardCharsets.UTF_8)
            val accept = request.getHeaders(HttpHeaders.ACCEPT).toList().joinToString(",")
            contentOk && (accept.isBlank() || MediaType.parseMediaTypes(accept).any { it.isCompatibleWith(MediaType.APPLICATION_JSON) })
        } catch (e: InvalidMediaTypeException) {
            false
        } catch (e: UnsupportedCharsetException) {
            false
        }
        if (!ok) throw RequestRejected(ApiError(ApiErrorCode.INVALID_REQUEST))
    }

    private fun record(result: String) {
        registry.counter(TOKENS_METRIC, "result", result).increment()
    }

    private companion object {
        const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"
        const val NO_STORE = "no-store"
        const val TOKENS_METRIC = "paycore.checkout.tokens"
        val FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    }
}

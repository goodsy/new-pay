package com.paycore.payment.payment.adapter.web

import com.paycore.payment.payment.application.provided.CreatePaymentFailure
import com.paycore.payment.payment.application.provided.CreatePaymentOutcome
import com.paycore.payment.payment.application.provided.CreatePaymentRejection
import com.paycore.payment.payment.application.provided.CreatePaymentRequest
import com.paycore.payment.payment.application.provided.CreatePaymentResult
import com.paycore.payment.payment.application.provided.CreatedPayment
import com.paycore.payment.payment.application.provided.EligibilityRefusal
import com.paycore.payment.payment.application.provided.MerchantAuthentication
import com.paycore.payment.payment.application.provided.SubmitCreatePaymentUseCase
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.InvalidMediaTypeException
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.StandardCharsets
import java.nio.charset.UnsupportedCharsetException
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * CreatePayment 공개 API(CP-CONTRACT-001 §3·§5·§9, CP-API-001 명세 §4·§7). `POST /v1/payments`(공개 connector만).
 *
 * 순서(계약 §3.2): 인증(보안 필터 — 401) → 헤더·본문 형식(400) → 본문 MID = 인증 MID(403) → Use Case(멱등·eligibility·주문 판정·commit) → 응답.
 * 응답은 commit 뒤 결과로 만든다. 본문·헤더에 요청 값·내부 값을 echo하지 않는다(성공 응답의 계약 echo 필드 제외, §9.1).
 * 로그(n-3): 예외 메시지 원문을 남기지 않는다 — operation·correlationId·result·failure 분류·vendorCode·SQLState·제약 이름, 결함이면 예외
 * 클래스 이름 사슬과 첫 stack frame만.
 */
@RestController
class CreatePaymentController(
    private val submit: SubmitCreatePaymentUseCase,
    private val settings: PaymentApiSettings,
    private val registry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(CreatePaymentController::class.java)

    @PostMapping("/v1/payments")
    fun create(request: HttpServletRequest): ResponseEntity<ByteArray> {
        val requestId = ApiResponses.requestId(request)
        val merchantRequestId = sanitizedMerchantRequestId(request.getHeader(MERCHANT_REQUEST_ID_HEADER))
        val authentication = (SecurityContextHolder.getContext().authentication?.principal as? MerchantAuthentication)
            ?: return respondError(ApiError(ApiErrorCode.UNAUTHENTICATED), requestId, merchantRequestId)
        return try {
            checkMediaTypes(request)
            val key = CreatePaymentRequestParser.idempotencyKey(request.getHeaders(IDEMPOTENCY_KEY_HEADER).toList())
            val body = CreatePaymentRequestParser.parse(readBody(request))
            if (body.merchantAccountId != authentication.merchantAccountId) {
                return respondError(ApiError(ApiErrorCode.MERCHANT_SCOPE_MISMATCH), requestId, merchantRequestId)
            }
            val result = submit.submit(
                CreatePaymentRequest(
                    authentication = authentication,
                    idempotencyKey = key,
                    merchantAccountId = body.merchantAccountId,
                    merchantSiteId = body.merchantSiteId,
                    merchantOrderId = body.merchantOrderId,
                    money = body.money,
                    paymentMethod = body.paymentMethod,
                    orderName = body.orderName,
                    correlationId = requestId,
                ),
            )
            respond(result, requestId, merchantRequestId)
        } catch (e: RequestRejected) {
            respondError(e.error, requestId, merchantRequestId)
        } catch (e: CreatePaymentFailure) {
            respondFailure(e, requestId, merchantRequestId)
        } catch (e: RuntimeException) {
            record("INTERNAL_ERROR")
            log.error(
                "operation=CreatePayment correlationId={} merchantRequestId={} result=INTERNAL_ERROR failure=DEFECT exception={}",
                requestId, merchantRequestId, SafeThrowable.summary(e),
            )
            ApiResponses.error(ApiError(ApiErrorCode.INTERNAL_ERROR), requestId)
        }
    }

    private fun respond(result: CreatePaymentResult, requestId: String, merchantRequestId: String?): ResponseEntity<ByteArray> = when (result) {
        is CreatePaymentResult.Accepted -> {
            val status = if (result.replayed || result.outcome != CreatePaymentOutcome.CREATED) HttpStatus.OK else HttpStatus.CREATED
            record(if (result.replayed) "REPLAYED" else result.outcome.name)
            ResponseEntity.status(status)
                .header(ApiResponses.REQUEST_ID_HEADER, requestId)
                .header(ApiResponses.REPLAYED_HEADER, result.replayed.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponses.mapper.writeValueAsBytes(successBody(result.outcome.name, result.payment)))
        }
        is CreatePaymentResult.Rejected -> {
            val code = rejectionCode(result.rejection)
            record(if (result.replayed) "REPLAYED_${code.name}" else code.name)
            log.info(
                "operation=CreatePayment correlationId={} merchantRequestId={} result={} replayed={} referencePaymentId={}",
                requestId, merchantRequestId, code.name, result.replayed, result.referencePaymentId.value,
            )
            ApiResponses.error(
                ApiError(code, referencePaymentId = result.referencePaymentId.value), requestId,
                HttpHeaders().apply { set(ApiResponses.REPLAYED_HEADER, result.replayed.toString()) },
            )
        }
        CreatePaymentResult.IdempotencyKeyReused -> respondError(ApiError(ApiErrorCode.IDEMPOTENCY_KEY_REUSED), requestId, merchantRequestId)
        is CreatePaymentResult.EligibilityRefused -> when (val refusal = result.refusal) {
            EligibilityRefusal.NotFoundOrForbidden -> respondError(ApiError(ApiErrorCode.MERCHANT_SITE_NOT_FOUND), requestId, merchantRequestId)
            is EligibilityRefusal.NotEligible ->
                respondError(ApiError(ApiErrorCode.PAYMENT_NOT_ELIGIBLE, reason = refusal.reason.name), requestId, merchantRequestId)
            is EligibilityRefusal.Unavailable -> {
                // 원인(SNAPSHOT_MISSING 등)은 내부 상태라 응답에 넣지 않고 로그·지표에만(계약 §9.1).
                log.warn(
                    "operation=CreatePayment correlationId={} merchantRequestId={} result=ELIGIBILITY_UNAVAILABLE failure={}",
                    requestId, merchantRequestId, refusal.reason,
                )
                respondError(ApiError(ApiErrorCode.ELIGIBILITY_UNAVAILABLE), requestId, merchantRequestId, settings.retryAfter.eligibilityUnavailable)
            }
        }
    }

    private fun respondFailure(e: CreatePaymentFailure, requestId: String, merchantRequestId: String?): ResponseEntity<ByteArray> {
        val (code, retryAfter) = when (e) {
            is CreatePaymentFailure.RequestInProgress -> ApiErrorCode.IDEMPOTENCY_REQUEST_IN_PROGRESS to settings.retryAfter.requestInProgress
            is CreatePaymentFailure.OutcomeUnknown -> ApiErrorCode.OUTCOME_UNCONFIRMED to settings.retryAfter.temporarilyUnavailable
            is CreatePaymentFailure.TemporarilyUnavailable, is CreatePaymentFailure.IdentifierCollision ->
                ApiErrorCode.TEMPORARILY_UNAVAILABLE to settings.retryAfter.temporarilyUnavailable
        }
        val diagnostic = e.diagnostic
        val message = "operation=CreatePayment correlationId={} merchantRequestId={} result={} failure={} vendorCode={} sqlState={} constraint={}"
        val args = arrayOf<Any?>(requestId, merchantRequestId, code.wireCode, e.kind, diagnostic?.vendorCode, diagnostic?.sqlState, diagnostic?.constraint)
        if (code == ApiErrorCode.IDEMPOTENCY_REQUEST_IN_PROGRESS) log.info(message, *args) else log.warn(message, *args)
        record("FAILURE_${e.kind.name}")
        return ApiResponses.error(ApiError(code), requestId, HttpHeaders().apply { set(HttpHeaders.RETRY_AFTER, retryAfter.toRetryAfterSeconds()) })
    }

    private fun respondError(
        error: ApiError,
        requestId: String,
        merchantRequestId: String?,
        retryAfter: Duration? = null,
    ): ResponseEntity<ByteArray> {
        record(error.code.wireCode + (error.reason?.let { "_$it" } ?: ""))
        if (error.code.status.is4xxClientError) {
            log.debug("operation=CreatePayment correlationId={} merchantRequestId={} result={} field={}", requestId, merchantRequestId, error.code, error.field)
        }
        val headers = HttpHeaders()
        retryAfter?.let { headers.set(HttpHeaders.RETRY_AFTER, it.toRetryAfterSeconds()) }
        return ApiResponses.error(error, requestId, headers)
    }

    private fun successBody(outcome: String, payment: CreatedPayment): Map<String, Any> = linkedMapOf(
        "paymentId" to payment.paymentId.value,
        "paymentKey" to payment.paymentKey.value,
        "outcome" to outcome,
        "lifecycleAtCreation" to payment.lifecycleAtCreation.name,
        "paymentCreatedAt" to formatInstant(payment.paymentCreatedAt),
        "merchantOrderId" to payment.merchantOrderId.value,
        "amount" to payment.money.amountMinor,
        "currency" to payment.money.currency.value,
    )

    private fun rejectionCode(rejection: CreatePaymentRejection): ApiErrorCode = when (rejection) {
        CreatePaymentRejection.FIXED_INTENT_CONFLICT -> ApiErrorCode.ORDER_INTENT_CONFLICT
        CreatePaymentRejection.INCIDENT_HOLD -> ApiErrorCode.ORDER_ON_HOLD
        CreatePaymentRejection.AUTHORIZED_NEW_ORDER_REQUIRED -> ApiErrorCode.ORDER_ALREADY_AUTHORIZED
        CreatePaymentRejection.TERMINATION_EVIDENCE_INSUFFICIENT -> ApiErrorCode.ORDER_RESULT_UNCONFIRMED
    }

    private fun record(result: String) {
        registry.counter(RESULTS_METRIC, "result", result).increment()
    }

    /** Content-Type은 application/json(charset이 있으면 UTF-8만), Accept는 없음 또는 JSON과 호환되는 값만. 그 밖은 400(명세 §4.1). */
    private fun checkMediaTypes(request: HttpServletRequest) {
        val ok = try {
            val contentType = request.contentType?.let(MediaType::parseMediaType)
            val contentOk = contentType != null && contentType.isCompatibleWith(MediaType.APPLICATION_JSON) && !contentType.isWildcardSubtype &&
                (contentType.charset == null || contentType.charset == StandardCharsets.UTF_8)
            val accept = request.getHeaders(HttpHeaders.ACCEPT).toList().joinToString(",")
            val acceptOk = accept.isBlank() || MediaType.parseMediaTypes(accept).any { it.isCompatibleWith(MediaType.APPLICATION_JSON) }
            contentOk && acceptOk
        } catch (e: InvalidMediaTypeException) {
            false
        } catch (e: UnsupportedCharsetException) {
            false
        }
        if (!ok) throw RequestRejected(ApiError(ApiErrorCode.INVALID_REQUEST))
    }

    /** 한도+1바이트까지만 읽는다(한도 초과는 파서가 거절). */
    private fun readBody(request: HttpServletRequest): ByteArray = request.inputStream.readNBytes(CreatePaymentRequestParser.MAX_BODY_BYTES + 1)

    companion object {
        const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"
        const val MERCHANT_REQUEST_ID_HEADER = "X-Request-Id"
        const val RESULTS_METRIC = "paycore.payment.create.results"
        private val FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

        fun formatInstant(at: Instant): String = FORMATTER.format(at)

        /** 가맹점 X-Request-Id: 출력 가능 ASCII 1..128자만 로그에 보존, 아니면 버린다(오류 아님, 로그 주입 방지). */
        fun sanitizedMerchantRequestId(value: String?): String? = value?.takeIf { v -> v.length in 1..128 && v.all { it in '!'..'~' } }
    }
}

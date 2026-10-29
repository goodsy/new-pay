package com.paycore.admin.paymentops.adapter.web

import com.paycore.admin.paymentops.application.provided.LookupResult
import com.paycore.admin.paymentops.application.provided.PaymentTargetLookupUseCase
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 화면 대상 조회(ADMIN-PAYMENT-OPS-001 §4.20·I-23): 모든 조회 역할, 감사 없음, `no-store`. 200 대상 / 400 `INVALID_INPUT` / 403 / 404 `PAYMENT_NOT_FOUND` /
 * 429 `RATE_LIMITED` / 503 `PAYMENT_UNAVAILABLE`(원인 분류는 응답에 넣지 않는다). 카드·기관 정보는 PAYMENT 응답에도 없다.
 */
@RestController
@RequestMapping("/api/payment-ops")
class PaymentTargetController(private val lookup: PaymentTargetLookupUseCase) {

    @GetMapping("/payments/{paymentId}/target")
    fun target(@PathVariable paymentId: String, authentication: Authentication): ResponseEntity<out Any> =
        when (val result = lookup.lookup(authentication.name, paymentId)) {
            is LookupResult.Found -> result.view.let { v ->
                respond(
                    HttpStatus.OK,
                    linkedMapOf(
                        "paymentId" to v.paymentId, "paymentStatus" to v.paymentStatus, "cancellable" to v.cancellable, "currency" to v.currency,
                        "authorizedMinor" to v.authorizedMinor, "cancellableMinor" to v.cancellableMinor, "balanceRevision" to v.balanceRevision, "onHold" to v.onHold,
                        "inFlightCancel" to v.inFlightCancel,
                    ),
                )
            }
            LookupResult.InvalidInput -> error(HttpStatus.BAD_REQUEST, "INVALID_INPUT")
            LookupResult.Forbidden -> error(HttpStatus.FORBIDDEN, "FORBIDDEN")
            LookupResult.NotFound -> error(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND")
            LookupResult.RateLimited -> error(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED")
            is LookupResult.Unavailable -> error(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENT_UNAVAILABLE")
        }

    private fun respond(status: HttpStatus, body: Any): ResponseEntity<Any> = ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body)

    private fun error(status: HttpStatus, code: String): ResponseEntity<Any> = respond(status, mapOf("error" to code))
}

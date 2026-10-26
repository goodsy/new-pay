package com.paycore.admin.account.adapter.web

import com.paycore.admin.account.application.provided.AdminRetryLaterException
import com.paycore.admin.audit.application.provided.AuditUnavailable
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * account·audit Use Case의 예외 → 고정 JSON code(§4.10·§4.13). 원인 메시지는 내지 않는다. 일반 예외(500)·404 등은 console의 공통 처리.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class AccountWebErrors {

    @ExceptionHandler(AdminRetryLaterException::class)
    fun retryLater(): ResponseEntity<Map<String, String>> = body(HttpStatus.CONFLICT, "RETRY_LATER")

    @ExceptionHandler(AuditUnavailable::class)
    fun auditUnavailable(): ResponseEntity<Map<String, String>> = body(HttpStatus.SERVICE_UNAVAILABLE, "AUDIT_UNAVAILABLE")

    private fun body(status: HttpStatus, code: String) = ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(mapOf("error" to code))
}

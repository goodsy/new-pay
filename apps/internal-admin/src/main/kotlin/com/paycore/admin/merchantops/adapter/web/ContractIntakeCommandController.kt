package com.paycore.admin.merchantops.adapter.web

import com.paycore.admin.account.application.provided.AdminRequestContext
import com.paycore.admin.merchantops.application.provided.ContractIntakeCommandUseCase
import com.paycore.admin.merchantops.application.provided.ContractIntakeOperation
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 화면 계약 접수 건 명령(MERCHANT-FLOW-001 §4B.11 표 2b 행 — 사업자 상세 '계약 진행' 섹션):
 * - 접수 건 생성 `POST /api/businesses/{businessId}/contract-intakes`
 * - 전이(계약서 접수·되돌리기·계약 불가) `POST /api/businesses/{businessId}/contract-intakes/{intakeSeq}/transitions`
 * - 계약 완료(사업자 계약·버전 생성) `POST /api/businesses/{businessId}/contract-intakes/{intakeSeq}/completion`
 * 세션 + CSRF(보안 체인), 모두 `no-store`. 멱등 키 = 본문 `submissionId`([BusinessCommandController] 관례). 읽기 전 거절(감사 없음): JSON이 아니면 415, 16 KiB 초과 413.
 * 그 뒤 [ContractIntakeBodyParser] 엄격 해석 → Use Case. 응답은 [BusinessCommandResponses](사업자 명령과 같은 모양 — 성공 응답에 상태·version 없음, 화면은 성공 뒤 상세를 다시 읽는다).
 */
@RestController
class ContractIntakeCommandController(private val commands: ContractIntakeCommandUseCase, private val registry: MeterRegistry) {

    @PostMapping("/api/businesses/{businessId}/contract-intakes")
    fun open(@PathVariable businessId: String, authentication: Authentication, request: HttpServletRequest): ResponseEntity<out Any> =
        handle(ContractIntakeOperation.OPEN, businessId, null, authentication, request)

    @PostMapping("/api/businesses/{businessId}/contract-intakes/{intakeSeq}/transitions")
    fun move(@PathVariable businessId: String, @PathVariable intakeSeq: String, authentication: Authentication, request: HttpServletRequest): ResponseEntity<out Any> =
        handle(ContractIntakeOperation.MOVE, businessId, intakeSeq, authentication, request)

    @PostMapping("/api/businesses/{businessId}/contract-intakes/{intakeSeq}/completion")
    fun complete(@PathVariable businessId: String, @PathVariable intakeSeq: String, authentication: Authentication, request: HttpServletRequest): ResponseEntity<out Any> =
        handle(ContractIntakeOperation.COMPLETE, businessId, intakeSeq, authentication, request)

    private fun handle(
        operation: ContractIntakeOperation,
        businessId: String,
        intakeSeq: String?,
        authentication: Authentication,
        request: HttpServletRequest,
    ): ResponseEntity<out Any> {
        if (!isJson(request.contentType)) return rejectBody(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE")
        if (request.contentLengthLong > BusinessCommandController.MAX_BODY_BYTES) return rejectBody(HttpStatus.CONTENT_TOO_LARGE, "REQUEST_TOO_LARGE")
        val body = request.inputStream.readNBytes(BusinessCommandController.MAX_BODY_BYTES + 1)
        if (body.size > BusinessCommandController.MAX_BODY_BYTES) return rejectBody(HttpStatus.CONTENT_TOO_LARGE, "REQUEST_TOO_LARGE")
        val context = AdminRequestContext(UUID.randomUUID().toString(), request.remoteAddr?.take(REMOTE_IP_MAX))
        val parsed = ContractIntakeBodyParser.parse(operation, businessId, intakeSeq, body)
        return BusinessCommandResponses.of(
            if (parsed == null) commands.rejectMalformed(authentication.name, operation, businessId, context) else commands.execute(authentication.name, parsed, context),
        )
    }

    private fun isJson(contentType: String?): Boolean =
        contentType != null && runCatching { MediaType.APPLICATION_JSON.equalsTypeAndSubtype(MediaType.parseMediaType(contentType)) }.getOrDefault(false)

    private fun rejectBody(status: HttpStatus, code: String): ResponseEntity<Any> {
        registry.counter(BusinessQueryController.BODY_REJECTED_METRIC, "reason", if (status == HttpStatus.UNSUPPORTED_MEDIA_TYPE) code else "TOO_LARGE").increment()
        return BusinessCommandResponses.error(status, code)
    }

    private companion object {
        const val REMOTE_IP_MAX = 45
    }
}

package com.paycore.admin.merchantops.adapter.web

import com.paycore.admin.account.application.provided.AdminRequestContext
import com.paycore.admin.account.application.provided.AdminSessionState
import com.paycore.admin.merchantops.application.provided.BusinessChangeRequestOutcome
import com.paycore.admin.merchantops.application.provided.BusinessChangeRequests
import com.paycore.admin.merchantops.application.provided.BusinessProfileRequest
import com.paycore.admin.merchantops.application.provided.BusinessProfileOperation
import com.paycore.admin.merchantops.application.provided.BusinessProfileResult
import com.paycore.admin.merchantops.application.provided.BusinessProfileUseCase
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 화면 사업자 명령(BUSINESS-PROFILE-001 §7.1 — BIZ-3): 생성 `POST /api/businesses`, 수정 `PUT /api/businesses/{id}`, 등록 완료 `POST /api/businesses/{id}/registration`.
 * 세션 + CSRF(보안 체인), 모두 `no-store`. 읽기 전 거절(감사 없음 — Q-MOPS-08): JSON이 아니면 415, 16 KiB 초과 413. 그 뒤 [BusinessProfileBodyParser] 엄격 해석 → Use Case.
 *
 * 200 `{outcome, reference?(성공만), rejection?(거절만), replayed, existing?(중복 거절 + 조회 권한 — D-BIZ-003)}` / 400 `INVALID_INPUT`(+ `fieldErrors` path·code — 값 없음) /
 * 403 `FORBIDDEN` / 409 `IDEMPOTENCY_KEY_REUSED`(기존 code) / 429 `RATE_LIMITED` / 503 `MASTER_BUSY`·`MASTER_RETRY_SAME_KEY`·`MASTER_OUTCOME_UNKNOWN`·`AUDIT_UNAVAILABLE`.
 * 오류 본문에 입력 값이 없다. 성공 응답에 상태·version이 없다(재생 응답과 같은 모양 — 화면은 성공 뒤 상세를 다시 읽는다, C-18).
 */
@RestController
class BusinessCommandController(
    private val profiles: BusinessProfileUseCase,
    private val registry: MeterRegistry,
    private val criticalChanges: BusinessChangeRequests,
) {

    /**
     * BIZ-CRIT-APPROVAL-001 §5.2 사업자 중요정보 변경 승인 요청(OPERATOR): 본문 = 수정 본문 + `evidenceRef`(필수 — 값 규칙) → 201 `{approvalId}` / 400 `INVALID_INPUT`(+ fieldErrors) /
     * 409 `DUPLICATE_UNRESOLVED`·MASTER 저장 거절 code / 403 / 401 / 503(같은 submissionId로 다시). 처리는 approval 모듈(SPI [BusinessChangeRequests]).
     */
    @PostMapping("/api/businesses/{businessId}/critical-change-requests")
    fun requestCriticalChange(@PathVariable businessId: String, authentication: Authentication, request: HttpServletRequest): ResponseEntity<out Any> {
        if (!isJson(request.contentType)) return rejectBody(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE")
        if (request.contentLengthLong > MAX_BODY_BYTES) return rejectBody(HttpStatus.CONTENT_TOO_LARGE, "REQUEST_TOO_LARGE")
        val body = request.inputStream.readNBytes(MAX_BODY_BYTES + 1)
        if (body.size > MAX_BODY_BYTES) return rejectBody(HttpStatus.CONTENT_TOO_LARGE, "REQUEST_TOO_LARGE")
        val generation = AdminSessionState.read { request.getSession(false)?.getAttribute(it) }?.takeIf { it.stage == AdminSessionState.Stage.AUTHENTICATED }?.sessionGeneration
            ?: return error(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED")
        val parsed = BusinessProfileBodyParser.parse(BusinessProfileOperation.UPDATE, businessId, body) as? BusinessProfileRequest.Change
            ?: return error(HttpStatus.BAD_REQUEST, "INVALID_INPUT")
        val context = AdminRequestContext(UUID.randomUUID().toString(), request.remoteAddr?.take(REMOTE_IP_MAX))
        return when (val outcome = criticalChanges.request(authentication.name, generation, parsed, context)) {
            is BusinessChangeRequestOutcome.Created ->
                ResponseEntity.status(HttpStatus.CREATED).cacheControl(org.springframework.http.CacheControl.noStore()).body(mapOf("approvalId" to outcome.approvalId))
            is BusinessChangeRequestOutcome.Invalid -> BusinessCommandResponses.of(com.paycore.admin.merchantops.application.provided.BusinessProfileResult.InvalidInput(outcome.fieldErrors))
            is BusinessChangeRequestOutcome.Conflict -> error(HttpStatus.CONFLICT, outcome.code)
            BusinessChangeRequestOutcome.Denied -> error(HttpStatus.FORBIDDEN, "FORBIDDEN")
            BusinessChangeRequestOutcome.SessionReplaced -> {
                request.getSession(false)?.invalidate()
                error(HttpStatus.UNAUTHORIZED, "SESSION_REPLACED")
            }
            BusinessChangeRequestOutcome.NotFound -> error(HttpStatus.NOT_FOUND, "NOT_FOUND")
            is BusinessChangeRequestOutcome.Unavailable -> error(HttpStatus.SERVICE_UNAVAILABLE, outcome.code)
            BusinessChangeRequestOutcome.RetryLater -> error(HttpStatus.CONFLICT, "RETRY_LATER")
            BusinessChangeRequestOutcome.IntegrityViolation -> error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_INTEGRITY")
        }
    }

    @PostMapping("/api/businesses")
    fun create(authentication: Authentication, request: HttpServletRequest): ResponseEntity<out Any> =
        handle(BusinessProfileOperation.CREATE, null, authentication, request)

    @PutMapping("/api/businesses/{businessId}")
    fun update(@PathVariable businessId: String, authentication: Authentication, request: HttpServletRequest): ResponseEntity<out Any> =
        handle(BusinessProfileOperation.UPDATE, businessId, authentication, request)

    @PostMapping("/api/businesses/{businessId}/registration")
    fun completeRegistration(@PathVariable businessId: String, authentication: Authentication, request: HttpServletRequest): ResponseEntity<out Any> =
        handle(BusinessProfileOperation.COMPLETE_REGISTRATION, businessId, authentication, request)

    private fun handle(operation: BusinessProfileOperation, businessId: String?, authentication: Authentication, request: HttpServletRequest): ResponseEntity<out Any> {
        if (!isJson(request.contentType)) return rejectBody(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE")
        if (request.contentLengthLong > MAX_BODY_BYTES) return rejectBody(HttpStatus.CONTENT_TOO_LARGE, "REQUEST_TOO_LARGE")
        val body = request.inputStream.readNBytes(MAX_BODY_BYTES + 1)
        if (body.size > MAX_BODY_BYTES) return rejectBody(HttpStatus.CONTENT_TOO_LARGE, "REQUEST_TOO_LARGE")
        val context = AdminRequestContext(UUID.randomUUID().toString(), request.remoteAddr?.take(REMOTE_IP_MAX))
        val parsed = BusinessProfileBodyParser.parse(operation, businessId, body)
        return respond(
            if (parsed == null) profiles.rejectMalformed(authentication.name, operation, businessId, context) else profiles.execute(authentication.name, parsed, context),
        )
    }

    private fun respond(result: BusinessProfileResult): ResponseEntity<out Any> = BusinessCommandResponses.of(result)

    private fun isJson(contentType: String?): Boolean =
        contentType != null && runCatching { MediaType.APPLICATION_JSON.equalsTypeAndSubtype(MediaType.parseMediaType(contentType)) }.getOrDefault(false)

    private fun rejectBody(status: HttpStatus, code: String): ResponseEntity<Any> {
        registry.counter(BusinessQueryController.BODY_REJECTED_METRIC, "reason", if (status == HttpStatus.UNSUPPORTED_MEDIA_TYPE) code else "TOO_LARGE").increment()
        return error(status, code)
    }

    private fun error(status: HttpStatus, code: String): ResponseEntity<Any> = BusinessCommandResponses.error(status, code)

    companion object {
        /** 명령 본문 상한 — Demo 값(MASTER 채널 16 KiB와 같다, 운영값 아님 — 최대 입력이 들어간다, §4.2). */
        const val MAX_BODY_BYTES = 16 * 1024
        private const val REMOTE_IP_MAX = 45
    }
}

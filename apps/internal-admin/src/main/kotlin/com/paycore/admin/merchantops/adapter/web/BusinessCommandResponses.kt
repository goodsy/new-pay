package com.paycore.admin.merchantops.adapter.web

import com.paycore.admin.merchantops.application.provided.BusinessProfileResult
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity

/**
 * 사업자 관리 화면 명령 응답(BUSINESS-PROFILE-001 §7.1 — [BusinessCommandController]·[ContractIntakeCommandController] 공용, 모두 `no-store`):
 * 200 `{outcome, reference?(성공만), rejection?(거절만), replayed, existing?}` / 400 `INVALID_INPUT`(+ `fieldErrors` path·code — 값 없음) / 403 / 409 `IDEMPOTENCY_KEY_REUSED` / 429 /
 * 503 `MASTER_BUSY`·`MASTER_RETRY_SAME_KEY`·`MASTER_OUTCOME_UNKNOWN`·`AUDIT_UNAVAILABLE`. 오류 본문에 입력 값이 없다.
 */
internal object BusinessCommandResponses {

    fun of(result: BusinessProfileResult): ResponseEntity<out Any> = when (result) {
        is BusinessProfileResult.Decided -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            linkedMapOf<String, Any>("outcome" to result.outcome).apply {
                result.reference?.let { put("reference", it) }
                result.rejection?.let { put("rejection", it) }
                put("replayed", result.replayed)
                result.existing?.let { put("existing", it) }
            },
        )
        is BusinessProfileResult.InvalidInput -> ResponseEntity.status(HttpStatus.BAD_REQUEST).cacheControl(CacheControl.noStore()).body(
            linkedMapOf<String, Any>("error" to "INVALID_INPUT").apply {
                if (result.fieldErrors.isNotEmpty()) put("fieldErrors", result.fieldErrors.map { linkedMapOf("path" to it.path, "code" to it.code) })
            },
        )
        BusinessProfileResult.Forbidden -> error(HttpStatus.FORBIDDEN, "FORBIDDEN")
        BusinessProfileResult.RateLimited -> error(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED")
        BusinessProfileResult.KeyReused -> error(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED")
        BusinessProfileResult.MasterBusy -> error(HttpStatus.SERVICE_UNAVAILABLE, "MASTER_BUSY")
        BusinessProfileResult.RetrySameKey -> error(HttpStatus.SERVICE_UNAVAILABLE, "MASTER_RETRY_SAME_KEY")
        BusinessProfileResult.OutcomeUnknown -> error(HttpStatus.SERVICE_UNAVAILABLE, "MASTER_OUTCOME_UNKNOWN")
        BusinessProfileResult.AuditUnavailable -> error(HttpStatus.SERVICE_UNAVAILABLE, "AUDIT_UNAVAILABLE")
    }

    fun error(status: HttpStatus, code: String): ResponseEntity<Any> = ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(mapOf("error" to code))
}

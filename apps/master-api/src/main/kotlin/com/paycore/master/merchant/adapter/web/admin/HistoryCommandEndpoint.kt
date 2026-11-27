package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.merchant.application.provided.AdminCommandFailure
import com.paycore.master.merchant.application.provided.AdminCommandResult
import com.paycore.master.merchant.application.provided.AdminRequest
import com.paycore.master.merchant.domain.BusinessFieldError
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.Logger
import org.springframework.http.ResponseEntity

/**
 * 사업자 계약·접수 건 명령 채널의 공통 처리(MERCHANT-FLOW-001 FLOW-2b·2c — [ContractIntakeCommandController]·[BusinessContractCommandController]가 같은 응답 틀을 쓴다):
 * permit(대기 0) → 엄격 해석(400 — Use Case 미호출) → Use Case → 200 SUCCEEDED/REJECTED·409·503. 로그·지표: 연산·결과 분류·상관 ID·오류 개수만(값 없음).
 */
internal class HistoryCommandEndpoint(private val permits: AdminChannelPermits, private val registry: MeterRegistry, private val log: Logger) {

    fun <C> command(
        request: HttpServletRequest,
        operation: String,
        parse: (ByteArray) -> BusinessCommandParse<C>,
        adminRequest: (C) -> AdminRequest,
        execute: (C) -> AdminCommandResult,
    ): ResponseEntity<ByteArray> =
        permits.write(operation, { AdminChannelResponses.error(request, SERVICE_UNAVAILABLE, AdminChannelResponses.ADMIN_WRITE_BUSY) }) {
            val command = when (val parsed = parse(request.inputStream.readAllBytes())) {
                BusinessCommandParse.Malformed -> {
                    log.info("operation={} requestId={} result=REQUEST_MALFORMED", operation, AdminChannelResponses.requestId(request))
                    return@write AdminChannelResponses.error(request, BAD_REQUEST, AdminChannelResponses.REQUEST_MALFORMED)
                }
                is BusinessCommandParse.Invalid -> return@write invalid(request, operation, parsed.errors)
                is BusinessCommandParse.Parsed -> parsed.command
            }
            val correlationId = adminRequest(command).correlationId
            try {
                respond(request, operation, correlationId, execute(command))
            } catch (e: AdminCommandFailure) {
                val code = when (e) {
                    is AdminCommandFailure.RequestInProgress -> AdminChannelResponses.REQUEST_IN_PROGRESS
                    is AdminCommandFailure.OutcomeUnknown -> AdminChannelResponses.OUTCOME_UNKNOWN
                    is AdminCommandFailure.IdentifierCollision, is AdminCommandFailure.TemporarilyUnavailable -> AdminChannelResponses.TEMPORARILY_UNAVAILABLE
                }
                count(operation, FAILED)
                log.warn("operation={} requestId={} correlationId={} result={}", operation, AdminChannelResponses.requestId(request), correlationId, code)
                AdminChannelResponses.error(request, SERVICE_UNAVAILABLE, code)
            } catch (e: RuntimeException) {
                // 예외 메시지·원인은 남기지 않는다(드라이버 원문에 바인드 값이 섞일 수 있다). 분류(타입 이름)만.
                count(operation, FAILED)
                log.warn(
                    "operation={} requestId={} correlationId={} result=TEMPORARILY_UNAVAILABLE failure={}",
                    operation, AdminChannelResponses.requestId(request), correlationId, e.javaClass.simpleName,
                )
                AdminChannelResponses.error(request, SERVICE_UNAVAILABLE, AdminChannelResponses.TEMPORARILY_UNAVAILABLE)
            }
        }

    private fun invalid(request: HttpServletRequest, operation: String, errors: List<BusinessFieldError>): ResponseEntity<ByteArray> {
        log.info("operation={} requestId={} result=BUSINESS_VALIDATION_FAILED errors={}", operation, AdminChannelResponses.requestId(request), errors.size)
        return AdminChannelResponses.json(request, BAD_REQUEST, BusinessProfileJson.validationFailed(errors))
    }

    private fun respond(request: HttpServletRequest, operation: String, correlationId: String, result: AdminCommandResult): ResponseEntity<ByteArray> {
        val (outcome, body) = when (result) {
            is AdminCommandResult.Succeeded -> SUCCEEDED to BusinessProfileJson.succeeded(result.reference, result.replayed)
            is AdminCommandResult.Rejected -> REJECTED to BusinessProfileJson.rejected(result.rejection.name, result.replayed)
            AdminCommandResult.IdempotencyKeyReused -> {
                count(operation, KEY_REUSED)
                log.info("operation={} requestId={} correlationId={} result={}", operation, AdminChannelResponses.requestId(request), correlationId, KEY_REUSED)
                return AdminChannelResponses.error(request, CONFLICT, AdminChannelResponses.IDEMPOTENCY_KEY_REUSED)
            }
        }
        count(operation, outcome)
        log.info("operation={} requestId={} correlationId={} result={}", operation, AdminChannelResponses.requestId(request), correlationId, outcome)
        return AdminChannelResponses.json(request, OK, body)
    }

    private fun count(operation: String, outcome: String) {
        registry.counter(MerchantOperationsController.COMMAND_METRIC, "operation", operation, "outcome", outcome).increment()
    }

    private companion object {
        const val SUCCEEDED = "SUCCEEDED"
        const val REJECTED = "REJECTED"
        const val KEY_REUSED = "KEY_REUSED"
        const val FAILED = "FAILED"
        const val OK = 200
        const val BAD_REQUEST = 400
        const val CONFLICT = 409
        const val SERVICE_UNAVAILABLE = 503
    }
}

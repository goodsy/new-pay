package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.merchant.application.provided.AdminCommandFailure
import com.paycore.master.merchant.application.provided.AdminCommandResult
import com.paycore.master.merchant.application.provided.AdminRequest
import com.paycore.master.merchant.application.provided.ContractIntakeCommandUseCase
import com.paycore.master.merchant.domain.BusinessFieldError
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 계약 접수 건 명령 채널(MERCHANT-FLOW-001 §4B.7 — FLOW-2b, ADMIN 서명 채널 뒤 — [BusinessCommandController]와 같은 응답 틀). 대상 ID·순번·기대 version은 서명된 본문에 있다.
 * 명령 실행은 provided [ContractIntakeCommandUseCase](MASTER 로컬 TX 하나). 이 컨트롤러는 TX를 열지 않는다.
 *
 * 응답: 성공 200 `{"outcome":"SUCCEEDED","reference":<businessId>,"replayed"}`, 저장된 거절 200 `{"outcome":"REJECTED","rejection","replayed"}`(reference 없음),
 * 400 `REQUEST_MALFORMED` / `BUSINESS_VALIDATION_FAILED` + fieldErrors(path·code만 — 저장 안 함), 409 `IDEMPOTENCY_KEY_REUSED`, 503(진행 중·결과 불명·기술 실패·쓰기 permit 없음).
 * 로그·지표: 연산·결과 분류·상관 ID·오류 개수만(사유·근거 원문 없음). 지표 `paycore.master.admin_channel.command{operation,outcome}`(label은 code만).
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/internal/v1/admin/businesses/contract-intakes/commands")
class ContractIntakeCommandController(
    private val commands: ContractIntakeCommandUseCase,
    private val permits: AdminChannelPermits,
    private val registry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(ContractIntakeCommandController::class.java)

    @PostMapping("/open")
    fun open(request: HttpServletRequest): ResponseEntity<ByteArray> =
        command(request, "OpenContractIntake", ContractIntakeRequestParser::open, { it.request }, commands::open)

    @PostMapping("/move")
    fun move(request: HttpServletRequest): ResponseEntity<ByteArray> =
        command(request, "MoveContractIntake", ContractIntakeRequestParser::move, { it.request }, commands::move)

    @PostMapping("/complete")
    fun complete(request: HttpServletRequest): ResponseEntity<ByteArray> =
        command(request, "CompleteContractIntake", ContractIntakeRequestParser::complete, { it.request }, commands::complete)

    private fun <C> command(
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

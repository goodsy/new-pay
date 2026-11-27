package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.merchant.application.provided.BusinessContractCommandUseCase
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 사업자 계약 명령 채널(MERCHANT-FLOW-001 §4B.7 — FLOW-2c, ADMIN 서명 채널 뒤): 정지·정지 해제·해지. 본문·응답 틀은 [ContractIntakeRequestParser.contractEvent]·[HistoryCommandEndpoint]
 * (접수 건 명령과 같음). 이 컨트롤러는 TX를 열지 않는다.
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/internal/v1/admin/businesses/business-contracts/commands")
class BusinessContractCommandController(
    private val commands: BusinessContractCommandUseCase,
    permits: AdminChannelPermits,
    registry: MeterRegistry,
) {
    private val endpoint = HistoryCommandEndpoint(permits, registry, LoggerFactory.getLogger(BusinessContractCommandController::class.java))

    @PostMapping("/suspend")
    fun suspend(request: HttpServletRequest): ResponseEntity<ByteArray> =
        endpoint.command(request, "SuspendBusinessContract", ContractIntakeRequestParser::contractEvent, { it.request }, commands::suspend)

    @PostMapping("/resume")
    fun resume(request: HttpServletRequest): ResponseEntity<ByteArray> =
        endpoint.command(request, "ResumeBusinessContract", ContractIntakeRequestParser::contractEvent, { it.request }, commands::resume)

    @PostMapping("/terminate")
    fun terminate(request: HttpServletRequest): ResponseEntity<ByteArray> =
        endpoint.command(request, "TerminateBusinessContract", ContractIntakeRequestParser::contractEvent, { it.request }, commands::terminate)
}

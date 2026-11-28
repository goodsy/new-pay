package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.merchant.application.provided.AdminCommandResult
import com.paycore.master.merchant.application.provided.AdminRequest
import com.paycore.master.merchant.application.provided.ContractIntakeCommandUseCase
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
    private val endpoint = HistoryCommandEndpoint(permits, registry, LoggerFactory.getLogger(ContractIntakeCommandController::class.java))

    @PostMapping("/open")
    fun open(request: HttpServletRequest): ResponseEntity<ByteArray> =
        command(request, "OpenContractIntake", ContractIntakeRequestParser::open, { it.request }, commands::open)

    @PostMapping("/move")
    fun move(request: HttpServletRequest): ResponseEntity<ByteArray> =
        command(request, "MoveContractIntake", ContractIntakeRequestParser::move, { it.request }, commands::move)

    /** FLOW-2d 접수 항목 수정(열린 건 — §4A.3). */
    @PostMapping("/update-items")
    fun updateItems(request: HttpServletRequest): ResponseEntity<ByteArray> =
        command(request, "UpdateContractIntakeItems", ContractIntakeRequestParser::updateItems, { it.request }, commands::updateItems)

    @PostMapping("/complete")
    fun complete(request: HttpServletRequest): ResponseEntity<ByteArray> =
        command(request, "CompleteContractIntake", ContractIntakeRequestParser::complete, { it.request }, commands::complete)

    private fun <C> command(
        request: HttpServletRequest,
        operation: String,
        parse: (ByteArray) -> BusinessCommandParse<C>,
        adminRequest: (C) -> AdminRequest,
        execute: (C) -> AdminCommandResult,
    ): ResponseEntity<ByteArray> = endpoint.command(request, operation, parse, adminRequest, execute)
}

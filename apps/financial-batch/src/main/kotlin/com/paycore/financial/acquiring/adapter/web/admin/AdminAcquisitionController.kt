package com.paycore.financial.acquiring.adapter.web.admin

import com.paycore.financial.acquiring.application.provided.AcquisitionFileViewQuery
import com.paycore.financial.acquiring.application.provided.AcquisitionViewFailure
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 매입 현황 조회(ACQ-VIEW-001 §3.3 — ADMIN 채널 서명 인증 뒤, 읽기 전용·비권위). 조회 permit(대기 0 — 없으면 503 `ADMIN_READ_BUSY`), 질의 시간 초과·저장소 실패 503
 * `TEMPORARILY_UNAVAILABLE`(시간 초과는 `paycore.financial.admin_channel.query_timeout{endpoint}`). 검색 본문은 서명된 바이트를 [AcquisitionSearchRequestParser]로 엄격 해석
 * (실패 400 `REQUEST_MALFORMED`), 형식이 아닌 fileId는 404 `ACQUISITION_FILE_NOT_FOUND`(조회하지 않는다). 로그는 연산·결과 분류·건수만(fileId·MID·거래 ID 없음).
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/internal/v1/admin")
class AdminAcquisitionController(
    private val query: AcquisitionFileViewQuery,
    private val permits: AdminChannelPermits,
    private val registry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(AdminAcquisitionController::class.java)

    @PostMapping("/acquisition-file-searches")
    fun search(request: HttpServletRequest): ResponseEntity<ByteArray> = read(request, FILE_SEARCH) {
        val criteria = AcquisitionSearchRequestParser.files(request.inputStream.readAllBytes())
            ?: return@read AdminChannelResponses.error(request, BAD_REQUEST, AdminChannelResponses.REQUEST_MALFORMED)
        val page = query.search(criteria)
        log.info("operation=AcquisitionFileSearch correlationId={} result=OK items={}", AdminChannelResponses.requestId(request), page.items.size)
        AdminChannelResponses.json(request, OK, AdminAcquisitionJson.page(page))
    }

    @GetMapping("/acquisition-files/{fileId}")
    fun detail(@PathVariable fileId: String, request: HttpServletRequest): ResponseEntity<ByteArray> = read(request, FILE_DETAIL) {
        if (!AcquisitionSearchRequestParser.FILE_ID.matches(fileId)) return@read notFound(request)
        val detail = query.detail(fileId) ?: return@read notFound(request)
        AdminChannelResponses.json(request, OK, AdminAcquisitionJson.detail(detail))
    }

    @PostMapping("/acquisition-files/{fileId}/record-searches")
    fun records(@PathVariable fileId: String, request: HttpServletRequest): ResponseEntity<ByteArray> = read(request, RECORD_SEARCH) {
        if (!AcquisitionSearchRequestParser.FILE_ID.matches(fileId)) return@read notFound(request)
        val criteria = AcquisitionSearchRequestParser.records(request.inputStream.readAllBytes())
            ?: return@read AdminChannelResponses.error(request, BAD_REQUEST, AdminChannelResponses.REQUEST_MALFORMED)
        val page = query.records(fileId, criteria) ?: return@read notFound(request)
        AdminChannelResponses.json(request, OK, AdminAcquisitionJson.records(page))
    }

    private fun read(request: HttpServletRequest, endpoint: String, block: () -> ResponseEntity<ByteArray>): ResponseEntity<ByteArray> =
        permits.read(endpoint, { AdminChannelResponses.error(request, SERVICE_UNAVAILABLE, AdminChannelResponses.ADMIN_READ_BUSY) }) {
            try {
                block()
            } catch (e: AcquisitionViewFailure.TimedOut) {
                registry.counter(TIMEOUT_METRIC, "endpoint", endpoint).increment()
                unavailable(request, endpoint, e)
            } catch (e: RuntimeException) {
                unavailable(request, endpoint, e)
            }
        }

    private fun unavailable(request: HttpServletRequest, endpoint: String, e: RuntimeException): ResponseEntity<ByteArray> {
        // 예외 메시지·원인은 남기지 않는다(드라이버 원문에 바인드 값이 섞일 수 있다) — 분류(타입 이름)만.
        log.warn("operation=AcquisitionView endpoint={} correlationId={} result=TEMPORARILY_UNAVAILABLE failure={}", endpoint, AdminChannelResponses.requestId(request), e.javaClass.simpleName)
        return AdminChannelResponses.error(request, SERVICE_UNAVAILABLE, AdminChannelResponses.TEMPORARILY_UNAVAILABLE)
    }

    private fun notFound(request: HttpServletRequest) = AdminChannelResponses.error(request, NOT_FOUND, AdminChannelResponses.ACQUISITION_FILE_NOT_FOUND)

    companion object {
        const val FILE_SEARCH = "acquisition_file_search"
        const val FILE_DETAIL = "acquisition_file_detail"
        const val RECORD_SEARCH = "acquisition_record_search"
        const val TIMEOUT_METRIC = "paycore.financial.admin_channel.query_timeout"
        private const val OK = 200
        private const val BAD_REQUEST = 400
        private const val NOT_FOUND = 404
        private const val SERVICE_UNAVAILABLE = 503
    }
}

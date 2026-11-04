package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.merchant.application.provided.MerchantOpsQuery
import com.paycore.master.merchant.domain.MerchantId
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
 * ADMIN 운영 화면용 가맹점 조회(MERCHANT-OPS-001 §3.1 — ADMIN 채널 서명 인증 뒤, 읽기 전용). 형식이 아닌 merchantId는 404 `MERCHANT_NOT_FOUND`, 검색 본문 형식 위반·이력 cursor
 * 형식 위반은 400 `REQUEST_MALFORMED`, permit 없음 503 `ADMIN_READ_BUSY`, 저장소 실패 503 `TEMPORARILY_UNAVAILABLE`. 로그는 연산·결과 분류·건수만(입력 값·등록번호 없음).
 * 서블릿 웹 컨텍스트에서만 만든다 — 비웹 1회성 명령(demo-seed)에는 채널 설정·permit이 없다(DemoSeedCommandSpec I-3 RED 2026-11-04로 발견).
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/internal/v1/admin")
class MerchantOpsController(private val query: MerchantOpsQuery, private val permits: AdminChannelPermits) {

    private val log = LoggerFactory.getLogger(MerchantOpsController::class.java)

    @PostMapping("/merchant-searches")
    fun search(request: HttpServletRequest): ResponseEntity<ByteArray> = guarded(request, "search") {
        val filter = MerchantSearchRequestParser.parse(request.inputStream.readAllBytes())
            ?: return@guarded AdminChannelResponses.error(request, BAD_REQUEST, AdminChannelResponses.REQUEST_MALFORMED)
        val page = query.search(filter)
        log.info("operation=MerchantSearch correlationId={} result=OK items={}", AdminChannelResponses.requestId(request), page.items.size)
        AdminChannelResponses.json(request, OK, MerchantOpsJson.directory(page))
    }

    @GetMapping("/merchants/{merchantId}")
    fun detail(@PathVariable merchantId: String, request: HttpServletRequest): ResponseEntity<ByteArray> = guarded(request, "detail") {
        if (!MerchantSearchRequestParser.MERCHANT_ID.matches(merchantId)) return@guarded notFound(request)
        val view = query.detail(MerchantId(merchantId)) ?: return@guarded notFound(request)
        AdminChannelResponses.json(request, OK, MerchantOpsJson.detail(view))
    }

    @GetMapping("/merchants/{merchantId}/history/after/{seq}")
    fun history(@PathVariable merchantId: String, @PathVariable seq: String, request: HttpServletRequest): ResponseEntity<ByteArray> = guarded(request, "history") {
        if (!MerchantSearchRequestParser.MERCHANT_ID.matches(merchantId)) return@guarded notFound(request)
        val after = seq.takeIf { SEQ.matches(it) }?.toLongOrNull() ?: return@guarded AdminChannelResponses.error(request, BAD_REQUEST, AdminChannelResponses.REQUEST_MALFORMED)
        val page = query.history(MerchantId(merchantId), after) ?: return@guarded notFound(request)
        AdminChannelResponses.json(request, OK, MerchantOpsJson.history(page))
    }

    private fun guarded(request: HttpServletRequest, endpoint: String, block: () -> ResponseEntity<ByteArray>): ResponseEntity<ByteArray> =
        permits.read(endpoint, { AdminChannelResponses.error(request, SERVICE_UNAVAILABLE, AdminChannelResponses.ADMIN_READ_BUSY) }) {
            try {
                block()
            } catch (e: RuntimeException) {
                // 예외 메시지·원인은 남기지 않는다(드라이버 원문에 바인드 값이 섞일 수 있다) — 분류(타입 이름)만.
                log.warn("operation=MerchantOps endpoint={} correlationId={} result=TEMPORARILY_UNAVAILABLE failure={}", endpoint, AdminChannelResponses.requestId(request), e.javaClass.simpleName)
                AdminChannelResponses.error(request, SERVICE_UNAVAILABLE, AdminChannelResponses.TEMPORARILY_UNAVAILABLE)
            }
        }

    private fun notFound(request: HttpServletRequest) = AdminChannelResponses.error(request, NOT_FOUND, AdminChannelResponses.MERCHANT_NOT_FOUND)

    private companion object {
        val SEQ = Regex("^[0-9]{1,19}$")
        const val OK = 200
        const val BAD_REQUEST = 400
        const val NOT_FOUND = 404
        const val SERVICE_UNAVAILABLE = 503
    }
}

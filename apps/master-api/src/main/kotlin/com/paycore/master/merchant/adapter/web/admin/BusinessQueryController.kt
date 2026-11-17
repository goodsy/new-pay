package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.merchant.application.provided.BusinessProfileQueryUseCase
import com.paycore.master.merchant.domain.BusinessId
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
 * 사업자 조회 채널(BUSINESS-PROFILE-001 §6.1~§6.5 — BIZ-2, ADMIN 서명 채널 뒤·읽기 전용). provided [BusinessProfileQueryUseCase]만 부른다(r-2 — jOOQ Reader 직접 사용 없음).
 * 조회 permit(`AdminChannelPermits.read` — 명령 permit과 독립), 소진 503 `ADMIN_READ_BUSY`. 검색·번호 조회 본문 위반·경로 ID 형식 위반·이력 cursor 형식 위반은 400
 * `REQUEST_MALFORMED`(값 미반환), 없는 사업자 404 `BUSINESS_NOT_FOUND`, 저장소 실패 503 `TEMPORARILY_UNAVAILABLE`.
 * 번호 조회는 POST 본문만(URL·접근 로그에 번호 없음 — C-6), 응답은 찾음·못 찾음 같은 모양. 로그는 연산·결과 분류·건수·상관 ID만(입력 값·번호·이름 없음).
 * 감사·속도 제한·역할(OPERATOR·APPROVER — D-BIZ-006 ①)은 ADMIN(BIZ-3)이 한다.
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/internal/v1/admin/businesses")
class BusinessQueryController(private val query: BusinessProfileQueryUseCase, private val permits: AdminChannelPermits) {

    private val log = LoggerFactory.getLogger(BusinessQueryController::class.java)

    @PostMapping("/searches")
    fun search(request: HttpServletRequest): ResponseEntity<ByteArray> = guarded(request, "businessSearch") {
        val filter = BusinessSearchRequestParser.parse(request.inputStream.readAllBytes()) ?: return@guarded malformed(request)
        val page = query.search(filter)
        log.info("operation=BusinessSearch correlationId={} result=OK items={}", AdminChannelResponses.requestId(request), page.items.size)
        AdminChannelResponses.json(request, OK, BusinessProfileJson.directory(page))
    }

    @PostMapping("/registration-number-lookups")
    fun lookup(request: HttpServletRequest): ResponseEntity<ByteArray> = guarded(request, "businessLookup") {
        val number = BusinessLookupRequestParser.parse(request.inputStream.readAllBytes()) ?: return@guarded malformed(request)
        val items = query.lookup(number)
        log.info("operation=BusinessLookup correlationId={} result=OK items={}", AdminChannelResponses.requestId(request), items.size)
        AdminChannelResponses.json(request, OK, BusinessProfileJson.lookup(items))
    }

    @GetMapping("/{businessId}")
    fun detail(@PathVariable businessId: String, request: HttpServletRequest): ResponseEntity<ByteArray> = guarded(request, "businessDetail") {
        if (!BusinessSearchRequestParser.BUSINESS_ID.matches(businessId)) return@guarded malformed(request)
        val view = query.detail(BusinessId(businessId)) ?: return@guarded notFound(request)
        AdminChannelResponses.json(request, OK, BusinessProfileJson.detail(view))
    }

    @GetMapping("/{businessId}/history/after/{seq}")
    fun history(@PathVariable businessId: String, @PathVariable seq: String, request: HttpServletRequest): ResponseEntity<ByteArray> =
        guarded(request, "businessHistory") {
            if (!BusinessSearchRequestParser.BUSINESS_ID.matches(businessId)) return@guarded malformed(request)
            val after = seq.takeIf { SEQ.matches(it) }?.toLongOrNull() ?: return@guarded malformed(request)
            val page = query.history(BusinessId(businessId), after) ?: return@guarded notFound(request)
            AdminChannelResponses.json(request, OK, BusinessProfileJson.history(page))
        }

    private fun guarded(request: HttpServletRequest, endpoint: String, block: () -> ResponseEntity<ByteArray>): ResponseEntity<ByteArray> =
        permits.read(endpoint, { AdminChannelResponses.error(request, SERVICE_UNAVAILABLE, AdminChannelResponses.ADMIN_READ_BUSY) }) {
            try {
                block()
            } catch (e: RuntimeException) {
                // 예외 메시지·원인은 남기지 않는다(드라이버 원문에 바인드 값 — 등록번호 — 이 섞일 수 있다). 분류(타입 이름)만.
                log.warn(
                    "operation=BusinessQuery endpoint={} correlationId={} result=TEMPORARILY_UNAVAILABLE failure={}",
                    endpoint, AdminChannelResponses.requestId(request), e.javaClass.simpleName,
                )
                AdminChannelResponses.error(request, SERVICE_UNAVAILABLE, AdminChannelResponses.TEMPORARILY_UNAVAILABLE)
            }
        }

    private fun malformed(request: HttpServletRequest) = AdminChannelResponses.error(request, BAD_REQUEST, AdminChannelResponses.REQUEST_MALFORMED)

    private fun notFound(request: HttpServletRequest) = AdminChannelResponses.error(request, NOT_FOUND, AdminChannelResponses.BUSINESS_NOT_FOUND)

    private companion object {
        val SEQ = Regex("^[0-9]{1,19}$")
        const val OK = 200
        const val BAD_REQUEST = 400
        const val NOT_FOUND = 404
        const val SERVICE_UNAVAILABLE = 503
    }
}

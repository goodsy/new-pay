package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.merchant.application.provided.MerchantAccountLabelQuery
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * ADMIN 거래 목록용 MID 라벨 묶음 조회(TXN-LIST-ENRICH-001 §4.2 — ADMIN 채널 서명 인증 뒤(query 금지·본문 상한은 채널 필터), 읽기 전용). **라벨 전용 permit**(§3.6 M-1 — 조회 permit과 별개,
 * 대기 0: 없으면 503 `ADMIN_READ_BUSY` + `busy{endpoint="merchant_account_labels"}`), 형식 위반 400 `REQUEST_MALFORMED`, 저장소 실패·코드 없는 가맹점(결함) 503 `TEMPORARILY_UNAVAILABLE`.
 * 200 `{"items":[{"merchantAccountId","merchantName","merchantCode"}]}`(MID 오름차순, 없는 MID 빠짐). 로그는 연산·결과 분류·건수만(MID·상호·코드·예외 문구 없음).
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/internal/v1/admin")
class MerchantAccountLabelController(private val query: MerchantAccountLabelQuery, private val permits: AdminChannelPermits) {

    private val log = LoggerFactory.getLogger(MerchantAccountLabelController::class.java)

    @PostMapping("/merchant-account-labels")
    fun labels(request: HttpServletRequest): ResponseEntity<ByteArray> =
        permits.labels(ENDPOINT, { AdminChannelResponses.error(request, SERVICE_UNAVAILABLE, AdminChannelResponses.ADMIN_READ_BUSY) }) {
            val ids = MerchantAccountLabelRequestParser.parse(request.inputStream.readAllBytes())?.sortedBy { it.value }
            if (ids == null) {
                log.info("operation=MerchantAccountLabels correlationId={} result=REQUEST_MALFORMED", AdminChannelResponses.requestId(request))
                return@labels AdminChannelResponses.error(request, BAD_REQUEST, AdminChannelResponses.REQUEST_MALFORMED)
            }
            try {
                val items = query.labels(ids)
                log.info("operation=MerchantAccountLabels correlationId={} result=OK requested={} items={}", AdminChannelResponses.requestId(request), ids.size, items.size)
                AdminChannelResponses.json(request, OK, MerchantOpsJson.accountLabels(items))
            } catch (e: RuntimeException) {
                // 예외 메시지·원인은 남기지 않는다(드라이버 원문에 바인드 값이 섞일 수 있다) — 분류(타입 이름)만.
                log.warn(
                    "operation=MerchantAccountLabels correlationId={} result=TEMPORARILY_UNAVAILABLE failure={}", AdminChannelResponses.requestId(request), e.javaClass.simpleName,
                )
                AdminChannelResponses.error(request, SERVICE_UNAVAILABLE, AdminChannelResponses.TEMPORARILY_UNAVAILABLE)
            }
        }

    private companion object {
        const val ENDPOINT = "merchant_account_labels"
        const val OK = 200
        const val BAD_REQUEST = 400
        const val SERVICE_UNAVAILABLE = 503
    }
}

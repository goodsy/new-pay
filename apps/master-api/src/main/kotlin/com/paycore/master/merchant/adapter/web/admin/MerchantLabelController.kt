package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.merchant.application.provided.MerchantLabelQuery
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * ADMIN 승인·감사 화면용 가맹점 라벨 묶음 조회(MERCHANT-CODE-001 §5.6.3 MC-3b — ADMIN 채널 서명 인증 뒤(query 금지·본문 16 KiB 상한은 채널 필터), 읽기 전용, 감사 없음).
 * **라벨 전용 permit 공유**(C-34 — `merchant-account-labels`와 같은 Semaphore, 조회 permit·`approval-decisions`와 별개, 대기 0: 없으면 503 `ADMIN_READ_BUSY` +
 * `busy{endpoint="merchant_labels"}`), 형식 위반 400 `REQUEST_MALFORMED`, 저장소 실패·코드 없는 가맹점(결함) 503 `TEMPORARILY_UNAVAILABLE`.
 * 200 `{"items":[{"merchantId","merchantName","merchantCode"}]}`(merchantId 오름차순, 없는 키 빠짐). 로그는 연산·결과 분류·건수만(키·상호·코드·예외 문구 없음).
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/internal/v1/admin")
class MerchantLabelController(private val query: MerchantLabelQuery, private val permits: AdminChannelPermits) {

    private val log = LoggerFactory.getLogger(MerchantLabelController::class.java)

    @PostMapping("/merchant-labels")
    fun labels(request: HttpServletRequest): ResponseEntity<ByteArray> =
        permits.labels(ENDPOINT, { AdminChannelResponses.error(request, SERVICE_UNAVAILABLE, AdminChannelResponses.ADMIN_READ_BUSY) }) {
            val ids = MerchantLabelRequestParser.parse(request.inputStream.readAllBytes())?.sortedBy { it.value }
            if (ids == null) {
                log.info("operation=MerchantLabels correlationId={} result=REQUEST_MALFORMED", AdminChannelResponses.requestId(request))
                return@labels AdminChannelResponses.error(request, BAD_REQUEST, AdminChannelResponses.REQUEST_MALFORMED)
            }
            try {
                val items = query.labels(ids)
                log.info("operation=MerchantLabels correlationId={} result=OK requested={} items={}", AdminChannelResponses.requestId(request), ids.size, items.size)
                AdminChannelResponses.json(request, OK, MerchantOpsJson.merchantLabels(items))
            } catch (e: RuntimeException) {
                // 예외 메시지·원인은 남기지 않는다(드라이버 원문에 바인드 값이 섞일 수 있다) — 분류(타입 이름)만.
                log.warn("operation=MerchantLabels correlationId={} result=TEMPORARILY_UNAVAILABLE failure={}", AdminChannelResponses.requestId(request), e.javaClass.simpleName)
                AdminChannelResponses.error(request, SERVICE_UNAVAILABLE, AdminChannelResponses.TEMPORARILY_UNAVAILABLE)
            }
        }

    private companion object {
        const val ENDPOINT = "merchant_labels"
        const val OK = 200
        const val BAD_REQUEST = 400
        const val SERVICE_UNAVAILABLE = 503
    }
}

package com.paycore.payment.payment.adapter.web.admin

import com.paycore.payment.payment.adapter.web.ApiResponses
import com.paycore.payment.payment.adapter.web.SafeThrowable
import com.paycore.payment.payment.application.provided.HomeKpiItem
import com.paycore.payment.payment.application.provided.HomeKpiQuery
import com.paycore.payment.payment.application.provided.HomeKpiSnapshot
import com.paycore.payment.payment.application.provided.OperatorQueryFailure
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 운영 홈 오늘 KPI(ADMIN-HOME-KPI-001 §4.1 — 비권위 jOOQ 집계, ADMIN 채널 서명 인증 뒤, ArchUnit 정확 FQN). 본문·query 없음(query 문자열은 서명 필터가 400). 기존 조회 permit 공용
 * (대기 0 — 없으면 503 `ADMIN_READ_BUSY` + `busy{endpoint=home_kpi}`), 질의 시간 초과·저장소 실패·불변식 위반 503 `TEMPORARILY_UNAVAILABLE`(시간 초과는 `query_timeout{endpoint=home_kpi}`,
 * 500 없음). 200 본문 키 순서 고정, 금액은 minor unit **10진 문자열**(BigInteger.toString — float·Long·JSON 숫자 없음), `Cache-Control: no-store`. 처리 시간 timer
 * `paycore.payment.admin_channel.home_kpi.duration`. 로그는 연산·결과 분류만(금액·예외 문구 없음). 감사 없음(§6 — 환경·통화별 합계뿐).
 */
@RestController
@RequestMapping("/internal/v1/admin/")
class AdminHomeKpiController(
    private val query: HomeKpiQuery,
    private val permits: AdminChannelPermits,
    private val registry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(AdminHomeKpiController::class.java)

    @GetMapping("home-kpis")
    fun today(request: HttpServletRequest): ResponseEntity<ByteArray> = noStore(
        permits.read(ENDPOINT, { AdminChannelResponses.error(request, 503, AdminChannelResponses.ADMIN_READ_BUSY) }) {
            val sample = Timer.start(registry)
            try {
                AdminChannelResponses.json(request, 200, body(query.today()))
            } catch (e: OperatorQueryFailure.TimedOut) {
                registry.counter(TIMEOUT_METRIC, "endpoint", ENDPOINT).increment()
                unavailable(request, e)
            } catch (e: RuntimeException) {
                unavailable(request, e)
            } finally {
                sample.stop(registry.timer(DURATION_METRIC))
            }
        },
    )

    private fun noStore(response: ResponseEntity<ByteArray>): ResponseEntity<ByteArray> =
        ResponseEntity.status(response.statusCode).headers { h -> h.addAll(response.headers); h.cacheControl = CacheControl.noStore().headerValue }.body(response.body)

    private fun unavailable(request: HttpServletRequest, e: RuntimeException): ResponseEntity<ByteArray> {
        log.warn("operation=AdminHomeKpi endpoint={} correlationId={} result=TEMPORARILY_UNAVAILABLE failure={}", ENDPOINT, ApiResponses.requestId(request), SafeThrowable.summary(e))
        return AdminChannelResponses.error(request, 503, AdminChannelResponses.TEMPORARILY_UNAVAILABLE)
    }

    private fun body(s: HomeKpiSnapshot): Map<String, Any?> = linkedMapOf(
        "asOf" to AdminChannelResponses.time(s.asOf),
        "businessDate" to s.businessDate.toString(),
        "timeZone" to s.timeZone,
        "from" to AdminChannelResponses.time(s.from),
        "items" to s.items.map(::item),
    )

    private fun item(i: HomeKpiItem) = linkedMapOf<String, Any?>(
        "environment" to i.environment,
        "currency" to i.currency,
        "approvedAmountMinor" to i.figures.approvedMinor.toString(),
        "cancelledAmountMinor" to i.figures.cancelledMinor.toString(),
        "netApprovedAmountMinor" to i.figures.netApprovedMinor.toString(),
        "approvedCount" to i.figures.approvedCount,
    )

    private companion object {
        const val ENDPOINT = "home_kpi"
        const val TIMEOUT_METRIC = "paycore.payment.admin_channel.query_timeout"
        const val DURATION_METRIC = "paycore.payment.admin_channel.home_kpi.duration"
    }
}

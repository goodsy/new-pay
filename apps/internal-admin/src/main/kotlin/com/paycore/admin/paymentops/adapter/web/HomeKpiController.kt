package com.paycore.admin.paymentops.adapter.web

import com.paycore.admin.paymentops.application.provided.HomeKpiResult
import com.paycore.admin.paymentops.application.provided.HomeKpiUseCase
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 화면 운영 홈 오늘 KPI(ADMIN-HOME-KPI-001 §4.2). 모두 `no-store`. 응답: 200(PAYMENT 문서를 fail-closed 검사·재구성한 같은 모양) / 403 `FORBIDDEN` / 429 `RATE_LIMITED` /
 * 503 `PAYMENT_UNAVAILABLE`(원인 분류는 응답에 넣지 않는다). 감사 없음(§6).
 */
@RestController
class HomeKpiController(private val useCase: HomeKpiUseCase) {

    @GetMapping("/api/home-kpis")
    fun today(authentication: Authentication): ResponseEntity<out Any> = when (val result = useCase.today(authentication.name)) {
        is HomeKpiResult.Found -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result.document)
        HomeKpiResult.Forbidden -> error(HttpStatus.FORBIDDEN, "FORBIDDEN")
        HomeKpiResult.RateLimited -> error(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED")
        is HomeKpiResult.Unavailable -> error(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENT_UNAVAILABLE")
    }

    private fun error(status: HttpStatus, code: String): ResponseEntity<Any> = ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(mapOf("error" to code))
}

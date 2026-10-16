package com.paycore.payment.payment.adapter.web

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.web.firewall.RequestRejectedException
import org.springframework.security.web.firewall.RequestRejectedHandler

/**
 * Spring Security 방화벽(StrictHttpFirewall)이 거절한 요청(`//`·`;`·인코딩된 `/`·`..` 등)을 오류 dispatch 없이 바로 공개 API 형식 400
 * `INVALID_REQUEST`로 쓴다(review round1 n-3). 경로·거절 사유 원문을 응답·로그에 넣지 않는다.
 */
@Configuration(proxyBeanMethods = false)
class ApiFirewallConfiguration {
    @Bean
    fun apiRequestRejectedHandler(): RequestRejectedHandler = RequestRejectedHandler {
            request: HttpServletRequest, response: HttpServletResponse, _: RequestRejectedException ->
        val requestId = ApiResponses.requestId(request)
        LoggerFactory.getLogger(ApiFirewallConfiguration::class.java).info("operation=Firewall correlationId={} result=INVALID_REQUEST", requestId)
        ApiResponses.write(response, ApiError(ApiErrorCode.INVALID_REQUEST), requestId)
    }
}

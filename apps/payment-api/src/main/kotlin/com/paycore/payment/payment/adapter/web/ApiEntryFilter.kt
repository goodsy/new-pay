package com.paycore.payment.payment.adapter.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.web.filter.OncePerRequestFilter

/**
 * 진입 필터: requestId 발급(`PayCore-Request-Id` — 401 등 모든 응답에, 로그 MDC `correlationId`) + connector별 경로 격리(3 connector — AUTHN-001 AN-15):
 * 내부 포트 ↔ `/internal` 아래, checkout 포트 ↔ `/checkout` 아래(어느 쪽이든 짝이 아니면 404). 공개 포트의 그 밖 경로 규칙은 기존과 같다(§24.12).
 */
class ApiEntryFilter(private val internal: InternalConnector, private val checkout: CheckoutConnector) : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val requestId = ApiResponses.requestId(request)
        response.setHeader(ApiResponses.REQUEST_ID_HEADER, requestId)
        // 요청 스레드의 로그 상관 ID(review round2 n-e): 웹 밖(entitlement 판정 등) 로그가 같은 correlationId를 남긴다.
        MDC.put(ApiResponses.CORRELATION_ID_MDC_KEY, requestId)
        try {
            val internalPath = isInternalPath(request)
            val internalPort = internal.localPort
            val viaInternalConnector = internalPort > 0 && request.localPort == internalPort
            val checkoutPort = checkout.localPort
            val viaCheckoutConnector = checkoutPort > 0 && request.localPort == checkoutPort
            if (viaInternalConnector != internalPath || viaCheckoutConnector != isCheckoutPath(request)) {
                ApiResponses.write(response, ApiError(ApiErrorCode.NOT_FOUND), requestId)
                return
            }
            chain.doFilter(request, response)
        } finally {
            MDC.remove(ApiResponses.CORRELATION_ID_MDC_KEY)
        }
    }

    companion object {
        /**
         * 컨테이너가 정규화·복호화한 경로(servletPath + pathInfo — `..`·`;` 경로 파라미터·퍼센트 인코딩이 풀린 값)로 판단한다. 원문 requestURI로
         * 판단하면 `/v1/..;/internal/…` 같은 표기로 판단과 실제 매핑이 어긋날 수 있다.
         */
        fun isInternalPath(request: HttpServletRequest): Boolean {
            val path = request.servletPath + (request.pathInfo ?: "")
            return path == "/internal" || path.startsWith("/internal/")
        }

        /** 결제창 경로(정규화된 경로 — [isInternalPath]와 같은 이유). */
        fun isCheckoutPath(request: HttpServletRequest): Boolean {
            val path = request.servletPath + (request.pathInfo ?: "")
            return path == "/checkout" || path.startsWith("/checkout/")
        }
    }
}

package com.paycore.payment.payment.adapter.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.web.filter.OncePerRequestFilter

/**
 * 진입 필터: requestId 발급(`PayCore-Request-Id` — 401 등 모든 응답에, 로그 MDC `correlationId`) + connector별 경로 격리.
 */
class ApiEntryFilter(private val internal: InternalConnector) : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val requestId = ApiResponses.requestId(request)
        response.setHeader(ApiResponses.REQUEST_ID_HEADER, requestId)
        // 요청 스레드의 로그 상관 ID(review round2 n-e): 웹 밖(entitlement 판정 등) 로그가 같은 correlationId를 남긴다.
        MDC.put(ApiResponses.CORRELATION_ID_MDC_KEY, requestId)
        try {
            val internalPath = isInternalPath(request)
            val internalPort = internal.localPort
            val viaInternalConnector = internalPort > 0 && request.localPort == internalPort
            if (viaInternalConnector != internalPath) {
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
    }
}

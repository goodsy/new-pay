package com.paycore.payment.payment.adapter.web

import com.paycore.payment.payment.application.provided.MerchantAuthenticator
import com.paycore.payment.payment.application.provided.MerchantAuthenticationResult
import com.paycore.payment.payment.application.provided.MerchantAuthenticationUnavailable
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.transaction.TransactionException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.web.filter.OncePerRequestFilter

/**
 * `Authorization: Bearer <key>` 검증 필터. 실패하면 원인을 요청 속성에 남기고 다음으로 넘긴다(보호 경로면 진입점이 401). 내부 경로는 검사하지
 * 않는다. 키 원문은 어디에도 남기지 않는다.
 */
class MerchantBearerAuthenticationFilter(
    private val authenticate: MerchantAuthenticator,
    private val settings: PaymentApiSettings,
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(MerchantBearerAuthenticationFilter::class.java)

    override fun shouldNotFilter(request: HttpServletRequest): Boolean = ApiEntryFilter.isInternalPath(request)

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val headers = request.getHeaders(HttpHeaders.AUTHORIZATION).toList()
        val presented: String? = when {
            headers.isEmpty() -> null
            // 인증 scheme 이름은 대소문자를 구분하지 않는다(RFC 7235 §2.1). 자격 증명(키) 자체는 그대로 비교한다.
            headers.size > 1 || !headers[0].regionMatches(0, BEARER_PREFIX, 0, BEARER_PREFIX.length, ignoreCase = true) -> MALFORMED_MARKER
            else -> headers[0].substring(BEARER_PREFIX.length)
        }
        val result = try {
            authenticate.authenticate(presented)
        } catch (e: TransactionException) {
            // 방어(review round2 m-1 (a)): application이 TX 실패를 MerchantAuthenticationUnavailable로 옮기지만, TX 기반 실패가 그대로 올라와도
            // (연결 없음·rollback/commit 실패) 결함이 아니라 검증 불가 503이다.
            val requestId = ApiResponses.requestId(request)
            log.warn("operation=AuthenticateMerchant correlationId={} result=TEMPORARILY_UNAVAILABLE failure=TRANSACTION_UNAVAILABLE", requestId)
            ApiResponses.write(
                response, ApiError(ApiErrorCode.TEMPORARILY_UNAVAILABLE), requestId,
                mapOf(HttpHeaders.RETRY_AFTER to settings.retryAfter.temporarilyUnavailable.toRetryAfterSeconds()),
            )
            return
        } catch (e: MerchantAuthenticationUnavailable) {
            // 검증 불가(투영 저장소 일시 실패: 교착·잠금 대기·연결) — 401로 거짓 판정하지 않는다. 같은 키로 재시도.
            val requestId = ApiResponses.requestId(request)
            log.warn(
                "operation=AuthenticateMerchant correlationId={} result=TEMPORARILY_UNAVAILABLE failure={} vendorCode={} sqlState={}",
                requestId, e.kind, e.diagnostic?.vendorCode, e.diagnostic?.sqlState,
            )
            ApiResponses.write(
                response, ApiError(ApiErrorCode.TEMPORARILY_UNAVAILABLE), requestId,
                mapOf(HttpHeaders.RETRY_AFTER to settings.retryAfter.temporarilyUnavailable.toRetryAfterSeconds()),
            )
            return
        } catch (e: RuntimeException) {
            // 분류되지 않은 예외는 결함이다(n-7): 500, 메시지 원문 없이 클래스 이름 사슬만 남긴다.
            val requestId = ApiResponses.requestId(request)
            log.error("operation=AuthenticateMerchant correlationId={} result=INTERNAL_ERROR failure=DEFECT exception={}", requestId, SafeThrowable.summary(e))
            ApiResponses.write(response, ApiError(ApiErrorCode.INTERNAL_ERROR), requestId)
            return
        }
        when (result) {
            is MerchantAuthenticationResult.Authenticated ->
                SecurityContextHolder.setContext(SecurityContextImpl(MerchantAuthenticationToken(result.authentication)))
            is MerchantAuthenticationResult.Rejected -> request.setAttribute(FAILURE_ATTRIBUTE, result.reason)
        }
        chain.doFilter(request, response)
    }

    companion object {
        const val FAILURE_ATTRIBUTE = "paycore.authenticationFailure"
        private const val BEARER_PREFIX = "Bearer "

        /** 키 형식 검사에서 반드시 MALFORMED가 되는 값(Bearer 아님·헤더 여러 개). */
        private const val MALFORMED_MARKER = "\u0000"
    }
}

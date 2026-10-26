package com.paycore.admin.security

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler
import org.springframework.security.web.csrf.CsrfTokenRequestHandler
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler
import java.util.function.Supplier

/**
 * SPA용 CSRF 토큰 해석(D-10). Security의 같은 이름 클래스는 `CsrfConfigurer` 안의 private 클래스라 재사용할 수 없다(S0 §17.5).
 *
 * - 요청 속성으로 노출하는 토큰은 BREACH 완화를 위해 XOR 마스킹(서버 렌더링용 — SPA는 쓰지 않음).
 * - `X-XSRF-TOKEN` 헤더가 있으면 원문 토큰으로 보고 그대로 비교한다(쿠키로 전달한 값이 원문이다). 없으면 XOR 형식으로 해석한다.
 * - 원문 토큰의 지연 공급자를 [RAW_TOKEN_ATTRIBUTE]로 남겨 [XsrfCookieFilter]가 쿠키에 담게 한다. 비교 대상은 언제나 세션에
 *   저장된 토큰이다(저장소 = StringSessionCsrfTokenRepository — 세션 문자열).
 *
 * BREACH 주의(S1 리뷰 n2): 원문 토큰은 **쿠키(Set-Cookie 헤더 — HTTP 압축 대상 아님)로만** 전달한다. 응답 본문에 원문 토큰을 넣지 않는다.
 * S4의 발급 응답(`GET /api/auth/csrf`)도 본문에 원문 토큰을 공격자가 영향을 줄 수 있는 내용과 함께 압축 가능한 형태로 넣지 않는다
 * (본문이 필요하면 XOR 마스킹 값 — 요청마다 바뀌어 압축 길이 비교가 무의미).
 */
class SpaCsrfTokenRequestHandler : CsrfTokenRequestHandler {

    private val plain = CsrfTokenRequestAttributeHandler()
    private val xor = XorCsrfTokenRequestAttributeHandler()

    override fun handle(request: HttpServletRequest, response: HttpServletResponse, csrfToken: Supplier<CsrfToken>) {
        xor.handle(request, response, csrfToken)
        request.setAttribute(RAW_TOKEN_ATTRIBUTE, csrfToken)
    }

    override fun resolveCsrfTokenValue(request: HttpServletRequest, csrfToken: CsrfToken): String? =
        if (!request.getHeader(csrfToken.headerName).isNullOrBlank()) {
            plain.resolveCsrfTokenValue(request, csrfToken)
        } else {
            xor.resolveCsrfTokenValue(request, csrfToken)
        }

    companion object {
        val RAW_TOKEN_ATTRIBUTE: String = SpaCsrfTokenRequestHandler::class.java.name + ".RAW_TOKEN"
    }
}

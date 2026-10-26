package com.paycore.admin.security

import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.web.util.matcher.RequestMatcher

/**
 * 익명에게 허용하는 SPA 문서 요청의 허용 목록(S1 리뷰 m3·m6). `GET /`·`GET /index.html`과 SPA 화면 경로만 맞는다.
 *
 * - 화면 경로: 소문자·숫자·하이픈 세그먼트 1~3개, 첫 세그먼트가 `api`·`assets`·`error`·`actuator`가 아님(console의 SPA fallback과 같은 규칙).
 * - **디코딩 전 원문 URI**(`requestURI`)로 판정한다 — `%61pprovals`처럼 인코딩된 문자·`;` 경로 매개변수·끝 슬래시는 화면 경로가 아니다.
 *   (정책: 끝 슬래시는 정규 경로가 아니므로 SPA로 보내지 않는다.)
 * - actuator·favicon 등 그 밖의 경로는 허용 목록 밖이라 익명 401이다. `/assets/` 아래는 별도 체인이 다룬다.
 */
object SpaDocumentRequestMatcher : RequestMatcher {

    private val SCREEN = Regex("""^/(?!(?:api|assets|error|actuator)(?:/|$))[a-z0-9-]+(?:/[a-z0-9-]+){0,2}$""")

    override fun matches(request: HttpServletRequest): Boolean {
        if (request.method != "GET") return false
        val uri = request.requestURI ?: return false
        return uri == "/" || uri == "/index.html" || SCREEN.matches(uri)
    }
}

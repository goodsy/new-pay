package com.paycore.admin.console.adapter.web

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

/**
 * SPA 경로(예: `/approvals/inbox`)의 새로고침·직접 진입을 `index.html`로 보낸다(같은 출처 서빙, §4.10).
 * 점(.)이 있는 경로(정적 파일)와 `api`·`assets`·`error`·`actuator`로 시작하는 경로는 제외한다 — 없는 API·asset은 index로 바꾸지 않는다.
 * 익명 접근 허용 여부는 security의 SpaDocumentRequestMatcher(같은 규칙 + 디코딩 전 원문 URI)가 먼저 판정한다.
 * 경로 깊이는 3단계까지(화면 라우트 범위). `/`는 Boot welcome page가 index.html을 준다.
 */
@Controller
class SpaFallbackController {

    @GetMapping(
        "/{first:$FIRST}",
        "/{first:$FIRST}/{second:$NEXT}",
        "/{first:$FIRST}/{second:$NEXT}/{third:$NEXT}",
    )
    fun index(): String = "forward:/index.html"

    private companion object {
        const val FIRST = "(?!api\$|assets\$|error\$|actuator\$)[a-z0-9-]+"
        const val NEXT = "[a-z0-9-]+"
    }
}

package com.paycore.payment.payment.adapter.web

import tools.jackson.databind.JsonNode

/**
 * 결제창 React shell 응답 읽기 도구(src/test 전용, CHECKOUT-REACT-001 §3): `#root`의 `data-checkout` 속성(HTML 속성 escape된 JSON)을 꺼내 JSON으로 읽고,
 * shell의 script 태그를 돌려준다. 시험이 Thymeleaf 마크업 대신 서버가 보낸 화면 데이터를 단언하게 한다.
 */
object CheckoutShellPage {
    private val ROOT = Regex("<div id=\"root\" data-checkout=\"([^\"]*)\"></div>")
    private val SCRIPT = Regex("<script\\b[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE)

    /** shell 데이터(정확히 하나의 `#root`). */
    fun data(body: String): JsonNode {
        val matches = ROOT.findAll(body).toList()
        check(matches.size == 1) { "expected exactly one #root with data-checkout, found ${matches.size}" }
        return ApiResponses.mapper.readTree(unescape(matches.single().groupValues[1]))
    }

    /** 화면의 CSRF 숨김 필드가 될 값(마스킹된 값). */
    fun csrf(body: String): String = data(body).let { d ->
        check(d.get("csrfParameter").stringValue() == "_csrf") { "unexpected CSRF parameter name" }
        d.get("csrfToken").stringValue()
    }

    fun scripts(body: String): List<String> = SCRIPT.findAll(body).map { it.value }.toList()

    private fun unescape(value: String): String =
        value.replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
}

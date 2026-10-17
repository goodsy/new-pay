package com.paycore.payment.payment.adapter.web

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/**
 * 결제창 템플릿 XSS 규칙(AUTHN-001 §12.5): `templates/checkout` 아래는 `th:text`·`th:value`·`th:href`·`th:action`만 쓴다 — `th:utext`, 비escape inline `[(…)]`,
 * `th:inline="javascript"`, `<script`는 금지(JS 없음 — CSP `script-src 'none'`). 검사기가 실제로 잡는지 위반 fixture 템플릿(시험 자원)으로 확인한다.
 */
class CheckoutTemplateScanSpec : FunSpec({

    val resolver = PathMatchingResourcePatternResolver()

    fun violations(location: String): List<String> = resolver.getResources(location).flatMap { r ->
        val text = r.inputStream.bufferedReader(Charsets.UTF_8).readText()
        FORBIDDEN.filter { (_, pattern) -> pattern.containsMatchIn(text) }.map { (name, _) -> "${r.filename}:$name" }
    }

    test("production checkout templates use only escaped output") {
        resolver.getResources("classpath*:templates/checkout/**/*.html").size shouldBeGreaterThan 1
        violations("classpath*:templates/checkout/**/*.html") shouldBe emptyList()
    }

    test("the scanner catches each forbidden construct in the violation fixtures") {
        violations("classpath*:checkout-template-violations/*.html") shouldContainExactlyInAnyOrder listOf(
            "utext.html:th:utext", "unescaped-inline.html:unescaped inline", "inline-js.html:inline javascript", "script.html:script tag",
        )
    }
})

private val FORBIDDEN = listOf(
    "th:utext" to Regex("th:utext", RegexOption.IGNORE_CASE),
    "unescaped inline" to Regex("\\[\\("),
    "inline javascript" to Regex("th:inline\\s*=\\s*\"javascript\"", RegexOption.IGNORE_CASE),
    "script tag" to Regex("<script", RegexOption.IGNORE_CASE),
)

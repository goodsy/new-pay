package com.paycore.payment.payment.adapter.web

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/**
 * 결제창 React 번들 정적 검사(CHECKOUT-REACT-001 §4 — `CheckoutTemplateScanSpec`(AUTHN-001 §12.5 Thymeleaf 템플릿 검사)을 대체): 빌드된 shell(`checkout-shell/index.html`)은
 * 같은 출처 `/checkout/assets/` 외부 script·stylesheet만 쓰고 inline script·`<style>`·`style=`·`on*=` 처리기·원격 자원이 없다. CSS에는 `@import`·`@font-face`·원격 `url()`이
 * 없다(CDN·원격 글꼴 금지). Thymeleaf 결제창 템플릿은 남지 않는다. 검사기가 실제로 잡는지 위반 fixture(시험 자원)로 확인한다.
 */
class CheckoutShellScanSpec : FunSpec({

    val resolver = PathMatchingResourcePatternResolver()

    fun text(r: org.springframework.core.io.Resource) = r.inputStream.bufferedReader(Charsets.UTF_8).readText()

    fun violations(location: String, rules: List<Pair<String, Regex>>, invert: Set<String> = emptySet()): List<String> =
        resolver.getResources(location).flatMap { r ->
            val t = text(r)
            rules.filter { (name, pattern) -> pattern.containsMatchIn(t) != (name in invert) }.map { (name, _) -> "${r.filename}:$name" }
        }

    test("the built checkout shell uses only same-origin external assets and one empty #root") {
        val shells = resolver.getResources("classpath*:checkout-shell/*.html")
        shells.map { it.filename } shouldBe listOf("index.html")
        violations("classpath*:checkout-shell/*.html", HTML_FORBIDDEN, invert = setOf(ROOT_RULE)) shouldBe emptyList()
        Regex("<script\\b").findAll(text(shells.single())).count() shouldBe 1
    }

    test("the built checkout stylesheet has no imports, web fonts or remote urls; no Thymeleaf checkout template remains") {
        resolver.getResources("classpath*:static/checkout/assets/*.css").map { it.filename } shouldBe listOf("checkout.css")
        violations("classpath*:static/checkout/assets/*.css", CSS_FORBIDDEN) shouldBe emptyList()
        resolver.getResources("classpath*:templates/checkout/**").filter { it.exists() && it.isReadable }.map { it.filename } shouldBe emptyList()
        resolver.getResources("classpath*:static/**/index.html").filter { it.exists() }.map { it.filename } shouldBe emptyList()
    }

    test("the scanner catches each forbidden construct in the violation fixtures") {
        violations("classpath*:checkout-shell-violations/*.html", HTML_FORBIDDEN, invert = setOf(ROOT_RULE)) shouldContainExactlyInAnyOrder listOf(
            "inline-script.html:inline script", "remote-script.html:non same-origin asset", "style-element.html:style element",
            "style-attribute.html:style attribute", "event-handler.html:event handler attribute", "two-roots.html:$ROOT_RULE",
        )
        violations("classpath*:checkout-shell-violations/*.css", CSS_FORBIDDEN) shouldContainExactlyInAnyOrder listOf(
            "css-import.css:@import", "web-font.css:@font-face", "remote-url.css:remote url",
        )
    }
}) {
    companion object {
        const val ROOT_RULE = "exactly one empty #root"

        val HTML_FORBIDDEN = listOf(
            // src 없는 script, 또는 본문이 있는 script.
            "inline script" to Regex("<script(?![^>]*\\ssrc=)[^>]*>|<script[^>]*>\\s*[^<\\s][\\s\\S]*?</script>", RegexOption.IGNORE_CASE),
            "non same-origin asset" to Regex("\\s(?:src|href)\\s*=\\s*\"(?!/checkout/assets/)", RegexOption.IGNORE_CASE),
            "style element" to Regex("<style\\b", RegexOption.IGNORE_CASE),
            "style attribute" to Regex("\\sstyle\\s*=", RegexOption.IGNORE_CASE),
            "event handler attribute" to Regex("\\son[a-z]+\\s*=", RegexOption.IGNORE_CASE),
            // 반전 규칙: 정확히 하나일 때 "맞음" — 0개·2개 이상이 위반.
            ROOT_RULE to Regex("^(?![\\s\\S]*<div id=\"root\"></div>[\\s\\S]*<div id=\"root\")[\\s\\S]*<div id=\"root\"></div>"),
        )

        val CSS_FORBIDDEN = listOf(
            "@import" to Regex("@import", RegexOption.IGNORE_CASE),
            "@font-face" to Regex("@font-face", RegexOption.IGNORE_CASE),
            "remote url" to Regex("url\\(\\s*['\"]?\\s*(?:https?:|//)", RegexOption.IGNORE_CASE),
        )
    }
}

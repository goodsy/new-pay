package com.paycore.payment.payment.adapter.web.admin

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import jakarta.servlet.FilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import tools.jackson.databind.JsonNode
import java.time.Instant

/**
 * ADMIN-PAYMENT-OPS-001 §4.19 ②: 서명 golden `admin-call-signature-v1.golden.json`(harness `test_admin_call_signature_golden.py`가 Python `hmac`·`hashlib`으로 두 앱과 독립 생성 —
 * internal-admin과 같은 파일)을 PAYMENT가 그대로 재현·판정한다. 서명 벡터는 canonical·v1·헤더 바이트 일치, 검증 사례는 **실제 진입 필터**에 넣어 결과·사유·HTTP 상태가 같아야 한다
 * (query → 문법 → 창 → kid → 서명 순서 포함). 키·ID는 Demo 값이다.
 */
class AdminCallSignatureGoldenSpec : BehaviorSpec({

    val golden = AdminChannelFixture.golden

    afterEach { SecurityContextHolder.clearContext() }

    fun JsonNode.items(): List<JsonNode> = iterator().asSequence().toList()
    fun JsonNode.textOrNull(field: String): String? = get(field)?.takeUnless { it.isNull }?.asString()

    Given("the golden header contract") {
        Then("header name, pattern, tag and window are the ones PAYMENT uses") {
            golden.get("header").asString() shouldBe AdminCallSignature.HEADER
            golden.get("canonicalTag").asString() shouldBe AdminCallSignature.CANONICAL_TAG
            golden.get("headerPattern").asString() shouldBe "^t=([0-9]{1,10}),kid=([A-Za-z0-9._-]{1,32}),v1=([0-9a-f]{64})$"
            golden.get("windowSeconds").asLong() shouldBe AdminChannelFixture.WINDOW.seconds
            golden.get("signVectors").items().map { it.get("name").asString() } shouldBe listOf(
                "target-get", "execute-post", "execute-post-hangul-reason", "decision-get", "decision-list-get", "deadline-confirmation-post", "execute-post-second-key",
            )
        }
    }

    Given("the sign vectors") {
        golden.get("signVectors").items().forEach { v ->
            Then("vector ${v.get("name").asString()}: canonical, v1 and header are byte-identical and the verifier accepts it") {
                val body = v.get("body").asString().toByteArray(Charsets.UTF_8)
                val method = v.get("method").asString()
                val path = v.get("path").asString()
                val t = v.get("t").asLong()
                val kid = v.get("kid").asString()
                AdminCallSignature.canonical(method, path, t.toString(), body) shouldBe v.get("canonical").asString()
                AdminCallSignature.v1(AdminChannelFixture.settings().key(kid)!!, method, path, t.toString(), body) shouldBe v.get("v1").asString()
                AdminChannelFixture.header(method, path, body, kid, t) shouldBe v.get("header").asString()
                AdminCallSignature.verify(v.get("header").asString(), method, path, body, t, AdminChannelFixture.WINDOW, AdminChannelFixture.settings()::key) shouldBe
                    AdminCallVerification.Accepted(kid)
            }
        }
    }

    Given("the verify cases through the real entry filter") {
        golden.get("verifyCases").items().forEach { c ->
            val expected = c.get("expected")
            Then("case ${c.get("name").asString()}: ${expected.get("result").asString()} ${expected.textOrNull("reason") ?: ""}") {
                val registry = SimpleMeterRegistry()
                val known = c.get("knownKids").items().map { it.asString() }
                val filter = AdminChannelAuthenticationFilter(AdminChannelFixture.settings(known), registry) { Instant.ofEpochSecond(c.get("now").asLong()) }
                val path = c.get("path").asString()
                val request = MockHttpServletRequest(c.get("method").asString(), path).apply {
                    servletPath = path
                    queryString = c.textOrNull("query")
                    setContent(c.get("body").asString().toByteArray(Charsets.UTF_8))
                    c.textOrNull("header")?.let { addHeader(AdminCallSignature.HEADER, it) }
                }
                val response = MockHttpServletResponse()
                var passed = false
                filter.doFilter(request, response, FilterChain { _, _ -> passed = true })
                if (expected.get("result").asString() == "ACCEPT") {
                    passed shouldBe true
                    SecurityContextHolder.getContext().authentication!!.authorities.map { it.authority } shouldBe listOf(AdminChannelAuthenticationFilter.ADMIN_SERVICE)
                    registry.counter(AdminChannelAuthenticationFilter.AUTH_METRIC, "result", "ACCEPTED", "reason", "NONE").count() shouldBe 1.0
                } else {
                    passed shouldBe false
                    response.status shouldBe expected.get("httpStatus").asInt()
                    val code = if (response.status == 400) "QUERY_NOT_ALLOWED" else "UNAUTHENTICATED"
                    response.contentAsString shouldBe """{"error":"$code"}"""
                    registry.counter(AdminChannelAuthenticationFilter.AUTH_METRIC, "result", "REJECTED", "reason", expected.get("reason").asString()).count() shouldBe 1.0
                    SecurityContextHolder.getContext().authentication shouldBe null
                }
            }
        }
    }
})

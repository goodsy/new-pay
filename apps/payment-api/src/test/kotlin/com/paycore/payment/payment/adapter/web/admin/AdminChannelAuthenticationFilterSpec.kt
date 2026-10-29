package com.paycore.payment.payment.adapter.web.admin

import com.paycore.payment.payment.adapter.web.CapturedLogs
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Instant

/**
 * ADMIN 채널 진입 필터 단위 시험(ADMIN-PAYMENT-OPS-001 §4.2 — 서블릿 mock, DB 없음): 본문 상한 16 KiB(서명 전, `Content-Length`와 스트림 둘 다 — L-4), query 금지(서명 전 400),
 * 판정 순서 크기 → query → 문법, 헤더 여러 개 = 문법 오류, 통과 시 같은 본문 바이트를 다음 단계가 다시 읽음, 실패 로그·응답에 서명·헤더 원문 없음.
 */
class AdminChannelAuthenticationFilterSpec : BehaviorSpec({

    val now = Instant.parse("2026-10-27T01:02:03Z")
    val path = "/internal/v1/admin/operator-cancellations"

    afterEach { SecurityContextHolder.clearContext() }

    class Outcome(val response: MockHttpServletResponse, val forwarded: HttpServletRequest?, val registry: SimpleMeterRegistry) {
        fun rejected(reason: String) = registry.counter(AdminChannelAuthenticationFilter.AUTH_METRIC, "result", "REJECTED", "reason", reason).count()
    }

    fun run(request: MockHttpServletRequest): Outcome {
        val registry = SimpleMeterRegistry()
        var forwarded: HttpServletRequest? = null
        val response = MockHttpServletResponse()
        AdminChannelAuthenticationFilter(AdminChannelFixture.settings(), registry) { now }
            .doFilter(request, response, FilterChain { r, _ -> forwarded = r as HttpServletRequest })
        return Outcome(response, forwarded, registry)
    }

    fun post(body: ByteArray, signed: Boolean = true, contentLength: Boolean = true) = object : MockHttpServletRequest("POST", path) {
        // contentLength=false: 청크 전송처럼 길이를 모르는 요청(-1) — 스트림 상한으로만 거른다.
        override fun getContentLengthLong(): Long = if (contentLength) super.getContentLengthLong() else -1L
        override fun getContentLength(): Int = if (contentLength) super.getContentLength() else -1
    }.apply {
        servletPath = path
        setContent(body)
        if (signed) addHeader(AdminCallSignature.HEADER, AdminChannelFixture.header("POST", path, body, t = now.epochSecond))
    }

    Given("a correctly signed POST") {
        Then("the next step reads exactly the same body bytes (twice), and the authority is ADMIN_SERVICE") {
            val body = """{"reason":"보정 𠀀"}""".toByteArray(Charsets.UTF_8)
            val o = run(post(body))
            val next = o.forwarded!!
            next.inputStream.readAllBytes() shouldBe body
            next.inputStream.readAllBytes() shouldBe body
            next.contentLengthLong shouldBe body.size.toLong()
            SecurityContextHolder.getContext().authentication!!.authorities.map { it.authority } shouldBe listOf("ADMIN_SERVICE")
            SecurityContextHolder.getContext().authentication!!.isAuthenticated shouldBe true
        }
        Then("a body of exactly 16 KiB is accepted (declared length and unknown length)") {
            val body = ByteArray(16 * 1024) { 'a'.code.toByte() }
            run(post(body)).forwarded!!.inputStream.readAllBytes() shouldBe body
            val unknownLength = post(body, signed = false, contentLength = false).apply {
                addHeader(AdminCallSignature.HEADER, AdminChannelFixture.header("POST", path, body, t = now.epochSecond))
            }
            run(unknownLength).forwarded!!.inputStream.readAllBytes() shouldBe body
        }
    }

    Given("a body over 16 KiB (checked before the signature, L-4)") {
        Then("a declared Content-Length over the limit is 413 REQUEST_TOO_LARGE even if signed") {
            val o = run(post(ByteArray(16 * 1024 + 1)))
            o.response.status shouldBe 413
            o.response.contentAsString shouldBe """{"error":"REQUEST_TOO_LARGE"}"""
            o.forwarded shouldBe null
            o.rejected("BODY_TOO_LARGE") shouldBe 1.0
        }
        Then("a body of unknown length is cut at the limit + 1 byte and is 413") {
            val o = run(post(ByteArray(64 * 1024), signed = false, contentLength = false))
            o.response.status shouldBe 413
            o.forwarded shouldBe null
        }
        Then("size comes before query and grammar") {
            run(post(ByteArray(16 * 1024 + 1), signed = false).apply { queryString = "x=1" }).response.status shouldBe 413
        }
    }

    Given("a query string (§4.2: not part of the signature, so never allowed)") {
        Then("it is 400 QUERY_NOT_ALLOWED before the signature check, signed or not, and even when empty") {
            listOf("x=1", "").forEach { q ->
                listOf(true, false).forEach { signed ->
                    val o = run(post(ByteArray(0), signed).apply { queryString = q })
                    o.response.status shouldBe 400
                    o.response.contentAsString shouldBe """{"error":"QUERY_NOT_ALLOWED"}"""
                    o.forwarded shouldBe null
                }
            }
        }
    }

    Given("authentication failures") {
        Then("two signature headers are MALFORMED (401) even if each one is valid") {
            val request = post(ByteArray(0)).apply { addHeader(AdminCallSignature.HEADER, AdminChannelFixture.header("POST", path, ByteArray(0), t = now.epochSecond)) }
            val o = run(request)
            o.response.status shouldBe 401
            o.rejected("MALFORMED") shouldBe 1.0
        }
        Then("every failure is the same 401 UNAUTHENTICATED; the log and the response carry the reason class only, never the header or v1") {
            val good = AdminChannelFixture.header("POST", path, ByteArray(0), t = now.epochSecond)
            val v1 = good.substringAfter("v1=")
            CapturedLogs().use { logs ->
                val wrongSignature = "t=${now.epochSecond},kid=${AdminChannelFixture.K1},v1=${"0".repeat(64)}"
                listOf(null, good.replace("t=${now.epochSecond}", "t=${now.epochSecond - 301}"), good.replace(AdminChannelFixture.K1, "adm-unknown"), wrongSignature)
                    .forEach { header ->
                        val request = post(ByteArray(0), signed = false).apply { header?.let { addHeader(AdminCallSignature.HEADER, it) } }
                        val o = run(request)
                        o.response.status shouldBe 401
                        o.response.contentAsString shouldBe """{"error":"UNAUTHENTICATED"}"""
                        o.response.contentAsString shouldNotContain v1
                    }
                val lines = logs.lines().filter { "operation=AdminChannelAuthenticate" in it }
                lines.size shouldBe 4
                lines.joinToString("\n") shouldNotContain v1
                lines.joinToString("\n") shouldNotContain AdminChannelFixture.secret(AdminChannelFixture.K1)
                lines.forEach { it shouldContain "result=REJECTED" }
            }
        }
    }

    Given("defensive checks (checkpoint n-3, n-4)") {
        Then("a request with a context path is 401 fail-closed even when signed (the signed path would be ambiguous)") {
            val request = post(ByteArray(0)).apply { contextPath = "/pay" }
            val o = run(request)
            o.response.status shouldBe 401
            o.forwarded shouldBe null
            o.rejected("CONTEXT_PATH_PRESENT") shouldBe 1.0
        }
        // S7 리뷰 L-B(Coordinator 결정): 읽기 I/O 실패는 같은 바이트를 다시 보내면 성공할 수 있으므로 400(결정적 형식 판정)이 아니라 503 — ADMIN은 결과 불명확으로 재전송한다.
        Then("a body read failure is a fixed 503 REQUEST_UNREADABLE (retryable — not a deterministic 400, never 500)") {
            val broken = object : MockHttpServletRequest("POST", path) {
                override fun getInputStream(): jakarta.servlet.ServletInputStream = object : jakarta.servlet.ServletInputStream() {
                    override fun read(): Int = throw java.io.IOException("connection reset")
                    override fun isFinished() = false
                    override fun isReady() = true
                    override fun setReadListener(listener: jakarta.servlet.ReadListener?) = Unit
                }
            }.apply { servletPath = path }
            val o = run(broken)
            o.response.status shouldBe 503
            o.response.contentAsString shouldBe """{"error":"REQUEST_UNREADABLE"}"""
            o.rejected("BODY_UNREADABLE") shouldBe 1.0
        }
    }
})

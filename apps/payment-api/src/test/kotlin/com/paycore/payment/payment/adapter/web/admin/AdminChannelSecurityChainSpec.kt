package com.paycore.payment.payment.adapter.web.admin

import com.paycore.payment.payment.adapter.web.CapturedLogs
import com.paycore.payment.payment.adapter.web.InternalConnector
import com.paycore.payment.payment.adapter.web.PaymentApiTest
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.core.env.Environment
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

/**
 * ADMIN 채널 전용 보안 체인(ADMIN-PAYMENT-OPS-001 §4.2·§7.1 `AdminChannelSecurityChainSpec`): 실제 내장 Tomcat 두 connector·실제 보안 필터 체인. `/internal/v1/admin` 아래는
 * 서명 필터 → `ADMIN_SERVICE`만 통과, 무서명·틀린 서명은 원인 비구별 401, query는 서명 전 400, 본문 16 KiB 초과는 서명 전 413, 공개 포트는 404, 응답 `Cache-Control: no-store`.
 * 통과 판정은 "진입 단계 거절이 아니라 컨트롤러·MVC가 답함"([passedChain]). handler mapping 전수: 등록된 `/internal/v1/admin/` 아래 handler 모두(S3 endpoint 5개 포함) +
 * 명세 §4.3의 5개 경로가 무서명이면 401이다.
 */
@PaymentApiTest
class AdminChannelSecurityChainSpec(
    env: Environment,
    private val internal: InternalConnector,
    private val registry: MeterRegistry,
    private val mappings: List<RequestMappingHandlerMapping>,
) : BehaviorSpec({

    val publicPort = env.getRequiredProperty("local.server.port").toInt()
    val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    data class Response(val status: Int, val body: String, val headers: Map<String, List<String>>) {
        fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.joinToString(",")
    }

    fun send(method: String, pathAndQuery: String, body: ByteArray = ByteArray(0), signature: String? = null, port: Int = internal.localPort): Response {
        val b = HttpRequest.newBuilder(URI.create("http://localhost:$port$pathAndQuery")).timeout(Duration.ofSeconds(30))
            .method(method, if (body.isEmpty()) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofByteArray(body))
            .header("Content-Type", "application/json")
        signature?.let { b.header(AdminCallSignature.HEADER, it) }
        val r = http.send(b.build(), HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        return Response(r.statusCode(), r.body(), r.headers().map())
    }

    fun signed(method: String, path: String, body: ByteArray = ByteArray(0), kid: String = AdminChannelFixture.K1, t: Long = Instant.now().epochSecond) =
        send(method, path, body, AdminChannelFixture.header(method, path, body, kid, t))

    /**
     * 체인 통과(S3 갱신 — I-47): 서명 필터·권한을 지나 컨트롤러·MVC가 답했다 = 진입 단계 거절(401 `UNAUTHENTICATED`·400 `QUERY_NOT_ALLOWED`·413 `REQUEST_TOO_LARGE`)이 아니다.
     * S3 endpoint는 시험 본문에 업무 판정(404 `PAYMENT_NOT_FOUND`·400 `REQUEST_MALFORMED`·200 `NONE` 등)을 돌려준다.
     */
    fun passedChain(r: Response) = withClue("status ${r.status} body ${r.body}") {
        (r.status != 401 && r.status != 413 && "QUERY_NOT_ALLOWED" !in r.body && "UNAUTHENTICATED" !in r.body) shouldBe true
    }

    fun auth(result: String, reason: String) =
        registry.find(AdminChannelAuthenticationFilter.AUTH_METRIC).tags("result", result, "reason", reason).counter()?.count() ?: 0.0

    val pay = "pay_01JC0000000000000000000001"
    val apr = "apr_01JC0000000000000000000001"
    val plannedPaths = listOf(
        "GET" to "/internal/v1/admin/payments/$pay/operator-cancel-target",
        "POST" to "/internal/v1/admin/operator-cancellations",
        "GET" to "/internal/v1/admin/operator-cancellations/$apr",
        "POST" to "/internal/v1/admin/operator-cancellations/$apr/deadline-confirmation",
        "GET" to "/internal/v1/admin/operator-decisions/after/0",
    )

    Given("every admin-channel route without a signature") {
        Then("handler mappings under /internal/v1/admin/ (all of them) and the five planned paths are 401 UNAUTHENTICATED with no-store") {
            val mapped = mappings.flatMap { m -> m.handlerMethods.keys }.flatMap { info ->
                val methods = info.methodsCondition.methods.map { it.name }.ifEmpty { listOf("GET", "POST") }
                info.patternValues.filter { it.startsWith("/internal/v1/admin/") }.flatMap { p ->
                    methods.map { it to p.replace(Regex("\\{[^}]+}"), "x") }
                }
            }
            (mapped + plannedPaths + listOf("GET" to "/internal/v1/admin", "DELETE" to "/internal/v1/admin/anything")).forEach { (method, path) ->
                val r = send(method, path)
                r.status shouldBe 401
                r.body shouldBe """{"error":"UNAUTHENTICATED"}"""
                r.header("Cache-Control")!! shouldContain "no-store"
                r.header("PayCore-Request-Id") shouldNotBe null
                r.header("WWW-Authenticate") shouldBe null
            }
        }
    }

    Given("a correctly signed request") {
        Then("it passes the chain (no business endpoint yet in S2: MVC answers 404, not 401) for GET and POST, with either rotating key") {
            val before = auth("ACCEPTED", "NONE")
            plannedPaths.forEach { (method, path) ->
                val body = if (method == "POST") """{"approvalId":"$apr"}""".toByteArray() else ByteArray(0)
                passedChain(signed(method, path, body))
            }
            passedChain(signed("POST", plannedPaths[1].second, "{}".toByteArray(), kid = AdminChannelFixture.K2))
            auth("ACCEPTED", "NONE") shouldBe before + plannedPaths.size + 1
        }
        Then("a body of exactly 16 KiB passes") {
            passedChain(signed("POST", "/internal/v1/admin/operator-cancellations", ByteArray(16 * 1024) { 'a'.code.toByte() }))
        }
    }

    Given("rejections over real HTTP") {
        val path = "/internal/v1/admin/operator-cancellations"
        val body = """{"approvalId":"$apr"}""".toByteArray()
        Then("a stale t is 401 and counted as STALE; the log has the reason class only") {
            val before = auth("REJECTED", "STALE")
            CapturedLogs().use { logs ->
                val header = AdminChannelFixture.header("POST", path, body, t = Instant.now().epochSecond - 400)
                val r = send("POST", path, body, header)
                r.status shouldBe 401
                val line = logs.lines().single { "operation=AdminChannelAuthenticate" in it && "correlationId=${r.header("PayCore-Request-Id")}" in it }
                line shouldContain "reason=STALE"
                line shouldNotContain header.substringAfter("v1=")
            }
            auth("REJECTED", "STALE") shouldBe before + 1
        }
        Then("the execute POST signature replayed on the deadline-confirmation path (same body) is 401 SIGNATURE, and the reverse too") {
            val deadline = "$path/$apr/deadline-confirmation"
            val now = Instant.now().epochSecond
            send("POST", deadline, body, AdminChannelFixture.header("POST", path, body, t = now)).status shouldBe 401
            send("POST", path, body, AdminChannelFixture.header("POST", deadline, body, t = now)).status shouldBe 401
        }
        Then("a query string is 400 QUERY_NOT_ALLOWED before the signature, even when the path is signed") {
            val target = plannedPaths[0].second
            val r = send("GET", "$target?watermark=0", signature = AdminChannelFixture.header("GET", target, ByteArray(0)))
            r.status shouldBe 400
            r.body shouldBe """{"error":"QUERY_NOT_ALLOWED"}"""
        }
        Then("a body over 16 KiB is 413 before the signature") {
            val big = ByteArray(16 * 1024 + 1) { 'a'.code.toByte() }
            val r = signed("POST", path, big)
            r.status shouldBe 413
            r.body shouldBe """{"error":"REQUEST_TOO_LARGE"}"""
        }
        Then("the public port answers 404 for the admin channel even with a valid signature (ApiEntryFilter before security)") {
            val target = plannedPaths[0].second
            send("GET", target, signature = AdminChannelFixture.header("GET", target, ByteArray(0)), port = publicPort).status shouldBe 404
        }
        Then("no rejection response echoes the signature header") {
            val header = AdminChannelFixture.header("POST", path, body, t = Instant.now().epochSecond - 400)
            send("POST", path, body, header).headers.values.flatten().filter { header.substringAfter("v1=") in it }.shouldBeEmpty()
        }
    }
})

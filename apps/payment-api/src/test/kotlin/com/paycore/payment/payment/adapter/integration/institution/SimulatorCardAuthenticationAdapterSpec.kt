package com.paycore.payment.payment.adapter.integration.institution

import com.paycore.payment.payment.adapter.integration.institution.AuthenticationSettingsFixture.connection
import com.paycore.payment.payment.adapter.integration.institution.AuthenticationSettingsFixture.settings
import com.paycore.payment.payment.application.required.CardAuthenticationRegistration
import com.paycore.payment.payment.application.required.RegistrationFailure
import com.paycore.payment.payment.application.required.RegistrationResult
import com.paycore.payment.payment.domain.AuthenticatedDetails
import com.paycore.payment.payment.domain.AuthenticationBinding
import com.paycore.payment.payment.domain.CurrencyCode
import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.LookupFailure
import com.paycore.payment.payment.domain.MerchantAccountId
import com.paycore.payment.payment.domain.MerchantOrderId
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.OrderKey
import com.paycore.payment.payment.domain.PaymentId
import com.paycore.payment.payment.domain.PaymentPlan
import com.paycore.payment.payment.domain.ProviderAuthenticationResult
import com.paycore.payment.payment.domain.ProviderBindingEcho
import com.paycore.payment.payment.domain.ProviderOutcome
import com.paycore.payment.payment.domain.ProviderResultLookup
import com.paycore.payment.payment.domain.SecretDigest
import com.paycore.payment.payment.domain.SecretKind
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.equals.shouldBeEqual
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.springframework.core.io.ClassPathResource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Simulator HTTP adapter(AUTHN-001 §13.5)를 JDK `HttpServer` stub(시험 전용)으로 시험한다: 공유 golden(`authentication-contract-v1.golden.json`, harness 독립 생성 —
 * Simulator와 같은 파일)의 등록·조회 본문이 Port 결과가 되고, 계약 위반 벡터(m-E)·HTTP 404/5xx·read timeout·연결 실패는 결과 타입(업무 예외 아님)으로 나뉜다.
 */
class SimulatorCardAuthenticationAdapterSpec : FunSpec({

    val mapper = JsonMapper.builder().build()
    val golden = mapper.readTree(ClassPathResource("contracts/authentication-contract-v1.golden.json").inputStream)

    /** path → (status, body, delayMillis). */
    val routes = ConcurrentHashMap<String, Triple<Int, String, Long>>()
    val received = ConcurrentHashMap<String, Pair<String?, String>>()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { s ->
        s.createContext("/") { ex: HttpExchange ->
            val path = ex.requestURI.rawPath
            received[path] = ex.requestHeaders.getFirst("Authorization") to ex.requestBody.readAllBytes().toString(Charsets.UTF_8)
            val (status, body, delay) = routes[path] ?: Triple(404, "{\"code\":\"NOT_FOUND\"}", 0L)
            if (delay > 0) Thread.sleep(delay)
            val bytes = body.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(status, bytes.size.toLong().takeIf { it > 0 } ?: -1)
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) }
            ex.close()
        }
        // 요청마다 스레드: read timeout 시험(2초 지연 route)의 handler가 끝나기 전에 다음 시험의 요청이 오면 기본(단일 dispatcher) 실행기에서는 그 요청이
        // 뒤에 줄을 서 TIMEOUT으로 바뀐다(전체 check 부하에서 관찰된 시험 결함 — 기대값은 그대로).
        s.executor = java.util.concurrent.Executors.newCachedThreadPool()
        s.start()
    }
    afterSpec { server.stop(0) }

    val meters = io.micrometer.core.instrument.simple.SimpleMeterRegistry()
    val adapter = SimulatorCardAuthenticationAdapter(registry = meters, settings = settings(connection = connection(baseUrl = "http://127.0.0.1:${server.address.port}", readTimeout = Duration.ofMillis(800))))

    fun scenario(name: String): JsonNode = golden.get("scenarios").get(name)

    fun registration(s: JsonNode): CardAuthenticationRegistration {
        val r = s.get("registerRequest")
        val binding = AuthenticationBinding(
            PaymentId(r.get("paymentId").stringValue()),
            OrderKey(Environment.TEST, MerchantAccountId(r.get("merchantRef").stringValue()), MerchantOrderId("order-demo")),
            Money(r.get("amountMinor").longValue(), CurrencyCode(r.get("currency").stringValue())),
            PaymentPlan.LumpSum,
            r.get("cardCompany").stringValue(),
        )
        return CardAuthenticationRegistration(binding, r.get("state").stringValue(), r.get("returnUrl").stringValue())
    }

    test("registration sends the golden request with the client Bearer key and maps the golden response to Registered") {
        val s = scenario("authenticated")
        routes["/sim/v1/authentications"] = Triple(201, s.get("registerResponse").toString(), 0)
        adapter.register(registration(s)) shouldBe RegistrationResult.Registered(
            s.get("registerResponse").get("authenticationTransactionId").stringValue(), s.get("registerResponse").get("customerUrl").stringValue(),
        )
        val (auth, body) = received.getValue("/sim/v1/authentications")
        auth shouldBe "Bearer ${AuthenticationSettingsFixture.CLIENT_KEY}"
        mapper.readTree(body) shouldBe s.get("registerRequest")
    }

    test("registration failures: 4xx → REJECTED, 5xx → SERVER_ERROR, a customer URL outside the configured origin or extra fields → MALFORMED") {
        val s = scenario("authenticated")
        val response = s.get("registerResponse") as tools.jackson.databind.node.ObjectNode
        mapOf(
            Triple(409, "{\"code\":\"STATE_REUSED_WITH_DIFFERENT_REQUEST\"}", 0L) to RegistrationResult.Failed(RegistrationFailure.REJECTED),
            Triple(503, "", 0L) to RegistrationResult.Failed(RegistrationFailure.SERVER_ERROR),
            Triple(201, response.deepCopy().put("customerUrl", "https://evil.example.test/sim/authenticate/x").toString(), 0L) to
                RegistrationResult.Failed(RegistrationFailure.MALFORMED),
            Triple(201, response.deepCopy().put("extra", true).toString(), 0L) to RegistrationResult.Failed(RegistrationFailure.MALFORMED),
            Triple(201, "not json", 0L) to RegistrationResult.Failed(RegistrationFailure.MALFORMED),
        ).forEach { (route, expected) ->
            withClue(route) {
                routes["/sim/v1/authentications"] = route
                adapter.register(registration(s)) shouldBe expected
            }
        }
    }

    test("the golden AUTHENTICATED lookup becomes Found with the echoed binding, the state digest and the evidence details") {
        val s = scenario("authenticated")
        val lookup = s.get("lookup")
        val ref = lookup.get("resultRef").stringValue()
        routes["/sim/v1/authentication-results/$ref"] = Triple(200, lookup.toString(), 0)
        val req = s.get("registerRequest")
        adapter.lookup(ref) shouldBeEqual ProviderResultLookup.Found(
            ProviderAuthenticationResult(
                providerTransactionRef = lookup.get("authenticationTransactionId").stringValue(),
                outcome = ProviderOutcome.AUTHENTICATED,
                stateDigest = SecretDigest.of(SecretKind.AUTHENTICATION_STATE, req.get("state").stringValue()),
                binding = ProviderBindingEcho(
                    PaymentId(req.get("paymentId").stringValue()), Environment.TEST, MerchantAccountId(req.get("merchantRef").stringValue()),
                    Money(10_000, CurrencyCode("KRW")), PaymentPlan.LumpSum, "SIMISSA",
                ),
                authenticated = AuthenticatedDetails("CREDIT", "SIMISSA", "SIMACQA", "SIMAUTH1", "sim-ev-A", Instant.parse(golden.get("completedAt").stringValue())),
                validUntil = Instant.parse(lookup.get("validUntil").stringValue()),
            ),
        )
    }

    test("the golden FAILED, CANCELLED and EXPIRED lookups become Found without evidence details") {
        mapOf("failed" to ProviderOutcome.FAILED, "cancelled" to ProviderOutcome.CANCELLED, "expired" to ProviderOutcome.EXPIRED).forEach { (name, outcome) ->
            withClue(name) {
                val lookup = scenario(name).get("lookup")
                val ref = lookup.get("resultRef").stringValue()
                routes["/sim/v1/authentication-results/$ref"] = Triple(200, lookup.toString(), 0)
                val found = adapter.lookup(ref) as ProviderResultLookup.Found
                listOf(found.result.outcome, found.result.authenticated) shouldBe listOf(outcome, null)
            }
        }
    }

    test("every golden contract-violation vector (m-E) is Unverifiable(MALFORMED), never a result") {
        val ref = scenario("authenticated").get("lookup").get("resultRef").stringValue()
        golden.get("malformedLookups").properties().forEach { (name, body) ->
            withClue(name) {
                // 각 벡터는 자기 resultRef로 요청한다 — 위반이 resultRef 불일치에 가려지지 않게(그 필드 하나만 계약 위반).
                val vectorRef = body.get("resultRef").stringValue()
                routes["/sim/v1/authentication-results/$vectorRef"] = Triple(200, body.toString(), 0)
                adapter.lookup(vectorRef) shouldBe ProviderResultLookup.Unverifiable(LookupFailure.MALFORMED)
            }
        }
        routes["/sim/v1/authentication-results/$ref"] = Triple(200, scenario("failed").get("lookup").toString(), 0)
        adapter.lookup(ref) shouldBe ProviderResultLookup.Unverifiable(LookupFailure.MALFORMED) // 응답의 resultRef가 요청과 다르다
    }

    test("HTTP 404 → NOT_FOUND, 5xx → SERVER_ERROR, slower than the read timeout → TIMEOUT, refused connection → CONNECTION, malformed reference → MALFORMED (no request)") {
        val ref = "srr_" + "Q".repeat(22)
        routes["/sim/v1/authentication-results/$ref"] = Triple(404, "{\"code\":\"NOT_FOUND\"}", 0)
        adapter.lookup(ref) shouldBe ProviderResultLookup.Unverifiable(LookupFailure.NOT_FOUND)
        routes["/sim/v1/authentication-results/$ref"] = Triple(503, "{\"code\":\"TEMPORARILY_UNAVAILABLE\"}", 0)
        adapter.lookup(ref) shouldBe ProviderResultLookup.Unverifiable(LookupFailure.SERVER_ERROR)
        routes["/sim/v1/authentication-results/$ref"] = Triple(200, scenario("authenticated").get("lookup").toString(), 2_000)
        adapter.lookup(ref) shouldBe ProviderResultLookup.Unverifiable(LookupFailure.TIMEOUT)
        val closedPort = ServerSocket(0).use { it.localPort }
        SimulatorCardAuthenticationAdapter(registry = meters, settings = settings(connection = connection(baseUrl = "http://127.0.0.1:$closedPort"))).lookup(ref) shouldBe
            ProviderResultLookup.Unverifiable(LookupFailure.CONNECTION)
        received.clear()
        adapter.lookup("../../etc/passwd") shouldBe ProviderResultLookup.Unverifiable(LookupFailure.MALFORMED)
        received.isEmpty() shouldBe true
    }

    test("review #2 n-5: an oversized body is cut at the cap (MALFORMED at once — the adapter does not wait for or buffer the rest)") {
        // 한도(16 KiB)를 넘는 본문을 먼저 보내고 끝을 늦게 닫는 stub: 전부 읽는 구현은 늦은 끝(3초)을 기다리거나 read timeout이 된다.
        val slow = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { s ->
            s.createContext("/") { ex: HttpExchange ->
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(if (ex.requestMethod == "POST") 201 else 200, 0)
                runCatching {
                    ex.responseBody.write(ByteArray(64 * 1024) { ' '.code.toByte() })
                    ex.responseBody.flush()
                    Thread.sleep(3_000)
                }
                runCatching { ex.close() }
            }
            // 요청마다 스레드(앞 요청의 늦은 끝이 다음 요청을 막아 측정이 섞이지 않게).
            s.executor = java.util.concurrent.Executors.newCachedThreadPool()
            s.start()
        }
        try {
            val capped = SimulatorCardAuthenticationAdapter(registry = meters, settings = settings(connection = connection(baseUrl = "http://127.0.0.1:${slow.address.port}", readTimeout = Duration.ofSeconds(10))))
            val started = System.nanoTime()
            capped.lookup("srr_" + "Q".repeat(22)) shouldBe ProviderResultLookup.Unverifiable(LookupFailure.MALFORMED)
            capped.register(registration(scenario("authenticated"))) shouldBe RegistrationResult.Failed(RegistrationFailure.MALFORMED)
            Duration.ofNanos(System.nanoTime() - started).toMillis() shouldBeLessThan 2_500L
        } finally {
            slow.stop(0)
        }
    }

    test("observability (§15): every lookup is timed and counted by outcome, every registration by outcome — labels carry no identifiers") {
        val ref = "srr_" + "M".repeat(22)
        routes["/sim/v1/authentication-results/$ref"] = Triple(503, "{}", 0)
        val before = meters.find("paycore.authentication.lookups").tag("outcome", "SERVER_ERROR").counter()?.count() ?: 0.0
        val timed = meters.find("paycore.authentication.lookup.duration").timer()?.count() ?: 0L
        adapter.lookup(ref)
        meters.find("paycore.authentication.lookups").tag("outcome", "SERVER_ERROR").counter()!!.count() shouldBe before + 1.0
        meters.find("paycore.authentication.lookup.duration").timer()!!.count() shouldBe timed + 1
        val s = scenario("authenticated")
        routes["/sim/v1/authentications"] = Triple(201, s.get("registerResponse").toString(), 0)
        adapter.register(registration(s))
        meters.find("paycore.authentication.registrations").tag("outcome", "REGISTERED").counter()!!.count() shouldBeGreaterThanOrEqual 1.0
        meters.meters.filter { it.id.name.startsWith("paycore.authentication") }.flatMap { it.id.tags.map { t -> t.key } }.toSet() shouldBe setOf("outcome")
    }

    test("review #2 n-5: a peer that does not speak HTTP never makes the Port throw (result types only)") {
        val garbage = ServerSocket(0)
        val worker = Thread {
            repeat(2) { runCatching { garbage.accept().use { c -> c.getOutputStream().write("HELLO\r\n\r\n".toByteArray()); c.getOutputStream().flush() } } }
        }.also { it.isDaemon = true; it.start() }
        try {
            val broken = SimulatorCardAuthenticationAdapter(registry = meters, settings = settings(connection = connection(baseUrl = "http://127.0.0.1:${garbage.localPort}")))
            (broken.lookup("srr_" + "Q".repeat(22)) as ProviderResultLookup.Unverifiable).reason shouldBeIn
                listOf(LookupFailure.CONNECTION, LookupFailure.SERVER_ERROR, LookupFailure.MALFORMED)
            (broken.register(registration(scenario("authenticated"))) as RegistrationResult.Failed).reason shouldBeIn
                listOf(RegistrationFailure.CONNECTION, RegistrationFailure.SERVER_ERROR, RegistrationFailure.MALFORMED)
        } finally {
            garbage.close()
            worker.join(2_000)
        }
    }
})

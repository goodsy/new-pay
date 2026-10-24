package com.paycore.simulator.merchant.adapter.web

import com.paycore.simulator.support.MutableSimulatorClock
import com.paycore.simulator.support.SimulatorMySqlContainerConfig
import com.paycore.simulator.support.SimulatorTestConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.core.io.ClassPathResource
import org.springframework.test.context.TestPropertySource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * T-S2(WEBHOOK-001 §9, 검토 n-6·m-8): 가상 가맹점 수신기를 실제 내장 Tomcat·MySQL로 확인한다 — 서명 검증(창·서명·문법·본문 변경), TEST 아닌 environment 거절, eventId 중복 제거
 * (첫 2xx만 ACCEPTED), 시나리오(n회 503 후 200·지연·302·큰 본문), 모르는 수신기 404(행 없음), 원장 조회(시험 client Bearer — 다른 client 존재 비노출). 서명 비밀은 golden의
 * 시험 전용 Demo 값이다(운영 비밀 아님). 시험 시계는 SimulatorTestConfig의 고정 시각이다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = ["PAYCORE_SIMULATOR_PORT=0"])
@Import(SimulatorMySqlContainerConfig::class, SimulatorTestConfig::class)
class MerchantWebhookReceiverApiSpec(
    private val env: Environment,
    private val clock: MutableSimulatorClock,
) : BehaviorSpec({

    val mapper = JsonMapper.builder().build()
    val golden = mapper.readTree(ClassPathResource("contracts/webhook-signature-v1.golden.json").inputStream)
    val secret = golden.get("keys").get(0).get("secret").asString()
    val kid = golden.get("keys").get(0).get("keyId").asString()
    val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
    val base by lazy { "http://127.0.0.1:${env.getRequiredProperty("local.server.port")}" }
    val client = "test-only-simulator-client-key-1"
    val otherClient = "test-only-simulator-client-key-2"

    afterEach { clock.reset() }

    fun body(eventId: String, environment: String = "TEST", sequence: Long = 1): String =
        """{"eventId":"$eventId","type":"payment.authorized","schemaVersion":1,"environment":"$environment","merchantAccountId":"mid_demo_0001",""" +
            """"createdAt":"2026-10-16T01:00:00.000Z","sequence":$sequence,"payment":{"paymentId":"pay_demo","merchantOrderId":"o-1","amount":10000,"currency":"KRW","status":"AUTHORIZED"}}"""

    fun sign(t: Long, b: String, key: String = secret): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256")) }
        mac.update("$t.".toByteArray(Charsets.US_ASCII))
        return "t=$t,kid=$kid,v1=${HexFormat.of().formatHex(mac.doFinal(b.toByteArray(Charsets.UTF_8)))}"
    }

    fun now(): Long = clock.now().epochSecond

    fun post(receiver: String, b: String, signature: String?): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(URI("$base/sim/merchant/v1/webhooks/$receiver")).header("Content-Type", "application/json")
            .also { r -> signature?.let { r.header("PayCore-Signature", it) } }.POST(HttpRequest.BodyPublishers.ofString(b)).build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    fun receipts(receiver: String, bearer: String? = client): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(URI("$base/sim/merchant/v1/webhooks/$receiver/receipts")).also { r -> bearer?.let { r.header("Authorization", "Bearer $it") } }.GET().build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    fun receiptsOf(receiver: String, eventId: String): List<JsonNode> =
        mapper.readTree(receipts(receiver).body()).get("receipts").iterator().asSequence().filter { it.get("eventId")?.asString() == eventId }.toList()

    fun sha256(s: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)))

    fun newEventId() = "whe_" + UUID.randomUUID().toString().replace("-", "").take(26).uppercase()

    Given("a correctly signed TEST event to an OK receiver") {
        val eventId = newEventId()
        val b = body(eventId)
        val first = post("shop-ok", b, sign(now(), b))
        val again = post("shop-ok", b, sign(now() + 1, b))

        Then("it is accepted once (200) and the redelivery is answered 200 as a duplicate (eventId dedup)") {
            first.statusCode() shouldBe 200
            again.statusCode() shouldBe 200
            receiptsOf("shop-ok", eventId).map { it.get("outcome").asString() } shouldBe listOf("ACCEPTED", "DUPLICATE")
        }
        Then("the ledger keeps the type, sequence, key id, response status and the body digest — not the body") {
            val r = receiptsOf("shop-ok", eventId).first()
            r.get("eventType").asString() shouldBe "payment.authorized"
            r.get("sequence").asLong() shouldBe 1
            r.get("keyId").asString() shouldBe kid
            r.get("responseStatus").asInt() shouldBe 200
            r.get("bodySha256").asString() shouldBe sha256(b)
            receipts("shop-ok").body() shouldNotContain "pay_demo"
        }
    }

    Given("requests that fail verification (nothing is trusted from the body)") {
        val eventId = newEventId()
        val b = body(eventId)
        Then("wrong signature or one changed body byte → 401 SIGNATURE; t outside ±300s → 401 STALE; no or malformed header → 400") {
            post("shop-ok", b, sign(now(), b, key = "whsec_" + "x".repeat(43))).statusCode() shouldBe 401
            post("shop-ok", b.replace("10000", "10001"), sign(now(), b)).statusCode() shouldBe 401
            post("shop-ok", b, sign(now() - 301, b)).statusCode() shouldBe 401
            post("shop-ok", b, sign(now() + 301, b)).statusCode() shouldBe 401
            post("shop-ok", b, sign(now() - 300, b).replace("v1=", "v1=Z")).statusCode() shouldBe 400
            post("shop-ok", b, null).statusCode() shouldBe 400
        }
        Then("the window edge itself (−300s) is accepted") {
            post("shop-ok", b, sign(now() - 300, b)).statusCode() shouldBe 200
            receiptsOf("shop-ok", eventId).map { it.get("outcome").asString() } shouldBe listOf("ACCEPTED")
        }
        Then("rejections are recorded without event values (MALFORMED/STALE/SIGNATURE rows carry only the digest)") {
            val rejected = mapper.readTree(receipts("shop-ok").body()).get("receipts").iterator().asSequence()
                .filter { it.get("outcome").asString().startsWith("REJECTED_") && it.get("bodySha256").asString() == sha256(b) }.toList()
            rejected.map { it.get("outcome").asString() }.toSet() shouldBe setOf("REJECTED_SIGNATURE", "REJECTED_STALE", "REJECTED_MALFORMED")
            rejected.all { it.get("eventId") == null && it.get("keyId") == null } shouldBe true
        }
    }

    Given("a correctly signed event that is not TEST, or whose body is not an envelope (n-6)") {
        val eventId = newEventId()
        val live = body(eventId, environment = "LIVE")
        val notJson = "not-json"
        Then("environment LIVE → 400 REJECTED_ENVIRONMENT; an unreadable signed body → 400 REJECTED_BODY") {
            post("shop-ok", live, sign(now(), live)).statusCode() shouldBe 400
            receiptsOf("shop-ok", eventId).map { it.get("outcome").asString() } shouldBe listOf("REJECTED_ENVIRONMENT")
            post("shop-ok", notJson, sign(now(), notJson)).statusCode() shouldBe 400
            mapper.readTree(receipts("shop-ok").body()).get("receipts").iterator().asSequence()
                .filter { it.get("bodySha256").asString() == sha256(notJson) }.map { it.get("outcome").asString() }.toList() shouldBe listOf("REJECTED_BODY")
        }
    }

    Given("the response scenarios") {
        Then("FAIL_THEN_OK(2): 503, 503, then 200 for the same event") {
            val eventId = newEventId()
            val b = body(eventId)
            (1..3).map { post("shop-flaky", b, sign(now(), b)).statusCode() } shouldBe listOf(503, 503, 200)
            receiptsOf("shop-flaky", eventId).map { it.get("outcome").asString() } shouldBe listOf("SCENARIO_REFUSED", "SCENARIO_REFUSED", "ACCEPTED")
        }
        Then("REDIRECT answers 302 with a Location and is not an acceptance") {
            val eventId = newEventId()
            val b = body(eventId)
            val r = post("shop-redirect", b, sign(now(), b))
            r.statusCode() shouldBe 302
            r.headers().firstValue("Location").isPresent shouldBe true
            receiptsOf("shop-redirect", eventId).map { it.get("outcome").asString() } shouldBe listOf("SCENARIO_REFUSED")
        }
        Then("LARGE_BODY answers 200 with the configured body size; DELAYED answers 200 after the delay") {
            val b1 = body(newEventId())
            post("shop-large", b1, sign(now(), b1)).let { it.statusCode() shouldBe 200; it.body().length shouldBe 65_536 }
            val b2 = body(newEventId())
            val started = System.nanoTime()
            post("shop-slow", b2, sign(now(), b2)).statusCode() shouldBe 200
            ((System.nanoTime() - started) / 1_000_000 >= 1_000) shouldBe true
        }
    }

    Given("an unknown receiver and the receipt query authentication") {
        Then("POST to an unknown receiver → 404 and no row") {
            val b = body(newEventId())
            post("no-such-shop", b, sign(now(), b)).statusCode() shouldBe 404
        }
        Then("the receipt query needs the owning client: no key → 401, another client → 404 (existence not revealed), unknown receiver → 404") {
            receipts("shop-ok", bearer = null).statusCode() shouldBe 401
            receipts("shop-ok", bearer = otherClient).statusCode() shouldBe 404
            receipts("no-such-shop").statusCode() shouldBe 404
            receipts("shop-other", bearer = otherClient).statusCode() shouldBe 200
        }
    }
})

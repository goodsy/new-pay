package com.paycore.financial.paymentfact.adapter.web

import com.paycore.financial.paymentfact.domain.PaymentFactFixture
import com.paycore.testsupport.MySqlTestContainerConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 결제 사실 Inbox HTTP 시험(실제 Tomcat 포트 — FIN-FACT-RELAY-001 §7 FR-H1..H8). 서명·봉투·크기·경로 거절이 저장 전에 일어나고, 200 ACK는 내구 커밋 뒤에만 오며
 * 모양이 golden ACK 계약과 같은지 본다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(MySqlTestContainerConfig::class)
class PaymentFactInboxHttpSpec(
    @LocalServerPort private val port: Int,
    private val jdbc: JdbcTemplate,
    private val registry: MeterRegistry,
) : BehaviorSpec({

    val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).proxy(HttpClient.Builder.NO_PROXY).connectTimeout(Duration.ofSeconds(5)).build()
    val mapper = JsonMapper.builder().build()
    val url = URI.create("http://127.0.0.1:$port${PaymentFactSignature.PATH}")

    fun post(body: ByteArray, signature: String? = PaymentFactEnvelopeFixture.signature(body), contentType: String = "application/json"): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(10)).header("Content-Type", contentType).POST(HttpRequest.BodyPublishers.ofByteArray(body))
        signature?.let { builder.header(PaymentFactSignature.HEADER, it) }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    fun json(r: HttpResponse<String>): JsonNode = mapper.readTree(r.body())
    fun storedEvents(eventId: String): Int = jdbc.queryForObject("SELECT COUNT(*) FROM payment_fact_inbox WHERE event_id = ?", Int::class.java, eventId)!!
    fun counter(result: String): Double = registry.find(PaymentFactInboxController.METRIC).tag("result", result).counter()?.count() ?: 0.0

    Given("FR-H1 a signed authorization envelope") {
        val auth = PaymentFactFixture.authorized()
        val eventId = UUID.randomUUID().toString()
        val body = PaymentFactEnvelopeFixture.body(auth, eventId)
        val first = post(body)
        val again = post(body)
        Then("200 ACK after commit with exactly the golden ACK fields; a re-delivery is a DUPLICATE with the original outcome") {
            first.statusCode() shouldBe 200
            json(first).properties().map { it.key } shouldBe listOf("consumer", "eventId", "effectKey", "outcome")
            json(first).get("consumer").asString() shouldBe "financial-batch"
            json(first).get("eventId").asString() shouldBe eventId
            json(first).get("effectKey").asString() shouldBe auth.effectKey
            json(first).get("outcome").asString() shouldBe "APPLIED"
            again.statusCode() shouldBe 200
            json(again).get("outcome").asString() shouldBe "DUPLICATE"
            json(again).get("originalOutcome").asString() shouldBe "APPLIED"
            storedEvents(eventId) shouldBe 1
            (counter("applied") >= 1.0) shouldBe true
            (counter("duplicate") >= 1.0) shouldBe true
        }
    }

    Given("FR-H2 a cancel whose original is unknown") {
        val (_, cancel) = PaymentFactFixture.cancelWithoutOriginal()
        val r = post(PaymentFactEnvelopeFixture.body(cancel))
        Then("200 ACK with outcome HELD") {
            r.statusCode() shouldBe 200
            json(r).get("outcome").asString() shouldBe "HELD"
        }
    }

    Given("FR-H3 signature failures (all answered 401 UNAUTHENTICATED, nothing stored)") {
        val eventId = UUID.randomUUID().toString()
        val body = PaymentFactEnvelopeFixture.body(PaymentFactFixture.authorized(), eventId)
        val wrongKey = ByteArray(32) { 7 }
        // 서명은 보내는 순간의 벽시계로 만든다. 300/301초 경계 자체는 고정 시각의 golden 판정(PaymentFactRelayGoldenSpec)이 검사하고, 여기서는
        // 벽시계 경계 경합(앞 요청 소요·초 절삭으로 301초가 300초로 읽힘)을 피하려고 창 밖으로 충분히(+60초) 벗어난 값을 쓴다.
        val cases: Map<String, () -> String?> = mapOf(
            "missing" to { null },
            "stale" to { PaymentFactEnvelopeFixture.signature(body, t = Instant.now().epochSecond - 360) },
            "future" to { PaymentFactEnvelopeFixture.signature(body, t = Instant.now().epochSecond + 360) },
            "unknown kid" to { PaymentFactEnvelopeFixture.signature(body, kid = "fact-unknown") },
            "wrong key" to { PaymentFactEnvelopeFixture.signature(body, key = wrongKey) },
            "malformed" to { "t=${Instant.now().epochSecond},kid=fact-golden-k1,v1=xyz" },
        )
        Then("each is 401 with the same error and no Inbox row") {
            cases.forEach { (name, signatureAtSend) ->
                val r = post(body, signatureAtSend())
                io.kotest.assertions.withClue(name) {
                    r.statusCode() shouldBe 401
                    json(r).get("error").asString() shouldBe "UNAUTHENTICATED"
                }
            }
            storedEvents(eventId) shouldBe 0
        }
        And("the second (rotation) key is accepted") {
            val second = java.util.Base64.getDecoder().decode("dGVzdC1vbmx5LXBheW1lbnQtZmFjdC1nb2xkZW4ta2V5LTAwMDI=")
            post(body, PaymentFactEnvelopeFixture.signature(body, kid = "fact-golden-k2", key = second)).statusCode() shouldBe 200
        }
    }

    Given("FR-H4 envelope and content violations (signed)") {
        val auth = PaymentFactFixture.authorized()
        Then("not JSON / duplicate keys / wrong category are 400 ENVELOPE_INVALID") {
            listOf(
                "not json".toByteArray(),
                """{"envelopeVersion":1,"envelopeVersion":1}""".toByteArray(),
                PaymentFactEnvelopeFixture.body(auth) { it.put("eventCategory", "INTEGRATION_NON_FINANCIAL") },
                PaymentFactEnvelopeFixture.body(auth) { it.put("eventId", "NOT-A-UUID") },
            ).forEach { b ->
                val r = post(b)
                r.statusCode() shouldBe 400
                json(r).get("error").asString() shouldBe "ENVELOPE_INVALID"
            }
        }
        Then("effect key not matching the transaction, a zero amount or a foreign aggregate are 422 CONTENT_INVALID and nothing is stored") {
            val eventIds = (1..3).map { UUID.randomUUID().toString() }
            listOf(
                PaymentFactEnvelopeFixture.body(auth, eventIds[0]) { it.put("effectKey", "PaymentAuthorized:ptx_other") },
                PaymentFactEnvelopeFixture.body(auth, eventIds[1]) { (it.get("payload") as tools.jackson.databind.node.ObjectNode).put("amount", 0) },
                PaymentFactEnvelopeFixture.body(auth, eventIds[2]) { it.put("aggregateId", "pau_other") },
            ).forEach { b ->
                val r = post(b)
                r.statusCode() shouldBe 422
                json(r).get("error").asString() shouldBe "CONTENT_INVALID"
            }
            eventIds.forEach { storedEvents(it) shouldBe 0 }
        }
    }

    Given("FR-H5 the same eventId with a different body") {
        val eventId = UUID.randomUUID().toString()
        post(PaymentFactEnvelopeFixture.body(PaymentFactFixture.authorized(), eventId)).statusCode() shouldBe 200
        val r = post(PaymentFactEnvelopeFixture.body(PaymentFactFixture.authorized(), eventId))
        Then("409 EVENT_ID_CONFLICT") {
            r.statusCode() shouldBe 409
            json(r).get("error").asString() shouldBe "EVENT_ID_CONFLICT"
        }
    }

    Given("FR-H6 the entry filter (internal-only surface)") {
        val body = PaymentFactEnvelopeFixture.body(PaymentFactFixture.authorized())
        Then("other paths and other methods on the inbox path are 404; health is reachable without details") {
            client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/internal/v1/other")).GET().build(), HttpResponse.BodyHandlers.ofString())
                .statusCode() shouldBe 404
            client.send(HttpRequest.newBuilder(url).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode() shouldBe 404
            client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/actuator/env")).GET().build(), HttpResponse.BodyHandlers.ofString())
                .statusCode() shouldBe 404
            val health = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/actuator/health")).GET().build(), HttpResponse.BodyHandlers.ofString())
            health.statusCode() shouldBe 200
            val healthJson = mapper.readTree(health.body())
            healthJson.get("status").asString() shouldBe "UP"
            (healthJson.has("components") || healthJson.has("details")) shouldBe false
        }
        Then("a query string is 400, a wrong media type is 415, a body over the limit is 413, a body without Content-Length is 411") {
            client.send(
                HttpRequest.newBuilder(URI.create("$url?x=1")).header("Content-Type", "application/json")
                    .header(PaymentFactSignature.HEADER, PaymentFactEnvelopeFixture.signature(body)).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                HttpResponse.BodyHandlers.ofString(),
            ).statusCode() shouldBe 400
            post(body, contentType = "text/plain").statusCode() shouldBe 415
            val big = ByteArray(65_537) { ' '.code.toByte() }
            post(big).statusCode() shouldBe 413
            client.send(
                HttpRequest.newBuilder(url).header("Content-Type", "application/json").header(PaymentFactSignature.HEADER, PaymentFactEnvelopeFixture.signature(body))
                    .POST(HttpRequest.BodyPublishers.ofInputStream { ByteArrayInputStream(body) }).build(),
                HttpResponse.BodyHandlers.ofString(),
            ).statusCode() shouldBe 411
        }
    }
})

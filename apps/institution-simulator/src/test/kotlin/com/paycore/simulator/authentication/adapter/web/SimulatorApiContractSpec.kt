package com.paycore.simulator.authentication.adapter.web

import com.paycore.simulator.support.MutableSimulatorClock
import com.paycore.simulator.support.ScriptedSimulatorIdGenerator
import com.paycore.simulator.support.SimulatorMySqlContainerConfig
import com.paycore.simulator.support.SimulatorTestConfig
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
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
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.UUID

/**
 * Simulator 계약 v1(AUTHN-001 §13.4, golden `authentication-contract-v1.golden.json` — harness 독립 생성, payment-api와 같은 파일)을 실제 내장 Tomcat·MySQL로
 * 확인한다: 등록 응답·고객 화면 redirect·결과 조회 본문이 golden과 같은 JSON이다(증거 6필드는 AUTHENTICATED에만 — m-E). 더해서 Bearer 인증, (client, state) 멱등,
 * TEST 전용, 반환 URL 고정 문법, 엄격 JSON, 다른 client 존재 비노출, 조회 멱등, "한 번만" 5xx·지연 시나리오.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = ["PAYCORE_SIMULATOR_PORT=0"])
@Import(SimulatorMySqlContainerConfig::class, SimulatorTestConfig::class)
class SimulatorApiContractSpec(
    private val env: Environment,
    private val clock: MutableSimulatorClock,
    private val ids: ScriptedSimulatorIdGenerator,
) : BehaviorSpec({

    val mapper = JsonMapper.builder().build()
    val golden = mapper.readTree(ClassPathResource("contracts/authentication-contract-v1.golden.json").inputStream)
    val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
    val base by lazy { "http://127.0.0.1:${env.getRequiredProperty("local.server.port")}" }
    val key = "test-only-simulator-client-key-1"
    val otherKey = "test-only-simulator-client-key-2"

    afterEach { clock.reset() }

    fun post(path: String, body: String, bearer: String? = key): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI("$base$path")).header("Content-Type", "application/json")
                .also { b -> bearer?.let { b.header("Authorization", "Bearer $it") } }.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    fun get(path: String, bearer: String? = key): HttpResponse<String> =
        http.send(HttpRequest.newBuilder(URI("$base$path")).also { b -> bearer?.let { b.header("Authorization", "Bearer $it") } }.GET().build(), HttpResponse.BodyHandlers.ofString())

    fun form(path: String, fields: Map<String, String>): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI("$base$path")).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(fields.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, Charsets.UTF_8)}" })).build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    fun tree(s: String): JsonNode = mapper.readTree(s)

    /** golden 요청을 바꾼 본문(state는 시험마다 새 값 — 같은 컨텍스트의 다른 시험과 섞이지 않게). */
    fun requestWith(vararg changes: Pair<String, Any?>): String {
        val node = golden.get("scenarios").get("authenticated").get("registerRequest").deepCopy() as tools.jackson.databind.node.ObjectNode
        node.put("state", UUID.randomUUID().toString().replace("-", "").padEnd(43, 'q').take(43))
        changes.forEach { (k, v) ->
            when (v) {
                null -> node.remove(k)
                is String -> node.put(k, v)
                is Long -> node.put(k, v)
                is Boolean -> node.put(k, v)
                else -> error("unsupported")
            }
        }
        return node.toString()
    }

    Given("the golden scenarios") {
        Then("registration, the customer redirect and the lookup body equal the golden contract for AUTHENTICATED, FAILED, CANCELLED and EXPIRED") {
            listOf("authenticated", "failed", "cancelled", "expired").forEach { name ->
                withClue(name) {
                    val s = golden.get("scenarios").get(name)
                    val tag = name.first().uppercaseChar().toString()
                    ids.enqueue("sat_" + "Tx$tag".padEnd(22, '0'), "srr_" + "Rr$tag".padEnd(22, '0'), "sim-ev-$tag")
                    clock.set(Instant.parse(golden.get("registeredAt").stringValue()))
                    val registered = post("/sim/v1/authentications", s.get("registerRequest").toString())
                    registered.statusCode() shouldBe 201
                    tree(registered.body()) shouldBe s.get("registerResponse")
                    val txId = s.get("registerResponse").get("authenticationTransactionId").stringValue()
                    get("/sim/authenticate/$txId", bearer = null).statusCode() shouldBe 200
                    clock.set(Instant.parse(golden.get("completedAt").stringValue()))
                    val action = if (s.get("card").isNull) mapOf("action" to "cancel") else mapOf("action" to "authenticate", "card" to s.get("card").stringValue())
                    val redirect = form("/sim/authenticate/$txId", action)
                    redirect.statusCode() shouldBe 303
                    redirect.headers().firstValue("Location").get() shouldBe s.get("redirect").stringValue()
                    val ref = s.get("lookup").get("resultRef").stringValue()
                    val first = get("/sim/v1/authentication-results/$ref")
                    first.statusCode() shouldBe 200
                    tree(first.body()) shouldBe s.get("lookup")
                    get("/sim/v1/authentication-results/$ref").body() shouldBe first.body() // 읽기 전용·멱등
                }
            }
        }
    }

    Given("registration rules") {
        Then("no or a wrong Bearer key is 401; LIVE, a foreign return URL, an unknown field or a wrong type is 400") {
            post("/sim/v1/authentications", requestWith(), bearer = null).statusCode() shouldBe 401
            post("/sim/v1/authentications", requestWith(), bearer = "wrong-key").statusCode() shouldBe 401
            mapOf(
                "LIVE" to requestWith("environment" to "LIVE"),
                "foreign return URL" to requestWith("returnUrl" to "https://evil.example.test/checkout/s/Hh0123456789abcdefghij/authentication/return"),
                "return URL with query" to requestWith("returnUrl" to "https://checkout.example.test/checkout/s/Hh0123456789abcdefghij/authentication/return?x=1"),
                "unknown field" to requestWith("extra" to true),
                "amount as string" to requestWith("amountMinor" to "10000"),
                "missing field" to requestWith("cardCompany" to null),
                // INST-CODE-001 IC-7(D-INST-CODE-001): 카드사(발급사) 코드는 기관 식별 코드 공통 형식 ^[A-Z0-9]{1,32}$.
                "hyphenated card company" to requestWith("cardCompany" to "SIMISS-A"),
                "underscore card company" to requestWith("cardCompany" to "SIM_ISS_A"),
                "lower-case card company" to requestWith("cardCompany" to "simissa"),
            ).forEach { (name, body) -> withClue(name) { post("/sim/v1/authentications", body).statusCode() shouldBe 400 } }
            tree(post("/sim/v1/authentications", requestWith("environment" to "LIVE")).body()).get("code").stringValue() shouldBe "ENVIRONMENT_NOT_SUPPORTED"
        }
        Then("the same (client, state) with the same content returns the same transaction (200); different content is 409") {
            val body = requestWith()
            val first = post("/sim/v1/authentications", body)
            first.statusCode() shouldBe 201
            val again = post("/sim/v1/authentications", body)
            again.statusCode() shouldBe 200
            tree(again.body()).get("authenticationTransactionId") shouldBe tree(first.body()).get("authenticationTransactionId")
            val changed = (tree(body).deepCopy() as tools.jackson.databind.node.ObjectNode).put("amountMinor", 10_001L).toString()
            post("/sim/v1/authentications", changed).statusCode() shouldBe 409
        }
    }

    Given("lookup rules") {
        fun completed(card: String): String {
            val txId = tree(post("/sim/v1/authentications", requestWith()).body()).get("authenticationTransactionId").stringValue()
            val location = form("/sim/authenticate/$txId", mapOf("action" to "authenticate", "card" to card)).headers().firstValue("Location").get()
            return location.substringAfter("resultRef=").substringBefore('&')
        }

        Then("another client's result and unknown or malformed references are 404 (no existence oracle); no key is 401") {
            val ref = completed("VC-CREDIT-OK")
            get("/sim/v1/authentication-results/$ref", bearer = otherKey).statusCode() shouldBe 404
            get("/sim/v1/authentication-results/srr_" + "Z".repeat(22)).statusCode() shouldBe 404
            get("/sim/v1/authentication-results/not-a-ref").statusCode() shouldBe 404
            get("/sim/v1/authentication-results/$ref", bearer = null).statusCode() shouldBe 401
        }
        Then("VC-LOOKUP-5XX-ONCE answers 503 once, then the result; VC-LOOKUP-TIMEOUT-ONCE delays only the first lookup") {
            val flaky = completed("VC-LOOKUP-5XX-ONCE")
            get("/sim/v1/authentication-results/$flaky").statusCode() shouldBe 503
            get("/sim/v1/authentication-results/$flaky").statusCode() shouldBe 200
            val slow = completed("VC-LOOKUP-TIMEOUT-ONCE")
            val started = System.nanoTime()
            get("/sim/v1/authentication-results/$slow").statusCode() shouldBe 200
            ((System.nanoTime() - started) / 1_000_000) shouldBeGreaterThanOrEqual 1_900L
            val again = System.nanoTime()
            get("/sim/v1/authentication-results/$slow").statusCode() shouldBe 200
            ((System.nanoTime() - again) / 1_000_000 < 1_900L) shouldBe true
        }
        Then("a non-AUTHENTICATED lookup carries no evidence keys and responses never echo card numbers") {
            val body = get("/sim/v1/authentication-results/${completed("VC-AUTH-FAIL")}").body()
            listOf("cardType", "issuerCode", "acquirerCode", "providerCode", "evidenceRef", "authenticatedAt").forEach { tree(body).has(it) shouldBe false }
            body shouldNotContain "4111"
        }
    }

    Given("the customer screen") {
        Then("an unknown or malformed transaction is 404; completing twice is refused (409)") {
            get("/sim/authenticate/sat_" + "Z".repeat(22), bearer = null).statusCode() shouldBe 404
            get("/sim/authenticate/bad", bearer = null).statusCode() shouldBe 404
            val txId = tree(post("/sim/v1/authentications", requestWith()).body()).get("authenticationTransactionId").stringValue()
            form("/sim/authenticate/$txId", mapOf("action" to "authenticate", "card" to "VC-CREDIT-OK")).statusCode() shouldBe 303
            form("/sim/authenticate/$txId", mapOf("action" to "cancel")).statusCode() shouldBe 409
        }
    }
})

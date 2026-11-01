package com.paycore.simulator.van

import com.paycore.simulator.support.MutableSimulatorClock
import com.paycore.simulator.support.SimulatorDb
import com.paycore.simulator.support.SimulatorMySqlContainerConfig
import com.paycore.simulator.support.SimulatorTestConfig
import com.paycore.simulator.support.VanTestClient
import com.paycore.simulator.support.VanTestClient.Companion.authRequest
import com.paycore.simulator.support.VanTestClient.Companion.cancelInquiry
import com.paycore.simulator.support.VanTestClient.Companion.cancelRequest
import com.paycore.simulator.support.VanTestClient.Evidence
import com.paycore.simulator.support.VanTestClient.Received
import com.paycore.simulator.van.adapter.integration.VanFrameFailure
import com.paycore.simulator.van.adapter.integration.VanTcpServer
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.test.context.TestPropertySource
import org.testcontainers.mysql.MySQLContainer
import tools.jackson.databind.JsonNode
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.DriverManager
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 가상 VAN 취소 실제 TCP·실제 MySQL 시험(CANCEL-001 §6.3~§6.5, K15·K16). 원 승인은 가상 인증기관 HTTP 경로 → AUTH_REQUEST로 만든다. 행 수는 migration 계정(읽기)으로 센다.
 * 기대값은 명세 §6.3 판정 순서·§6.4 시나리오 표에서 직접 적는다: 원장 INSERT 뒤 응답, 같은 요청 ID 재전송 = 같은 결정, 기관 잔액은 승인된 취소 합 Σ(n·Σ 한 스냅샷 — m-6)와
 * 승인 순번 UNIQUE + 재판정으로 직렬화(K15), 취소와 무효과 종결의 경합은 namespace UNIQUE가 하나로 만든다(K16).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = ["PAYCORE_SIMULATOR_PORT=0"])
@Import(SimulatorMySqlContainerConfig::class, SimulatorTestConfig::class)
class VanCancelServerSpec(
    private val env: Environment,
    private val clock: MutableSimulatorClock,
    private val server: VanTcpServer,
    private val container: MySQLContainer,
) : BehaviorSpec({

    val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
    val base by lazy { "http://127.0.0.1:${env.getRequiredProperty("local.server.port")}" }
    val bearer = "test-only-simulator-client-key-1"
    val mapper = VanTestClient.MAPPER

    afterEach { clock.reset() }

    fun client(readTimeout: Int = 5_000) = VanTestClient(server.localPort(), readTimeout)

    fun count(sql: String, vararg args: Any): Long =
        DriverManager.getConnection(SimulatorDb.url(container), SimulatorDb.MIGRATION_USER, SimulatorDb.password(SimulatorDb.MIGRATION_USER)).use { c ->
            c.prepareStatement(sql).use { ps ->
                args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
                ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
            }
        }

    fun cancelRows(requestId: String) = count("SELECT COUNT(*) FROM van_cancel_entry WHERE request_id = ?", requestId)

    fun JsonNode.text(field: String): String = get(field).stringValue()

    fun authenticate(card: String, amountMinor: Long = 10_000): Evidence {
        val state = UUID.randomUUID().toString().replace("-", "").padEnd(43, 'c').take(43)
        val register = http.send(
            HttpRequest.newBuilder(URI("$base/sim/v1/authentications")).header("Authorization", "Bearer $bearer").header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        """{"paymentId":"pay_cxl_${state.take(8)}","merchantRef":"mid-demo-0001","environment":"TEST","amountMinor":$amountMinor,"currency":"KRW",""" +
                            """"plan":{"kind":"LUMP_SUM"},"cardCompany":"SIMISSA","state":"$state",""" +
                            """"returnUrl":"https://checkout.example.test/checkout/s/Hh0123456789abcdefghij/authentication/return"}""",
                    ),
                ).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        withClue(register.body()) { register.statusCode() shouldBe 201 }
        val txId = mapper.readTree(register.body()).get("authenticationTransactionId").stringValue()
        val redirect = http.send(
            HttpRequest.newBuilder(URI("$base/sim/authenticate/$txId")).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("action=authenticate&card=${URLEncoder.encode(card, Charsets.UTF_8)}")).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        val ref = redirect.headers().firstValue("Location").get().substringAfter("resultRef=").substringBefore('&')
        val lookup = mapper.readTree(
            http.send(
                HttpRequest.newBuilder(URI("$base/sim/v1/authentication-results/$ref")).header("Authorization", "Bearer $bearer").GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            ).body(),
        )
        lookup.get("outcome").stringValue() shouldBe "AUTHENTICATED"
        return Evidence(
            lookup.get("evidenceRef").stringValue(), lookup.get("providerCode").stringValue(), lookup.get("issuerCode").stringValue(), lookup.get("acquirerCode").stringValue(),
            lookup.get("cardType").stringValue(), amountMinor, null,
        )
    }

    /** 원 승인(APPROVED). 반환 = (원 요청 ID, AUTH_RESPONSE). */
    fun approve(card: String = "VC-CREDIT-OK", account: String = "SIMMERCH0001"): Pair<String, JsonNode> {
        val ev = authenticate(card)
        val rid = VanTestClient.requestId(clock.now().minusSeconds(1))
        val r = client().use { it.call(authRequest(rid, ev, account = account)) }
        r.text("outcome") shouldBe "APPROVED"
        return rid to r
    }

    fun newCancelId(age: Duration = Duration.ofSeconds(1)) = VanTestClient.cancelRequestId(clock.now().minus(age))

    Given("an approved original authorization") {
        Then("a full cancel is APPROVED after the ledger INSERT; a resend replays the same decision, another payload is REQUEST_CONFLICT") {
            val (orig, auth) = approve()
            val cr = newCancelId()
            client().use { c ->
                val first = c.call(cancelRequest(cr, orig, auth, 10_000, "FULL"))
                first.text("type") shouldBe "CANCEL_RESPONSE"
                first.text("outcome") shouldBe "APPROVED"
                first.text("requestId") shouldBe cr
                first.text("originalProviderTransactionId") shouldBe auth.text("providerTransactionId")
                first.get("cancelledAmountMinor").longValue() shouldBe 10_000
                first.has("cancelApprovalNumber") shouldBe true
                first.has("institutionBusinessDate") shouldBe true
                val resent = c.call(cancelRequest(cr, orig, auth, 10_000, "FULL", requestedAt = "2026-10-16T01:00:59.000Z"))
                resent.text("decisionId") shouldBe first.text("decisionId")
                resent.text("providerTransactionId") shouldBe first.text("providerTransactionId")
                c.call(cancelRequest(cr, orig, auth, 9_000, "PARTIAL")).text("outcome") shouldBe "REQUEST_CONFLICT"
            }
            cancelRows(cr) shouldBe 1
        }
        Then("partial cancels take sequences 1 and 2 up to the original amount; one more minor unit is DECLINED EXCEEDS_REMAINING") {
            val (orig, auth) = approve()
            val outcomes = client().use { c ->
                listOf(3_000L to "PARTIAL", 7_000L to "PARTIAL", 1L to "PARTIAL").map { (amount, scope) ->
                    c.call(cancelRequest(newCancelId(), orig, auth, amount, scope)).let { it.text("outcome") to it.get("declineCategory")?.stringValue() }
                }
            }
            outcomes shouldBe listOf("APPROVED" to null, "APPROVED" to null, "DECLINED" to "EXCEEDS_REMAINING")
            count("SELECT COUNT(*) FROM van_cancel_entry WHERE original_request_id = ? AND approved_sequence IN (1, 2)", orig) shouldBe 2
            count("SELECT COALESCE(SUM(cancelled_amount_minor), 0) FROM van_cancel_entry WHERE original_request_id = ?", orig) shouldBe 10_000
        }
        Then("an unknown original is DECLINED ORIGINAL_NOT_FOUND and recorded (a resend gets the same decline)") {
            val (_, auth) = approve()
            val unknown = VanTestClient.requestId(clock.now().minusSeconds(5))
            val cr = newCancelId()
            client().use { c ->
                val first = c.call(cancelRequest(cr, unknown, auth, 10_000, "FULL"))
                first.text("outcome") shouldBe "DECLINED"
                first.text("declineCategory") shouldBe "ORIGINAL_NOT_FOUND"
                c.call(cancelRequest(cr, unknown, auth, 10_000, "FULL")).text("decisionId") shouldBe first.text("decisionId")
            }
            cancelRows(cr) shouldBe 1
        }
        Then("after the Demo cancel window (P365D) the cancel is DECLINED CANCEL_PERIOD_EXPIRED") {
            val (orig, auth) = approve()
            clock.set(clock.now().plus(Duration.ofDays(366)))
            client().use { c -> c.call(cancelRequest(newCancelId(), orig, auth, 10_000, "FULL")).text("declineCategory") shouldBe "CANCEL_PERIOD_EXPIRED" }
        }
        Then("profile B refuses a check-card partial cancel (PARTIAL_NOT_SUPPORTED) but approves its full cancel") {
            val (orig, auth) = approve(card = "VC-CHECK-OK", account = "SIMMERCH0002")
            client().use { c ->
                c.call(cancelRequest(newCancelId(), orig, auth, 3_000, "PARTIAL", account = "SIMMERCH0002")).text("declineCategory") shouldBe "PARTIAL_NOT_SUPPORTED"
                c.call(cancelRequest(newCancelId(), orig, auth, 10_000, "FULL", account = "SIMMERCH0002")).text("outcome") shouldBe "APPROVED"
            }
        }
    }

    Given("cancel scenario cards") {
        Then("VC-CXL-DECLINE is DECLINED ISSUER_DECLINED") {
            val (orig, auth) = approve(card = "VC-CXL-DECLINE")
            client().use { c -> c.call(cancelRequest(newCancelId(), orig, auth, 10_000, "FULL")).text("declineCategory") shouldBe "ISSUER_DECLINED" }
        }
        Then("VC-CXL-DROP-BEFORE closes without a record; NOT_FOUND, then profile A closes it and a late resend is REJECTED_CLOSED") {
            val (orig, auth) = approve(card = "VC-CXL-DROP-BEFORE")
            val cr = newCancelId(age = Duration.ofMinutes(3))
            client().use { c -> c.receiveAfter(cancelRequest(cr, orig, auth, 10_000, "FULL")) shouldBe Received.Closed }
            cancelRows(cr) shouldBe 0
            client().use { c ->
                c.call(cancelInquiry(cr)).text("result") shouldBe "NOT_FOUND"
                val closed = c.call(cancelInquiry(cr, closeIfAbsent = true))
                closed.text("result") shouldBe "NO_EFFECT_CONFIRMED"
                c.call(cancelInquiry(cr)).text("closureId") shouldBe closed.text("closureId")
            }
            client().use { c -> c.call(cancelRequest(cr, orig, auth, 10_000, "FULL")).text("outcome") shouldBe "REJECTED_CLOSED" }
            cancelRows(cr) shouldBe 1
        }
        Then("VC-CXL-DROP-AFTER commits the decision and closes without a response; the inquiry finds it") {
            val (orig, auth) = approve(card = "VC-CXL-DROP-AFTER")
            val cr = newCancelId()
            client().use { c -> c.receiveAfter(cancelRequest(cr, orig, auth, 10_000, "FULL")) shouldBe Received.Closed }
            cancelRows(cr) shouldBe 1
            client().use { c ->
                val found = c.call(cancelInquiry(cr))
                found.text("result") shouldBe "FOUND"
                found.text("outcome") shouldBe "APPROVED"
                found.get("cancelledAmountMinor").longValue() shouldBe 10_000
            }
        }
        Then("VC-CXL-BAD-MAC sends a frame with a broken MAC (INTEGRITY) after the commit; VC-CXL-FRAGMENTED arrives whole") {
            val (orig, auth) = approve(card = "VC-CXL-BAD-MAC")
            client().use { c -> c.receiveAfter(cancelRequest(newCancelId(), orig, auth, 10_000, "FULL")) shouldBe Received.Broken(VanFrameFailure.INTEGRITY) }
            val (orig2, auth2) = approve(card = "VC-CXL-FRAGMENTED")
            client().use { c -> c.call(cancelRequest(newCancelId(), orig2, auth2, 10_000, "FULL")).text("outcome") shouldBe "APPROVED" }
        }
        Then("VC-CXL-LATE-RESPONSE answers after the read timeout; the decision is already committed") {
            val (orig, auth) = approve(card = "VC-CXL-LATE-RESPONSE")
            val cr = newCancelId()
            client(readTimeout = 500).use { c -> c.receiveAfter(cancelRequest(cr, orig, auth, 10_000, "FULL")) shouldBe Received.TimedOut }
            cancelRows(cr) shouldBe 1
        }
        Then("VC-CXL-PARTIAL approves one minor unit less than requested") {
            val (orig, auth) = approve(card = "VC-CXL-PARTIAL")
            client().use { c -> c.call(cancelRequest(newCancelId(), orig, auth, 10_000, "FULL")).get("cancelledAmountMinor").longValue() shouldBe 9_999 }
        }
        Then("VC-CXL-INQUIRY-UNAVAILABLE-ONCE: the first cancel inquiry is UNAVAILABLE, the next FOUND; every inquiry is logged as CANCEL") {
            val (orig, auth) = approve(card = "VC-CXL-INQUIRY-UNAVAILABLE-ONCE")
            val cr = newCancelId()
            client().use { c ->
                c.call(cancelRequest(cr, orig, auth, 10_000, "FULL")).text("outcome") shouldBe "APPROVED"
                c.call(cancelInquiry(cr)).text("result") shouldBe "UNAVAILABLE"
                c.call(cancelInquiry(cr)).text("result") shouldBe "FOUND"
            }
            count("SELECT COUNT(*) FROM van_inquiry_log WHERE original_request_id = ? AND operation = 'CANCEL'", cr) shouldBe 2
        }
    }

    Given("the cancel inquiry on profile B") {
        Then("closeIfAbsent never closes a cancel request ID (no cancel no-effect capability)") {
            val cr = newCancelId(age = Duration.ofMinutes(3))
            client().use { c -> c.call(cancelInquiry(cr, closeIfAbsent = true, account = "SIMMERCH0002")).text("result") shouldBe "NOT_FOUND" }
            cancelRows(cr) shouldBe 0
        }
    }

    Given("authorization inquiries after V6") {
        Then("an authorization inquiry is logged with operation AUTHORIZE") {
            val (orig, _) = approve()
            client().use { c -> c.call(VanTestClient.inquiry(orig)).text("result") shouldBe "FOUND" }
            count("SELECT COUNT(*) FROM van_inquiry_log WHERE original_request_id = ? AND operation = 'AUTHORIZE'", orig) shouldBe 1
        }
    }

    // K15(m-6): 같은 원 승인의 동시 부분취소 — 기관 잔액을 넘는 두 번째 승인이 없다(n·Σ 한 스냅샷 + 순번 UNIQUE 1062 → 새 TX 재판정).
    Given("K15 — two concurrent partial cancels of one original whose sum exceeds it") {
        Then("exactly one is APPROVED and the other DECLINED EXCEEDS_REMAINING; the approved sum never exceeds the original") {
            val pool = Executors.newFixedThreadPool(2)
            try {
                repeat(6) { round ->
                    val (orig, auth) = approve()
                    val barrier = CyclicBarrier(2)
                    val results = List(2) {
                        val cr = newCancelId()
                        pool.submit(Callable { client().use { c -> barrier.await(); c.call(cancelRequest(cr, orig, auth, 6_000, "PARTIAL")) } })
                    }.map { it.get(20, TimeUnit.SECONDS) }
                    withClue("round $round") {
                        results.map { it.text("outcome") to it.get("declineCategory")?.stringValue() } shouldContainExactlyInAnyOrder
                            listOf("APPROVED" to null, "DECLINED" to "EXCEEDS_REMAINING")
                        count("SELECT COALESCE(SUM(cancelled_amount_minor), 0) FROM van_cancel_entry WHERE original_request_id = ? AND outcome = 'APPROVED'", orig) shouldBeLessThanOrEqual
                            10_000
                        count("SELECT COUNT(*) FROM van_cancel_entry WHERE original_request_id = ? AND approved_sequence = 1", orig) shouldBe 1
                    }
                }
            } finally {
                pool.shutdownNow()
            }
        }
    }

    // K16: 같은 취소 요청 ID의 취소 결정과 무효과 종결 표지 경합 — namespace UNIQUE가 하나만 남긴다.
    Given("K16 — a cancel and a closeIfAbsent inquiry of the same cancel request ID at the same time") {
        Then("exactly one ledger row; either the cancel is APPROVED and the inquiry FOUND, or the closure wins and the cancel is REJECTED_CLOSED") {
            val pool = Executors.newFixedThreadPool(2)
            try {
                repeat(6) { round ->
                    val (orig, auth) = approve()
                    val cr = newCancelId(age = Duration.ofMinutes(3))
                    val barrier = CyclicBarrier(2)
                    val cancel = pool.submit(Callable { client().use { c -> barrier.await(); c.call(cancelRequest(cr, orig, auth, 10_000, "FULL")) } })
                    val close = pool.submit(Callable { client().use { c -> barrier.await(); c.call(cancelInquiry(cr, closeIfAbsent = true)) } })
                    val a = cancel.get(20, TimeUnit.SECONDS).text("outcome")
                    val q = close.get(20, TimeUnit.SECONDS).text("result")
                    withClue("round $round: $a / $q") {
                        listOf("APPROVED" to "FOUND", "REJECTED_CLOSED" to "NO_EFFECT_CONFIRMED").contains(a to q) shouldBe true
                        cancelRows(cr) shouldBe 1
                    }
                }
            } finally {
                pool.shutdownNow()
            }
        }
    }

    Given("a refused cancel") {
        Then("a frame signed with an unknown key closes without a record") {
            val (orig, auth) = approve()
            val cr = newCancelId()
            client().use { c ->
                c.send(cancelRequest(cr, orig, auth, 10_000, "FULL"), key = ByteArray(32) { 7 })
                c.receive() shouldBe Received.Closed
            }
            cancelRows(cr) shouldBe 0
        }
    }
})

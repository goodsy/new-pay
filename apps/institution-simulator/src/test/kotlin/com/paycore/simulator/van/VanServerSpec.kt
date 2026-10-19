package com.paycore.simulator.van

import com.paycore.simulator.support.MutableSimulatorClock
import com.paycore.simulator.support.SimulatorDb
import com.paycore.simulator.support.SimulatorMySqlContainerConfig
import com.paycore.simulator.support.SimulatorTestConfig
import com.paycore.simulator.support.VanTestClient
import com.paycore.simulator.support.VanTestClient.Companion.authRequest
import com.paycore.simulator.support.VanTestClient.Companion.inquiry
import com.paycore.simulator.support.VanTestClient.Evidence
import com.paycore.simulator.support.VanTestClient.Received
import com.paycore.simulator.van.adapter.integration.VanFrameFailure
import com.paycore.simulator.van.adapter.integration.VanTcpServer
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
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
 * 가상 VAN 실제 TCP·실제 MySQL 시험(AUTH-001 §5.3·§5.4·§5.5·§8 C30). 인증 증거는 가상 인증기관 HTTP 경로(등록 → 고객 카드 선택 → 결과 조회)로 만든다. 원장 행 수는
 * migration 계정(읽기)으로 센다. 판정 기대값은 명세 §5.3 표·§5.4에서 직접 적는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = ["PAYCORE_SIMULATOR_PORT=0"])
@Import(SimulatorMySqlContainerConfig::class, SimulatorTestConfig::class)
class VanServerSpec(
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

    fun ledgerRows(requestId: String) = count("SELECT COUNT(*) FROM van_ledger_entry WHERE request_id = ?", requestId)

    /** 가상 인증기관에서 [card]로 인증을 마치고 결과 조회의 증거를 돌려준다(현재 시험 시계 기준). */
    fun authenticate(card: String, amountMinor: Long = 10_000, months: Int? = null): Evidence {
        val plan = if (months == null) """{"kind":"LUMP_SUM"}""" else """{"kind":"INSTALLMENT","months":$months}"""
        val state = UUID.randomUUID().toString().replace("-", "").padEnd(43, 'v').take(43)
        val register = http.send(
            HttpRequest.newBuilder(URI("$base/sim/v1/authentications")).header("Authorization", "Bearer $bearer").header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        """{"paymentId":"pay_van_${state.take(8)}","merchantRef":"mid-demo-0001","environment":"TEST","amountMinor":$amountMinor,"currency":"KRW",""" +
                            """"plan":$plan,"cardCompany":"SIMISS-A","state":"$state",""" +
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
            lookup.get("cardType").stringValue(), amountMinor, months,
        )
    }

    fun newRequestId(age: Duration = Duration.ofSeconds(1)) = VanTestClient.requestId(clock.now().minus(age))

    fun JsonNode.text(field: String): String = get(field).stringValue()

    Given("an authorization request with a valid evidence") {
        Then("it is APPROVED, the decision is in the ledger once, and a resend with another messageId/requestedAt replays the same decision (m-7)") {
            val ev = authenticate("VC-CREDIT-OK")
            val rid = newRequestId()
            client().use { c ->
                val first = c.call(authRequest(rid, ev))
                first.text("type") shouldBe "AUTH_RESPONSE"
                first.text("outcome") shouldBe "APPROVED"
                first.text("requestId") shouldBe rid
                first.get("approvedAmountMinor").longValue() shouldBe 10_000
                first.text("cardType") shouldBe "CREDIT"
                first.has("institutionBusinessDate") shouldBe true
                val resent = c.call(authRequest(rid, ev, requestedAt = "2026-10-16T01:00:59.000Z"))
                resent.text("outcome") shouldBe "APPROVED"
                resent.text("decisionId") shouldBe first.text("decisionId")
                resent.text("inReplyTo") shouldNotBe first.text("inReplyTo")
            }
            ledgerRows(rid) shouldBe 1
        }
        Then("the same request ID with a different payload is REQUEST_CONFLICT and the ledger is unchanged") {
            val ev = authenticate("VC-CREDIT-OK")
            val rid = newRequestId()
            client().use { c ->
                c.call(authRequest(rid, ev)).text("outcome") shouldBe "APPROVED"
                c.call(authRequest(rid, ev, amountMinor = 9_000)).text("outcome") shouldBe "REQUEST_CONFLICT"
            }
            ledgerRows(rid) shouldBe 1
        }
        Then("the same evidence under another request ID is DECLINED AUTHENTICATION_INVALID (reuse only for the same ID)") {
            val ev = authenticate("VC-CREDIT-OK")
            client().use { c ->
                c.call(authRequest(newRequestId(), ev)).text("outcome") shouldBe "APPROVED"
                val other = c.call(authRequest(newRequestId(), ev))
                other.text("outcome") shouldBe "DECLINED"
                other.text("declineCategory") shouldBe "AUTHENTICATION_INVALID"
            }
        }
        Then("two requests on one connection are answered in turn with their own inReplyTo") {
            val a = authenticate("VC-CREDIT-OK")
            val b = authenticate("VC-SPLIT-OK")
            client().use { c ->
                val ra = authRequest(newRequestId(), a)
                val rb = authRequest(newRequestId(), b)
                c.send(ra)
                c.send(rb)
                val first = (c.receive() as Received.Message).body
                val second = (c.receive() as Received.Message).body
                setOf(first.text("inReplyTo"), second.text("inReplyTo")) shouldBe setOf(ra.text("messageId"), rb.text("messageId"))
                setOf(first.text("requestId"), second.text("requestId")) shouldBe setOf(ra.text("requestId"), rb.text("requestId"))
            }
        }
    }

    Given("institution-side re-validation (D-AUTH1-004, §5.3-3·4) — declines are recorded before the response (m-7)") {
        Then("an evidence used after its validUntil is DECLINED AUTHENTICATION_INVALID, recorded, and a resend gets the same decision") {
            val ev = authenticate("VC-CREDIT-OK")
            clock.set(clock.now().plus(Duration.ofMinutes(6)))
            val rid = newRequestId()
            client().use { c ->
                val first = c.call(authRequest(rid, ev))
                first.text("outcome") shouldBe "DECLINED"
                first.text("declineCategory") shouldBe "AUTHENTICATION_INVALID"
                c.call(authRequest(rid, ev)).text("decisionId") shouldBe first.text("decisionId")
            }
            ledgerRows(rid) shouldBe 1
        }
        Then("a check card installment sent directly (PG already refuses it) is DECLINED PLAN_NOT_SUPPORTED and recorded") {
            val ev = authenticate("VC-CHECK-OK", months = 3)
            val rid = newRequestId()
            client().use { c ->
                val r = c.call(authRequest(rid, ev))
                r.text("outcome") shouldBe "DECLINED"
                r.text("declineCategory") shouldBe "PLAN_NOT_SUPPORTED"
            }
            ledgerRows(rid) shouldBe 1
        }
        Then("installment months outside the profile are DECLINED PLAN_NOT_SUPPORTED (VC-VAN-PLAN-OUT-OF-PROFILE procedure)") {
            val ev = authenticate("VC-CREDIT-OK", months = 5)
            client().use { c -> c.call(authRequest(newRequestId(), ev)).text("declineCategory") shouldBe "PLAN_NOT_SUPPORTED" }
        }
    }

    Given("frames the VAN refuses without a ledger record") {
        Then("a frame with a bad MAC closes the connection and writes nothing (n-4)") {
            val ev = authenticate("VC-CREDIT-OK")
            val rid = newRequestId()
            client().use { c ->
                c.send(authRequest(rid, ev), key = "another-test-only-key-0000000002".toByteArray())
                c.receive() shouldBe Received.Closed
            }
            ledgerRows(rid) shouldBe 0
        }
        Then("a LIVE request closes the connection and writes nothing (§5.6 layer 4)") {
            val ev = authenticate("VC-CREDIT-OK")
            val rid = newRequestId()
            client().use { c -> c.receiveAfter(authRequest(rid, ev, environment = "LIVE")) shouldBe Received.Closed }
            ledgerRows(rid) shouldBe 0
        }
        Then("a malformed message (unknown field) closes the connection and writes nothing") {
            val ev = authenticate("VC-CREDIT-OK")
            val rid = newRequestId()
            client().use { c -> c.receiveAfter(authRequest(rid, ev).put("extra", true)) shouldBe Received.Closed }
            ledgerRows(rid) shouldBe 0
        }
        Then("an unknown institution account closes the connection and writes nothing") {
            val ev = authenticate("VC-CREDIT-OK")
            val rid = newRequestId()
            client().use { c -> c.receiveAfter(authRequest(rid, ev, account = "SIMMERCH9999")) shouldBe Received.Closed }
            ledgerRows(rid) shouldBe 0
        }
    }

    Given("transport scenarios (§5.3 Demo table)") {
        Then("VC-VAN-DROP-AFTER-APPROVE: the decision is recorded, no response arrives, and the inquiry finds the same decision") {
            val ev = authenticate("VC-VAN-DROP-AFTER-APPROVE")
            val rid = newRequestId()
            client().use { c -> c.receiveAfter(authRequest(rid, ev)) shouldBe Received.Closed }
            ledgerRows(rid) shouldBe 1
            client().use { c ->
                val found = c.call(inquiry(rid))
                found.text("result") shouldBe "FOUND"
                found.text("outcome") shouldBe "APPROVED"
            }
        }
        Then("VC-VAN-LATE-RESPONSE: the decision is committed before the late response (inquiry FOUND while the response is still pending)") {
            val ev = authenticate("VC-VAN-LATE-RESPONSE")
            val rid = newRequestId()
            client(readTimeout = 300).use { c -> c.receiveAfter(authRequest(rid, ev)) shouldBe Received.TimedOut }
            ledgerRows(rid) shouldBe 1
            client().use { c -> c.call(inquiry(rid)).text("result") shouldBe "FOUND" }
        }
        Then("VC-VAN-FRAGMENTED: the response written in small pieces is read whole") {
            val ev = authenticate("VC-VAN-FRAGMENTED")
            client().use { c -> c.call(authRequest(newRequestId(), ev)).text("outcome") shouldBe "APPROVED" }
        }
        Then("VC-VAN-DUPLICATE-RESPONSE: the same response frame arrives twice (deterministic, n-4)") {
            val ev = authenticate("VC-VAN-DUPLICATE-RESPONSE")
            client().use { c ->
                val first = c.call(authRequest(newRequestId(), ev))
                val second = (c.receive() as Received.Message).body
                second shouldBe first
            }
        }
        Then("VC-VAN-BAD-MAC: the response MAC is wrong (INTEGRITY at the PG) while the ledger has the approval") {
            val ev = authenticate("VC-VAN-BAD-MAC")
            val rid = newRequestId()
            client().use { c -> c.receiveAfter(authRequest(rid, ev)) shouldBe Received.Broken(VanFrameFailure.INTEGRITY) }
            client().use { c -> c.call(inquiry(rid)).text("outcome") shouldBe "APPROVED" }
        }
        Then("a resend of a transport-scenario request gets a normal response with the recorded decision") {
            val ev = authenticate("VC-VAN-DROP-AFTER-APPROVE")
            val rid = newRequestId()
            client().use { c -> c.receiveAfter(authRequest(rid, ev)) shouldBe Received.Closed }
            client().use { c -> c.call(authRequest(rid, ev)).text("outcome") shouldBe "APPROVED" }
            ledgerRows(rid) shouldBe 1
        }
        Then("VC-VAN-INQUIRY-UNAVAILABLE-ONCE: the first inquiry is UNAVAILABLE and the next one FOUND") {
            val ev = authenticate("VC-VAN-INQUIRY-UNAVAILABLE-ONCE")
            val rid = newRequestId()
            client().use { c ->
                c.call(authRequest(rid, ev)).text("outcome") shouldBe "APPROVED"
                c.call(inquiry(rid)).text("result") shouldBe "UNAVAILABLE"
                c.call(inquiry(rid)).text("result") shouldBe "FOUND"
            }
        }
    }

    Given("no-effect closure (§5.4, AU-14) and profiles A/B (AU-15)") {
        Then("VC-VAN-DROP-BEFORE-RECORD: nothing is recorded; profile A closes an old-enough ID; the late original is REJECTED_CLOSED; one ledger row") {
            val ev = authenticate("VC-VAN-DROP-BEFORE-RECORD")
            val rid = newRequestId(age = Duration.ofMinutes(3))
            client().use { c -> c.receiveAfter(authRequest(rid, ev)) shouldBe Received.Closed }
            ledgerRows(rid) shouldBe 0
            client().use { c ->
                c.call(inquiry(rid)).text("result") shouldBe "NOT_FOUND"
                val closed = c.call(inquiry(rid, closeIfAbsent = true))
                closed.text("result") shouldBe "NO_EFFECT_CONFIRMED"
                c.call(inquiry(rid)).text("closureId") shouldBe closed.text("closureId")
            }
            val late = authenticate("VC-CREDIT-OK")
            client().use { c -> c.call(authRequest(rid, late)).text("outcome") shouldBe "REJECTED_CLOSED" }
            ledgerRows(rid) shouldBe 1
        }
        Then("profile B never closes (NOT_FOUND, no tombstone); profile A does not close a request younger than closableAfter") {
            val young = newRequestId(age = Duration.ofSeconds(10))
            val old = newRequestId(age = Duration.ofMinutes(3))
            client().use { c ->
                c.call(inquiry(old, closeIfAbsent = true, account = "SIMMERCH0002")).text("result") shouldBe "NOT_FOUND"
                c.call(inquiry(young, closeIfAbsent = true)).text("result") shouldBe "NOT_FOUND"
            }
            ledgerRows(old) shouldBe 0
            ledgerRows(young) shouldBe 0
        }
        Then("a request ID older than the inquiry retention is UNAVAILABLE and closeIfAbsent creates no tombstone (m-7)") {
            val expired = newRequestId(age = Duration.ofDays(8))
            client().use { c ->
                c.call(inquiry(expired)).text("result") shouldBe "UNAVAILABLE"
                c.call(inquiry(expired, closeIfAbsent = true)).text("result") shouldBe "UNAVAILABLE"
            }
            ledgerRows(expired) shouldBe 0
        }
    }

    Given("VC-VAN-PENDING-THEN-APPROVE (rev 0.3 H-3)") {
        Then("PENDING with a pendingId; the inquiry answers PENDING with the same id and closeIfAbsent does not close; after the delay the inquiry finds the approval") {
            val ev = authenticate("VC-VAN-PENDING-THEN-APPROVE")
            val rid = newRequestId(age = Duration.ofMinutes(3))
            client().use { c ->
                val pending = c.call(authRequest(rid, ev))
                pending.text("outcome") shouldBe "PENDING"
                val pendingId = pending.text("pendingId")
                c.call(inquiry(rid)).text("pendingId") shouldBe pendingId
                val notClosed = c.call(inquiry(rid, closeIfAbsent = true))
                notClosed.text("result") shouldBe "PENDING"
                notClosed.text("pendingId") shouldBe pendingId
                ledgerRows(rid) shouldBe 0
                clock.set(clock.now().plus(Duration.ofSeconds(6)))
                val found = c.call(inquiry(rid))
                found.text("result") shouldBe "FOUND"
                found.text("outcome") shouldBe "APPROVED"
                c.call(authRequest(rid, ev)).text("decisionId") shouldBe found.text("decisionId")
            }
            ledgerRows(rid) shouldBe 1
        }
    }

    Given("races on the ledger UNIQUE(namespace) (§8 C30)") {
        Then("an authorization and a closeIfAbsent for the same new ID at the same time leave exactly one ledger row and consistent answers") {
            val pool = Executors.newFixedThreadPool(2)
            try {
                repeat(6) { round ->
                    val ev = authenticate("VC-CREDIT-OK")
                    val rid = newRequestId(age = Duration.ofMinutes(3))
                    val barrier = CyclicBarrier(2)
                    val auth = pool.submit(Callable { client().use { c -> barrier.await(); c.call(authRequest(rid, ev)) } })
                    val close = pool.submit(Callable { client().use { c -> barrier.await(); c.call(inquiry(rid, closeIfAbsent = true)) } })
                    val a = auth.get(20, TimeUnit.SECONDS)
                    val q = close.get(20, TimeUnit.SECONDS)
                    withClue("round $round: ${a.text("outcome")} / ${q.text("result")}") {
                        ledgerRows(rid) shouldBe 1
                        if (a.text("outcome") == "APPROVED") q.text("result") shouldBe "FOUND" else {
                            a.text("outcome") shouldBe "REJECTED_CLOSED"
                            q.text("result") shouldBe "NO_EFFECT_CONFIRMED"
                        }
                    }
                }
            } finally {
                pool.shutdownNow()
            }
        }
        Then("two inquiries (one closeIfAbsent) resolving the same due PENDING reservation leave one DECISION row and both FOUND") {
            val ev = authenticate("VC-VAN-PENDING-THEN-APPROVE")
            val rid = newRequestId(age = Duration.ofMinutes(3))
            client().use { c -> c.call(authRequest(rid, ev)).text("outcome") shouldBe "PENDING" }
            clock.set(clock.now().plus(Duration.ofSeconds(6)))
            val pool = Executors.newFixedThreadPool(2)
            try {
                val barrier = CyclicBarrier(2)
                val results = listOf(false, true).map { close -> pool.submit(Callable { client().use { c -> barrier.await(); c.call(inquiry(rid, closeIfAbsent = close)) } }) }
                    .map { it.get(20, TimeUnit.SECONDS) }
                results.map { it.text("result") } shouldBe listOf("FOUND", "FOUND")
                results.map { it.text("decisionId") }.toSet().size shouldBe 1
            } finally {
                pool.shutdownNow()
            }
            ledgerRows(rid) shouldBe 1
            count("SELECT COUNT(*) FROM van_ledger_entry WHERE request_id = ? AND kind = 'DECISION'", rid) shouldBe 1
        }
    }

    // 검토 #2 m-C: PENDING 예약 생성과 무효과 종결이 동시에 오면 둘 다 기록될 수 있으나 기관 사실은 원장 한 행이다 — 종결이 있으면 기한 뒤에도 승인이 생기지 않는다.
    Given("races of a PENDING-THEN-APPROVE authorization against closeIfAbsent (review #2 m-C)") {
        Then("each round: at most one ledger row; where a closure exists, after the due time the inquiry is NO_EFFECT_CONFIRMED, a resent AUTH is REJECTED_CLOSED and no DECISION row exists") {
            val pool = Executors.newFixedThreadPool(2)
            try {
                repeat(6) { round ->
                    clock.reset()
                    val ev = authenticate("VC-VAN-PENDING-THEN-APPROVE")
                    val rid = newRequestId(age = Duration.ofMinutes(3))
                    val barrier = CyclicBarrier(2)
                    val auth = pool.submit(Callable { client().use { c -> barrier.await(); c.call(authRequest(rid, ev)) } })
                    val close = pool.submit(Callable { client().use { c -> barrier.await(); c.call(inquiry(rid, closeIfAbsent = true)) } })
                    val a = auth.get(20, TimeUnit.SECONDS)
                    val q = close.get(20, TimeUnit.SECONDS)
                    withClue("round $round: ${a.text("outcome")} / ${q.text("result")}") {
                        (ledgerRows(rid) <= 1L) shouldBe true
                        clock.set(clock.now().plus(Duration.ofSeconds(6)))
                        val closureRows = count("SELECT COUNT(*) FROM van_ledger_entry WHERE request_id = ? AND kind = 'CLOSURE'", rid)
                        if (closureRows == 1L) {
                            client().use { c ->
                                c.call(inquiry(rid)).text("result") shouldBe "NO_EFFECT_CONFIRMED"
                                c.call(authRequest(rid, ev)).text("outcome") shouldBe "REJECTED_CLOSED"
                            }
                            count("SELECT COUNT(*) FROM van_ledger_entry WHERE request_id = ? AND kind = 'DECISION'", rid) shouldBe 0
                        } else {
                            client().use { c -> c.call(inquiry(rid)).text("result") shouldBe "FOUND" }
                            ledgerRows(rid) shouldBe 1
                        }
                    }
                }
            } finally {
                pool.shutdownNow()
            }
        }
    }

    // 검토 #2 n-2: 증거 재사용은 요청 ID가 아니라 namespace 전체로 본다 — 같은 요청 ID라도 다른 기관 가맹점 계정이면 다른 요청이다.
    Given("the same evidence and the same request ID under another institution account (review #2 n-2)") {
        Then("it is DECLINED AUTHENTICATION_INVALID (evidence used by another namespace)") {
            val ev = authenticate("VC-CREDIT-OK")
            val rid = newRequestId()
            client().use { c ->
                c.call(authRequest(rid, ev)).text("outcome") shouldBe "APPROVED"
                val other = c.call(authRequest(rid, ev, account = "SIMMERCH0002"))
                other.text("outcome") shouldBe "DECLINED"
                other.text("declineCategory") shouldBe "AUTHENTICATION_INVALID"
            }
        }
    }

    // 검토 #2 n-3(Demo 규칙): 요청 ID의 ULID 시각이 조회 보존기간보다 오래된 새 승인 요청은 기록 없이 거절한다(조회로도 확인할 수 없는 요청이 승인되지 않게).
    Given("an authorization request whose request ID is older than the inquiry retention (review #2 n-3)") {
        Then("the connection closes without a ledger record") {
            val ev = authenticate("VC-CREDIT-OK")
            val rid = newRequestId(age = Duration.ofDays(8))
            client().use { c -> c.receiveAfter(authRequest(rid, ev)) shouldBe Received.Closed }
            ledgerRows(rid) shouldBe 0
        }
    }

    // 검토 #2 n-4: 서버 소켓에 유휴 읽기 timeout — 아무것도 보내지 않는 연결은 닫힌다.
    Given("an idle connection (review #2 n-4)") {
        Then("the server closes it after the idle timeout") {
            client(readTimeout = 6_000).use { c -> c.receive() shouldBe Received.Closed }
        }
    }

    Given("the inquiry log (append-only)") {
        Then("every inquiry leaves one log row") {
            val rid = newRequestId()
            val before = count("SELECT COUNT(*) FROM van_inquiry_log WHERE original_request_id = ?", rid)
            client().use { c -> repeat(3) { c.call(inquiry(rid)) } }
            count("SELECT COUNT(*) FROM van_inquiry_log WHERE original_request_id = ?", rid) shouldBeGreaterThanOrEqual before + 3
        }
    }
})

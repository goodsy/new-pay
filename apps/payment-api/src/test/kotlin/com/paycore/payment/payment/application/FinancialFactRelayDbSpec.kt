package com.paycore.payment.payment.application

import com.paycore.payment.payment.adapter.integration.HttpFinancialFactDispatcher
import com.paycore.payment.payment.adapter.integration.StubFinancialInbox
import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.adapter.persistence.JpaFinancialFactRelayStore
import com.paycore.payment.payment.adapter.web.MutablePaymentClock
import com.paycore.payment.payment.adapter.web.PaymentApiTest
import com.paycore.payment.payment.application.provided.CancelPaymentResult
import com.paycore.payment.payment.application.provided.CancelPaymentUseCase
import com.paycore.payment.payment.application.provided.ConfirmPaymentUseCase
import com.paycore.payment.payment.application.provided.FinancialFactRelayBacklogQuery
import com.paycore.payment.payment.application.provided.FinancialFactRelayReport
import com.paycore.payment.payment.application.provided.RelayFinancialFactsCommand
import com.paycore.payment.payment.application.required.AuthenticationSessionRepository
import com.paycore.payment.payment.application.required.CheckoutSessionStore
import com.paycore.payment.payment.application.required.CheckoutTokenStore
import com.paycore.payment.payment.application.required.FinancialFactRelayMark
import com.paycore.payment.payment.application.required.OrderPaymentControlRepository
import com.paycore.payment.payment.application.required.PaymentClock
import com.paycore.payment.payment.application.required.PaymentRepository
import com.paycore.payment.payment.domain.Money
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * PAYMENT → FINANCIAL Relay 실제 MySQL 시험(FIN-FACT-RELAY-001 §7 FR-R1..R8). 금융 사실은 운영 경로(confirm → stub VAN 승인 → 취소)로 만든다. FINANCIAL은 실제 HTTP 소켓의
 * 대역([StubFinancialInbox] — golden 서명 검증·멱등 ACK)이다. Relay 서비스는 운영 Store에 이 대역을 가리키는 운영 dispatcher와 시험 전용 시계를 넣어 직접 만든다.
 * 공유 컨테이너에는 다른 spec의 FINANCIAL_FACT Outbox 행도 있으므로(대역이 모두 ACK한다) 단언은 이 시험의 Payment·eventId로 한정한다.
 */
@PaymentApiTest
class FinancialFactRelayDbSpec(
    private val store: JpaFinancialFactRelayStore,
    private val cancel: CancelPaymentUseCase,
    private val van: ProgrammableVan,
    private val admission: ScriptedAdmissionPort,
    private val transactionManager: PlatformTransactionManager,
    private val backlog: FinancialFactRelayBacklogQuery,
    private val db: DbAccess,
    confirm: ConfirmPaymentUseCase,
    payments: PaymentRepository,
    controls: OrderPaymentControlRepository,
    sessions: AuthenticationSessionRepository,
    tokens: CheckoutTokenStore,
    checkoutSessions: CheckoutSessionStore,
) : BehaviorSpec({

    val confirmFixture = ConfirmFixture(payments, controls, sessions, tokens, checkoutSessions, transactionManager, db)
    val fixture = CancelFixture(confirmFixture, confirm, admission, controls, payments, transactionManager, van)
    val mapper = JsonMapper.builder().build()
    val stub = StubFinancialInbox()
    val clock = MutablePaymentClock(Instant.parse("2026-11-05T00:00:00Z"))
    val command = RelayFinancialFactsCommand(batchSize = 500, claimLease = Duration.ofSeconds(30), Duration.ofSeconds(1), Duration.ofMinutes(1), 0.0)

    afterSpec { stub.close() }

    fun service(s: StubFinancialInbox = stub, requestTimeout: Duration = Duration.ofSeconds(1), relayClock: PaymentClock = clock) =
        FinancialFactRelayService(transactionManager, store, HttpFinancialFactDispatcher(StubFinancialInbox.settings(s.endpoint, requestTimeout)), relayClock)

    fun advance(d: Duration) = clock.set(clock.now().plus(d))

    fun drain(s: FinancialFactRelayService = service()): List<FinancialFactRelayReport> {
        val reports = mutableListOf<FinancialFactRelayReport>()
        repeat(20) {
            val r = s.relayOnce(command)
            reports += r
            if (r.claimed == 0) return reports
        }
        return reports
    }

    fun rows(sql: String, vararg args: Any?): List<Map<String, Any?>> = db.withRoot { c -> with(db) { c.catalog = db.databaseName; c.rows(sql, *args) } }

    /** 이 Payment의 FINANCIAL_FACT Outbox 이벤트(id 순). */
    fun facts(paymentId: String): List<Map<String, Any?>> = rows(
        "SELECT id, event_id, event_type, effect_key FROM outbox_event WHERE event_category = 'FINANCIAL_FACT' AND JSON_UNQUOTE(JSON_EXTRACT(payload, '$.paymentId')) = ? ORDER BY id",
        paymentId,
    )

    fun relayRow(eventId: String): Map<String, Any?> = rows(
        "SELECT status, attempts, ack_outcome, last_failure_code, claim_owner, DATE_FORMAT(next_attempt_at, '%Y-%m-%dT%H:%i:%s.%fZ') AS next_at FROM financial_fact_relay WHERE event_id = ?",
        eventId,
    ).single()

    fun ackCount(eventId: String): Long = (rows("SELECT COUNT(*) AS n FROM financial_fact_relay_ack WHERE event_id = ?", eventId).single()["n"] as Number).toLong()

    /** Outbox 행 전체(모든 열) — Relay가 publish_* 밖의 Outbox 열을 바꾸지 않는다는 비교용. */
    fun outboxSnapshot(paymentId: String): List<Map<String, Any?>> = rows(
        "SELECT id, event_id, effect_key, event_type, event_category, CAST(payload AS CHAR) AS payload, publish_status, published_at, merchant_notification_status, " +
            "merchant_notified_at FROM outbox_event WHERE JSON_UNQUOTE(JSON_EXTRACT(payload, '$.paymentId')) = ? ORDER BY id",
        paymentId,
    )

    fun authorizedAndPartiallyCancelled(): Pair<String, List<Map<String, Any?>>> {
        val a = fixture.authorized()
        cancel.cancel(a.command(money = Money(3_000, a.money.currency))).shouldBeInstanceOf<CancelPaymentResult.Accepted>()
        return a.paymentId.value to facts(a.paymentId.value)
    }

    Given("FR-R1 an authorized and partially cancelled payment (official paths) relayed to FINANCIAL") {
        val (paymentId, events) = authorizedAndPartiallyCancelled()
        val before = outboxSnapshot(paymentId)
        drain()
        Then("both facts arrive once each, signed, in Outbox order with their effect keys; the relay rows are DELIVERED with the ACK outcome") {
            events.map { it["event_type"] } shouldBe listOf("PaymentAuthorized", "PaymentCancelled")
            events.forEach { e ->
                val eventId = e["event_id"] as String
                stub.receivedFor(eventId).size shouldBe 1
                stub.receivedFor(eventId).single().signatureValid shouldBe true
                stub.receivedFor(eventId).single().effectKey shouldBe e["effect_key"]
                relayRow(eventId)["status"] shouldBe "DELIVERED"
                relayRow(eventId)["ack_outcome"] shouldBe "APPLIED"
                relayRow(eventId)["claim_owner"] shouldBe null
                ackCount(eventId) shouldBe 1L
            }
            val order = stub.received.map { it.eventId }
            (order.indexOf(events[0]["event_id"]) < order.indexOf(events[1]["event_id"])) shouldBe true
        }
        Then("the payload sent is the Outbox payload; D-FFR-001: only publish_status·published_at change (PENDING → PUBLISHED at the ACK), every other Outbox column is untouched") {
            events.forEach { e ->
                val sent = mapper.readTree(stub.receivedFor(e["event_id"] as String).single().body)
                val stored = rows("SELECT CAST(payload AS CHAR) AS p FROM outbox_event WHERE id = ?", e["id"]).single()["p"] as String
                sent.get("payload") shouldBe mapper.readTree(stored)
                sent.get("eventCategory").asString() shouldBe "FINANCIAL_FACT"
            }
            val after = outboxSnapshot(paymentId)
            fun withoutPublish(r: List<Map<String, Any?>>) = r.map { it - "publish_status" - "published_at" }
            withoutPublish(after) shouldBe withoutPublish(before)
            before.map { it["publish_status"] to it["published_at"] }.toSet() shouldBe setOf("PENDING" to null)
            after.map { it["publish_status"] }.toSet() shouldBe setOf("PUBLISHED")
            after.all { it["published_at"] != null } shouldBe true
        }
        And("a further round sends nothing for them (DELIVERED is final)") {
            drain()
            events.forEach { stub.receivedFor(it["event_id"] as String).size shouldBe 1 }
        }
    }

    Given("FR-R2 FINANCIAL is temporarily unavailable (503) for the authorization fact") {
        val a = fixture.authorized()
        val eventId = facts(a.paymentId.value).single()["event_id"] as String
        stub.script(eventId, StubFinancialInbox.Behavior.Status(503))
        drain()
        Then("the row stays PENDING with one attempt, failure HTTP_503 and next attempt = now + 1s backoff; it is not resent before that") {
            relayRow(eventId)["status"] shouldBe "PENDING"
            relayRow(eventId)["attempts"] shouldBe 1
            relayRow(eventId)["last_failure_code"] shouldBe "HTTP_503"
            Instant.parse(relayRow(eventId)["next_at"] as String) shouldBe clock.now().plusSeconds(1)
            drain()
            stub.receivedFor(eventId).size shouldBe 1
        }
        And("after the backoff the same bytes are resent and delivered") {
            advance(Duration.ofSeconds(1))
            drain()
            stub.receivedFor(eventId).size shouldBe 2
            stub.receivedFor(eventId).map { String(it.body, Charsets.UTF_8) }.toSet().size shouldBe 1
            relayRow(eventId)["status"] shouldBe "DELIVERED"
            relayRow(eventId)["attempts"] shouldBe 2
        }
    }

    Given("FR-R3 FINANCIAL commits but the response is lost (stall past the request timeout)") {
        val a = fixture.authorized()
        val eventId = facts(a.paymentId.value).single()["event_id"] as String
        stub.script(eventId, StubFinancialInbox.Behavior.CommitThenStall(Duration.ofMillis(2_000)))
        drain()
        Then("PAYMENT does not mark it delivered (TIMEOUT, retry) although FINANCIAL already has it") {
            stub.isCommitted(eventId) shouldBe true
            relayRow(eventId)["status"] shouldBe "PENDING"
            relayRow(eventId)["last_failure_code"] shouldBe "TIMEOUT"
        }
        And("the resend converges: FINANCIAL answers DUPLICATE(original APPLIED), the row is DELIVERED with APPLIED, one fact on FINANCIAL") {
            advance(Duration.ofSeconds(1))
            drain()
            relayRow(eventId)["status"] shouldBe "DELIVERED"
            relayRow(eventId)["ack_outcome"] shouldBe "APPLIED"
            rows("SELECT outcome, original_outcome FROM financial_fact_relay_ack WHERE event_id = ?", eventId).single() shouldBe
                mapOf("outcome" to "DUPLICATE", "original_outcome" to "APPLIED")
            stub.receivedFor(eventId).map { String(it.body, Charsets.UTF_8) }.toSet().size shouldBe 1
        }
    }

    Given("FR-R4 FINANCIAL rejects the content (422 CONTENT_INVALID)") {
        val a = fixture.authorized()
        val eventId = facts(a.paymentId.value).single()["event_id"] as String
        stub.script(eventId, StubFinancialInbox.Behavior.Status(422, StubFinancialInbox.error("CONTENT_INVALID")))
        drain()
        Then("the row is QUARANTINED (not delivered, not resent even later) and the quarantine gauge counts it") {
            relayRow(eventId)["status"] shouldBe "QUARANTINED"
            relayRow(eventId)["last_failure_code"] shouldBe "HTTP_422_CONTENT_INVALID"
            advance(Duration.ofHours(1))
            drain()
            stub.receivedFor(eventId).size shouldBe 1
            (backlog.backlog().quarantinedRows >= 1) shouldBe true
        }
    }

    Given("FR-R5 an ACK that does not echo the sent eventId (wrong receiver/proxy) is not trusted") {
        val a = fixture.authorized()
        val eventId = facts(a.paymentId.value).single()["event_id"] as String
        stub.script(eventId, StubFinancialInbox.Behavior.CommitThenAck(StubFinancialInbox.ack(UUID.randomUUID().toString(), "x", "APPLIED", null)))
        drain()
        Then("the row stays PENDING with ACK_UNREADABLE and is later delivered by a proper DUPLICATE ACK") {
            relayRow(eventId)["status"] shouldBe "PENDING"
            relayRow(eventId)["last_failure_code"] shouldBe "ACK_UNREADABLE"
            advance(Duration.ofSeconds(1))
            drain()
            relayRow(eventId)["status"] shouldBe "DELIVERED"
        }
    }

    Given("FR-R6 two relay instances run the same round concurrently") {
        val events = (1..4).flatMap { facts(fixture.authorized().paymentId.value) }.map { it["event_id"] as String }
        val first = service()
        val second = service()
        val pool = Executors.newFixedThreadPool(2)
        val reports = try {
            val start = CountDownLatch(1)
            listOf(first, second).map { s -> pool.submit(Callable { start.await(); s.relayOnce(command) }) }.also { start.countDown() }.map { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        drain()
        Then("SKIP LOCKED claims are disjoint: every fact is sent exactly once and delivered") {
            events.forEach { e ->
                stub.receivedFor(e).size shouldBe 1
                relayRow(e)["status"] shouldBe "DELIVERED"
            }
            reports.sumOf { it.claimLost } shouldBe 0
        }
    }

    Given("FR-R7 a relay crashes after claiming (claim committed, never marked)") {
        val a = fixture.authorized()
        val eventId = facts(a.paymentId.value).single()["event_id"] as String
        val tx = TransactionTemplate(transactionManager).apply { isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED }
        tx.execute { store.discover(clock.now(), 500) }
        val crashed = tx.execute { store.claim("crashed-relay", clock.now(), clock.now().plusSeconds(30), 500) }
        Then("other instances do not send it while the lease holds") {
            crashed.any { it.eventId == eventId } shouldBe true
            drain()
            stub.receivedFor(eventId).size shouldBe 0
            relayRow(eventId)["claim_owner"] shouldBe "crashed-relay"
        }
        And("after the lease expires it is re-claimed and delivered; the crashed owner's late mark does not overwrite it") {
            advance(Duration.ofSeconds(31))
            drain()
            stub.receivedFor(eventId).size shouldBe 1
            relayRow(eventId)["status"] shouldBe "DELIVERED"
            val outboxId = crashed.first { it.eventId == eventId }.outboxId
            tx.execute { store.markRetry(outboxId, "crashed-relay", clock.now(), "TIMEOUT", clock.now()) } shouldBe FinancialFactRelayMark.ALREADY_FINAL
            relayRow(eventId)["status"] shouldBe "DELIVERED"
        }
    }

    Given("FR-R8b D-FFR-001: discovery looks only at undelivered (publish_status = PENDING) FINANCIAL_FACT rows") {
        val a = fixture.authorized()
        val eventId = facts(a.paymentId.value).single()["event_id"] as String
        drain()
        Then("a DELIVERED fact is PUBLISHED on the Outbox; a non-financial row of the same payment stays PENDING (no consumer)") {
            rows("SELECT publish_status FROM outbox_event WHERE event_id = ?", eventId).single()["publish_status"] shouldBe "PUBLISHED"
            rows("SELECT COUNT(*) AS n FROM outbox_event WHERE event_category = 'FINANCIAL_FACT' AND publish_status = 'PENDING' " +
                "AND id IN (SELECT outbox_id FROM financial_fact_relay WHERE status = 'DELIVERED')").single()["n"].let { (it as Number).toLong() } shouldBe 0L
        }
    }

    Given("FR-R8 non-financial Outbox events (PaymentRequested·PaymentAuthorizationFailed·…) and the backlog gauge") {
        Then("only FINANCIAL_FACT rows ever get a relay row") {
            rows(
                "SELECT COUNT(*) AS n FROM financial_fact_relay r JOIN outbox_event o ON o.id = r.outbox_id WHERE o.event_category <> 'FINANCIAL_FACT'",
            ).single()["n"].let { (it as Number).toLong() } shouldBe 0L
        }
        Then("after draining, nothing FINANCIAL_FACT is left undiscovered") {
            drain()
            backlog.backlog().undiscoveredRows shouldBe 0L
        }
    }

    Given("FR-R9 (review M-1) slow FINANCIAL: per-row delay × batch exceeds the claim lease while a second relay instance runs") {
        // 실제 시간으로 흐르는 시계(두 인스턴스 공유 — 고정 시계로는 lease 만료가 생기지 않는다). 기준은 앞 시나리오의 시각보다 뒤.
        val base = clock.now().plus(Duration.ofDays(1))
        val started = System.nanoTime()
        val realClock = object : PaymentClock {
            override fun now(): Instant = base.plusNanos(System.nanoTime() - started)
        }
        drain(service(relayClock = realClock))
        val events = (1..8).map { facts(fixture.authorized().paymentId.value).single()["event_id"] as String }
        events.forEach { stub.script(it, StubFinancialInbox.Behavior.Delay(Duration.ofMillis(600))) }
        val shortLease = RelayFinancialFactsCommand(batchSize = 500, claimLease = Duration.ofSeconds(4), Duration.ofSeconds(1), Duration.ofMinutes(1), 0.0)
        val first = service(requestTimeout = Duration.ofMillis(1_500), relayClock = realClock)
        val second = service(requestTimeout = Duration.ofMillis(1_500), relayClock = realClock)
        val pool = Executors.newFixedThreadPool(2)
        val reports = try {
            val a = pool.submit(Callable { first.relayOnce(shortLease) })
            Thread.sleep(4_300) // 첫 인스턴스의 lease(4초)가 끝난 뒤 — 수정 전에는 첫 인스턴스가 아직 8번째 행을 보내는 중이다(8 × 0.6초 = 4.8초).
            val b = pool.submit(Callable { second.relayOnce(shortLease) })
            listOf(a.get(60, TimeUnit.SECONDS), b.get(60, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
        for (round in 1..10) if (service(relayClock = realClock).relayOnce(command).claimed == 0) break
        Then("no fact is sent twice: a relay only dispatches while the remaining lease exceeds the send budget + 1s, and releases the rest") {
            events.forEach { e -> withClue(e) { stub.receivedFor(e).size shouldBe 1 } }
            events.forEach { e -> relayRow(e)["status"] shouldBe "DELIVERED" }
            (reports[0].deferred > 0) shouldBe true
        }
    }
})

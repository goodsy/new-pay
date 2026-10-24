package com.paycore.payment.payment.application

import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.adapter.persistence.JpaMerchantNotificationFanoutStore
import com.paycore.payment.payment.adapter.persistence.JpaMerchantWebhookDeliveryRepository
import com.paycore.payment.payment.adapter.persistence.JpaMerchantWebhookEventStore
import com.paycore.payment.payment.adapter.persistence.PersistenceTestData
import com.paycore.payment.payment.adapter.web.MutablePaymentClock
import com.paycore.payment.payment.adapter.web.PaymentApiTest
import com.paycore.payment.payment.application.provided.CancelPaymentResult
import com.paycore.payment.payment.application.provided.CancelPaymentUseCase
import com.paycore.payment.payment.application.provided.ConfirmPaymentUseCase
import com.paycore.payment.payment.application.provided.CreatePaymentResult
import com.paycore.payment.payment.application.provided.CreatePaymentUseCase
import com.paycore.payment.payment.application.provided.FanOutMerchantNotificationsCommand
import com.paycore.payment.payment.application.provided.MerchantNotificationFanoutReport
import com.paycore.payment.payment.application.provided.OperatorCorrectionCancelResult
import com.paycore.payment.payment.application.provided.OperatorCorrectionCancelUseCase
import com.paycore.payment.payment.application.required.AuthenticationSessionRepository
import com.paycore.payment.payment.application.required.CheckoutSessionStore
import com.paycore.payment.payment.application.required.CheckoutTokenStore
import com.paycore.payment.payment.application.required.MerchantWebhookEndpoint
import com.paycore.payment.payment.application.required.MerchantWebhookEndpointDirectory
import com.paycore.payment.payment.application.required.MerchantWebhookEventRecord
import com.paycore.payment.payment.application.required.MerchantNotificationFanoutStore
import com.paycore.payment.payment.application.required.MerchantWebhookEventStore
import com.paycore.payment.payment.application.required.OutboxNotificationCandidate
import com.paycore.payment.payment.application.required.OutboxNotificationMark
import com.paycore.payment.payment.application.required.OrderPaymentControlRepository
import com.paycore.payment.payment.application.required.PaymentCancelFailedV1Payload
import com.paycore.payment.payment.application.required.PaymentAuthorizedV1Payload
import com.paycore.payment.payment.application.required.PaymentExpiredV1Payload
import com.paycore.payment.payment.application.required.PaymentRepository
import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.MerchantAccountId
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.PaymentId
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.transaction.PlatformTransactionManager
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * fan-out 실제 MySQL 시험(WEBHOOK-001 §5.0 rev 0.2 — T-F1~T-F6·C-F1·C-F2). 업무 사실은 운영 경로(confirm → stub VAN 승인 → 취소)로 만든다. 운영 경로가 아직 만들 수 없는 P1·이상 조합만
 * 의도를 이름에 적은 **Invalid Fixture**(root 연결의 Outbox INSERT·Payment 상태 UPDATE)로 만든다. fan-out 서비스는 운영 Store·이벤트 Store·전달 Repository에 시험 endpoint 대역(MID별)만 바꿔 만든다.
 * 공유 컨테이너에는 다른 spec의 Outbox 행도 있으므로 단언은 이 시험의 Payment로 한정하고 fan-out은 남은 후보가 없을 때까지 돌린다([drain]).
 */
@PaymentApiTest
class MerchantNotificationFanoutDbSpec(
    private val store: JpaMerchantNotificationFanoutStore,
    private val eventStore: JpaMerchantWebhookEventStore,
    private val deliveries: JpaMerchantWebhookDeliveryRepository,
    private val createPayment: CreatePaymentUseCase,
    private val cancel: CancelPaymentUseCase,
    private val operator: OperatorCorrectionCancelUseCase,
    private val van: ProgrammableVan,
    private val admission: ScriptedAdmissionPort,
    private val clock: MutablePaymentClock,
    private val backlog: com.paycore.payment.payment.application.provided.MerchantNotificationBacklogQuery,
    private val transactionManager: PlatformTransactionManager,
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
    val retry = Duration.ofMinutes(1)
    val endpoints = ConcurrentHashMap<String, MerchantWebhookEndpoint>()
    val directory = object : MerchantWebhookEndpointDirectory {
        override fun find(environment: Environment, merchantAccountId: MerchantAccountId): MerchantWebhookEndpoint? =
            if (environment == Environment.TEST) endpoints[merchantAccountId.value] else null
    }

    fun service(events: MerchantWebhookEventStore = eventStore) =
        MerchantNotificationFanoutService(transactionManager, store, events, deliveries, directory, SecureRandomMerchantWebhookIdentifierGenerator(), clock)

    fun serviceWith(fanoutStore: MerchantNotificationFanoutStore, events: MerchantWebhookEventStore = eventStore) =
        MerchantNotificationFanoutService(transactionManager, fanoutStore, events, deliveries, directory, SecureRandomMerchantWebhookIdentifierGenerator(), clock)

    afterEach { clock.reset() }

    fun drain(s: MerchantNotificationFanoutService = service()): List<MerchantNotificationFanoutReport> {
        val reports = mutableListOf<MerchantNotificationFanoutReport>()
        repeat(50) {
            val r = s.fanOut(FanOutMerchantNotificationsCommand(500, retry))
            reports += r
            if (r.claimed == 0 || r.claimed == r.failed + r.deferred) return reports
        }
        return reports
    }

    fun rows(sql: String, vararg args: Any?): List<Map<String, Any?>> = db.withRoot { c -> with(db) { c.catalog = db.databaseName; c.rows(sql, *args) } }
    fun scalar(sql: String, vararg args: Any?): Any? = db.withRoot { c -> with(db) { c.catalog = db.databaseName; c.scalar(sql, *args) } }

    /** n-2: 가맹점 이벤트는 실제 Outbox 이벤트와 실제 Payment를 가리킨다(FK가 없으므로 — WH-23 — anti-join으로 확인). */
    fun orphanCounts(): Pair<Long, Long> = Pair(
        (scalar("SELECT COUNT(*) FROM merchant_webhook_event e LEFT JOIN outbox_event o ON o.event_id = e.source_event_id WHERE o.id IS NULL") as Number).toLong(),
        (scalar("SELECT COUNT(*) FROM merchant_webhook_event e LEFT JOIN payment p ON p.payment_id = e.payment_id WHERE p.payment_id IS NULL") as Number).toLong(),
    )


    fun outboxMarks(paymentId: String): List<Pair<String, String>> = rows(
        "SELECT event_type, merchant_notification_status FROM outbox_event WHERE JSON_UNQUOTE(JSON_EXTRACT(payload, '$.paymentId')) = ? ORDER BY id", paymentId,
    ).map { it["event_type"] as String to it["merchant_notification_status"] as String }

    fun webhookEvents(paymentId: String): List<Map<String, Any?>> = rows(
        "SELECT webhook_event_id, event_type, payment_sequence, delivery_plan, CAST(body AS CHAR) AS body FROM merchant_webhook_event WHERE payment_id = ? ORDER BY payment_sequence", paymentId,
    )

    /** Invalid Fixture: 운영 경로가 아직 만들지 않는 Outbox 이벤트를 root로 넣는다(P1·이상 조합 재현 전용). payload는 운영 직렬화기와 같은 형식. */
    fun insertOutbox(eventType: String, aggregateId: String, payload: String): String {
        val eventId = UUID.randomUUID().toString()
        db.withRoot { c ->
            c.catalog = db.databaseName
            c.prepareStatement(
                """INSERT INTO outbox_event (event_id, effect_key, event_type, schema_version, event_category, producer, aggregate_type, aggregate_id, aggregate_version,
                   occurred_at, correlation_id, causation_id, payload, publish_status, published_at, created_at)
                   VALUES (?, ?, ?, 1, 'INTEGRATION_NON_FINANCIAL', 'payment-api/payment', 'Fixture', ?, 0, UTC_TIMESTAMP(3), 'fixture', 'fixture', ?, 'PENDING', NULL, UTC_TIMESTAMP(3))""",
            ).use { ps ->
                ps.setString(1, eventId); ps.setString(2, "$eventType:fixture:$eventId"); ps.setString(3, eventType); ps.setString(4, aggregateId); ps.setString(5, payload)
                ps.executeUpdate() shouldBe 1
            }
        }
        return eventId
    }

    fun authorizedPayload(paymentId: String, mid: String, orderId: String) = invokeSerialize(
        PaymentAuthorizedV1Payload(
            Environment.TEST, mid, "demo-site-001", orderId, paymentId, "pau_fixture", "ptx_fixture", 10_000, "KRW", "LUMP_SUM", null, "CREDIT", "ISS", "ACQ", "12345678",
            "PTX", LocalDate.parse("2026-10-21"), null, Instant.parse("2026-10-21T00:00:00Z"), "pa", "ent",
        ),
    )

    fun expiredPayload(paymentId: String, mid: String, orderId: String) = invokeSerialize(
        PaymentExpiredV1Payload(
            Environment.TEST, mid, "demo-site-001", orderId, paymentId, 10_000, "KRW", "CARD", 1, "EXPIRED", "NOT_TRANSMITTED_AND_SESSION_DISCARDED",
            Instant.parse("2026-10-21T00:00:00Z"), "v1", Instant.parse("2026-10-21T00:00:00Z"),
        ),
    )

    fun requested(): Triple<PaymentId, String, String> {
        val order = PersistenceTestData.newOrder()
        val created = createPayment.create(CreatePaymentFixture.command(order)).shouldBeInstanceOf<CreatePaymentResult.Accepted>()
        return Triple(created.payment.paymentId, order.merchantAccountId.value, order.merchantOrderId.value)
    }

    Given("T-F4 an authorized payment, a merchant partial cancel and an operator cancel of the rest (official paths)") {
        val a = fixture.authorized()
        endpoints[a.mid.value] = MerchantWebhookEndpoint("ep-${a.mid.value}", "https://merchant.example.test/webhooks")
        val firstCommand = a.command(money = Money(3_000, a.money.currency))
        val first = cancel.cancel(firstCommand).shouldBeInstanceOf<CancelPaymentResult.Accepted>().view
        val revision = (scalar("SELECT revision FROM return_balance WHERE payment_id = ?", a.paymentId.value) as Number).toLong()
        val op = operator.cancel(a.operatorCommand(money = Money(7_000, a.money.currency), fingerprintRevision = revision))
            .shouldBeInstanceOf<OperatorCorrectionCancelResult.Accepted>()
        drain()

        // ConfirmFixture는 Payment를 CreatePayment Use Case 없이 준비하므로(PaymentRequested 없음) Outbox는 승인·취소 이벤트뿐이다.
        Then("the three results are notified in causal order with sequence 1, 2, 3") {
            outboxMarks(a.paymentId.value) shouldBe listOf(
                "PaymentAuthorized" to "NOTIFIED", "PaymentCancelled" to "NOTIFIED", "PaymentCancelled" to "NOTIFIED",
            )
            webhookEvents(a.paymentId.value).map { it["event_type"] to it["payment_sequence"] } shouldBe listOf(
                "payment.authorized" to 1, "payment.cancelled" to 2, "payment.cancelled" to 3,
            )
        }
        Then("bodies carry GET values: PayCore cancel transaction id (n-5), merchant cancel id only for the merchant cancel, initiator OPERATOR for the operator one (D-WH-001)") {
            val bodies = webhookEvents(a.paymentId.value).map { mapper.readTree(it["body"] as String) }
            val merchantCancel = bodies[1].get("cancellation")
            merchantCancel.get("initiator").stringValue() shouldBe "MERCHANT"
            merchantCancel.get("merchantCancelId").stringValue() shouldBe firstCommand.merchantCancelId.value
            merchantCancel.get("cancelTransactionId").stringValue() shouldBe
                scalar("SELECT transaction_id FROM payment_transaction WHERE cancellation_id = ?", first.cancellationId.value)
            val operatorCancel = bodies[2].get("cancellation")
            operatorCancel.get("initiator").stringValue() shouldBe "OPERATOR"
            operatorCancel.has("merchantCancelId") shouldBe false
            operatorCancel.get("cancellationId").stringValue() shouldBe op.view.cancellationId.value
            bodies[2].get("returnSummary").get("returnStatus").stringValue() shouldBe "FULLY_CANCELLED"
            bodies.forEach { it.get("payment").get("status").stringValue() shouldBe "AUTHORIZED" }
        }
        Then("n-2: no merchant event points to a missing Outbox event or payment") {
            orphanCounts() shouldBe (0L to 0L)
        }
        Then("each event has one PENDING delivery due now (TEST endpoint)") {
            rows(
                "SELECT d.status, d.attempt_count FROM merchant_webhook_delivery d JOIN merchant_webhook_event e ON e.webhook_event_id = d.webhook_event_id WHERE e.payment_id = ?",
                a.paymentId.value,
            ).map { it["status"] to it["attempt_count"] } shouldBe List(3) { "PENDING" to 0 }
        }
    }

    Given("T-F3 P1: an approval and its cancellations on a payment known to the merchant as FAILED (Invalid Fixture: status flipped by root)") {
        val a = fixture.authorized()
        endpoints[a.mid.value] = MerchantWebhookEndpoint("ep-${a.mid.value}", "https://merchant.example.test/webhooks")
        val op = operator.cancel(a.operatorCommand(money = Money(2_000, a.money.currency))).shouldBeInstanceOf<OperatorCorrectionCancelResult.Accepted>()
        insertOutbox(
            "PaymentCancelFailed", op.view.cancellationId.value,
            invokeSerialize(
                PaymentCancelFailedV1Payload(Environment.TEST, a.mid.value, a.prepared.order.merchantOrderId.value, a.paymentId.value, op.view.cancellationId.value, 2_000, "KRW",
                    "INSTITUTION_CONFIRMED_DECLINE", "DECLINED", Instant.parse("2026-10-21T00:00:00Z")),
            ),
        )
        db.withRoot { c -> c.catalog = db.databaseName; c.prepareStatement("UPDATE payment SET status = 'FAILED' WHERE payment_id = ?").use { it.setString(1, a.paymentId.value); it.executeUpdate() } }
        drain()

        Then("the late approval, the operator cancel and the operator cancel failure are SUPPRESSED and no merchant event exists for the payment") {
            outboxMarks(a.paymentId.value) shouldBe listOf(
                "PaymentAuthorized" to "SUPPRESSED", "PaymentCancelled" to "SUPPRESSED", "PaymentCancelFailed" to "SUPPRESSED",
            )
            webhookEvents(a.paymentId.value) shouldBe emptyList()
        }
    }

    Given("T-F5 an impossible combination (Invalid Fixture: PaymentAuthorized for a REQUESTED payment) followed by a later row of the same payment") {
        val (pid, mid, orderId) = requested()
        val other = requested()
        insertOutbox("PaymentAuthorized", "pau_fixture", authorizedPayload(pid.value, mid, orderId))
        insertOutbox("PaymentExpired", pid.value, expiredPayload(pid.value, mid, orderId))
        val start = Instant.parse("2026-10-21T02:00:00Z")
        clock.set(start)
        drain()

        Then("the anomalous row is FANOUT_FAILED with a retry time (never SUPPRESSED), the later row stays PENDING, other payments are processed") {
            outboxMarks(pid.value) shouldBe listOf("PaymentRequested" to "NOT_APPLICABLE", "PaymentAuthorized" to "FANOUT_FAILED", "PaymentExpired" to "PENDING")
            rows(
                "SELECT f.failures, f.failure_code, DATE_FORMAT(f.retry_at, '%Y-%m-%dT%H:%i:%sZ') AS retry FROM merchant_notification_failure f JOIN outbox_event o ON o.id = f.outbox_id " +
                    "WHERE o.event_type = 'PaymentAuthorized' AND JSON_UNQUOTE(JSON_EXTRACT(o.payload, '$.paymentId')) = ?", pid.value,
            ).single() shouldBe mapOf("failures" to 1, "failure_code" to "UNEXPECTED_COMBINATION", "retry" to "2026-10-21T02:01:00Z")
            outboxMarks(other.first.value) shouldBe listOf("PaymentRequested" to "NOT_APPLICABLE")
            webhookEvents(pid.value) shouldBe emptyList()
        }
        Then("before the retry time the failed row and the deferred row are not picked; after it the failed row is retried (failures 2) and still blocks the later row") {
            clock.set(start.plusSeconds(30))
            drain()
            outboxMarks(pid.value)[2] shouldBe ("PaymentExpired" to "PENDING")
            scalar("SELECT MAX(failures) FROM merchant_notification_failure f JOIN outbox_event o ON o.id = f.outbox_id WHERE JSON_UNQUOTE(JSON_EXTRACT(o.payload, '$.paymentId')) = ?", pid.value) shouldBe 1
            clock.set(start.plusSeconds(61))
            drain()
            scalar("SELECT MAX(failures) FROM merchant_notification_failure f JOIN outbox_event o ON o.id = f.outbox_id WHERE JSON_UNQUOTE(JSON_EXTRACT(o.payload, '$.paymentId')) = ?", pid.value) shouldBe 2
            outboxMarks(pid.value)[2] shouldBe ("PaymentExpired" to "PENDING")
            // n-3: 경보 gauge 원천 — 비종결 실패 행이 보이고 최고 연령은 첫 실패(02:00) 이후 경과 이상이다. 보류된 뒤 행은 PENDING 적체로 보인다.
            val b = backlog.backlog()
            (b.failedRows >= 1) shouldBe true
            (b.oldestFailedAgeSeconds >= 61) shouldBe true
            (b.pendingRows >= 1) shouldBe true
        }
    }

    Given("T-F1 a store defect after the event INSERT inside the row TX") {
        val (pid, mid, orderId) = requested()
        db.withRoot { c -> c.catalog = db.databaseName; c.prepareStatement("UPDATE payment SET status = 'EXPIRED' WHERE payment_id = ?").use { it.setString(1, pid.value); it.executeUpdate() } }
        insertOutbox("PaymentExpired", pid.value, expiredPayload(pid.value, mid, orderId))
        val failing = object : MerchantWebhookEventStore {
            override fun append(record: MerchantWebhookEventRecord) {
                eventStore.append(record)
                error("injected defect after the event insert")
            }
        }
        val start = Instant.parse("2026-10-21T03:00:00Z")
        clock.set(start)
        drain(service(failing))

        Then("the whole row TX rolled back: no event, no sequence, the row is FANOUT_FAILED (DEFECT)") {
            webhookEvents(pid.value) shouldBe emptyList()
            scalar("SELECT COUNT(*) FROM merchant_webhook_stream WHERE payment_id = ?", pid.value).let { (it as Number).toLong() } shouldBe 0
            outboxMarks(pid.value).last() shouldBe ("PaymentExpired" to "FANOUT_FAILED")
        }
        Then("after the retry time the healthy store notifies it exactly once with sequence 1") {
            clock.set(start.plusSeconds(61))
            drain()
            webhookEvents(pid.value).map { it["event_type"] to it["payment_sequence"] } shouldBe listOf("payment.expired" to 1)
            outboxMarks(pid.value).last() shouldBe ("PaymentExpired" to "NOTIFIED")
            drain()
            webhookEvents(pid.value).size shouldBe 1
        }
    }

    Given("T-F6 the fan-out changes only the two notification columns of an Outbox row (m-7, D-WH-009)") {
        val a = fixture.authorized()
        val cols = "id, event_id, effect_key, event_type, schema_version, event_category, producer, aggregate_type, aggregate_id, aggregate_version, " +
            "DATE_FORMAT(occurred_at, '%Y-%m-%dT%H:%i:%s.%f') AS occurred, correlation_id, causation_id, CAST(payload AS CHAR) AS payload, publish_status, published_at, " +
            "DATE_FORMAT(created_at, '%Y-%m-%dT%H:%i:%s.%f') AS created"
        val sql = "SELECT $cols FROM outbox_event WHERE JSON_UNQUOTE(JSON_EXTRACT(payload, '$.paymentId')) = ? ORDER BY id"
        val before = rows(sql, a.paymentId.value)
        drain()
        Then("every other column is byte-identical, publish_status stays PENDING (FINANCIAL delivery mark untouched)") {
            rows(sql, a.paymentId.value) shouldBe before
            before.map { it["publish_status"] }.toSet() shouldBe setOf("PENDING")
            outboxMarks(a.paymentId.value).map { it.second }.toSet() shouldBe setOf("NOTIFIED")
        }
    }

    Given("C-F1 four fan-out workers at once over many payments with several events each (real MySQL, multiple connections)") {
        val subjects = (1..30).map { requested() }
        subjects.forEach { (pid, _, _) ->
            db.withRoot { c -> c.catalog = db.databaseName; c.prepareStatement("UPDATE payment SET status = 'EXPIRED' WHERE payment_id = ?").use { it.setString(1, pid.value); it.executeUpdate() } }
        }
        // 같은 Payment에 이벤트 3개(Invalid Fixture — 실제로는 만료 하나지만 순번 경합을 보려고 여러 개).
        repeat(3) { subjects.forEach { (pid, mid, orderId) -> insertOutbox("PaymentExpired", pid.value, expiredPayload(pid.value, mid, orderId)) } }
        val pool = Executors.newFixedThreadPool(4)
        val go = CountDownLatch(1)
        val futures = (1..4).map { pool.submit<List<MerchantNotificationFanoutReport>> { go.await(); drain() } }
        go.countDown()
        val reports = futures.flatMap { it.get(120, TimeUnit.SECONDS) }
        pool.shutdown()

        Then("every row is notified exactly once and each payment's sequence is 1, 2, 3 in Outbox id order (guard serialization)") {
            subjects.forEach { (pid, _, _) ->
                outboxMarks(pid.value).filter { it.first == "PaymentExpired" }.map { it.second } shouldBe List(3) { "NOTIFIED" }
                val ordered = rows(
                    "SELECT e.payment_sequence FROM merchant_webhook_event e JOIN outbox_event o ON o.event_id = e.source_event_id WHERE e.payment_id = ? ORDER BY o.id", pid.value,
                ).map { it["payment_sequence"] }
                ordered shouldBe listOf(1, 2, 3)
            }
        }
        Then("m-1: no worker recorded a failure - the races were absorbed without FANOUT_FAILED (no failure rows for these payments)") {
            reports.sumOf { it.failed } shouldBe 0
            subjects.sumOf { (pid, _, _) ->
                (scalar(
                    "SELECT COUNT(*) FROM merchant_notification_failure f JOIN outbox_event o ON o.id = f.outbox_id WHERE JSON_UNQUOTE(JSON_EXTRACT(o.payload, '$.paymentId')) = ?", pid.value,
                ) as Number).toLong()
            } shouldBe 0L
            orphanCounts() shouldBe (0L to 0L)
        }
    }

    Given("H-1: an instance read its candidates, then another instance failed an earlier row of the same payment before the first resumed") {
        val (pid, mid, orderId) = requested()
        db.withRoot { c -> c.catalog = db.databaseName; c.prepareStatement("UPDATE payment SET status = 'EXPIRED' WHERE payment_id = ?").use { it.setString(1, pid.value); it.executeUpdate() } }
        drain()
        // Invalid Fixture: 취소 행이 없는 PaymentCancelFailed(→ CANCELLATION_ROW_MISSING 실패) 다음에 정상 PaymentExpired.
        insertOutbox(
            "PaymentCancelFailed", "pcn_missing",
            invokeSerialize(
                PaymentCancelFailedV1Payload(Environment.TEST, mid, orderId, pid.value, "pcn_missing_" + UUID.randomUUID().toString().take(8), 1_000, "KRW",
                    "INSTITUTION_CONFIRMED_DECLINE", "DECLINED", Instant.parse("2026-10-21T00:00:00Z")),
            ),
        )
        insertOutbox("PaymentExpired", pid.value, expiredPayload(pid.value, mid, orderId))
        clock.set(Instant.parse("2026-10-21T04:00:00Z"))
        val read = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val pausing = object : MerchantNotificationFanoutStore by store {
            override fun candidates(now: Instant, limit: Int): List<OutboxNotificationCandidate> {
                val c = store.candidates(now, limit)
                read.countDown()
                resume.await(60, TimeUnit.SECONDS)
                return c
            }
        }
        val pool = Executors.newSingleThreadExecutor()
        val b = pool.submit<MerchantNotificationFanoutReport> { serviceWith(pausing).fanOut(FanOutMerchantNotificationsCommand(500, retry)) }
        read.await(60, TimeUnit.SECONDS) shouldBe true
        // 다른 인스턴스(A)가 앞 행을 실패로 커밋한다(뒤 행은 A의 회차에서 보류).
        drain()
        val afterA = outboxMarks(pid.value).takeLast(2)
        resume.countDown()
        b.get(60, TimeUnit.SECONDS)
        pool.shutdown()

        Then("the resumed instance defers the later row: it stays PENDING and no merchant event exists (sequence = causal order kept)") {
            afterA shouldBe listOf("PaymentCancelFailed" to "FANOUT_FAILED", "PaymentExpired" to "PENDING")
            outboxMarks(pid.value).takeLast(2) shouldBe listOf("PaymentCancelFailed" to "FANOUT_FAILED", "PaymentExpired" to "PENDING")
            webhookEvents(pid.value) shouldBe emptyList()
        }
    }

    Given("m-2: a fan-out row TX held open right after it marked the Outbox row (row X lock + secondary index updated)") {
        val (pid, mid, orderId) = requested()
        db.withRoot { c -> c.catalog = db.databaseName; c.prepareStatement("UPDATE payment SET status = 'EXPIRED' WHERE payment_id = ?").use { it.setString(1, pid.value); it.executeUpdate() } }
        insertOutbox("PaymentExpired", pid.value, expiredPayload(pid.value, mid, orderId))
        val target = (scalar(
            "SELECT id FROM outbox_event WHERE event_type = 'PaymentExpired' AND JSON_UNQUOTE(JSON_EXTRACT(payload, '$.paymentId')) = ?", pid.value,
        ) as Number).toLong()
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holdAfterMark = object : MerchantNotificationFanoutStore by store {
            override fun mark(outboxId: Long, mark: OutboxNotificationMark, at: Instant) {
                store.mark(outboxId, mark, at)
                if (outboxId == target) {
                    holding.countDown()
                    release.await(60, TimeUnit.SECONDS)
                }
            }
        }
        val pool = Executors.newSingleThreadExecutor()
        val held = pool.submit<Unit> { drain(serviceWith(holdAfterMark)) }
        holding.await(60, TimeUnit.SECONDS) shouldBe true

        Then("a business Outbox INSERT and a business payment FOR UPDATE commit without waiting") {
            val t0 = System.nanoTime()
            requested()
            db.withApp { c ->
                c.autoCommit = false
                c.createStatement().use { st -> st.executeQuery("SELECT payment_id FROM payment WHERE payment_id = '${pid.value}' FOR UPDATE").use { it.next() } }
                c.rollback()
            }
            val elapsedMs = (System.nanoTime() - t0) / 1_000_000
            val waits = (scalar("SELECT COUNT(*) FROM performance_schema.data_lock_waits") as Number).toLong()
            release.countDown()
            held.get(60, TimeUnit.SECONDS)
            pool.shutdown()
            waits shouldBe 0
            (elapsedMs < 5_000) shouldBe true
            outboxMarks(pid.value).last() shouldBe ("PaymentExpired" to "NOTIFIED")
        }
    }

    Given("C-F2 a fan-out row TX held open after its writes (H-3)") {
        val (pid, mid, orderId) = requested()
        db.withRoot { c -> c.catalog = db.databaseName; c.prepareStatement("UPDATE payment SET status = 'EXPIRED' WHERE payment_id = ?").use { it.setString(1, pid.value); it.executeUpdate() } }
        insertOutbox("PaymentExpired", pid.value, expiredPayload(pid.value, mid, orderId))
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocking = object : MerchantWebhookEventStore {
            override fun append(record: MerchantWebhookEventRecord) {
                eventStore.append(record)
                if (record.envelope.paymentId == pid) {
                    holding.countDown()
                    release.await(60, TimeUnit.SECONDS)
                }
            }
        }
        val pool = Executors.newSingleThreadExecutor()
        val held = pool.submit<Unit> { drain(service(blocking)) }
        holding.await(60, TimeUnit.SECONDS) shouldBe true

        Then("a business TX inserting Outbox rows and a business TX locking the same payment row commit without waiting") {
            val t0 = System.nanoTime()
            requested() // CreatePayment: Outbox INSERT
            db.withApp { c ->
                c.autoCommit = false
                c.createStatement().use { st -> st.executeQuery("SELECT payment_id FROM payment WHERE payment_id = '${pid.value}' FOR UPDATE").use { it.next() } }
                c.rollback()
            }
            val elapsedMs = (System.nanoTime() - t0) / 1_000_000
            val waits = scalar("SELECT COUNT(*) FROM performance_schema.data_lock_waits").let { (it as Number).toLong() }
            release.countDown()
            held.get(60, TimeUnit.SECONDS)
            pool.shutdown()
            waits shouldBe 0
            (elapsedMs < 5_000) shouldBe true
            outboxMarks(pid.value).last() shouldBe ("PaymentExpired" to "NOTIFIED")
        }
    }
})

/** 운영 직렬화기(`OutboxPayloadJson` — 같은 모듈 internal)로 Invalid Fixture payload를 만든다(운영과 같은 바이트 형식). */
private fun invokeSerialize(payload: com.paycore.payment.payment.application.required.OutboxPayload): String =
    com.paycore.payment.payment.adapter.persistence.OutboxPayloadJson.serialize(payload)

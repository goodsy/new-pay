package com.paycore.payment.payment.application

import com.paycore.payment.payment.adapter.integration.HmacMerchantWebhookSigner
import com.paycore.payment.payment.adapter.integration.HttpMerchantWebhookSender
import com.paycore.payment.payment.adapter.integration.MerchantWebhookTransportSettings
import com.paycore.payment.payment.adapter.integration.ProgrammableMerchantReceiver
import com.paycore.payment.payment.adapter.integration.ProgrammableMerchantReceiver.Behaviour
import com.paycore.payment.payment.adapter.integration.SystemWebhookHostResolver
import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.adapter.persistence.JpaMerchantNotificationFanoutStore
import com.paycore.payment.payment.adapter.persistence.JpaMerchantWebhookAttemptStore
import com.paycore.payment.payment.adapter.persistence.JpaMerchantWebhookDeliveryRepository
import com.paycore.payment.payment.adapter.persistence.JpaMerchantWebhookEventStore
import com.paycore.payment.payment.adapter.persistence.PersistenceTestData
import com.paycore.payment.payment.adapter.web.MutablePaymentClock
import com.paycore.payment.payment.adapter.web.PaymentApiTest
import com.paycore.payment.payment.application.provided.CreatePaymentResult
import com.paycore.payment.payment.application.provided.CreatePaymentUseCase
import com.paycore.payment.payment.application.provided.DeliverMerchantWebhooksCommand
import com.paycore.payment.payment.application.provided.FanOutMerchantNotificationsCommand
import com.paycore.payment.payment.application.required.DeliveryAttemptResult
import com.paycore.payment.payment.application.required.DeliveryRecordOutcome
import com.paycore.payment.payment.application.required.MerchantWebhookDeliveryTimings
import com.paycore.payment.payment.application.required.MerchantWebhookEndpoint
import com.paycore.payment.payment.application.required.MerchantWebhookEndpointDirectory
import com.paycore.payment.payment.application.required.MerchantWebhookSigningKeys
import com.paycore.payment.payment.application.required.PaymentExpiredV1Payload
import com.paycore.payment.payment.application.required.WebhookSigningKey
import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.MerchantAccountId
import com.paycore.payment.payment.domain.MerchantWebhookDeliveryId
import com.paycore.payment.payment.domain.WebhookRetrySchedule
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * delivery 실제 MySQL·실제 HTTP 시험(WEBHOOK-001 §5.0 delivery·§5.5~§5.8 — T-D1~T-D3·C-D1, m-2·n-1·n-4, D-WH-006). 가맹점 이벤트·전달 행은 운영 fan-out으로 만들고(주체만 Invalid Fixture:
 * Payment 상태 EXPIRED + PaymentExpired Outbox 행), 전송은 운영 adapter(HMAC 서명·목적지 검사·HttpClient 5)가 loopback 시험 수신기(TEST allowlist)로 한다. Demo 일정: 10s 시작, 상한 3회.
 */
@PaymentApiTest
class MerchantWebhookDeliveryDbSpec(
    private val fanoutStore: JpaMerchantNotificationFanoutStore,
    private val eventStore: JpaMerchantWebhookEventStore,
    private val deliveries: JpaMerchantWebhookDeliveryRepository,
    private val attempts: JpaMerchantWebhookAttemptStore,
    private val createPayment: CreatePaymentUseCase,
    private val clock: MutablePaymentClock,
    private val transactionManager: PlatformTransactionManager,
    private val db: DbAccess,
) : BehaviorSpec({

    val receiver = ProgrammableMerchantReceiver.http()
    afterSpec { receiver.stop() }
    afterEach {
        receiver.reset()
        clock.reset()
    }

    val secret = "whsec_dGVzdC1vbmx5LWRlbGl2ZXJ5LXNpZ25pbmcta2V5LTE"
    val endpoints = ConcurrentHashMap<String, MerchantWebhookEndpoint>()
    val directory = object : MerchantWebhookEndpointDirectory {
        override fun find(environment: Environment, merchantAccountId: MerchantAccountId) = if (environment == Environment.TEST) endpoints[merchantAccountId.value] else null
    }
    val keys = object : MerchantWebhookSigningKeys {
        override fun active(environment: Environment, merchantAccountId: MerchantAccountId) =
            if (environment == Environment.TEST) listOf(WebhookSigningKey("whk_t_db", secret.toByteArray())) else emptyList()
    }
    val transport = MerchantWebhookTransportSettings(
        connectTimeout = Duration.ofSeconds(1), responseTimeout = Duration.ofSeconds(1), requestDeadline = Duration.ofSeconds(2), dnsTimeout = Duration.ofSeconds(1),
        maxResponseBytes = 4_096, testAllowlist = listOf("127.0.0.1:${receiver.port}"), testTrustPem = null,
    )
    val sender = HttpMerchantWebhookSender(transport, SystemWebhookHostResolver(), null)
    fun timings(maxAttempts: Int = 3, lease: Duration = Duration.ofSeconds(60), budget: Duration = Duration.ofSeconds(8)) = object : MerchantWebhookDeliveryTimings {
        override val schedule = WebhookRetrySchedule("webhook-retry-demo-1", Duration.ofSeconds(10), Duration.ofHours(1), 0.0, maxAttempts, Duration.ofHours(24))
        override val claimLease: Duration = lease
        override val attemptBudget: Duration = budget
    }
    fun delivery(
        maxAttempts: Int = 3,
        t: MerchantWebhookDeliveryTimings = timings(maxAttempts),
        c: com.paycore.payment.payment.application.required.PaymentClock = clock,
        s: com.paycore.payment.payment.application.required.MerchantWebhookSender = sender,
    ) = MerchantWebhookDeliveryService(transactionManager, deliveries, attempts, eventStore, directory, keys, HmacMerchantWebhookSigner(), s, t, c)
    val fanout = MerchantNotificationFanoutService(transactionManager, fanoutStore, eventStore, deliveries, directory, SecureRandomMerchantWebhookIdentifierGenerator(), clock)

    fun rows(sql: String, vararg args: Any?): List<Map<String, Any?>> = db.withRoot { c -> with(db) { c.catalog = db.databaseName; c.rows(sql, *args) } }
    fun scalar(sql: String, vararg args: Any?): Any? = db.withRoot { c -> with(db) { c.catalog = db.databaseName; c.scalar(sql, *args) } }

    /** 이 시험의 TEST Payment 하나에 대한 가맹점 이벤트·전달 행(운영 fan-out). 다른 spec의 PENDING 전달 행과 섞이지 않게 claim은 [deliverUntilIdle]이 이 MID 행만 확인한다. */
    fun scheduledDelivery(): Pair<String, String> {
        val order = PersistenceTestData.newOrder()
        val created = createPayment.create(CreatePaymentFixture.command(order)).shouldBeInstanceOf<CreatePaymentResult.Accepted>()
        val pid = created.payment.paymentId.value
        db.withRoot { c -> c.catalog = db.databaseName; c.prepareStatement("UPDATE payment SET status = 'EXPIRED' WHERE payment_id = ?").use { it.setString(1, pid); it.executeUpdate() } }
        val payload = com.paycore.payment.payment.adapter.persistence.OutboxPayloadJson.serialize(
            PaymentExpiredV1Payload(
                Environment.TEST, order.merchantAccountId.value, "demo-site-001", order.merchantOrderId.value, pid, 10_000, "KRW", "CARD", 1, "EXPIRED",
                "NOT_TRANSMITTED_AND_SESSION_DISCARDED", Instant.parse("2026-10-22T00:00:00Z"), "v1", Instant.parse("2026-10-22T00:00:00Z"),
            ),
        )
        db.withRoot { c ->
            c.catalog = db.databaseName
            c.prepareStatement(
                """INSERT INTO outbox_event (event_id, effect_key, event_type, schema_version, event_category, producer, aggregate_type, aggregate_id, aggregate_version,
                   occurred_at, correlation_id, causation_id, payload, publish_status, published_at, created_at)
                   VALUES (?, ?, 'PaymentExpired', 1, 'INTEGRATION_NON_FINANCIAL', 'payment-api/payment', 'Payment', ?, 1, UTC_TIMESTAMP(3), 'fixture', 'fixture', ?, 'PENDING', NULL, UTC_TIMESTAMP(3))""",
            ).use { ps ->
                val e = UUID.randomUUID().toString()
                ps.setString(1, e); ps.setString(2, "PaymentExpired:fixture:$e"); ps.setString(3, pid); ps.setString(4, payload); ps.executeUpdate()
            }
        }
        endpoints[order.merchantAccountId.value] = MerchantWebhookEndpoint("ep-db", "http://127.0.0.1:${receiver.port}/webhooks")
        repeat(20) { if (fanout.fanOut(FanOutMerchantNotificationsCommand(500, Duration.ofMinutes(1))).claimed == 0) return@repeat }
        val row = rows(
            "SELECT d.delivery_id, e.webhook_event_id FROM merchant_webhook_delivery d JOIN merchant_webhook_event e ON e.webhook_event_id = d.webhook_event_id WHERE e.payment_id = ?", pid,
        ).single()
        return row["delivery_id"] as String to row["webhook_event_id"] as String
    }

    fun deliveryRow(id: String): Map<String, Any?> = rows(
        "SELECT status, attempt_count, last_failure_code, DATE_FORMAT(next_attempt_at, '%Y-%m-%dT%H:%i:%sZ') AS next_at, claim_owner FROM merchant_webhook_delivery WHERE delivery_id = ?", id,
    ).single()

    fun attemptRows(id: String): List<Pair<Int, String>> =
        rows("SELECT attempt_no, outcome FROM merchant_webhook_attempt WHERE delivery_id = ? ORDER BY attempt_no", id).map { (it["attempt_no"] as Number).toInt() to it["outcome"] as String }

    fun drainDeliveries(s: MerchantWebhookDeliveryService = delivery()) {
        repeat(20) { if (s.deliver(DeliverMerchantWebhooksCommand(200)).claimed == 0) return }
    }

    fun hmac(t: String, body: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
        mac.update("$t.".toByteArray(Charsets.US_ASCII))
        return HexFormat.of().formatHex(mac.doFinal(body))
    }

    Given("T-D1 a merchant answering 200") {
        val t0 = Instant.parse("2026-10-22T05:00:00Z")
        clock.set(t0)
        val (deliveryId, eventId) = scheduledDelivery()
        drainDeliveries()

        Then("the delivery is DELIVERED with one SUCCESS attempt and the receiver got the stored bytes with a verifiable signature") {
            deliveryRow(deliveryId)["status"] shouldBe "DELIVERED"
            attemptRows(deliveryId) shouldBe listOf(1 to "SUCCESS")
            val r = receiver.received.single { it.headers["paycore-event-id"] == eventId }
            val stored = scalar("SELECT body FROM merchant_webhook_event WHERE webhook_event_id = ?", eventId) as ByteArray
            r.body.contentEquals(stored) shouldBe true
            val sig = r.headers.getValue("paycore-signature")
            val t = sig.substringAfter("t=").substringBefore(",")
            t shouldBe t0.epochSecond.toString()
            sig shouldBe "t=$t,kid=whk_t_db,v1=${hmac(t, r.body)}"
            r.headers["paycore-delivery-attempt"] shouldBe "1"
        }
    }

    Given("T-D1 retries and give-up (D-WH-006)") {
        val t0 = Instant.parse("2026-10-22T06:00:00Z")
        clock.set(t0)
        val (deliveryId, _) = scheduledDelivery()
        receiver.respond { Behaviour.Status(500) }
        drainDeliveries()

        Then("a 500 schedules the next attempt 10s later and records HTTP_500") {
            deliveryRow(deliveryId).let { it["status"] to it["next_at"] } shouldBe ("PENDING" to "2026-10-22T06:00:10Z")
            attemptRows(deliveryId) shouldBe listOf(1 to "HTTP_500")
        }
        Then("nothing is sent before the next attempt time; then a 404 and a final 500 give up (GAVE_UP is not delivered)") {
            clock.set(t0.plusSeconds(5))
            drainDeliveries()
            attemptRows(deliveryId).size shouldBe 1
            receiver.respond { Behaviour.Status(404) }
            clock.set(t0.plusSeconds(10))
            drainDeliveries()
            receiver.respond { Behaviour.Status(500) }
            clock.set(t0.plusSeconds(60))
            drainDeliveries()
            attemptRows(deliveryId) shouldBe listOf(1 to "HTTP_500", 2 to "HTTP_404", 3 to "HTTP_500")
            deliveryRow(deliveryId).let { it["status"] to it["last_failure_code"] } shouldBe ("GAVE_UP" to "HTTP_500")
        }
    }

    Given("T-D2 the merchant stores the request but the response is lost") {
        val t0 = Instant.parse("2026-10-22T07:00:00Z")
        clock.set(t0)
        val (deliveryId, eventId) = scheduledDelivery()
        receiver.respond { Behaviour.DropAfterReceive }
        drainDeliveries()
        receiver.respond { Behaviour.Status(200) }
        clock.set(t0.plusSeconds(10))
        drainDeliveries()

        Then("the resend carries the same event id and the same body bytes; only t and the signature differ (merchant de-duplicates by eventId)") {
            val got = receiver.received.filter { it.headers["paycore-event-id"] == eventId }
            got.size shouldBe 2
            got[0].body.contentEquals(got[1].body) shouldBe true
            got.map { it.headers["paycore-delivery-attempt"] } shouldBe listOf("1", "2")
            (got[0].headers["paycore-signature"] == got[1].headers["paycore-signature"]) shouldBe false
            attemptRows(deliveryId) shouldBe listOf(1 to "CONNECTION", 2 to "SUCCESS")
            deliveryRow(deliveryId)["status"] shouldBe "DELIVERED"
        }
    }

    Given("T-D3 a worker loses its lease while its attempt is still in flight (m-2, n-1)") {
        val t0 = Instant.parse("2026-10-22T08:00:00Z")
        clock.set(t0)
        val (deliveryId, _) = scheduledDelivery()
        val tx = TransactionTemplate(transactionManager)
        // 워커 A: 시도 1을 예약하고 결과를 아직 내지 않는다(전송 중 정지를 흉내 — 결과는 아래에서 늦게 낸다).
        val claimedByA = tx.execute { deliveries.claimDue(t0, 500, "worker-A", t0.plusSeconds(60)) }.single { it.delivery.id.value == deliveryId }
        clock.set(t0.plusSeconds(61))
        drainDeliveries() // 워커 B: lease 만료 뒤 시도 2 예약·전송·성공

        Then("B delivers with attempt 2; A's late failure is CLAIM_LOST (no state change); both attempt rows exist") {
            claimedByA.attemptNo shouldBe 1
            deliveryRow(deliveryId)["status"] shouldBe "DELIVERED"
            val late = tx.execute {
                deliveries.recordResult(
                    MerchantWebhookDeliveryId(deliveryId), "worker-A", 1, DeliveryAttemptResult.Failure("TIMEOUT", null), t0.plusSeconds(62), timings().schedule, 0.0,
                )
            }
            late shouldBe DeliveryRecordOutcome.CLAIM_LOST
            tx.executeWithoutResult {
                attempts.append(
                    com.paycore.payment.payment.application.required.MerchantWebhookAttempt(
                        MerchantWebhookDeliveryId(deliveryId), 1, "worker-A", "ep-db", "whk_t_db", "TIMEOUT", null, "webhook-retry-demo-1", t0, t0.plusSeconds(62),
                    ),
                )
            }
            attemptRows(deliveryId) shouldBe listOf(1 to "TIMEOUT", 2 to "SUCCESS")
            deliveryRow(deliveryId)["status"] shouldBe "DELIVERED"
        }
        Then("a late failure from the worker that lost ownership while the new owner is still sending changes nothing (CLAIM_LOST, the new claim stays)") {
            val t2 = Instant.parse("2026-10-22T08:20:00Z")
            clock.set(t2)
            val (other, _) = scheduledDelivery()
            tx.execute { deliveries.claimDue(t2, 500, "worker-A", t2.plusSeconds(60)) }
            tx.execute { deliveries.claimDue(t2.plusSeconds(61), 500, "worker-B", t2.plusSeconds(121)) }
            val r = tx.execute {
                deliveries.recordResult(MerchantWebhookDeliveryId(other), "worker-A", 1, DeliveryAttemptResult.Failure("TIMEOUT", null), t2.plusSeconds(62), timings().schedule, 0.0)
            }
            r shouldBe DeliveryRecordOutcome.CLAIM_LOST
            rows("SELECT status, attempt_count, claim_owner, last_failure_code FROM merchant_webhook_delivery WHERE delivery_id = ?", other).single() shouldBe
                mapOf("status" to "PENDING", "attempt_count" to 2, "claim_owner" to "worker-B", "last_failure_code" to null)
        }
        Then("n-1: a late failure of an older attempt from the same owner (re-claimed after its own lease expired) is fenced by the attempt number") {
            val t3 = Instant.parse("2026-10-22T08:30:00Z")
            clock.set(t3)
            val (other, _) = scheduledDelivery()
            tx.execute { deliveries.claimDue(t3, 500, "worker-A", t3.plusSeconds(60)) }
            tx.execute { deliveries.claimDue(t3.plusSeconds(61), 500, "worker-A", t3.plusSeconds(121)) }
            tx.execute {
                deliveries.recordResult(MerchantWebhookDeliveryId(other), "worker-A", 1, DeliveryAttemptResult.Failure("TIMEOUT", null), t3.plusSeconds(62), timings().schedule, 0.0)
            } shouldBe DeliveryRecordOutcome.CLAIM_LOST
            rows("SELECT status, attempt_count, claim_owner, last_failure_code FROM merchant_webhook_delivery WHERE delivery_id = ?", other).single() shouldBe
                mapOf("status" to "PENDING", "attempt_count" to 2, "claim_owner" to "worker-A", "last_failure_code" to null)
        }
        Then("a late 2xx from the worker that lost ownership on a still-pending delivery marks it DELIVERED (success is monotonic)") {
            val t1 = Instant.parse("2026-10-22T08:10:00Z")
            clock.set(t1)
            val (other, _) = scheduledDelivery()
            tx.execute { deliveries.claimDue(t1, 500, "worker-A", t1.plusSeconds(60)) }
            tx.execute { deliveries.claimDue(t1.plusSeconds(61), 500, "worker-B", t1.plusSeconds(121)) }
            val r = tx.execute {
                deliveries.recordResult(MerchantWebhookDeliveryId(other), "worker-A", 1, DeliveryAttemptResult.Success(200), t1.plusSeconds(62), timings().schedule, 0.0)
            }
            r shouldBe DeliveryRecordOutcome.DELIVERED
            deliveryRow(other)["status"] shouldBe "DELIVERED"
        }
    }

    Given("H-1 (review #2): a slow merchant and a batch whose lease covers only part of it, with a second worker running (real time)") {
        val wall = object : com.paycore.payment.payment.application.required.PaymentClock {
            override fun now(): Instant = Instant.now()
        }
        // 앞선 시험·다른 spec이 남긴 도래 전달은 먼저 정리한다(최대 1회 — 곧 GAVE_UP).
        repeat(10) { if (delivery(maxAttempts = 1, c = wall).deliver(DeliverMerchantWebhooksCommand(500)).claimed == 0) return@repeat }
        clock.set(Instant.now())
        val ids = (1..4).map { scheduledDelivery() }
        // 수신 2s, 이 시험 전용 전송(응답 4s·기한 4s), 한 시도 예산 5s, lease 7s, 회차 batch 4: 한 회차를 끝까지 보내면 8s라 lease가 회차 중간에 끝난다
        // (검사 없으면 4번째가 lease 만료 뒤 다른 worker와 겹쳐 두 번 간다 — MR1b). 검사가 있으면 회차에서 시작 시각 + 5s ≤ lease 끝인 앞 1~2건만 보낸다.
        receiver.respond { Behaviour.Delay(2_000) }
        val slowTransport = transport.copy(responseTimeout = Duration.ofSeconds(4), requestDeadline = Duration.ofSeconds(4))
        val slowSender = HttpMerchantWebhookSender(slowTransport, SystemWebhookHostResolver(), null)
        val t = timings(lease = Duration.ofSeconds(7), budget = Duration.ofSeconds(5))
        val pool = Executors.newFixedThreadPool(2)
        val go = CountDownLatch(1)
        // 두 worker 모두 고정 시간(20s) 동안 계속 돈다 — 회차 수로 끝내면 빈 회차만 돈 worker가 lease 만료(7s) 전에 멈춰 겹침을 관찰하지 못한다(MR1b 2차 생존 원인).
        val until = System.nanoTime() + Duration.ofSeconds(20).toNanos()
        val claimedPerRound = java.util.concurrent.ConcurrentLinkedQueue<Int>()
        val workers = (1..2).map {
            pool.submit<Unit> {
                go.await()
                while (System.nanoTime() < until) {
                    claimedPerRound += delivery(t = t, c = wall, s = slowSender).deliver(DeliverMerchantWebhooksCommand(4)).claimed
                    Thread.sleep(200)
                }
            }
        }
        go.countDown()
        workers.forEach { it.get(180, TimeUnit.SECONDS) }
        pool.shutdown()
        slowSender.close()

        Then("every delivery is DELIVERED; per delivery the receipts, the attempt rows and attempt_count are equal (no send without a covering lease, no burnt numbers)") {
            ids.forEach { (deliveryId, eventId) ->
                val receipts = receiver.received.count { it.headers["paycore-event-id"] == eventId }
                val rowsForDelivery = attemptRows(deliveryId)
                val count = (scalar("SELECT attempt_count FROM merchant_webhook_delivery WHERE delivery_id = ?", deliveryId) as Number).toInt()
                deliveryRow(deliveryId)["status"] shouldBe "DELIVERED"
                receipts shouldBe rowsForDelivery.size
                count shouldBe rowsForDelivery.size
                rowsForDelivery.map { it.first } shouldBe (1..rowsForDelivery.size).toList()
                // 추가 기대(MR1b 생존 후): 수신기가 늘 2xx이고 한 시도가 예산 안에 끝나므로 lease 만료로 인한 재claim이 없다 → 전달마다 정확히 한 번 간다.
                receipts shouldBe 1
            }
        }
        Then("n-2 (final review): no round claimed more than floor(lease 7s / budget 5s) = 1 delivery although the batch is 4") {
            claimedPerRound.max() shouldBe 1
        }
    }

    Given("final review #2 m-1: releaseClaim on real MySQL (lock → domain release → row; CHECK ck_merchant_webhook_delivery_attempts)") {
        val tx = TransactionTemplate(transactionManager)
        val owner = "worker-R"
        fun row(id: String): Map<String, Any?> = rows(
            "SELECT status, attempt_count, DATE_FORMAT(first_attempt_at, '%Y-%m-%dT%H:%i:%s.%f') AS first_at, claim_owner, " +
                "DATE_FORMAT(claim_until, '%Y-%m-%dT%H:%i:%s.%f') AS claim_until, DATE_FORMAT(next_attempt_at, '%Y-%m-%dT%H:%i:%s.%f') AS next_at, last_failure_code " +
                "FROM merchant_webhook_delivery WHERE delivery_id = ?",
            id,
        ).single()
        fun claimMine(id: String, at: Instant): Int =
            tx.execute { deliveries.claimDue(at, 500, owner, at.plusSeconds(60)) }.single { it.delivery.id.value == id }.attemptNo
        fun release(id: String, o: String, attemptNo: Int): Boolean = tx.execute { deliveries.releaseClaim(MerchantWebhookDeliveryId(id), o, attemptNo) }

        Then("first reservation: released → attempt_count 0, first_attempt_at NULL, claim cleared, PENDING; the next claim reserves 1 again") {
            val t = Instant.parse("2026-10-22T10:00:00Z")
            clock.set(t)
            val (id, _) = scheduledDelivery()
            claimMine(id, t) shouldBe 1
            row(id).let { it["attempt_count"] shouldBe 1; it["claim_owner"] shouldBe owner; (it["first_at"] != null) shouldBe true }
            release(id, owner, 1) shouldBe true
            row(id).let {
                it["status"] shouldBe "PENDING"
                it["attempt_count"] shouldBe 0
                it["first_at"] shouldBe null
                it["claim_owner"] shouldBe null
                it["claim_until"] shouldBe null
            }
            attemptRows(id) shouldBe emptyList()
            claimMine(id, t.plusSeconds(1)) shouldBe 1
            row(id)["first_at"] shouldBe "2026-10-22T10:00:01.000000"
        }
        Then("second reservation after one real attempt: released → attempt_count 1, first_attempt_at kept; then the rejections leave the row unchanged") {
            val t = Instant.parse("2026-10-22T11:00:00Z")
            clock.set(t)
            val (id, _) = scheduledDelivery()
            claimMine(id, t) shouldBe 1
            tx.execute {
                deliveries.recordResult(MerchantWebhookDeliveryId(id), owner, 1, DeliveryAttemptResult.Failure("HTTP_500", 500), t, timings().schedule, 0.0)
            } shouldBe DeliveryRecordOutcome.RETRY_SCHEDULED
            claimMine(id, t.plusSeconds(11)) shouldBe 2
            release(id, owner, 2) shouldBe true
            row(id).let {
                it["status"] shouldBe "PENDING"
                it["attempt_count"] shouldBe 1
                it["first_at"] shouldBe "2026-10-22T11:00:00.000000"
                it["claim_owner"] shouldBe null
                it["claim_until"] shouldBe null
                it["last_failure_code"] shouldBe "HTTP_500"
            }
            // 거절: 다른 소유자, 낡은 시도 번호 — false, 행 무변경.
            claimMine(id, t.plusSeconds(12)) shouldBe 2
            val held = row(id)
            release(id, "worker-other", 2) shouldBe false
            row(id) shouldBe held
            release(id, owner, 1) shouldBe false
            row(id) shouldBe held
        }
        Then("a DELIVERED row is never released (false, row unchanged)") {
            val t = Instant.parse("2026-10-22T12:00:00Z")
            clock.set(t)
            val (id, _) = scheduledDelivery()
            claimMine(id, t) shouldBe 1
            tx.execute { deliveries.recordResult(MerchantWebhookDeliveryId(id), owner, 1, DeliveryAttemptResult.Success(200), t, timings().schedule, 0.0) } shouldBe
                DeliveryRecordOutcome.DELIVERED
            val delivered = row(id)
            delivered["status"] shouldBe "DELIVERED"
            release(id, owner, 1) shouldBe false
            row(id) shouldBe delivered
        }
    }

    Given("C-D1 four delivery workers at once (real MySQL — SKIP LOCKED claim)") {
        val t0 = Instant.parse("2026-10-22T09:00:00Z")
        clock.set(t0)
        val ids = (1..25).map { scheduledDelivery() }
        val pool = Executors.newFixedThreadPool(4)
        val go = CountDownLatch(1)
        val fs = (1..4).map { pool.submit<Unit> { go.await(); drainDeliveries() } }
        go.countDown()
        fs.forEach { it.get(120, TimeUnit.SECONDS) }
        pool.shutdown()

        Then("each delivery was claimed and sent exactly once (one attempt row, one receipt) and is DELIVERED") {
            ids.forEach { (deliveryId, eventId) ->
                deliveryRow(deliveryId).let { it["status"] to it["claim_owner"] } shouldBe ("DELIVERED" to null)
                attemptRows(deliveryId) shouldBe listOf(1 to "SUCCESS")
                receiver.received.count { it.headers["paycore-event-id"] == eventId } shouldBe 1
            }
        }
    }
})

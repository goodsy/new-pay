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
import com.paycore.payment.payment.adapter.web.ApiClient
import com.paycore.payment.payment.adapter.web.ApiTestTimes
import com.paycore.payment.payment.adapter.web.CapturedLogs
import com.paycore.payment.payment.adapter.web.MutablePaymentClock
import com.paycore.payment.payment.adapter.web.PaymentApiTest
import com.paycore.payment.payment.adapter.web.TestCredentials
import com.paycore.payment.payment.application.provided.CancelPaymentResult
import com.paycore.payment.payment.application.provided.CancelPaymentUseCase
import com.paycore.payment.payment.application.provided.ConfirmPaymentUseCase
import com.paycore.payment.payment.application.provided.CreatePaymentResult
import com.paycore.payment.payment.application.provided.CreatePaymentUseCase
import com.paycore.payment.payment.application.provided.DeliverMerchantWebhooksCommand
import com.paycore.payment.payment.application.provided.ExpireElapsedRequestedPaymentsCommand
import com.paycore.payment.payment.application.provided.ExpireElapsedRequestedPaymentsUseCase
import com.paycore.payment.payment.application.provided.FanOutMerchantNotificationsCommand
import com.paycore.payment.payment.application.provided.MerchantNotificationBacklogQuery
import com.paycore.payment.payment.application.provided.OperatorCorrectionCancelResult
import com.paycore.payment.payment.application.provided.OperatorCorrectionCancelUseCase
import com.paycore.payment.payment.application.required.AuthenticationSessionRepository
import com.paycore.payment.payment.application.required.CheckoutSessionStore
import com.paycore.payment.payment.application.required.CheckoutTokenStore
import com.paycore.payment.payment.application.required.MerchantWebhookDeliveryTimings
import com.paycore.payment.payment.application.required.MerchantWebhookEndpoint
import com.paycore.payment.payment.application.required.MerchantWebhookEndpointDirectory
import com.paycore.payment.payment.application.required.MerchantWebhookSigningKeys
import com.paycore.payment.payment.application.required.OrderPaymentControlRepository
import com.paycore.payment.payment.application.required.PaymentRepository
import com.paycore.payment.payment.application.required.WebhookSigningKey
import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.MerchantAccountId
import com.paycore.payment.payment.domain.MerchantOrderId
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.OrderKey
import com.paycore.payment.payment.domain.WebhookRetrySchedule
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.transaction.PlatformTransactionManager
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.springframework.core.env.Environment as SpringEnvironment

/**
 * WEBHOOK-001 S5 일관성·무효과·시간대·비노출(§4.4·§8·§11·§12 — T-V1·T-V2·T-Z1·T-N1, gauge). 업무 사실은 운영 경로(create·confirm(stub VAN)·가맹점 취소·운영자 취소·만료)로 만들고,
 * fan-out·delivery는 운영 서비스에 시험 endpoint 대역(MID별 loopback 시험 수신기 — TEST allowlist)만 바꿔 실행한다. GET은 실제 Tomcat·공개 API(TEST credential)로 읽는다.
 * - T-V1: 가맹점 이벤트 본문의 섹션별 키·값 ⊆ 그 뒤 GET이 보이는 같은 섹션의 키·값(취소는 같은 cancellationId 항목, returnSummary는 이벤트 시점 값 — 마지막 이벤트는 현재 GET과 같다).
 *   P1 숨김은 fan-out spec T-F3이 덮는다(같은 Payment의 늦은 승인·운영자 취소 이벤트가 만들어지지 않음).
 * - T-V2: 수신기 영구 실패 → 포기(GAVE_UP)까지 가도 GET 응답 바이트·반환 잔액 행·Outbox 발행 상태가 그대로다(Webhook은 금융 상태를 바꾸지 않는다).
 * - T-Z1: JVM 기본 시간대가 Asia/Seoul이어도 본문 `createdAt`·서명 `t`·저장 시각이 UTC 기준으로 같은 순간이다.
 * - T-N1: fan-out·delivery 로그에 서명 비밀·서명 값·본문 조각·URL(host:port·경로)이 없다.
 */
@PaymentApiTest
class MerchantWebhookConsistencySpec(
    env: SpringEnvironment,
    private val fanoutStore: JpaMerchantNotificationFanoutStore,
    private val eventStore: JpaMerchantWebhookEventStore,
    private val deliveries: JpaMerchantWebhookDeliveryRepository,
    private val attempts: JpaMerchantWebhookAttemptStore,
    private val createPayment: CreatePaymentUseCase,
    private val cancel: CancelPaymentUseCase,
    private val operator: OperatorCorrectionCancelUseCase,
    private val expiry: ExpireElapsedRequestedPaymentsUseCase,
    private val van: ProgrammableVan,
    private val admission: ScriptedAdmissionPort,
    private val clock: MutablePaymentClock,
    private val backlog: MerchantNotificationBacklogQuery,
    private val registry: MeterRegistry,
    private val transactionManager: PlatformTransactionManager,
    private val db: DbAccess,
    private val confirm: ConfirmPaymentUseCase,
    payments: PaymentRepository,
    controls: OrderPaymentControlRepository,
    sessions: AuthenticationSessionRepository,
    tokens: CheckoutTokenStore,
    checkoutSessions: CheckoutSessionStore,
) : BehaviorSpec({

    val api = ApiClient(env.getRequiredProperty("local.server.port").toInt())
    val confirmFixture = ConfirmFixture(payments, controls, sessions, tokens, checkoutSessions, transactionManager, db)
    val fixture = CancelFixture(confirmFixture, confirm, admission, controls, payments, transactionManager, van)
    val mapper = JsonMapper.builder().build()
    val receiver = ProgrammableMerchantReceiver.http()
    afterSpec { receiver.stop() }
    afterEach {
        receiver.reset()
        clock.reset()
    }

    val secret = "whsec_dGVzdC1vbmx5LWNvbnNpc3RlbmN5LXNpZ25pbmcta2V5"
    val endpoints = ConcurrentHashMap<String, MerchantWebhookEndpoint>()
    val directory = object : MerchantWebhookEndpointDirectory {
        override fun find(environment: Environment, merchantAccountId: MerchantAccountId) = if (environment == Environment.TEST) endpoints[merchantAccountId.value] else null
    }
    val keys = object : MerchantWebhookSigningKeys {
        override fun active(environment: Environment, merchantAccountId: MerchantAccountId) =
            if (environment == Environment.TEST) listOf(WebhookSigningKey("whk_t_v", secret.toByteArray())) else emptyList()
    }
    val transport = MerchantWebhookTransportSettings(
        connectTimeout = Duration.ofSeconds(1), responseTimeout = Duration.ofSeconds(2), requestDeadline = Duration.ofSeconds(3), dnsTimeout = Duration.ofSeconds(1),
        maxResponseBytes = 4_096, testAllowlist = listOf("127.0.0.1:${receiver.port}"), testTrustPem = null,
    )
    val sender = HttpMerchantWebhookSender(transport, SystemWebhookHostResolver(), null)
    afterSpec { sender.close() }

    fun timings(maxAttempts: Int) = object : MerchantWebhookDeliveryTimings {
        override val schedule = WebhookRetrySchedule("webhook-retry-demo-1", Duration.ofSeconds(10), Duration.ofHours(1), 0.0, maxAttempts, Duration.ofHours(24))
        override val claimLease: Duration = Duration.ofSeconds(60)
        override val attemptBudget: Duration = Duration.ofSeconds(9)
    }
    val fanout = MerchantNotificationFanoutService(transactionManager, fanoutStore, eventStore, deliveries, directory, SecureRandomMerchantWebhookIdentifierGenerator(), clock, NO_MERCHANT_CODE)
    fun delivery(maxAttempts: Int) =
        MerchantWebhookDeliveryService(transactionManager, deliveries, attempts, eventStore, directory, keys, HmacMerchantWebhookSigner(), sender, timings(maxAttempts), clock)

    fun drainFanout() {
        repeat(50) { if (fanout.fanOut(FanOutMerchantNotificationsCommand(500, Duration.ofMinutes(1))).claimed == 0) return }
    }
    fun drainDeliveries(maxAttempts: Int) {
        repeat(20) { if (delivery(maxAttempts).deliver(DeliverMerchantWebhooksCommand(500)).claimed == 0) return }
    }

    fun rows(sql: String, vararg args: Any?): List<Map<String, Any?>> = db.withRoot { c -> with(db) { c.catalog = db.databaseName; c.rows(sql, *args) } }
    fun scalar(sql: String, vararg args: Any?): Any? = db.withRoot { c -> with(db) { c.catalog = db.databaseName; c.scalar(sql, *args) } }
    fun webhookBodies(paymentId: String): List<JsonNode> =
        rows("SELECT CAST(body AS CHAR) AS body FROM merchant_webhook_event WHERE payment_id = ? ORDER BY payment_sequence", paymentId).map { mapper.readTree(it["body"] as String) }
    fun JsonNode.fields(): List<Pair<String, JsonNode>> = properties().map { it.key to it.value }

    fun issue(): TestCredentials.Issued = TestCredentials.issue(db, environment = Environment.TEST).also {
        endpoints[it.mid] = MerchantWebhookEndpoint("ep-consistency", "http://127.0.0.1:${receiver.port}/hook-consistency")
    }
    fun order(cred: TestCredentials.Issued) = OrderKey(Environment.TEST, MerchantAccountId(cred.mid), MerchantOrderId("ord-" + UUID.randomUUID().toString().take(12)))
    fun get(cred: TestCredentials.Issued, paymentId: String): JsonNode =
        api.get("/v1/payments/$paymentId", cred.key).let { r -> withClue(r.body) { r.status shouldBe 200 }; mapper.readTree(r.body) }

    /** T-V1 판정: 섹션별 Webhook 키·값 ⊆ GET 키·값. [latest]면 returnSummary 값도 현재 GET과 같다(이벤트 시점 = 현재). */
    fun assertSubsetOfGet(webhook: JsonNode, get: JsonNode, latest: Boolean) {
        webhook.get("payment").fields().forEach { (k, v) -> withClue("payment.$k") { get.get(k) shouldBe v } }
        webhook.get("authorization")?.fields()?.forEach { (k, v) -> withClue("authorization.$k") { get.get("authorization").get(k) shouldBe v } }
        webhook.get("cancellation")?.let { w ->
            val item = get.get("cancellations").iterator().asSequence().single { it.get("cancellationId") == w.get("cancellationId") }
            w.fields().forEach { (k, v) -> withClue("cancellation.$k") { item.get(k) shouldBe v } }
        }
        webhook.get("returnSummary")?.let { w ->
            val g = get.get("returnSummary")
            w.fields().map { it.first }.toSet().all { g.has(it) } shouldBe true
            g.get("authorizedAmount") shouldBe w.get("authorizedAmount")
            if (latest) {
                g.get("cancelledAmount") shouldBe w.get("cancelledAmount")
                g.get("returnStatus") shouldBe w.get("returnStatus")
            } else {
                (g.get("cancelledAmount").asLong() >= w.get("cancelledAmount").asLong()) shouldBe true
            }
        }
    }

    fun <T> withJvmTimeZone(zone: String, block: () -> T): T {
        val original = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of(zone)))
        try {
            return block()
        } finally {
            TimeZone.setDefault(original)
        }
    }

    Given("T-V1 official paths: approval, merchant partial cancel, operator cancel of the rest") {
        val cred = issue()
        val a = fixture.authorized(order = order(cred))
        cancel.cancel(a.command(money = Money(3_000, a.money.currency))).shouldBeInstanceOf<CancelPaymentResult.Accepted>()
        val revision = (scalar("SELECT revision FROM return_balance WHERE payment_id = ?", a.paymentId.value) as Number).toLong()
        operator.cancel(a.operatorCommand(money = Money(7_000, a.money.currency), fingerprintRevision = revision)).shouldBeInstanceOf<OperatorCorrectionCancelResult.Accepted>()
        drainFanout()
        val bodies = webhookBodies(a.paymentId.value)
        val current = get(cred, a.paymentId.value)

        Then("three events (authorized, cancelled, cancelled) and every section of every body is a subset of what GET shows") {
            bodies.map { it.get("type").stringValue() } shouldBe listOf("payment.authorized", "payment.cancelled", "payment.cancelled")
            bodies.forEachIndexed { i, b -> withClue("event ${i + 1}") { assertSubsetOfGet(b, current, latest = i == bodies.lastIndex) } }
        }
    }

    Given("T-V1 official paths: a declined confirm, a declined merchant cancel and an expired payment") {
        val credFailed = issue()
        val declined = confirmFixture.prepared(order = order(credFailed))
        van.onAuth(declined.order.merchantAccountId.value) { van.decline(it) }
        confirm.confirm(declined.command())

        val credCancelFailed = issue()
        val b = fixture.authorized(order = order(credCancelFailed))
        van.onCancel(b.mid.value) { van.declineCancel(it) }
        cancel.cancel(b.command(money = Money(1_000, b.money.currency)))

        val credExpired = issue()
        val created = createPayment.create(CreatePaymentFixture.command(order(credExpired))).shouldBeInstanceOf<CreatePaymentResult.Accepted>()
        val expiredId = created.payment.paymentId.value
        clock.set(ApiTestTimes.T0.plus(Duration.ofDays(1)))
        repeat(50) {
            if (scalar("SELECT status FROM payment WHERE payment_id = ?", expiredId) == "EXPIRED") return@repeat
            expiry.expireElapsed(ExpireElapsedRequestedPaymentsCommand("wh-tv1-" + UUID.randomUUID().toString().take(8), 500))
        }
        drainFanout()

        Then("payment.failed, payment.authorized + payment.cancel_failed and payment.expired bodies are subsets of GET") {
            webhookBodies(declined.paymentId.value).map { it.get("type").stringValue() } shouldBe listOf("payment.failed")
            assertSubsetOfGet(webhookBodies(declined.paymentId.value).single(), get(credFailed, declined.paymentId.value), latest = true)
            val cancelFailed = webhookBodies(b.paymentId.value)
            cancelFailed.map { it.get("type").stringValue() } shouldBe listOf("payment.authorized", "payment.cancel_failed")
            cancelFailed.forEach { assertSubsetOfGet(it, get(credCancelFailed, b.paymentId.value), latest = true) }
            webhookBodies(expiredId).map { it.get("type").stringValue() } shouldBe listOf("payment.expired")
            assertSubsetOfGet(webhookBodies(expiredId).single(), get(credExpired, expiredId), latest = true)
        }
    }

    Given("T-V2 and T-N1: a merchant that always fails until the deliveries give up") {
        val cred = issue()
        val a = fixture.authorized(order = order(cred))
        cancel.cancel(a.command(money = Money(2_000, a.money.currency))).shouldBeInstanceOf<CancelPaymentResult.Accepted>()
        val getBefore = api.get("/v1/payments/${a.paymentId.value}", cred.key).body
        val balanceSql = "SELECT authorized_minor, completed_minor, reserved_minor, forced_minor, revision, version, updated_at FROM return_balance WHERE payment_id = ?"
        val balanceBefore = rows(balanceSql, a.paymentId.value)
        val outboxSql = "SELECT event_id, event_type, publish_status FROM outbox_event WHERE JSON_UNQUOTE(JSON_EXTRACT(payload, '$.paymentId')) = ? ORDER BY id"
        val outboxBefore = rows(outboxSql, a.paymentId.value)
        receiver.respond { Behaviour.Status(500) }
        val logs = CapturedLogs()
        logs.use {
            drainFanout()
            drainDeliveries(maxAttempts = 1)
        }
        // afterEach가 수신 기록을 지우므로 Then 전에 모아 둔다.
        val sentToHook = receiver.received.count { it.path == "/hook-consistency" }.toLong()
        val signatures = receiver.received.mapNotNull { it.headers["paycore-signature"] }.flatMap { h -> h.split(',').filter { it.startsWith("v1=") }.map { it.removePrefix("v1=") } }
        val gaveUp = rows(
            "SELECT d.status FROM merchant_webhook_delivery d JOIN merchant_webhook_event e ON e.webhook_event_id = d.webhook_event_id WHERE e.payment_id = ?", a.paymentId.value,
        ).map { it["status"] }

        Then("both deliveries gave up after a real send, and GET bytes, the return balance and the Outbox publish state did not change") {
            gaveUp shouldBe listOf("GAVE_UP", "GAVE_UP")
            sentToHook shouldBeGreaterThanOrEqual 2
            api.get("/v1/payments/${a.paymentId.value}", cred.key).body shouldBe getBefore
            rows(balanceSql, a.paymentId.value) shouldBe balanceBefore
            rows(outboxSql, a.paymentId.value) shouldBe outboxBefore
        }
        Then("the GAVE_UP gauge shows the given-up deliveries (no ID labels)") {
            val gauge = registry.find("paycore.webhook.delivery.gave_up").gauges()
            gauge.size shouldBe 1
            gauge.single().id.tags shouldBe emptyList()
            gauge.single().value().toLong() shouldBeGreaterThanOrEqual 2
            backlog.backlog().gaveUpRows shouldBeGreaterThanOrEqual 2
            registry.find("paycore.webhook.delivery.pending").gauges().size shouldBe 1
            registry.find("paycore.webhook.delivery.pending.oldest_due_age_seconds").gauges().size shouldBe 1
        }
        Then("T-N1: the fan-out and delivery logs carry no secret, no signature value, no body fragment and no URL") {
            val lines = logs.lines()
            lines.shouldNotBeEmpty()
            signatures.shouldNotBeEmpty()
            lines.forEach { line ->
                line shouldNotContain secret
                line shouldNotContain "whsec_"
                signatures.forEach { line shouldNotContain it }
                line shouldNotContain "hook-consistency"
                line shouldNotContain "127.0.0.1:${receiver.port}"
                line shouldNotContain a.prepared.order.merchantOrderId.value
                line shouldNotContain "\"payment\":"
            }
        }
    }

    Given("T-Z1 the JVM default time zone is Asia/Seoul") {
        val at = Instant.parse("2026-10-22T15:30:00.123Z") // KST 2026-10-23 00:30:00.123 — 날짜가 달라지는 시각
        val cred = issue()
        val (paymentId, attemptT) = withJvmTimeZone("Asia/Seoul") {
            val a = fixture.authorized(order = order(cred))
            clock.set(at)
            receiver.respond { Behaviour.Status(200) }
            drainFanout()
            drainDeliveries(maxAttempts = 3)
            // 이 Payment의 이벤트만(앞 Given의 전달 중 이 시각에 도래한 것도 같은 수신기로 간다).
            val eventId = scalar("SELECT webhook_event_id FROM merchant_webhook_event WHERE payment_id = ?", a.paymentId.value) as String
            val t = receiver.received.filter { it.headers["paycore-event-id"] == eventId }.map { r ->
                r.headers.getValue("paycore-signature").split(',').first().removePrefix("t=").toLong()
            }
            a.paymentId.value to t
        }

        Then("the body createdAt, the stored event and delivery times and the signature t are the same UTC instant") {
            webhookBodies(paymentId).single().get("createdAt").stringValue() shouldBe "2026-10-22T15:30:00.123Z"
            rows(
                "SELECT DATE_FORMAT(e.created_at, '%Y-%m-%dT%H:%i:%s.%f') AS event_at, DATE_FORMAT(d.delivered_at, '%Y-%m-%dT%H:%i:%s.%f') AS delivered_at, d.status " +
                    "FROM merchant_webhook_event e JOIN merchant_webhook_delivery d ON d.webhook_event_id = e.webhook_event_id WHERE e.payment_id = ?",
                paymentId,
            ).single() shouldBe mapOf("event_at" to "2026-10-22T15:30:00.123000", "delivered_at" to "2026-10-22T15:30:00.123000", "status" to "DELIVERED")
            attemptT shouldBe listOf(at.epochSecond)
        }
    }
})

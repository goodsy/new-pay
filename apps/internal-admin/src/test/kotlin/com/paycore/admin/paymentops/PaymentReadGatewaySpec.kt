package com.paycore.admin.paymentops

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.paycore.admin.paymentops.adapter.integration.AdminCallSigner
import com.paycore.admin.paymentops.adapter.integration.HttpPaymentOpsGateway
import com.paycore.admin.paymentops.adapter.integration.PaymentOpsSettings
import com.paycore.admin.paymentops.application.provided.TransactionSearchCriteria
import com.paycore.admin.paymentops.application.required.PaymentReadResult
import com.paycore.admin.support.StubPaymentOpsServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.slf4j.LoggerFactory
import org.springframework.transaction.support.TransactionSynchronizationManager
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * 거래 조회 HTTP gateway(TXN-OPS-001 §4 — **기존** ADMIN → PAYMENT 채널, 실제 HTTP 대역 [StubPaymentOpsServer]): 서명은 대역이 앱과 독립으로 golden ② 시험 키로 검증,
 * 검색 본문은 형식 검사된 키만·query 없음, 응답은 허용 필드 모양(fail-closed — 모르는 필드·형식 위반·10자 넘는 목록 주문명 = 해석 불가), 404/5xx/과대/timeout/연결 실패 → 값,
 * DB TX 안 호출 거부, `lookup` permit 포화 → 호출 없이 busy, 로그에 키·서명·주문명·주문번호 없음.
 */
class PaymentReadGatewaySpec : FunSpec({

    StubPaymentOpsServer.start()
    val key = "dGVzdC1vbmx5LWFkbWluLWNhbGwtZ29sZGVuLWtleS0wMDAx"
    fun settings(baseUrl: String = "http://127.0.0.1:${StubPaymentOpsServer.PORT}", read: Duration = Duration.ofSeconds(2), lookup: Int = 4) = PaymentOpsSettings(
        baseUrl, "adm-golden-k1", key, Duration.ofSeconds(1), read, PaymentOpsSettings.Permits(4, lookup, 4), 5, Duration.ofHours(24), Duration.ofSeconds(30),
        Duration.ofMinutes(1), Duration.ofHours(24), Duration.ofMinutes(5), 50, Duration.ofMinutes(2), 10, Duration.ofDays(1),
    )
    fun gateway(s: PaymentOpsSettings = settings()) = HttpPaymentOpsGateway(s, AdminCallSigner(s.keyId, s.signingKey())) { Instant.now() }
    var seq = System.nanoTime() % 1_000_000L * 100
    fun paymentId() = "pay_01JD" + (++seq).toString().padStart(22, '0')
    fun orderId() = "order-gw-" + (++seq)
    val mapper = JsonMapper.builder().build()

    fun captureLogs(block: () -> Unit): List<String> {
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(appender)
        try {
            block()
        } finally {
            root.detachAppender(appender)
        }
        return appender.list.map { e -> e.loggerName + " " + e.formattedMessage + (e.throwableProxy?.let { " | " + it.className + ": " + it.message } ?: "") }
    }

    test("search: a signed POST the stub verifies independently, only the given keys, no query string; the response is rebuilt through the allowed-field shape") {
        val order = orderId()
        val id = paymentId()
        StubPaymentOpsServer.search(order, StubPaymentOpsServer.Reply(200, TransactionJson.list(TransactionJson.item(id, orderId = order), next = id)))
        val criteria = TransactionSearchCriteria(
            merchantAccountId = "mid-demo-1", merchantOrderId = order, environment = "TEST", afterCreatedAt = "2026-11-02T00:00:00.000Z", afterPaymentId = id, limit = 5,
        )
        val found = gateway().searchPayments(criteria).shouldBeInstanceOf<PaymentReadResult.Found>().document
        mapper.writeValueAsString(found) shouldBe mapper.writeValueAsString(mapper.readTree(TransactionJson.list(TransactionJson.item(id, orderId = order), next = id)))
        val r = StubPaymentOpsServer.received.last { it.path == StubPaymentOpsServer.SEARCH_PATH && it.body.toString(Charsets.UTF_8).contains(order) }
        listOf(r.method, r.query, r.signatureValid) shouldBe listOf("POST", null, true)
        mapper.readTree(r.body).propertyNames().toList() shouldBe listOf("environment", "merchantAccountId", "merchantOrderId", "after", "limit")
        mapper.readTree(r.body).get("after").propertyNames().toList() shouldBe listOf("createdAt", "paymentId")
    }

    test("detail: 200 parsed exactly; 404 PAYMENT_NOT_FOUND is NotFound; other 404, 5xx, unknown fields, malformed values and oversized bodies are Unavailable") {
        val g = gateway()
        val ok = paymentId()
        StubPaymentOpsServer.detail(ok, StubPaymentOpsServer.Reply(200, TransactionJson.detail(ok)))
        mapper.writeValueAsString(g.paymentDetail(ok).shouldBeInstanceOf<PaymentReadResult.Found>().document) shouldBe
            mapper.writeValueAsString(mapper.readTree(TransactionJson.detail(ok)))
        g.paymentDetail(paymentId()) shouldBe PaymentReadResult.NotFound
        val cases = mapOf(
            "other 404" to StubPaymentOpsServer.Reply(404, "{\"error\":\"NOT_FOUND\"}"),
            "503" to StubPaymentOpsServer.Reply(503, "{\"error\":\"ADMIN_READ_BUSY\"}"),
            "unknown field (fail-closed)" to null,
            "institution account field" to null,
            "other payment" to null,
            "wrong type" to null,
            "oversized" to StubPaymentOpsServer.Reply(200, "{\"x\":\"" + "a".repeat(PaymentOpsSettings.RESPONSE_MAX_BYTES) + "\"}"),
        )
        cases.forEach { (name, reply) ->
            val id = paymentId()
            val body = when (name) {
                "unknown field (fail-closed)" -> TransactionJson.detail(id, ",\"paymentKey\":\"pk-1\"")
                "institution account field" -> TransactionJson.detail(id, ",\"institutionAccount\":\"SIMVAN-ACC-1\"")
                "other payment" -> TransactionJson.detail(paymentId())
                "wrong type" -> TransactionJson.detail(id).replace("\"amountMinor\":10000", "\"amountMinor\":\"10000\"")
                else -> null
            }
            StubPaymentOpsServer.detail(id, reply ?: StubPaymentOpsServer.Reply(200, body!!))
            withClue(name) { g.paymentDetail(id).shouldBeInstanceOf<PaymentReadResult.Unavailable>() }
        }
        // 목록: 10자 넘는 주문명 미리보기(PAYMENT가 실수로 전체 이름을 보냄)·모르는 항목 필드는 해석 불가.
        val long = orderId()
        StubPaymentOpsServer.search(long, StubPaymentOpsServer.Reply(200, TransactionJson.list(TransactionJson.item(paymentId(), preview = TransactionJson.ORDER_FULL, orderId = long))))
        g.searchPayments(TransactionSearchCriteria(merchantAccountId = "mid-demo-1", merchantOrderId = long)).shouldBeInstanceOf<PaymentReadResult.Unavailable>().reason shouldBe
            "RESPONSE_UNREADABLE"
        // 변이 (d) 생존 후 추가(리뷰 MINOR-4): 잘림 표시 없이 10 code point를 넘는 미리보기도 모양 위반(잘림 일관성 검사와 별개로 길이 상한 자체를 확인).
        val eleven = orderId()
        StubPaymentOpsServer.search(eleven, StubPaymentOpsServer.Reply(200, TransactionJson.list(TransactionJson.item(paymentId(), preview = "가".repeat(11), truncated = false, orderId = eleven))))
        g.searchPayments(TransactionSearchCriteria(merchantAccountId = "mid-demo-1", merchantOrderId = eleven)) shouldBe PaymentReadResult.Unavailable("RESPONSE_UNREADABLE")
        // 10 code point(보충 문자 포함 — UTF-16 길이는 10보다 길다)는 통과.
        val emoji = orderId()
        val tenCodePoints = "😀".repeat(10)
        StubPaymentOpsServer.search(emoji, StubPaymentOpsServer.Reply(200, TransactionJson.list(TransactionJson.item(paymentId(), preview = tenCodePoints, orderId = emoji))))
        g.searchPayments(TransactionSearchCriteria(merchantAccountId = "mid-demo-1", merchantOrderId = emoji)).shouldBeInstanceOf<PaymentReadResult.Found>()
    }

    // 리뷰 MINOR-1: 모양이 맞아도 요청에 대한 답이 아니면(다른 결제·필터 불일치·limit 초과·기간 밖·미리보기 불일치) 해석 불가 — fail-closed.
    test("a search response that does not answer the request is unreadable (fail-closed); a matching one is found") {
        val g = gateway()
        fun reply(marker: String, vararg items: String) = StubPaymentOpsServer.search(marker, StubPaymentOpsServer.Reply(200, TransactionJson.list(*items)))
        fun unreadable(criteria: TransactionSearchCriteria) = g.searchPayments(criteria) shouldBe PaymentReadResult.Unavailable("RESPONSE_UNREADABLE")

        // 결제 ID 정확 조회: 다른 결제·두 건.
        val exact = paymentId()
        reply(exact, TransactionJson.item(paymentId()))
        withClue("other payment") { unreadable(TransactionSearchCriteria(paymentId = exact)) }
        val twice = paymentId()
        reply(twice, TransactionJson.item(twice), TransactionJson.item(twice))
        withClue("two rows for one payment id") { unreadable(TransactionSearchCriteria(paymentId = twice)) }
        val found = paymentId()
        reply(found, TransactionJson.item(found))
        g.searchPayments(TransactionSearchCriteria(paymentId = found)).shouldBeInstanceOf<PaymentReadResult.Found>()

        // 환경·MID·주문번호·상태 필터와 다른 항목.
        val order = orderId()
        val item = TransactionJson.item(paymentId(), orderId = order)
        mapOf(
            "environment" to (TransactionSearchCriteria(merchantAccountId = "mid-demo-1", merchantOrderId = order, environment = "TEST") to item.replace("\"TEST\"", "\"LIVE\"")),
            "MID" to (TransactionSearchCriteria(merchantAccountId = "mid-demo-1", merchantOrderId = order) to item.replace("mid-demo-1", "mid-demo-2")),
            "order" to (TransactionSearchCriteria(merchantAccountId = "mid-demo-1", merchantOrderId = order) to TransactionJson.item(paymentId(), orderId = order + "x")),
        ).forEach { (name, case) ->
            reply(order, case.second)
            withClue(name) { unreadable(case.first) }
        }
        val from = "2026-11-0${(seq % 5) + 1}T00:00:00.000Z"
        val period = TransactionSearchCriteria(createdFrom = from, createdTo = from.replace("T00", "T12"), status = "AUTHORIZED", limit = 1)
        val inside = from.replace("T00:00:00.000Z", "T01:00:00.000Z")
        reply(from, TransactionJson.item(paymentId(), createdAt = inside).replace("AUTHORIZED", "FAILED"))
        withClue("status") { unreadable(period) }
        reply(from, TransactionJson.item(paymentId(), createdAt = inside), TransactionJson.item(paymentId(), createdAt = inside))
        withClue("more rows than the limit") { unreadable(period) }
        reply(from, TransactionJson.item(paymentId(), createdAt = from.replace("T00", "T12")))
        withClue("createdAt at the exclusive end") { unreadable(period) }
        reply(from, TransactionJson.item(paymentId(), createdAt = from.replace("2026-11", "2026-10")))
        withClue("createdAt before the period") { unreadable(period) }
        reply(from, TransactionJson.item(paymentId(), createdAt = inside, preview = "다섯글자요", truncated = true))
        withClue("truncated preview shorter than 10") { unreadable(period) }
        reply(from, TransactionJson.item(paymentId(), createdAt = inside, preview = null, truncated = true))
        withClue("no preview but truncated") { unreadable(period) }
        reply(from, TransactionJson.item(paymentId(), createdAt = from))
        g.searchPayments(period).shouldBeInstanceOf<PaymentReadResult.Found>()
    }

    // 리뷰 NIT: 중복 key 응답은 어느 값을 믿을지 정할 수 없다 — 해석 불가.
    test("a PAYMENT response with a duplicated key is unreadable") {
        val dup = paymentId()
        StubPaymentOpsServer.detail(dup, StubPaymentOpsServer.Reply(200, TransactionJson.detail(dup).replace("\"onHold\":false", "\"onHold\":true,\"onHold\":false")))
        gateway().paymentDetail(dup) shouldBe PaymentReadResult.Unavailable("RESPONSE_UNREADABLE")
    }

    test("calling inside an active DB transaction is refused before any HTTP call") {
        val before = StubPaymentOpsServer.received.size
        TransactionSynchronizationManager.setActualTransactionActive(true)
        try {
            shouldThrow<IllegalStateException> { gateway().paymentDetail(paymentId()) }
            shouldThrow<IllegalStateException> { gateway().searchPayments(TransactionSearchCriteria(paymentId = paymentId())) }
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false)
        }
        StubPaymentOpsServer.received.size shouldBe before
    }

    test("a saturated lookup pool answers busy immediately without calling PAYMENT; a read timeout and a refused connection are values") {
        val g = gateway(settings(lookup = 1))
        val slow = paymentId()
        StubPaymentOpsServer.detail(slow, StubPaymentOpsServer.Reply(200, TransactionJson.detail(slow), delayMillis = 1_200))
        val holding = CompletableFuture.supplyAsync { g.paymentDetail(slow) }
        Thread.sleep(300)
        val idle = paymentId()
        val t0 = System.nanoTime()
        g.paymentDetail(idle) shouldBe PaymentReadResult.Unavailable("PAYMENT_OPS_BUSY")
        ((System.nanoTime() - t0) / 1_000_000 < 200) shouldBe true
        StubPaymentOpsServer.requestsForPath("/internal/v1/admin/payments/$idle").size shouldBe 0
        holding.get(10, TimeUnit.SECONDS).shouldBeInstanceOf<PaymentReadResult.Found>()

        val late = paymentId()
        StubPaymentOpsServer.detail(late, StubPaymentOpsServer.Reply(200, TransactionJson.detail(late), delayMillis = 1_500))
        gateway(settings(read = Duration.ofMillis(400))).paymentDetail(late).shouldBeInstanceOf<PaymentReadResult.Unavailable>()
        gateway(settings(baseUrl = "http://127.0.0.1:1")).paymentDetail(paymentId()) shouldBe PaymentReadResult.Unavailable("CONNECT_FAILED")
    }

    test("no signing key, signature header or v1 value, order name, merchant order id or MID appears in any log line") {
        val order = orderId()
        val id = paymentId()
        StubPaymentOpsServer.search(order, StubPaymentOpsServer.Reply(200, TransactionJson.list(TransactionJson.item(id, orderId = order))))
        StubPaymentOpsServer.detail(id, StubPaymentOpsServer.Reply(200, TransactionJson.detail(id)))
        val lines = captureLogs {
            gateway().searchPayments(TransactionSearchCriteria(merchantAccountId = "mid-demo-1", merchantOrderId = order)).shouldBeInstanceOf<PaymentReadResult.Found>()
            gateway().paymentDetail(id).shouldBeInstanceOf<PaymentReadResult.Found>()
        }
        withClue("control: the gateway logs its calls") { lines.count { it.contains("payment-ops-read") } shouldBe 2 }
        val signature = StubPaymentOpsServer.received.last { it.path == "/internal/v1/admin/payments/$id" }.signature!!
        lines.forEach { line ->
            line shouldNotContain key
            line shouldNotContain signature.substringAfter("v1=")
            line shouldNotContain "PayCore-Admin-Signature"
            line shouldNotContain TransactionJson.ORDER_PREVIEW
            line shouldNotContain order
            line shouldNotContain "mid-demo-1"
            line shouldNotContain id
        }
    }
})

package com.paycore.admin.paymentops

import com.paycore.admin.paymentops.adapter.integration.AdminCallSigner
import com.paycore.admin.paymentops.adapter.integration.HttpPaymentOpsGateway
import com.paycore.admin.paymentops.adapter.integration.PaymentOpsConfiguration
import com.paycore.admin.paymentops.adapter.integration.PaymentOpsSettings
import com.paycore.admin.paymentops.application.provided.CallPurpose
import com.paycore.admin.paymentops.application.provided.ExecutionResult
import com.paycore.admin.paymentops.application.provided.TargetResult
import com.paycore.admin.support.StubPaymentOpsServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.transaction.support.TransactionSynchronizationManager
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant

/**
 * HTTP gateway(ADMIN-PAYMENT-OPS-001 §4.2·§4.7·§4.15) — 실제 HTTP 대역([StubPaymentOpsServer])으로:
 * - `PaymentOpsResponseMappingGoldenSpec`: golden ③ `responseCases` 27개를 대역이 그대로 응답 → 기대 ADMIN 상태·경보.
 * - 서명 헤더는 대역이 앱과 독립으로 검증(golden ② 키), query 없음, 본문 바이트 그대로.
 * - `PaymentCallOutsideTransactionSpec`: DB TX 안 호출은 즉시 `IllegalStateException`(PAYMENT에 요청이 가지 않는다).
 * - timeout(read 2초) → UNKNOWN `TIMEOUT`, 응답 상한 64 KiB 초과 → UNKNOWN, 연결 실패 → Unavailable·UNKNOWN, permit 0 → 호출 없이 busy.
 * - `PaymentOpsSettingsSpec`(L-6·L-3): 시간 순서 위반·context path·LIVE Demo 키·base-url 경로는 기동 거부.
 */
class PaymentOpsGatewaySpec : FunSpec({

    StubPaymentOpsServer.start()
    val mapper = JsonMapper.builder().build()
    val api = mapper.readTree(PaymentOpsGatewaySpec::class.java.getResource("/contracts/admin-payment-ops-api-v1.golden.json")!!.readText(Charsets.UTF_8))
    val key = "dGVzdC1vbmx5LWFkbWluLWNhbGwtZ29sZGVuLWtleS0wMDAx"
    fun settings(
        baseUrl: String = "http://127.0.0.1:${StubPaymentOpsServer.PORT}", read: Duration = Duration.ofSeconds(2), execution: Int = 4, backoffMax: Duration = Duration.ofMinutes(1),
        window: Duration = Duration.ofHours(24), paymentMax: Duration = Duration.ofHours(24), signingKey: String = key, initial: Duration = Duration.ofSeconds(30),
    ) = PaymentOpsSettings(
        baseUrl, "adm-golden-k1", signingKey, Duration.ofSeconds(1), read, PaymentOpsSettings.Permits(4, 4, execution), 5, window, initial, backoffMax,
        paymentMax, Duration.ofMinutes(5), 50, Duration.ofMinutes(2), 10, Duration.ofDays(1),
    )
    fun gateway(s: PaymentOpsSettings = settings()) = HttpPaymentOpsGateway(s, AdminCallSigner(s.keyId, s.signingKey())) { Instant.now() }
    var seq = 9_000L
    fun approvalId() = "apr_" + (++seq).toString().padStart(26, '0')
    fun body(approvalId: String) = "{\"approvalId\":\"$approvalId\",\"x\":1}".toByteArray()

    test("PaymentOpsResponseMappingGoldenSpec: every golden response case replayed over HTTP maps to its expected ADMIN state and alert") {
        val placeholders = mapOf("CANCELLATION_ID" to "\"pcn_01JC0000000000000000000001\"", "TIMESTAMP" to "\"2026-10-27T01:02:03.456Z\"", "CONTENT_HASH" to "\"opd1:${"0".repeat(64)}\"")
        val cases = api["responseCases"].toList()
        val g = gateway()
        cases.associate { c ->
            val id = approvalId()
            var text = mapper.writeValueAsString(c["body"])
            placeholders.forEach { (name, value) -> text = text.replace("{\"\$placeholder\":\"$name\"}", value) }
            StubPaymentOpsServer.execution(id, StubPaymentOpsServer.Reply(c["httpStatus"].asInt(), text))
            val state = when (val r = g.execute(id, body(id))) {
                is ExecutionResult.Started -> "EXECUTED" to false
                is ExecutionResult.OwnerRejected -> "OWNER_REJECTED" to r.integrityAlert
                is ExecutionResult.Unknown -> "EXECUTION_UNKNOWN" to (r.integrityFlag != null)
            }
            c["name"].asString() to state
        } shouldBe cases.associate { c -> c["name"].asString() to (c["expectedAdminState"].asString() to c["expectedIntegrityAlert"].asBoolean()) }
    }

    test("the POST carries a signature the stub verifies independently (golden ② key), no query string, and the exact body bytes") {
        val id = approvalId()
        StubPaymentOpsServer.execution(id, StubPaymentOpsServer.Reply(200, "{\"cancellationId\":\"pcn_01JC0000000000000000000002\",\"status\":\"IN_PROGRESS\",\"existing\":false}"))
        gateway().execute(id, body(id)) shouldBe ExecutionResult.Started("pcn_01JC0000000000000000000002", false)
        val r = StubPaymentOpsServer.requestsFor(id).single()
        listOf(r.method, r.path, r.query, r.signatureValid, r.body.toList()) shouldBe listOf("POST", "/internal/v1/admin/operator-cancellations", null, true, body(id).toList())
    }

    test("target lookup: 200 parsed exactly, 404 PAYMENT_NOT_FOUND, malformed 200 and other statuses are Unavailable") {
        val g = gateway()
        StubPaymentOpsServer.targetFound("pay_01JC00000000000000000000T1", revision = 7, inFlight = true)
        StubPaymentOpsServer.target("pay_01JC00000000000000000000T2", StubPaymentOpsServer.Reply(200, "{\"paymentId\":\"pay_01JC00000000000000000000T2\",\"cancellableMinor\":\"1\"}"))
        StubPaymentOpsServer.target("pay_01JC00000000000000000000T3", StubPaymentOpsServer.Reply(503, "{\"error\":\"ADMIN_READ_BUSY\"}"))
        val found = g.target("pay_01JC00000000000000000000T1", CallPurpose.APPROVAL).shouldBeInstanceOf<TargetResult.Found>().view
        listOf(found.balanceRevision, found.inFlightCancel, found.currency, found.cancellableMinor) shouldBe listOf(7L, true, "KRW", 10_000L)
        listOf(
            g.target("pay_01JC00000000000000000000T9", CallPurpose.LOOKUP), g.target("pay_01JC00000000000000000000T2", CallPurpose.LOOKUP),
            g.target("pay_01JC00000000000000000000T3", CallPurpose.LOOKUP),
        ) shouldBe listOf(TargetResult.NotFound, TargetResult.Unavailable("RESPONSE_UNREADABLE"), TargetResult.Unavailable("HTTP_503"))
        StubPaymentOpsServer.requestsForPath("/internal/v1/admin/payments/pay_01JC00000000000000000000T1/operator-cancel-target").single().let { listOf(it.method, it.signatureValid, it.body.size) } shouldBe
            listOf("GET", true, 0)
    }

    test("timeout → UNKNOWN TIMEOUT; response over 64 KiB → UNKNOWN; connection refused → Unavailable / UNKNOWN") {
        val id = approvalId()
        StubPaymentOpsServer.execution(id, StubPaymentOpsServer.Reply(200, "{}", delayMillis = 3_500))
        gateway().execute(id, body(id)) shouldBe ExecutionResult.Unknown("TIMEOUT", null, false)
        val big = approvalId()
        StubPaymentOpsServer.execution(big, StubPaymentOpsServer.Reply(409, "{\"error\":\"CANCEL_IN_PROGRESS\",\"pad\":\"${"x".repeat(PaymentOpsSettings.RESPONSE_MAX_BYTES)}\"}"))
        gateway().execute(big, body(big)) shouldBe ExecutionResult.Unknown("RESPONSE_UNREADABLE", null, false)
        val closed = gateway(settings(baseUrl = "http://127.0.0.1:1"))
        closed.target("pay_01JC00000000000000000000T1", CallPurpose.APPROVAL).shouldBeInstanceOf<TargetResult.Unavailable>()
        closed.execute(id, body(id)).shouldBeInstanceOf<ExecutionResult.Unknown>()
    }

    test("PaymentCallOutsideTransactionSpec: inside an active DB transaction the gateway refuses before calling PAYMENT") {
        val id = approvalId()
        val before = StubPaymentOpsServer.received.size
        TransactionSynchronizationManager.setActualTransactionActive(true)
        try {
            shouldThrow<IllegalStateException> { gateway().execute(id, body(id)) }
            shouldThrow<IllegalStateException> { gateway().target("pay_01JC00000000000000000000T1", CallPurpose.APPROVAL) }
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false)
        }
        StubPaymentOpsServer.received.size shouldBe before
    }

    test("S7 review L-1 (I-113): the Demo values in the main application.yml pass the startup checks (backoff-initial 40s > connect 2s + read 30s, L-6 order kept)") {
        val yaml = org.springframework.beans.factory.config.YamlPropertiesFactoryBean().apply {
            setResources(org.springframework.core.io.ClassPathResource("application.yml"))
        }.getObject()!!
        fun d(name: String): Duration = org.springframework.boot.convert.DurationStyle.detectAndParse(yaml.getProperty("paycore.admin.payment-ops.$name")!!)
        fun i(name: String): Int = yaml.getProperty("paycore.admin.payment-ops.$name")!!.toInt()
        val demo = PaymentOpsSettings(
            "http://127.0.0.1:1", "adm-golden-k1", key, d("connect-timeout"), d("read-timeout"),
            PaymentOpsSettings.Permits(i("permits.approval"), i("permits.lookup"), i("permits.execution")), i("lookup-per-minute"), d("execution-window"),
            d("backoff-initial"), d("backoff-max"), d("payment-max-execution-window"), d("deadline-skew"), i("recovery-batch-size"), d("decision-stable-delay"),
            i("reconciliation-max-pages"), d("reconciliation-full-scan-interval"),
        )
        listOf(demo.backoffInitial, demo.connectTimeout, demo.readTimeout) shouldBe listOf(Duration.ofSeconds(40), Duration.ofSeconds(2), Duration.ofSeconds(30))
        (demo.staleAfter < demo.backoffMax && demo.backoffMax < demo.executionWindow) shouldBe true
    }

    test("PaymentOpsSettingsSpec (L-6, L-3, I-31): time order, base-url path, key length, context path and LIVE Demo key are startup failures") {
        settings().staleAfter shouldBe Duration.ofSeconds(33)
        listOf(
            { settings(backoffMax = Duration.ofSeconds(33)) }, { settings(window = Duration.ofMinutes(1)) }, { settings(window = Duration.ofHours(25)) },
            { settings(baseUrl = "http://127.0.0.1:1/pay") }, { settings(baseUrl = "ftp://127.0.0.1:1") }, { settings(signingKey = "c2hvcnQ=") },
            // S6 리뷰 L-3 → S7 리뷰 L-1(I-113, Coordinator 결정): backoff-initial > connect + read(= 2 × initial > 2 × (connect + read)). 경계(같음)도 거부.
            { settings(initial = Duration.ofSeconds(1), read = Duration.ofSeconds(5)) },
            { settings(initial = Duration.ofSeconds(3), read = Duration.ofSeconds(2)) },
            { settings(initial = Duration.ofSeconds(4), read = Duration.ofSeconds(3)) },
        ).forEach { shouldThrow<IllegalArgumentException> { it() } }
        settings(initial = Duration.ofSeconds(4), read = Duration.ofSeconds(2)).backoffInitial shouldBe Duration.ofSeconds(4)
        PaymentOpsConfiguration.startupGuard(null, "DEMO", settings())
        PaymentOpsConfiguration.startupGuard("", "LIVE", settings(signingKey = "bGl2ZS1zZWNyZXQta2V5LW5vdC1hLWRlbW8ta2V5LTAwMDE="))
        shouldThrow<IllegalStateException> { PaymentOpsConfiguration.startupGuard("/admin", "DEMO", settings()) }
        shouldThrow<IllegalStateException> { PaymentOpsConfiguration.startupGuard("/", "DEMO", settings()) }
        shouldThrow<IllegalStateException> { PaymentOpsConfiguration.startupGuard(null, "LIVE", settings()) }
        settings().toString().contains(key) shouldBe false
    }
})

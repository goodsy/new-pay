package com.paycore.admin.merchantops

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.paycore.admin.merchantops.adapter.integration.HttpMasterOpsGateway
import com.paycore.admin.merchantops.adapter.integration.MasterCallSigner
import com.paycore.admin.merchantops.adapter.integration.MasterOpsConfiguration
import com.paycore.admin.merchantops.adapter.integration.MasterOpsSettings
import com.paycore.admin.merchantops.application.provided.MerchantSearchCriteria
import com.paycore.admin.merchantops.application.required.MasterCallResult
import com.paycore.admin.paymentops.adapter.integration.AdminCallSigner
import com.paycore.admin.paymentops.adapter.integration.HttpPaymentOpsGateway
import com.paycore.admin.paymentops.adapter.integration.PaymentOpsSettings
import com.paycore.admin.paymentops.application.provided.CallPurpose
import com.paycore.admin.paymentops.application.provided.TargetResult
import com.paycore.admin.support.StubMasterOpsServer
import com.paycore.admin.support.StubPaymentOpsServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
 * ADMIN → MASTER 호출(MERCHANT-OPS-001 §4·AC 7·8 — 실제 HTTP 대역 [StubMasterOpsServer], 서명은 대역이 앱과 독립 검증):
 * golden 서명 벡터 바이트 일치, 응답 허용 필드 모양(알 수 없는 필드·기관 가맹점 번호 필드 → 해석 불가), 404·5xx·과대 응답·timeout·연결 실패 → 값, DB TX 안 호출 거부,
 * 전용 permit(포화 시 즉시 busy, PAYMENT ops 조회는 영향 없음), 설정·기동 검사(PAYMENT ops 키와 같으면 거부, LIVE Demo 키 거부).
 */
class MasterOpsGatewaySpec : BehaviorSpec({

    StubMasterOpsServer.start()
    StubPaymentOpsServer.start()
    val json = JsonMapper.builder().build()

    fun settings(baseUrl: String = "http://127.0.0.1:${StubMasterOpsServer.PORT}", permits: Int = 2, read: Duration = Duration.ofSeconds(2)) = MasterOpsSettings(
        baseUrl, StubMasterOpsServer.KEY_ID, StubMasterOpsServer.KEY_BASE64, Duration.ofSeconds(1), read, permits, 30, 10,
    )

    fun gateway(s: MasterOpsSettings = settings()) = HttpMasterOpsGateway(s, MasterCallSigner(s.keyId, s.signingKey())) { Instant.now() }

    var seq = System.nanoTime() % 1_000_000L
    fun merchantId() = "mer_01JC" + (++seq).toString().padStart(22, '0')

    /** 루트 로거 캡처: 로거 이름·형식화된 메시지·예외 사슬 메시지(MERCHANT-OPS-001 §5.4 — 로그에 키·서명·등록번호 없음). */
    fun captureLogs(block: () -> Unit): List<String> {
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(appender)
        try {
            block()
        } finally {
            root.detachAppender(appender)
        }
        return appender.list.map { e ->
            buildString {
                append(e.loggerName).append(' ').append(e.formattedMessage)
                var t = e.throwableProxy
                while (t != null) {
                    append(" | ").append(t.className).append(": ").append(t.message)
                    t = t.cause
                }
            }
        }
    }

    Given("the signature contract (golden ② shared with payment-api and master-api)") {
        Then("every sign vector is reproduced byte-for-byte by the MASTER signer") {
            val golden = json.readTree(requireNotNull(javaClass.getResourceAsStream("/contracts/admin-call-signature-v1.golden.json")).readAllBytes())
            val keys = golden.get("keys").associate { it.get("kid").asString() to java.util.Base64.getDecoder().decode(it.get("secretBase64").asString()) }
            golden.get("signVectors").forEach { v ->
                val signer = MasterCallSigner(v.get("kid").asString(), keys.getValue(v.get("kid").asString()))
                val body = v.get("body").asString().toByteArray(Charsets.UTF_8)
                signer.canonical(v.get("method").asString(), v.get("path").asString(), v.get("t").asLong(), body) shouldBe v.get("canonical").asString()
                signer.header(v.get("method").asString(), v.get("path").asString(), v.get("t").asLong(), body) shouldBe v.get("header").asString()
            }
        }
    }

    Given("MASTER answers") {
        Then("detail 200 in the contract shape is Found, signed (stub verifies independently), GET without query") {
            val id = merchantId()
            StubMasterOpsServer.reply(StubMasterOpsServer.merchantPath(id), StubMasterOpsServer.Reply(200, StubMasterOpsServer.detailJson(id)))
            val r = gateway().detail(id).shouldBeInstanceOf<MasterCallResult.Found>()
            @Suppress("UNCHECKED_CAST")
            ((r.document["merchant"] as Map<String, Any?>)["merchantId"]) shouldBe id
            val req = StubMasterOpsServer.requestsForPath(StubMasterOpsServer.merchantPath(id)).single()
            listOf(req.method, req.query, req.signatureValid) shouldBe listOf("GET", null, true)
        }
        Then("an unknown field anywhere (e.g. institutionMerchantRef in a card route) makes the response unreadable — fail closed") {
            val id = merchantId()
            StubMasterOpsServer.reply(
                StubMasterOpsServer.merchantPath(id),
                StubMasterOpsServer.Reply(200, StubMasterOpsServer.detailJson(id, routeExtra = ""","institutionMerchantRef":"${StubMasterOpsServer.INSTITUTION_CANARY}"""")),
            )
            gateway().detail(id) shouldBe MasterCallResult.Unavailable("RESPONSE_UNREADABLE")
            val other = merchantId()
            StubMasterOpsServer.reply(StubMasterOpsServer.merchantPath(other), StubMasterOpsServer.Reply(200, StubMasterOpsServer.detailJson(other).replace("\"name\":\"Demo Merchant\"", "\"name\":1")))
            gateway().detail(other) shouldBe MasterCallResult.Unavailable("RESPONSE_UNREADABLE")
        }
        Then("404 MERCHANT_NOT_FOUND is NotFound; other 404 and 5xx are Unavailable; an oversized body is unreadable") {
            gateway().detail(merchantId()) shouldBe MasterCallResult.NotFound
            val id = merchantId()
            StubMasterOpsServer.reply(StubMasterOpsServer.merchantPath(id), StubMasterOpsServer.Reply(404, "{\"error\":\"OTHER\"}"))
            gateway().detail(id) shouldBe MasterCallResult.Unavailable("HTTP_404")
            StubMasterOpsServer.reply(StubMasterOpsServer.merchantPath(id), StubMasterOpsServer.Reply(503, "{\"error\":\"ADMIN_READ_BUSY\"}"))
            gateway().detail(id) shouldBe MasterCallResult.Unavailable("HTTP_503")
            StubMasterOpsServer.reply(StubMasterOpsServer.merchantPath(id), StubMasterOpsServer.Reply(200, " ".repeat(MasterOpsSettings.RESPONSE_MAX_BYTES + 1)))
            gateway().detail(id) shouldBe MasterCallResult.Unavailable("RESPONSE_UNREADABLE")
        }
        Then("search sends the criteria as a signed POST body (the number only in the body, never in the URL); history uses the path cursor") {
            val id = merchantId()
            StubMasterOpsServer.reply(StubMasterOpsServer.SEARCH, StubMasterOpsServer.Reply(200, StubMasterOpsServer.directoryJson(id, "biz_" + id.removePrefix("mer_"))))
            val before = StubMasterOpsServer.requestsForPath(StubMasterOpsServer.SEARCH).size
            gateway().search(MerchantSearchCriteria(registrationNumber = "1234567890", limit = 5)).shouldBeInstanceOf<MasterCallResult.Found>()
            val req = StubMasterOpsServer.requestsForPath(StubMasterOpsServer.SEARCH).drop(before).single()
            listOf(req.method, req.query, req.signatureValid) shouldBe listOf("POST", null, true)
            String(req.body, Charsets.UTF_8) shouldBe """{"registrationNumber":"1234567890","limit":5}"""
            req.path shouldNotContain "1234567890"
            StubMasterOpsServer.reply(StubMasterOpsServer.historyPath(id, 3), StubMasterOpsServer.Reply(200, StubMasterOpsServer.historyJson(id)))
            gateway().history(id, 3).shouldBeInstanceOf<MasterCallResult.Found>()
        }
        Then("a read timeout and a refused connection are values, not exceptions") {
            val id = merchantId()
            StubMasterOpsServer.reply(StubMasterOpsServer.merchantPath(id), StubMasterOpsServer.Reply(200, StubMasterOpsServer.detailJson(id), delayMillis = 1_500))
            gateway(settings(read = Duration.ofMillis(300))).detail(id) shouldBe MasterCallResult.Unavailable("TIMEOUT")
            gateway(settings(baseUrl = "http://127.0.0.1:9")).detail(id) shouldBe MasterCallResult.Unavailable("CONNECT_FAILED")
        }
    }

    Given("a response whose headers arrive in time but whose body trickles (review MINOR-3)") {
        Then("the read timeout also bounds reading the body: the call ends near the timeout as a value, not after the full trickle") {
            val id = merchantId()
            StubMasterOpsServer.reply(StubMasterOpsServer.merchantPath(id), StubMasterOpsServer.Reply(200, StubMasterOpsServer.detailJson(id), bodyDripMillis = 3_000))
            val started = System.nanoTime()
            val result = gateway(settings(read = Duration.ofMillis(400))).detail(id)
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            // 본문 단계의 timeout은 Spring JdkClientHttpRequest가 본문 스트림을 닫아 끝낸다 — 분류는 TIMEOUT이 아니라 CALL_FAILED(관찰값 고정, 명세 §9.1 MINOR-3).
            result shouldBe MasterCallResult.Unavailable("CALL_FAILED")
            (elapsed < 2_000) shouldBe true
        }
    }

    Given("logs of the MASTER call (review MINOR-5 — non-exposure)") {
        Then("no signing key, signature header, v1 value or registration number appears in any log line or exception message; operation and class are logged") {
            val number = "9081726354"
            val id = merchantId()
            StubMasterOpsServer.reply(StubMasterOpsServer.merchantPath(id), StubMasterOpsServer.Reply(200, StubMasterOpsServer.detailJson(id)))
            StubMasterOpsServer.reply(StubMasterOpsServer.SEARCH, StubMasterOpsServer.Reply(200, StubMasterOpsServer.EMPTY_DIRECTORY))
            val slow = merchantId()
            StubMasterOpsServer.reply(StubMasterOpsServer.merchantPath(slow), StubMasterOpsServer.Reply(200, StubMasterOpsServer.detailJson(slow), delayMillis = 1_000))
            val before = StubMasterOpsServer.received.size
            val lines = captureLogs {
                gateway().detail(id).shouldBeInstanceOf<MasterCallResult.Found>()
                gateway().search(MerchantSearchCriteria(registrationNumber = number)).shouldBeInstanceOf<MasterCallResult.Found>()
                gateway().detail(merchantId()) shouldBe MasterCallResult.NotFound
                gateway(settings(read = Duration.ofMillis(200))).detail(slow) shouldBe MasterCallResult.Unavailable("TIMEOUT")
                gateway(settings(baseUrl = "http://127.0.0.1:9")).detail(id) shouldBe MasterCallResult.Unavailable("CONNECT_FAILED")
            }
            val signatures = StubMasterOpsServer.received.drop(before).mapNotNull { it.signature }
            signatures.size shouldBe 4
            val v1s = signatures.map { it.substringAfter("v1=") }
            v1s.forEach { it.length shouldBe 64 }
            // 대조군: 캡처가 실제로 동작한다(호출마다 operation·결과 분류 한 줄).
            lines.count { "operation=master-ops-" in it } shouldBe 5
            (listOf(StubMasterOpsServer.KEY_BASE64, number) + signatures + v1s).forEach { s -> lines.filter { s in it }.shouldBeEmpty() }
        }
    }

    Given("transaction and permit rules") {
        Then("calling inside an active DB transaction is refused before any HTTP call") {
            val id = merchantId()
            TransactionSynchronizationManager.setActualTransactionActive(true)
            try {
                shouldThrow<IllegalStateException> { gateway().detail(id) }
            } finally {
                TransactionSynchronizationManager.setActualTransactionActive(false)
            }
            StubMasterOpsServer.requestsForPath(StubMasterOpsServer.merchantPath(id)) shouldBe emptyList()
        }
        Then("a saturated MASTER permit answers busy immediately and does not slow PAYMENT ops lookups (separate pools)") {
            val slow = merchantId()
            StubMasterOpsServer.reply(StubMasterOpsServer.merchantPath(slow), StubMasterOpsServer.Reply(200, StubMasterOpsServer.detailJson(slow), delayMillis = 1_500))
            val master = gateway(settings(permits = 1))
            val holder = CompletableFuture.supplyAsync { master.detail(slow) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (StubMasterOpsServer.requestsForPath(StubMasterOpsServer.merchantPath(slow)).isEmpty() && System.nanoTime() < deadline) Thread.sleep(20)
            val payment = PaymentOpsSettings(
                "http://127.0.0.1:${StubPaymentOpsServer.PORT}", "adm-golden-k1", "dGVzdC1vbmx5LWFkbWluLWNhbGwtZ29sZGVuLWtleS0wMDAx", Duration.ofSeconds(1),
                Duration.ofSeconds(5), PaymentOpsSettings.Permits(1, 1, 1), 5, Duration.ofHours(24), Duration.ofSeconds(30), Duration.ofMinutes(1), Duration.ofHours(24),
                Duration.ofMinutes(5), 50, Duration.ofMinutes(2), 10, Duration.ofDays(1),
            )
            val pay = "pay_01JC" + (++seq).toString().padStart(22, '0')
            StubPaymentOpsServer.targetFound(pay)
            val started = System.nanoTime()
            master.detail(merchantId()) shouldBe MasterCallResult.Unavailable("MASTER_OPS_BUSY")
            HttpPaymentOpsGateway(payment, AdminCallSigner(payment.keyId, payment.signingKey())) { Instant.now() }.target(pay, CallPurpose.LOOKUP)
                .shouldBeInstanceOf<TargetResult.Found>()
            (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_200) shouldBe true
            holder.get(10, TimeUnit.SECONDS).shouldBeInstanceOf<MasterCallResult.Found>()
        }
    }

    Given("settings and startup guard") {
        Then("no defaults; base-url without path; key length; rates in range; toString hides the key") {
            shouldThrow<IllegalArgumentException> { MasterOpsSettings(null, "k", StubMasterOpsServer.KEY_BASE64, Duration.ofSeconds(1), Duration.ofSeconds(1), 1, 1, 1) }
            shouldThrow<IllegalArgumentException> { MasterOpsSettings("http://h/x", "k", StubMasterOpsServer.KEY_BASE64, Duration.ofSeconds(1), Duration.ofSeconds(1), 1, 1, 1) }
            shouldThrow<IllegalArgumentException> { MasterOpsSettings("http://h", "k", "c2hvcnQ=", Duration.ofSeconds(1), Duration.ofSeconds(1), 1, 1, 1) }
            shouldThrow<IllegalArgumentException> { MasterOpsSettings("http://h", "k", StubMasterOpsServer.KEY_BASE64, Duration.ofSeconds(1), Duration.ofSeconds(1), 1, 1, 0) }
            settings().toString() shouldNotContain StubMasterOpsServer.KEY_BASE64
        }
        Then("a master-ops key equal to the payment-ops key (bytes or kid) is refused; LIVE refuses a Demo key; a context path is refused") {
            val s = settings()
            MasterOpsConfiguration.startupGuard(null, "DEMO", s, "adm-golden-k1", "dGVzdC1vbmx5LWFkbWluLWNhbGwtZ29sZGVuLWtleS0wMDAx")
            shouldThrow<IllegalStateException> { MasterOpsConfiguration.startupGuard(null, "DEMO", s, "other-kid", StubMasterOpsServer.KEY_BASE64) }
                .message!! shouldNotContain StubMasterOpsServer.KEY_BASE64
            shouldThrow<IllegalStateException> { MasterOpsConfiguration.startupGuard(null, "DEMO", s, StubMasterOpsServer.KEY_ID, "dGVzdC1vbmx5LWFkbWluLWNhbGwtZ29sZGVuLWtleS0wMDAx") }
            shouldThrow<IllegalStateException> { MasterOpsConfiguration.startupGuard(null, "LIVE", s, null, null) }.message!! shouldContain "LIVE"
            shouldThrow<IllegalStateException> { MasterOpsConfiguration.startupGuard("/admin", "DEMO", s, null, null) }
        }
    }
})

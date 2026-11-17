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
import com.paycore.admin.merchantops.application.required.MasterCommand
import com.paycore.admin.merchantops.application.required.MasterCommandKind
import com.paycore.admin.merchantops.application.required.MasterCommandResult
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

    fun settings(baseUrl: String = "http://127.0.0.1:${StubMasterOpsServer.PORT}", permits: Int = 2, read: Duration = Duration.ofSeconds(2), writePermits: Int = 2) = MasterOpsSettings(
        // TXN-LIST-ENRICH-001 §3.6: 라벨 permit·라벨 read timeout(기본값 없음 — 시험 값). MERCHANT-CODE-001 C-35: 라벨 분당 창(기본값 없음 — Demo 60, 시험 값).
        baseUrl, StubMasterOpsServer.KEY_ID, StubMasterOpsServer.KEY_BASE64, Duration.ofSeconds(1), read, permits, 30, 10, writePermits, 20, 2, minOf(read, Duration.ofMillis(500)), 60,
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

    Given("MERCHANT-OPS-002 §4.1 registration commands (B1-1)") {
        // 기대값 변경(BUSINESS-PROFILE-001 BIZ-4 C-27): 옛 REGISTER_BUSINESS 종류 제거 — 공통 시험의 기본 운반 명령을 Site 등록으로(같은 단언).
        fun command(kind: MasterCommandKind = MasterCommandKind.REGISTER_MERCHANT_SITE, sub: String = java.util.UUID.randomUUID().toString().replace("-", "")) = MasterCommand(
            kind, "mo1.01JC00000000000000000000V1.$sub", "admin:adm_01JC00000000000000000000V1", "mos.$sub",
            when (kind) {
                // 기대값 변경(MERCHANT-CODE-001 MC-1 §4 ②): 가맹점 등록 필드에 merchantCode(필수 — 필드 증가, 완화 아님).
                MasterCommandKind.REGISTER_MERCHANT -> mapOf("businessId" to "biz_01JC0000000000000000000001", "displayName" to "시험 가맹점", "merchantCode" to "shopabcd12")
                MasterCommandKind.REGISTER_MERCHANT_SITE -> mapOf("merchantId" to "mer_01JC0000000000000000000001", "channelType" to "WEB", "channelIdentifier" to "demo-shop2.paycore.test")
                MasterCommandKind.REGISTER_CONTRACT -> mapOf(
                    "partyBusinessId" to "biz_01JC0000000000000000000001", "scopeMerchantId" to "mer_01JC0000000000000000000001",
                    "scopeSiteIds" to listOf("site_01JC0000000000000000000001"), "effectiveFrom" to "2026-11-08T15:00:00.000Z",
                )
                MasterCommandKind.ADD_SERVICE_AGREEMENT -> mapOf("contractId" to "ctr_01JC0000000000000000000001", "serviceKind" to "CARD_PG_PAYMENT")
                MasterCommandKind.REGISTER_CONTRACT_REVISION -> mapOf(
                    "contractId" to "ctr_01JC0000000000000000000001", "scopeMerchantId" to "mer_01JC0000000000000000000001",
                    "scopeSiteIds" to emptyList<String>(), "effectiveFrom" to "2026-11-08T15:00:00.000Z",
                )
                // 목록 추가(B3-1 §9.14): 1인 개통 등록·Site 심사 거절(시험 본문은 MasterApprovalGatewayB3Spec).
                MasterCommandKind.REGISTER_PAYMENT_METHOD_ACTIVATION -> mapOf("merchantAccountId" to "mid_01JC0000000000000000000001", "paymentMethod" to "CARD")
                MasterCommandKind.REJECT_SITE_REVIEW -> mapOf("merchantSiteId" to "site_01JC0000000000000000000001", "reasonCode" to "DOCUMENT_INSUFFICIENT")
            },
        )
        fun lastBody(path: String) = String(StubMasterOpsServer.requestsForPath(path).last().body, Charsets.UTF_8)

        Then("the signed POST body carries the common fields then the command fields in order; the stub verifies the signature independently; the write path signature is reproduced byte-for-byte") {
            StubMasterOpsServer.reply(StubMasterOpsServer.MERCHANTS, StubMasterOpsServer.Reply(200, StubMasterOpsServer.succeeded("mer_01JC0000000000000000000009")))
            val c = command(MasterCommandKind.REGISTER_MERCHANT, sub = "0123456789abcdef0123456789abcdef")
            gateway().command(c) shouldBe MasterCommandResult.Decided(MasterCommandResult.Outcome.SUCCEEDED, null, "mer_01JC0000000000000000000009", false)
            val req = StubMasterOpsServer.requestsForPath(StubMasterOpsServer.MERCHANTS).last()
            listOf(req.method, req.query, req.signatureValid) shouldBe listOf("POST", null, true)
            lastBody(StubMasterOpsServer.MERCHANTS) shouldBe """{"idempotencyKey":"mo1.01JC00000000000000000000V1.0123456789abcdef0123456789abcdef",""" +
                """"actor":"admin:adm_01JC00000000000000000000V1","correlationId":"mos.0123456789abcdef0123456789abcdef","businessId":"biz_01JC0000000000000000000001","displayName":"시험 가맹점","merchantCode":"shopabcd12"}"""
            // C-9: golden 파일은 바꾸지 않는다 — 쓰기 경로 사례는 앱 시험에서 독립 HMAC(javax.crypto 직접)과 대조한다.
            val body = req.body
            val t = 1_767_000_000L
            val expectedCanonical = listOf("paycore.admin-call.v1", "POST", StubMasterOpsServer.MERCHANTS, t.toString(),
                java.security.MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }).joinToString("\n")
            val mac = javax.crypto.Mac.getInstance("HmacSHA256").apply { init(javax.crypto.spec.SecretKeySpec(java.util.Base64.getDecoder().decode(StubMasterOpsServer.KEY_BASE64), "HmacSHA256")) }
            val expected = "t=$t,kid=${StubMasterOpsServer.KEY_ID},v1=" + mac.doFinal(expectedCanonical.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            MasterCallSigner(StubMasterOpsServer.KEY_ID, java.util.Base64.getDecoder().decode(StubMasterOpsServer.KEY_BASE64)).header("POST", StubMasterOpsServer.MERCHANTS, t, body) shouldBe expected
        }
        Then("200 shapes are fail-closed: a rejection carrying a reference (C-12), unknown fields, wrong types or an unknown outcome are Unknown(RESPONSE_UNREADABLE)") {
            StubMasterOpsServer.reply(StubMasterOpsServer.SITES, StubMasterOpsServer.Reply(200, StubMasterOpsServer.rejected("SITE_CHANNEL_DUPLICATE", replayed = true)))
            gateway().command(command()) shouldBe MasterCommandResult.Decided(MasterCommandResult.Outcome.REJECTED, "SITE_CHANNEL_DUPLICATE", null, true)
            listOf(
                """{"outcome":"REJECTED","rejection":"SITE_CHANNEL_DUPLICATE","reference":"site_01JC0000000000000000000001","replayed":false}""",
                """{"outcome":"REJECTED","rejection":"SITE_CHANNEL_DUPLICATE","reference":null,"replayed":false}""",
                """{"outcome":"SUCCEEDED","reference":"site_01JC0000000000000000000001","replayed":false,"extra":1}""",
                """{"outcome":"SUCCEEDED","reference":"site_01JC0000000000000000000001","replayed":"false"}""",
                """{"outcome":"SUCCEEDED","reference":"not an id","replayed":false}""",
                """{"outcome":"SUCCEEDED","replayed":false}""",
                """{"outcome":"MAYBE","replayed":false}""",
                """[]""", "", "not json",
            ).forEach { reply ->
                StubMasterOpsServer.reply(StubMasterOpsServer.SITES, StubMasterOpsServer.Reply(200, reply))
                io.kotest.assertions.withClue(reply) { gateway().command(command()) shouldBe MasterCommandResult.Unknown("RESPONSE_UNREADABLE") }
            }
            StubMasterOpsServer.reply(StubMasterOpsServer.SITES, StubMasterOpsServer.Reply(200, StubMasterOpsServer.succeeded("site_01JC0000000000000000000001") + " ".repeat(MasterOpsSettings.COMMAND_RESPONSE_MAX_BYTES)))
            gateway().command(command()) shouldBe MasterCommandResult.Unknown("RESPONSE_UNREADABLE")
        }
        Then("B1-2: the contract body carries scopeSiteIds as a JSON array and omits an absent effectiveTo; the agreement body is two strings; both are signed and verified") {
            StubMasterOpsServer.reply(StubMasterOpsServer.CONTRACTS, StubMasterOpsServer.Reply(200, StubMasterOpsServer.succeeded("ctr_01JC0000000000000000000009")))
            val sub = "0123456789abcdef0123456789abcdef"
            gateway().command(command(MasterCommandKind.REGISTER_CONTRACT, sub)) shouldBe
                MasterCommandResult.Decided(MasterCommandResult.Outcome.SUCCEEDED, null, "ctr_01JC0000000000000000000009", false)
            StubMasterOpsServer.requestsForPath(StubMasterOpsServer.CONTRACTS).last().signatureValid shouldBe true
            lastBody(StubMasterOpsServer.CONTRACTS) shouldBe """{"idempotencyKey":"mo1.01JC00000000000000000000V1.$sub","actor":"admin:adm_01JC00000000000000000000V1",""" +
                """"correlationId":"mos.$sub","partyBusinessId":"biz_01JC0000000000000000000001","scopeMerchantId":"mer_01JC0000000000000000000001",""" +
                """"scopeSiteIds":["site_01JC0000000000000000000001"],"effectiveFrom":"2026-11-08T15:00:00.000Z"}"""
            val withEnd = MasterCommand(
                MasterCommandKind.REGISTER_CONTRACT, "mo1.01JC00000000000000000000V1.$sub", "admin:adm_01JC00000000000000000000V1", "mos.$sub",
                mapOf(
                    "partyBusinessId" to "biz_01JC0000000000000000000001", "scopeMerchantId" to "mer_01JC0000000000000000000001", "scopeSiteIds" to emptyList<String>(),
                    "effectiveFrom" to "2026-11-08T15:00:00.000Z", "effectiveTo" to "2026-12-31T15:00:00.000Z",
                ),
            )
            gateway().command(withEnd).shouldBeInstanceOf<MasterCommandResult.Decided>()
            lastBody(StubMasterOpsServer.CONTRACTS) shouldContain """"scopeSiteIds":[],"effectiveFrom":"2026-11-08T15:00:00.000Z","effectiveTo":"2026-12-31T15:00:00.000Z"}"""
            StubMasterOpsServer.reply(StubMasterOpsServer.AGREEMENTS, StubMasterOpsServer.Reply(200, StubMasterOpsServer.rejected("SERVICE_KIND_DUPLICATE")))
            gateway().command(command(MasterCommandKind.ADD_SERVICE_AGREEMENT)) shouldBe
                MasterCommandResult.Decided(MasterCommandResult.Outcome.REJECTED, "SERVICE_KIND_DUPLICATE", null, false)
            StubMasterOpsServer.requestsForPath(StubMasterOpsServer.AGREEMENTS).last().signatureValid shouldBe true
            // 명령 필드 계약: 모르는 필드·빠진 필수 필드·문자열/문자열 배열 아닌 값은 만들 수 없다.
            listOf(
                mapOf("contractId" to "ctr_01JC0000000000000000000001"),
                mapOf("contractId" to "ctr_01JC0000000000000000000001", "serviceKind" to "CARD_PG_PAYMENT", "extra" to "x"),
                mapOf("contractId" to listOf("ctr_01JC0000000000000000000001"), "serviceKind" to "CARD_PG_PAYMENT"),
                mapOf("contractId" to 1, "serviceKind" to "CARD_PG_PAYMENT"),
            ).forEach { fields ->
                io.kotest.assertions.withClue(fields) {
                    shouldThrow<IllegalArgumentException> { MasterCommand(MasterCommandKind.ADD_SERVICE_AGREEMENT, "k", "admin:a", "c", fields) }
                }
            }
        }
        Then("review n-3: a SUCCEEDED reference must carry the prefix of the command kind (merchant→mer_, site→site_, contract→ctr_, agreement→csa_); any other prefix is Unknown(RESPONSE_UNREADABLE)") {
            val paths = mapOf(
                MasterCommandKind.REGISTER_MERCHANT to StubMasterOpsServer.MERCHANTS,
                MasterCommandKind.REGISTER_MERCHANT_SITE to StubMasterOpsServer.SITES,
                MasterCommandKind.REGISTER_CONTRACT to StubMasterOpsServer.CONTRACTS,
                MasterCommandKind.ADD_SERVICE_AGREEMENT to StubMasterOpsServer.AGREEMENTS,
            )
            val own = mapOf(
                MasterCommandKind.REGISTER_MERCHANT to "mer_01JC0000000000000000000001",
                MasterCommandKind.REGISTER_MERCHANT_SITE to "site_01JC0000000000000000000001",
                MasterCommandKind.REGISTER_CONTRACT to "ctr_01JC0000000000000000000001",
                MasterCommandKind.ADD_SERVICE_AGREEMENT to "csa_01JC0000000000000000000001",
            )
            paths.forEach { (kind, path) ->
                StubMasterOpsServer.reply(path, StubMasterOpsServer.Reply(200, StubMasterOpsServer.succeeded(own.getValue(kind))))
                gateway().command(command(kind)) shouldBe MasterCommandResult.Decided(MasterCommandResult.Outcome.SUCCEEDED, null, own.getValue(kind), false)
                (own.filterKeys { it != kind }.values + "mst_01JC0000000000000000000001" + "biz_01JC0000000000000000000001").forEach { foreign ->
                    StubMasterOpsServer.reply(path, StubMasterOpsServer.Reply(200, StubMasterOpsServer.succeeded(foreign)))
                    io.kotest.assertions.withClue("$kind $foreign") { gateway().command(command(kind)) shouldBe MasterCommandResult.Unknown("RESPONSE_UNREADABLE") }
                }
            }
        }
        Then("B2-1 (§9.2, C-30): the revision command posts to contract-revisions and accepts only ctr_…#<n> with n in 1..2147483647; a plain ctr_, n=0, a leading zero, n > Int.MAX or #n on another kind is Unknown(RESPONSE_UNREADABLE)") {
            val ok = listOf("ctr_01JC0000000000000000000001#2", "ctr_01JC0000000000000000000001#1", "ctr_01JC0000000000000000000001#2147483647", "ctr_01JC0000000000000000000001#999999999")
            ok.forEach { ref ->
                StubMasterOpsServer.reply(StubMasterOpsServer.CONTRACT_REVISIONS, StubMasterOpsServer.Reply(200, StubMasterOpsServer.succeeded(ref)))
                io.kotest.assertions.withClue(ref) {
                    gateway().command(command(MasterCommandKind.REGISTER_CONTRACT_REVISION)) shouldBe MasterCommandResult.Decided(MasterCommandResult.Outcome.SUCCEEDED, null, ref, false)
                }
            }
            lastBody(StubMasterOpsServer.CONTRACT_REVISIONS) shouldContain "\"scopeSiteIds\":[]"
            listOf(
                "ctr_01JC0000000000000000000001", "ctr_01JC0000000000000000000001#0", "ctr_01JC0000000000000000000001#02",
                "ctr_01JC0000000000000000000001#2147483648", "ctr_01JC0000000000000000000001#10000000000", "csa_01JC0000000000000000000001#2",
            ).forEach { ref ->
                StubMasterOpsServer.reply(StubMasterOpsServer.CONTRACT_REVISIONS, StubMasterOpsServer.Reply(200, StubMasterOpsServer.succeeded(ref)))
                io.kotest.assertions.withClue(ref) { gateway().command(command(MasterCommandKind.REGISTER_CONTRACT_REVISION)) shouldBe MasterCommandResult.Unknown("RESPONSE_UNREADABLE") }
            }
            StubMasterOpsServer.reply(StubMasterOpsServer.CONTRACTS, StubMasterOpsServer.Reply(200, StubMasterOpsServer.succeeded("ctr_01JC0000000000000000000001#2")))
            gateway().command(command(MasterCommandKind.REGISTER_CONTRACT)) shouldBe MasterCommandResult.Unknown("RESPONSE_UNREADABLE")
        }
        Then("409 and 503 codes are classified; other statuses, a read timeout and a refused connection are Unknown (no effect assumed)") {
            listOf(
                StubMasterOpsServer.Reply(409, """{"error":"IDEMPOTENCY_KEY_REUSED"}""") to MasterCommandResult.KeyReused,
                StubMasterOpsServer.Reply(409, """{"error":"OTHER"}""") to MasterCommandResult.Unknown("HTTP_409"),
                StubMasterOpsServer.Reply(503, """{"error":"ADMIN_WRITE_BUSY"}""") to MasterCommandResult.Busy("ADMIN_WRITE_BUSY"),
                StubMasterOpsServer.Reply(503, """{"error":"REQUEST_IN_PROGRESS"}""") to MasterCommandResult.RetrySameKey("REQUEST_IN_PROGRESS"),
                StubMasterOpsServer.Reply(503, """{"error":"OUTCOME_UNKNOWN"}""") to MasterCommandResult.RetrySameKey("OUTCOME_UNKNOWN"),
                StubMasterOpsServer.Reply(503, """{"error":"TEMPORARILY_UNAVAILABLE"}""") to MasterCommandResult.RetrySameKey("TEMPORARILY_UNAVAILABLE"),
                StubMasterOpsServer.Reply(503, """{"error":"ADMIN_READ_BUSY"}""") to MasterCommandResult.Unknown("HTTP_503"),
                StubMasterOpsServer.Reply(400, """{"error":"REQUEST_MALFORMED"}""") to MasterCommandResult.Unknown("HTTP_400"),
                StubMasterOpsServer.Reply(401, """{"error":"UNAUTHENTICATED"}""") to MasterCommandResult.Unknown("HTTP_401"),
                StubMasterOpsServer.Reply(500, "") to MasterCommandResult.Unknown("HTTP_500"),
            ).forEach { (reply, expected) ->
                StubMasterOpsServer.reply(StubMasterOpsServer.SITES, reply)
                io.kotest.assertions.withClue("${reply.status} ${reply.body}") { gateway().command(command(MasterCommandKind.REGISTER_MERCHANT_SITE)) shouldBe expected }
            }
            StubMasterOpsServer.reply(StubMasterOpsServer.SITES, StubMasterOpsServer.Reply(200, StubMasterOpsServer.succeeded("site_01JC0000000000000000000001"), delayMillis = 1_000))
            gateway(settings(read = Duration.ofMillis(200))).command(command(MasterCommandKind.REGISTER_MERCHANT_SITE)) shouldBe MasterCommandResult.Unknown("TIMEOUT")
            gateway(settings(baseUrl = "http://127.0.0.1:9")).command(command(MasterCommandKind.REGISTER_MERCHANT_SITE)) shouldBe MasterCommandResult.Unknown("CONNECT_FAILED")
            StubMasterOpsServer.reply(StubMasterOpsServer.SITES, StubMasterOpsServer.Reply(200, StubMasterOpsServer.succeeded("site_01JC0000000000000000000001")))
            gateway().command(command(MasterCommandKind.REGISTER_MERCHANT_SITE)).shouldBeInstanceOf<MasterCommandResult.Decided>().reference shouldBe "site_01JC0000000000000000000001"
        }
        Then("inside a DB transaction the command is refused before any HTTP call; logs never carry the MASTER key, the channel identifier or the signature") {
            val c = command(MasterCommandKind.REGISTER_MERCHANT)
            val before = StubMasterOpsServer.requestsForPath(StubMasterOpsServer.MERCHANTS).size
            TransactionSynchronizationManager.setActualTransactionActive(true)
            try {
                shouldThrow<IllegalStateException> { gateway().command(c) }
            } finally {
                TransactionSynchronizationManager.setActualTransactionActive(false)
            }
            StubMasterOpsServer.requestsForPath(StubMasterOpsServer.MERCHANTS).size shouldBe before
            StubMasterOpsServer.reply(StubMasterOpsServer.MERCHANTS, StubMasterOpsServer.Reply(200, StubMasterOpsServer.succeeded("mer_01JC0000000000000000000001")))
            val site = command(MasterCommandKind.REGISTER_MERCHANT_SITE)
            StubMasterOpsServer.reply(StubMasterOpsServer.SITES, StubMasterOpsServer.Reply(200, StubMasterOpsServer.rejected("SITE_CHANNEL_DUPLICATE")))
            val lines = captureLogs {
                gateway().command(c).shouldBeInstanceOf<MasterCommandResult.Decided>()
                gateway().command(site).shouldBeInstanceOf<MasterCommandResult.Decided>()
            }
            lines.count { "operation=master-ops-command-" in it } shouldBe 2
            val signatures = StubMasterOpsServer.received.takeLast(2).mapNotNull { it.signature }
            (listOf(c.idempotencyKey, site.idempotencyKey, "demo-shop2.paycore.test", StubMasterOpsServer.KEY_BASE64) + signatures).forEach { s -> lines.filter { s in it }.shouldBeEmpty() }
            c.toString() shouldNotContain c.idempotencyKey
            site.toString() shouldNotContain "demo-shop2.paycore.test"
        }
        Then("C-1: the write permit is separate from the lookup permit — a held lookup permit does not block a command, a held write permit answers Busy at once without calling MASTER") {
            val slow = merchantId()
            StubMasterOpsServer.reply(StubMasterOpsServer.merchantPath(slow), StubMasterOpsServer.Reply(200, StubMasterOpsServer.detailJson(slow), delayMillis = 1_200))
            StubMasterOpsServer.reply(StubMasterOpsServer.SITES, StubMasterOpsServer.Reply(200, StubMasterOpsServer.succeeded("site_01JC0000000000000000000001")))
            val g = gateway(settings(permits = 1, writePermits = 1))
            val lookup = CompletableFuture.supplyAsync { g.detail(slow) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (StubMasterOpsServer.requestsForPath(StubMasterOpsServer.merchantPath(slow)).isEmpty() && System.nanoTime() < deadline) Thread.sleep(20)
            g.detail(merchantId()) shouldBe MasterCallResult.Unavailable("MASTER_OPS_BUSY")
            g.command(command()).shouldBeInstanceOf<MasterCommandResult.Decided>()
            lookup.get(10, TimeUnit.SECONDS).shouldBeInstanceOf<MasterCallResult.Found>()

            StubMasterOpsServer.reply(StubMasterOpsServer.MERCHANTS, StubMasterOpsServer.Reply(200, StubMasterOpsServer.succeeded("mer_01JC0000000000000000000001"), delayMillis = 1_200))
            val holder = CompletableFuture.supplyAsync { g.command(command(MasterCommandKind.REGISTER_MERCHANT)) }
            val deadline2 = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            val merchantsBefore = StubMasterOpsServer.requestsForPath(StubMasterOpsServer.MERCHANTS).size
            while (StubMasterOpsServer.requestsForPath(StubMasterOpsServer.MERCHANTS).size == merchantsBefore && System.nanoTime() < deadline2) Thread.sleep(20)
            val sitesBefore = StubMasterOpsServer.requestsForPath(StubMasterOpsServer.SITES).size
            g.command(command()) shouldBe MasterCommandResult.Busy("MASTER_OPS_BUSY")
            StubMasterOpsServer.requestsForPath(StubMasterOpsServer.SITES).size shouldBe sitesBefore
            g.detail(merchantId()) shouldBe MasterCallResult.NotFound
            holder.get(10, TimeUnit.SECONDS).shouldBeInstanceOf<MasterCommandResult.Decided>()
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
        Then("MERCHANT-OPS-002: write-permits and registrations-per-minute have no default and are range-checked") {
            fun s(write: Int?, regs: Int?) = MasterOpsSettings("http://h", "k", StubMasterOpsServer.KEY_BASE64, Duration.ofSeconds(1), Duration.ofSeconds(1), 1, 1, 1, write, regs, 1, Duration.ofSeconds(1), 60)
            shouldThrow<IllegalArgumentException> { s(null, 1) }
            shouldThrow<IllegalArgumentException> { s(1, null) }
            shouldThrow<IllegalArgumentException> { s(0, 1) }
            shouldThrow<IllegalArgumentException> { s(17, 1) }
            shouldThrow<IllegalArgumentException> { s(1, 0) }
            s(16, 10_000).writePermits shouldBe 16
        }
        Then("no defaults; base-url without path; key length; rates in range; toString hides the key") {
            shouldThrow<IllegalArgumentException> { MasterOpsSettings(null, "k", StubMasterOpsServer.KEY_BASE64, Duration.ofSeconds(1), Duration.ofSeconds(1), 1, 1, 1, 1, 1, 1, Duration.ofSeconds(1), 60) }
            shouldThrow<IllegalArgumentException> { MasterOpsSettings("http://h/x", "k", StubMasterOpsServer.KEY_BASE64, Duration.ofSeconds(1), Duration.ofSeconds(1), 1, 1, 1, 1, 1, 1, Duration.ofSeconds(1), 60) }
            shouldThrow<IllegalArgumentException> { MasterOpsSettings("http://h", "k", "c2hvcnQ=", Duration.ofSeconds(1), Duration.ofSeconds(1), 1, 1, 1, 1, 1, 1, Duration.ofSeconds(1), 60) }
            shouldThrow<IllegalArgumentException> { MasterOpsSettings("http://h", "k", StubMasterOpsServer.KEY_BASE64, Duration.ofSeconds(1), Duration.ofSeconds(1), 1, 1, 0, 1, 1, 1, Duration.ofSeconds(1), 60) }
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

package com.paycore.financial.acquiring.adapter.web.admin

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.paycore.financial.FinancialDbAccounts
import com.paycore.financial.FinancialMySqlContainerConfig
import com.paycore.financial.acquiring.application.AcquisitionSealService
import com.paycore.financial.acquiring.application.AcquisitionWaitingBoard
import com.paycore.financial.acquiring.application.required.AcquiringClock
import com.paycore.financial.acquiring.application.required.AcquisitionFileRepository
import com.paycore.financial.acquiring.application.required.AcquisitionLaneStore
import com.paycore.financial.acquiring.application.required.AcquisitionMembershipStore
import com.paycore.financial.acquiring.application.required.AcquisitionPolicySource
import com.paycore.financial.acquiring.application.required.AcquisitionRecordResultStore
import com.paycore.financial.column
import com.paycore.financial.paymentfact.adapter.web.PaymentFactEnvelopeFixture
import com.paycore.financial.paymentfact.adapter.web.PaymentFactSignature
import com.paycore.financial.paymentfact.application.PaymentFactInboxService
import com.paycore.financial.paymentfact.application.provided.PaymentFactAcquisitionSource
import com.paycore.financial.paymentfact.domain.PaymentFactFixture
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.security.web.SecurityFilterChain
import org.springframework.transaction.PlatformTransactionManager
import org.testcontainers.mysql.MySQLContainer
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * ACQ-VIEW-001 §3·AC 1·3·6·7(실제 Tomcat 포트 + 실제 보안 체인 + MySQL — 결제 사실 Inbox HTTP 시험과 같은 컨텍스트):
 * 무서명·틀린 키(결제 사실 Inbox 키 포함)·오래된 서명 401, query 400, 16 KiB 초과 413, 그 밖 경로 404, health 200, **결제 사실 Inbox는 그대로 동작**(R-2 회귀), 서명 호출 응답 모양·
 * 오류 code(입력 값 미반영), Idempotency(같은 요청 = 같은 본문, FINANCIAL 표 행 수·checksum 불변), 로그에 서명·키·fileId·MID 없음.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FinancialMySqlContainerConfig::class)
class AdminAcquisitionApiSpec(
    @LocalServerPort private val port: Int,
    private val inbox: PaymentFactInboxService,
    private val transactionManager: PlatformTransactionManager,
    private val lanes: AcquisitionLaneStore,
    private val files: AcquisitionFileRepository,
    private val membership: AcquisitionMembershipStore,
    private val source: PaymentFactAcquisitionSource,
    private val policies: AcquisitionPolicySource,
    private val results: AcquisitionRecordResultStore,
    private val board: AcquisitionWaitingBoard,
    private val container: MySQLContainer,
    private val registry: MeterRegistry,
    private val chains: List<SecurityFilterChain>,
) : BehaviorSpec({

    val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).proxy(HttpClient.Builder.NO_PROXY).connectTimeout(Duration.ofSeconds(5)).build()
    val mapper = JsonMapper.builder().build()
    val day = LocalDate.parse("2026-10-21")
    val acq = PaymentFactFixture.nextAcquirerCode()
    val searchPath = "/internal/v1/admin/acquisition-file-searches"

    val sealedFileId: String by lazy {
        repeat(2) { i ->
            val auth = PaymentFactFixture.authorized(amountMinor = 11_000L + i, acquirerCode = acq, approvedAt = Instant.parse("2026-10-21T01:02:03.456Z"))
            inbox.receive(PaymentFactEnvelopeFixture.inbound(auth, UUID.randomUUID().toString()))
        }
        AcquisitionSealService(transactionManager, lanes, files, membership, source, policies, object : AcquiringClock {
            override fun now(): Instant = Instant.parse("2026-10-21T14:05:00Z")
        }, results, board).seal(acq, "TEST", day).fileId
    }

    fun send(method: String, path: String, body: ByteArray = ByteArray(0), header: String? = FinancialAdminChannelFixture.springHeader(method, path, body), query: String? = null):
        HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path${query?.let { "?$it" } ?: ""}")).timeout(Duration.ofSeconds(10))
        header?.let { builder.header(AdminCallSignature.HEADER, it) }
        if (method == "POST") builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body)) else builder.GET()
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    fun post(path: String, json: String) = send("POST", path, json.toByteArray(Charsets.UTF_8))
    fun JsonNode.keys(): List<String> = properties().map { it.key }
    fun json(r: HttpResponse<String>): JsonNode = mapper.readTree(r.body())

    val periodBody = """{"businessDateFrom":"2026-10-21","businessDateTo":"2026-10-21","acquirerCode":"$acq"}"""

    fun checksums(): Map<String, String> = FinancialDbAccounts.root(container).use { c ->
        val tables = c.column(
            "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = 'paycore_financial' AND TABLE_TYPE = 'BASE TABLE' AND TABLE_NAME <> 'flyway_schema_history' " +
                "AND TABLE_NAME NOT LIKE 'BATCH%' ORDER BY TABLE_NAME",
        )
        tables.associateWith { t ->
            c.createStatement().use { s ->
                val count = s.executeQuery("SELECT COUNT(*) FROM `$t`").use { rs -> rs.next(); rs.getLong(1) }
                val sum = s.executeQuery("CHECKSUM TABLE `$t`").use { rs -> rs.next(); rs.getString(2) }
                "$count:$sum"
            }
        }
    }

    Given("the ADMIN channel without a valid signature") {
        val detailPath = "/internal/v1/admin/acquisition-files/$sealedFileId"
        Then("every endpoint is 401 UNAUTHENTICATED for a missing, stale, foreign-channel or wrong-key signature, with no-store and no WWW-Authenticate") {
            val inboxKey = java.util.Base64.getDecoder().decode("dGVzdC1vbmx5LXBheW1lbnQtZmFjdC1nb2xkZW4ta2V5LTAwMDE=")
            listOf("POST" to searchPath, "GET" to detailPath, "POST" to "$detailPath/record-searches", "GET" to "/internal/v1/admin/anything").forEach { (method, path) ->
                val body = if (method == "POST") "{}".toByteArray() else ByteArray(0)
                val t = Instant.now().epochSecond
                val inboxSigned = "t=$t,kid=fact-golden-k1,v1=${AdminCallSignature.v1(inboxKey, method, path, t.toString(), body)}"
                listOf(
                    null,
                    FinancialAdminChannelFixture.springHeader(method, path, body, t = t - 400),
                    inboxSigned,
                    FinancialAdminChannelFixture.header(method, path, body),
                ).forEach { header ->
                    val r = send(method, path, body, header)
                    withClue("$method $path ${header?.take(20)}") {
                        r.statusCode() shouldBe 401
                        r.body() shouldBe """{"error":"UNAUTHENTICATED"}"""
                        r.headers().firstValue("Cache-Control").orElse("") shouldContain "no-store"
                        r.headers().firstValue("WWW-Authenticate").isPresent shouldBe false
                    }
                }
            }
        }
        Then("a query string is 400 before the signature, a body over 16 KiB is 413, other paths stay 404 and health is reachable") {
            send("GET", detailPath, query = "x=1").let {
                it.statusCode() shouldBe 400
                it.body() shouldBe """{"error":"QUERY_NOT_ALLOWED"}"""
            }
            send("POST", searchPath, ByteArray(16 * 1024 + 1) { ' '.code.toByte() }).statusCode() shouldBe 413
            listOf("/internal/v1/adminx", "/internal/v1/other", "/actuator/env", "/").forEach { send("GET", it, header = null).statusCode() shouldBe 404 }
            send("GET", "/actuator/health", header = null).statusCode() shouldBe 200
            client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/internal/v1/admin/x")).method("DELETE", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode() shouldBe 404
        }
        Then("R-2 regression: the payment-fact inbox keeps working with its own HMAC (200 ACK), and an admin-channel signature does not open it (401)") {
            val body = PaymentFactEnvelopeFixture.body(PaymentFactFixture.authorized())
            fun inboxPost(signature: String) = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port${PaymentFactSignature.PATH}")).header("Content-Type", "application/json")
                    .header(PaymentFactSignature.HEADER, signature).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            inboxPost(PaymentFactEnvelopeFixture.signature(body)).statusCode() shouldBe 200
            inboxPost(FinancialAdminChannelFixture.springHeader("POST", PaymentFactSignature.PATH, body)).statusCode() shouldBe 401
            // Inbox 쪽 Spring 오류 응답은 그대로다(리뷰 MINOR-1의 고정 code 변환은 ADMIN 채널 경로만): 415는 Boot 기본 오류 모양.
            val unsupported = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port${PaymentFactSignature.PATH}")).header("Content-Type", "text/plain")
                    .header(PaymentFactSignature.HEADER, PaymentFactEnvelopeFixture.signature(body)).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            unsupported.statusCode() shouldBe 415
            json(unsupported).get("status").asInt() shouldBe 415
        }
        Then("exactly two security chains: the admin chain (with the signature filter) and the default chain") {
            chains.size shouldBe 2
            chains.count { chain -> chain.filters.any { it is AdminChannelAuthenticationFilter } } shouldBe 1
        }
    }

    Given("signed calls") {
        val detailPath = "/internal/v1/admin/acquisition-files/$sealedFileId"
        Then("search: 200 {items,nextCursor} with the list item keys in order; found and not found have the same shape") {
            val r = post(searchPath, periodBody)
            r.statusCode() shouldBe 200
            json(r).keys() shouldBe listOf("items", "nextCursor")
            val item = json(r).get("items").single()
            item.keys() shouldBe listOf(
                "fileId", "acquirerCode", "environment", "businessDate", "fileVersion", "originalFileId", "recordCount", "saleCount", "saleAmountMinor",
                "cancelCount", "cancelAmountMinor", "netAmountMinor", "currency", "transmissionState", "receiptState", "resultState", "sealedAt", "transmittedAt",
                // ACQ-ADMIN-001 rev 0.7(B2 — §5.1 3상태): 키 하나 추가(reacquisitionInProgress — 기존 키·순서 불변, 추가만).
                "recordStatusCounts", "attention", "reacquisitionCandidate", "reacquisitionInProgress",
            )
            item.get("fileId").asString() shouldBe sealedFileId
            item.get("businessDate").asString() shouldBe "2026-10-21"
            item.get("transmissionState").asString() shouldBe "SEALED"
            item.get("netAmountMinor").asLong() shouldBe 22_001L
            item.get("sealedAt").asString() shouldBe "2026-10-21T14:05:00.000Z"
            item.get("transmittedAt").isNull shouldBe true
            json(r).get("nextCursor").isNull shouldBe true
            val none = post(searchPath, """{"businessDateFrom":"2026-10-21","businessDateTo":"2026-10-21","acquirerCode":"NOSUCHACQ"}""")
            (none.statusCode() to json(none).keys()) shouldBe (200 to listOf("items", "nextCursor"))
            json(none).get("items").size() shouldBe 0
        }
        Then("detail: 200 with file, attempts, responses, records and readAt; record search: 200 {items,nextCursor}") {
            val d = send("GET", detailPath)
            d.statusCode() shouldBe 200
            json(d).keys() shouldBe listOf("file", "attempts", "responses", "records", "readAt")
            json(d).get("file").keys().takeLast(6) shouldBe listOf("cutoffPolicyVersion", "cutoffAt", "contentSha256", "contentBytes", "transmissionLeaseUntil", "updatedAt")
            json(d).get("attempts").keys() shouldBe listOf("total", "latest")
            json(d).get("responses").keys() shouldBe listOf("total", "quarantined", "latest")
            val record = json(d).get("records").get("items").first()
            record.keys() shouldBe listOf(
                "seq", "recordType", "transactionId", "originalTransactionId", "merchantAccountId", "institutionTransactionDate", "eventAt", "currency",
                "approvedAmountMinor", "nettedCancelAmountMinor", "recordAmountMinor", "generation", "reacquisitionOf", "status", "results",
            )
            record.get("status").asString() shouldBe "AWAITING"
            val records = post("$detailPath/record-searches", """{"status":"AWAITING","limit":1}""")
            records.statusCode() shouldBe 200
            json(records).keys() shouldBe listOf("items", "nextCursor")
            json(records).get("nextCursor").asInt() shouldBe 1
        }
        Then("review MINOR-1: signed requests the channel does not route answer a fixed code only (405 METHOD_NOT_ALLOWED, 404 NOT_FOUND) — no Boot error body, no path") {
            listOf(
                Triple("GET", searchPath, 405 to "METHOD_NOT_ALLOWED"),
                Triple("POST", detailPath, 405 to "METHOD_NOT_ALLOWED"),
                Triple("GET", "/internal/v1/admin/anything", 404 to "NOT_FOUND"),
                Triple("POST", "/internal/v1/admin/acquisition-files", 404 to "NOT_FOUND"),
            ).forEach { (method, path, want) ->
                val body = if (method == "POST") "{}".toByteArray() else ByteArray(0)
                val r = send(method, path, body)
                withClue("$method $path") {
                    r.statusCode() shouldBe want.first
                    r.body() shouldBe """{"error":"${want.second}"}"""
                    r.headers().firstValue(AdminChannelResponses.REQUEST_ID_HEADER).isPresent shouldBe true
                }
            }
        }
        Then("errors carry a fixed code only: 400 REQUEST_MALFORMED (no echo), 404 ACQUISITION_FILE_NOT_FOUND for unknown and malformed ids") {
            post(searchPath, """{"environment":"NOT-AN-ENV-$acq"}""").let {
                it.statusCode() shouldBe 400
                it.body() shouldBe """{"error":"REQUEST_MALFORMED"}"""
            }
            listOf("/internal/v1/admin/acquisition-files/ACQ.NOSUCH.TEST.20261021.001", "/internal/v1/admin/acquisition-files/not-a-file").forEach {
                send("GET", it).let { r ->
                    r.statusCode() shouldBe 404
                    r.body() shouldBe """{"error":"ACQUISITION_FILE_NOT_FOUND"}"""
                }
            }
            post("/internal/v1/admin/acquisition-files/ACQ.NOSUCH.TEST.20261021.001/record-searches", "{}").statusCode() shouldBe 404
        }
    }

    Given("Idempotency and non-exposure in logs") {
        Then("repeating the same search, detail and record search returns identical bodies (detail except readAt) and leaves every FINANCIAL table unchanged") {
            val detailPath = "/internal/v1/admin/acquisition-files/$sealedFileId"
            val before = checksums()
            withClue("the checksum covers the FINANCIAL tables (non-empty table list)") {
                before.keys.containsAll(listOf("acquisition_file", "acquisition_file_record", "acquisition_record_result", "acquisition_response", "payment_fact_inbox")) shouldBe true
            }
            fun round() = listOf(
                post(searchPath, periodBody).body(),
                mapper.readTree(send("GET", detailPath).body()).let { (it as tools.jackson.databind.node.ObjectNode).remove("readAt"); it.toString() },
                post("$detailPath/record-searches", "{}").body(),
            )
            val first = round()
            val second = round()
            second shouldBe first
            checksums() shouldBe before
        }
        Then("no signature header or v1 value, no key, no file id and no MID appear in any log line of accepted and rejected calls") {
            val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
            val appender = ListAppender<ILoggingEvent>().also { it.start() }
            root.addAppender(appender)
            try {
                val body = periodBody.toByteArray()
                val header = FinancialAdminChannelFixture.springHeader("POST", searchPath, body)
                send("POST", searchPath, body, header).statusCode() shouldBe 200
                send("POST", searchPath, body, header.replaceAfterLast("v1=", "0".repeat(64))).statusCode() shouldBe 401
                send("GET", "/internal/v1/admin/acquisition-files/$sealedFileId").statusCode() shouldBe 200
                withClue("control: the appender captures this test's channel log lines") {
                    appender.list.any { it.formattedMessage.contains("operation=AdminChannelAuthenticate") } shouldBe true
                }
                appender.list.forEach { e ->
                    val line = e.formattedMessage + (e.throwableProxy?.message ?: "")
                    listOf(header.substringAfter("v1="), FinancialAdminChannelFixture.SPRING_SECRET, sealedFileId, "demo-mid-001", AdminCallSignature.HEADER).forEach {
                        line shouldNotContain it
                    }
                }
                ((registry.find(AdminChannelAuthenticationFilter.AUTH_METRIC).tag("result", "ACCEPTED").counter()?.count() ?: 0.0) >= 2.0) shouldBe true
                ((registry.find(AdminChannelAuthenticationFilter.AUTH_METRIC).tag("reason", "SIGNATURE").counter()?.count() ?: 0.0) >= 1.0) shouldBe true
            } finally {
                root.detachAppender(appender)
            }
        }
    }
})

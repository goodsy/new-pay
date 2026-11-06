package com.paycore.admin.financialops

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.paycore.admin.financialops.adapter.integration.FinancialCallSigner
import com.paycore.admin.financialops.adapter.integration.FinancialOpsConfiguration
import com.paycore.admin.financialops.adapter.integration.FinancialOpsSettings
import com.paycore.admin.financialops.adapter.integration.HttpFinancialOpsGateway
import com.paycore.admin.financialops.application.provided.AcquisitionFileCursor
import com.paycore.admin.financialops.application.provided.AcquisitionFileSearchCriteria
import com.paycore.admin.financialops.application.provided.AcquisitionRecordSearchCriteria
import com.paycore.admin.financialops.application.required.FinancialCallResult
import com.paycore.admin.support.StubFinancialOpsServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.slf4j.LoggerFactory
import org.springframework.transaction.support.TransactionSynchronizationManager
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * ADMIN → FINANCIAL 호출(ACQ-VIEW-001 §3.1·§4.6 — 실제 HTTP 대역 [StubFinancialOpsServer], 서명은 대역이 앱과 독립 검증):
 * golden 서명 벡터 바이트 일치, 요청 모양(경로·method·본문 키·query 없음), 응답 허용 필드 모양(알 수 없는 필드·D-ACQV-002 값·평평한 응답 참조 → 해석 불가), **요청-응답 대응**
 * (다른 파일·필터 밖·cursor 밖·limit 초과 → 해석 불가), 404·5xx·과대 응답·timeout·연결 실패 → 값, DB TX 안 호출 거부, 전용 permit(포화 시 즉시 busy),
 * 기동 검사(PAYMENT·MASTER ops 키와 같으면 거부, LIVE Demo 키 거부, context path), 로그 비노출.
 */
class FinancialOpsGatewaySpec : BehaviorSpec({

    StubFinancialOpsServer.start()
    val json = JsonMapper.builder().build()
    val fileId = "ACQ.SFTPV1.TEST.20261020.001"
    val period = AcquisitionFileSearchCriteria(businessDateFrom = "2026-10-20", businessDateTo = "2026-10-20")

    fun settings(baseUrl: String = "http://127.0.0.1:${StubFinancialOpsServer.PORT}", permits: Int = 2, read: Duration = Duration.ofSeconds(2)) =
        FinancialOpsSettings(baseUrl, StubFinancialOpsServer.KEY_ID, StubFinancialOpsServer.KEY_BASE64, Duration.ofSeconds(1), read, permits, 30)

    fun gateway(s: FinancialOpsSettings = settings()) = HttpFinancialOpsGateway(s, FinancialCallSigner(s.keyId, s.signingKey())) { Instant.now() }

    fun captureLogs(block: () -> Unit): List<String> {
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(appender)
        try {
            block()
        } finally {
            root.detachAppender(appender)
        }
        return appender.list.map { it.loggerName + " " + it.formattedMessage }
    }

    fun search(reply: String, criteria: AcquisitionFileSearchCriteria = period): FinancialCallResult {
        StubFinancialOpsServer.reply(StubFinancialOpsServer.SEARCH, StubFinancialOpsServer.Reply(200, reply))
        return gateway().search(criteria)
    }

    Given("the signature contract (golden ② shared with payment-api, master-api and financial-batch)") {
        Then("every sign vector is reproduced byte-for-byte by the FINANCIAL signer") {
            val golden = json.readTree(requireNotNull(javaClass.getResourceAsStream("/contracts/admin-call-signature-v1.golden.json")).readAllBytes())
            val keys = golden.get("keys").associate { it.get("kid").asString() to java.util.Base64.getDecoder().decode(it.get("secretBase64").asString()) }
            golden.get("signVectors").forEach { v ->
                val signer = FinancialCallSigner(v.get("kid").asString(), keys.getValue(v.get("kid").asString()))
                val body = v.get("body").asString().toByteArray(Charsets.UTF_8)
                signer.canonical(v.get("method").asString(), v.get("path").asString(), v.get("t").asLong(), body) shouldBe v.get("canonical").asString()
                signer.header(v.get("method").asString(), v.get("path").asString(), v.get("t").asLong(), body) shouldBe v.get("header").asString()
            }
        }
    }

    Given("FINANCIAL answers in the contract shape") {
        Then("search: POST with exactly the given keys, signed (stub verifies independently), no query; the page is Found with the item fields in order") {
            val before = StubFinancialOpsServer.received.size
            val criteria = period.copy(acquirerCode = "SFTPV1", needsAttention = true, after = AcquisitionFileCursor("2026-10-20", "ACQ.SFTPV9.TEST.20261020.001"), limit = 5)
            val result = search(StubFinancialOpsServer.pageJson(StubFinancialOpsServer.itemJson(fileId, attention = listOf("RESULT_OVERDUE"))), criteria)
            val found = result.shouldBeInstanceOf<FinancialCallResult.Found>()
            @Suppress("UNCHECKED_CAST")
            val item = (found.document["items"] as List<Map<String, Any?>>).single()
            item.keys.toList().take(4) shouldBe listOf("fileId", "acquirerCode", "environment", "businessDate")
            item["attention"] shouldBe listOf("RESULT_OVERDUE")
            val request = StubFinancialOpsServer.received.drop(before).single()
            listOf(request.method, request.path, request.query, request.signatureValid) shouldBe listOf("POST", StubFinancialOpsServer.SEARCH, null, true)
            json.readTree(request.body).properties().map { it.key } shouldBe listOf("businessDateFrom", "businessDateTo", "acquirerCode", "needsAttention", "after", "limit")
        }
        Then("detail: GET without body is Found; records: POST {status, afterSeq, limit} is Found with responseRef {kind, sequenceNo}") {
            StubFinancialOpsServer.reply(StubFinancialOpsServer.filePath(fileId), StubFinancialOpsServer.Reply(200, StubFinancialOpsServer.detailJson(fileId)))
            val before = StubFinancialOpsServer.received.size
            gateway().detail(fileId).shouldBeInstanceOf<FinancialCallResult.Found>()
            StubFinancialOpsServer.received.drop(before).single().let { listOf(it.method, it.body.size, it.signatureValid) shouldBe listOf("GET", 0, true) }
            StubFinancialOpsServer.reply(StubFinancialOpsServer.recordsPath(fileId), StubFinancialOpsServer.Reply(200, StubFinancialOpsServer.recordsJson(StubFinancialOpsServer.recordJson(3))))
            val records = gateway().records(fileId, AcquisitionRecordSearchCriteria("ACCEPTED", 2, 10)).shouldBeInstanceOf<FinancialCallResult.Found>()
            @Suppress("UNCHECKED_CAST")
            val result = ((records.document["items"] as List<Map<String, Any?>>).single()["results"] as List<Map<String, Any?>>).single()
            result["responseRef"] shouldBe mapOf("kind" to "RESULT", "sequenceNo" to 1L)
            json.readTree(StubFinancialOpsServer.requestsForPath(StubFinancialOpsServer.recordsPath(fileId)).last().body).toString() shouldBe
                """{"status":"ACCEPTED","afterSeq":2,"limit":10}"""
        }
        Then("404 ACQUISITION_FILE_NOT_FOUND is NotFound; another 404 body, 5xx and malformed bodies are Unavailable") {
            gateway().detail("ACQ.NOSUCH.TEST.20261020.001") shouldBe FinancialCallResult.NotFound
            StubFinancialOpsServer.reply(StubFinancialOpsServer.filePath("ACQ.OTHER.TEST.20261020.001"), StubFinancialOpsServer.Reply(404, """{"error":"NOT_FOUND"}"""))
            gateway().detail("ACQ.OTHER.TEST.20261020.001") shouldBe FinancialCallResult.Unavailable("HTTP_404")
            search("""{"error":"TEMPORARILY_UNAVAILABLE"}""").let { it shouldBe FinancialCallResult.Unavailable("RESPONSE_UNREADABLE") }
            StubFinancialOpsServer.reply(StubFinancialOpsServer.SEARCH, StubFinancialOpsServer.Reply(503, """{"error":"ADMIN_READ_BUSY"}"""))
            gateway().search(period) shouldBe FinancialCallResult.Unavailable("HTTP_503")
        }
    }

    Given("values inside the FINANCIAL storage grammar (review MAJOR-1 — the allowed shape must not be narrower than what FINANCIAL can store)") {
        Then("numeric reason codes (record result `05`, ACK FILE_REJECTED `1001`) and `#` in ids are Found, not unreadable") {
            val detail = StubFinancialOpsServer.detailJson(
                fileId,
                StubFinancialOpsServer.recordsJson(
                    StubFinancialOpsServer.recordJson(1, status = "REJECTED", result = "REJECTED", reasonCode = "05", transactionId = "ptx#auth:1"),
                    StubFinancialOpsServer.recordJson(2, status = "REJECTED", result = "REJECTED", reasonCode = "SCENARIO_FILE_REJECTED_1001"),
                ),
                responses = StubFinancialOpsServer.responsesJson(ackOutcome = "FILE_REJECTED", ackReasonCode = "1001"),
            )
            StubFinancialOpsServer.reply(StubFinancialOpsServer.filePath(fileId), StubFinancialOpsServer.Reply(200, detail))
            val found = gateway().detail(fileId).shouldBeInstanceOf<FinancialCallResult.Found>()
            @Suppress("UNCHECKED_CAST")
            val records = (found.document["records"] as Map<String, Any?>)["items"] as List<Map<String, Any?>>
            @Suppress("UNCHECKED_CAST")
            (records.first()["results"] as List<Map<String, Any?>>).single()["reasonCode"] shouldBe "05"
            records.first()["transactionId"] shouldBe "ptx#auth:1"
            StubFinancialOpsServer.reply(StubFinancialOpsServer.recordsPath(fileId), StubFinancialOpsServer.Reply(200,
                StubFinancialOpsServer.recordsJson(StubFinancialOpsServer.recordJson(1, status = "REJECTED", result = "REJECTED", reasonCode = "1001"))))
            gateway().records(fileId, AcquisitionRecordSearchCriteria()).shouldBeInstanceOf<FinancialCallResult.Found>()
        }
        Then("every value each FINANCIAL CHECK set allows (V5·V7·V9·V10·V14) is Found; a value outside the set and an integer outside its CHECK range are unreadable") {
            // 기본 상세 JSON의 첫 등장 조각을 바꿔 끼운다(필드 간 조합 규칙은 FINANCIAL CHECK 몫 — 여기서는 열마다 값 집합만 본다).
            val stored = mapOf(
                "\"resumeState\":\"SEALED\"" to listOf("SEALED", "TRANSMISSION_UNKNOWN").map { "\"resumeState\":\"$it\"" },
                "\"outcome\":\"DELIVERED\"" to listOf("DELIVERED", "NOT_DELIVERED", "UNKNOWN", "CONTENT_MISMATCH", "SUPERSEDED").map { "\"outcome\":\"$it\"" },
                "\"observed\":null" to listOf("DELIVERED", "NOT_DELIVERED", "UNKNOWN", "CONTENT_MISMATCH").map { "\"observed\":\"$it\"" },
                "\"evidence\":\"RENAMED_AND_VERIFIED\"" to listOf("RENAMED_AND_VERIFIED", "VERIFIED_FINAL_PRESENT", "RENAMED").map { "\"evidence\":\"$it\"" },
                "\"reason\":null" to listOf(
                    "CONNECT_FAILED", "HOST_KEY_REJECTED", "AUTHENTICATION_FAILED", "UPLOAD_FAILED", "RENAME_REFUSED", "RENAME_OUTCOME_UNKNOWN", "VERIFY_FAILED",
                    "RENAMED_BUT_ABSENT", "LEASE_EXHAUSTED", "INTERNAL_ERROR",
                ).map { "\"reason\":\"$it\"" },
                "\"resultingState\":\"TRANSMITTED\"" to listOf("SEALED", "TRANSMITTING", "TRANSMITTED", "TRANSMISSION_UNKNOWN", "TRANSMISSION_QUARANTINED")
                    .map { "\"resultingState\":\"$it\"" },
                "\"disposition\":\"APPLIED\"" to listOf("APPLIED", "QUARANTINED").map { "\"disposition\":\"$it\"" },
                "\"quarantineReason\":null" to listOf(
                    "FORMAT_INVALID", "NAME_MISMATCH", "UNKNOWN_FILE", "NAME_REUSED", "HASH_MISMATCH", "COUNT_MISMATCH", "RECORD_MISMATCH", "RECEIPT_CONFLICT",
                    "ACK_CONFLICT", "AFTER_CONFLICT", "TRANSMISSION_QUARANTINED",
                ).map { "\"quarantineReason\":\"$it\"" },
                "\"ackOutcome\":\"RECEIVED\"" to listOf("RECEIVED", "FILE_REJECTED", "RECEIPT_CONFLICT").map { "\"ackOutcome\":\"$it\"" },
                "\"evidence\":\"RESULT_HASH_MATCH\"" to listOf("ACK_HASH_MATCH", "RESULT_HASH_MATCH").map { "\"evidence\":\"$it\"" },
                "\"source\":\"RESULT_FILE\"" to listOf("RESULT_FILE", "FILE_REJECTED", "DEADLINE").map { "\"source\":\"$it\"" },
                "\"fileVersion\":1" to listOf("\"fileVersion\":999"),
                "\"kind\":\"RESULT\",\"sequenceNo\":1}" to listOf("\"kind\":\"RESULT\",\"sequenceNo\":99}"),
            )
            val outside = mapOf(
                "\"resumeState\":\"SEALED\"" to "\"resumeState\":\"TRANSMITTING\"",
                "\"outcome\":\"DELIVERED\"" to "\"outcome\":\"DELIVERED_LATER\"",
                "\"reason\":null" to "\"reason\":\"TIMEOUT\"",
                "\"disposition\":\"APPLIED\"" to "\"disposition\":\"IGNORED\"",
                "\"quarantineReason\":null" to "\"quarantineReason\":\"OTHER\"",
                "\"ackOutcome\":\"RECEIVED\"" to "\"ackOutcome\":\"ACCEPTED\"",
                "\"source\":\"RESULT_FILE\"" to "\"source\":\"MANUAL\"",
                "\"fileVersion\":1" to "\"fileVersion\":1000",
                "\"kind\":\"RESULT\",\"sequenceNo\":1}" to "\"kind\":\"RESULT\",\"sequenceNo\":100}",
            )
            val base = StubFinancialOpsServer.detailJson(fileId)
            fun detailWith(from: String, to: String): FinancialCallResult {
                check(base.contains(from)) { "fixture fragment $from" }
                StubFinancialOpsServer.reply(StubFinancialOpsServer.filePath(fileId), StubFinancialOpsServer.Reply(200, base.replaceFirst(from, to)))
                return gateway().detail(fileId)
            }
            stored.forEach { (from, tos) -> tos.forEach { to -> withClue(to) { detailWith(from, to).shouldBeInstanceOf<FinancialCallResult.Found>() } } }
            outside.forEach { (from, to) -> withClue(to) { detailWith(from, to) shouldBe FinancialCallResult.Unavailable("RESPONSE_UNREADABLE") } }
        }
        Then("values outside the FINANCIAL grammar stay unreadable: a 33-character or lower-case reason code, a 65-character id") {
            listOf(
                StubFinancialOpsServer.recordJson(1, status = "REJECTED", result = "REJECTED", reasonCode = "R".repeat(33)),
                StubFinancialOpsServer.recordJson(1, status = "REJECTED", result = "REJECTED", reasonCode = "amount_mismatch"),
                StubFinancialOpsServer.recordJson(1, transactionId = "p".repeat(65)),
            ).forEach { r ->
                StubFinancialOpsServer.reply(StubFinancialOpsServer.recordsPath(fileId), StubFinancialOpsServer.Reply(200, StubFinancialOpsServer.recordsJson(r)))
                gateway().records(fileId, AcquisitionRecordSearchCriteria()) shouldBe FinancialCallResult.Unavailable("RESPONSE_UNREADABLE")
            }
        }
    }

    Given("responses outside the allowed shape (fail-closed — nothing new reaches the screen)") {
        Then("an unknown field, a D-ACQV-002 value, the flat response reference and a wrong value set are unreadable") {
            mapOf(
                "unknown field on the item" to StubFinancialOpsServer.pageJson(StubFinancialOpsServer.itemJson(fileId, extra = ""","senderId":"PAYCORE"""")),
                "approval number on a record" to StubFinancialOpsServer.detailJson(
                    fileId, StubFinancialOpsServer.recordsJson(StubFinancialOpsServer.recordJson(1, recordExtra = ""","approvalNumber":"30000001"""")),
                ),
                "flat responseKind (pre-MINOR-2 shape)" to StubFinancialOpsServer.detailJson(
                    fileId, StubFinancialOpsServer.recordsJson(StubFinancialOpsServer.recordJson(1, resultExtra = ""","responseKind":"RESULT"""")),
                ),
                "unknown attention reason" to StubFinancialOpsServer.pageJson(StubFinancialOpsServer.itemJson(fileId, attention = listOf("SOMETHING_NEW"))),
            ).forEach { (name, body) ->
                withClue(name) {
                    if (body.startsWith("{\"file\"")) {
                        StubFinancialOpsServer.reply(StubFinancialOpsServer.filePath(fileId), StubFinancialOpsServer.Reply(200, body))
                        gateway().detail(fileId) shouldBe FinancialCallResult.Unavailable("RESPONSE_UNREADABLE")
                    } else {
                        search(body) shouldBe FinancialCallResult.Unavailable("RESPONSE_UNREADABLE")
                    }
                }
            }
        }
        Then("a response that does not answer the request is unreadable: other file, outside the period, other acquirer, behind the cursor, over the limit, no reason under needsAttention") {
            val other = "ACQ.SFTPV2.TEST.20261019.001"
            StubFinancialOpsServer.reply(StubFinancialOpsServer.filePath(fileId), StubFinancialOpsServer.Reply(200, StubFinancialOpsServer.detailJson(other)))
            gateway().detail(fileId) shouldBe FinancialCallResult.Unavailable("RESPONSE_UNREADABLE")
            search(StubFinancialOpsServer.pageJson(StubFinancialOpsServer.itemJson(other))) shouldBe FinancialCallResult.Unavailable("RESPONSE_UNREADABLE")
            search(StubFinancialOpsServer.pageJson(StubFinancialOpsServer.itemJson(fileId)), period.copy(acquirerCode = "SFTPV9")) shouldBe
                FinancialCallResult.Unavailable("RESPONSE_UNREADABLE")
            search(StubFinancialOpsServer.pageJson(StubFinancialOpsServer.itemJson(fileId)), period.copy(after = AcquisitionFileCursor("2026-10-20", "ACQ.SFTPV0.TEST.20261020.001"))) shouldBe
                FinancialCallResult.Unavailable("RESPONSE_UNREADABLE")
            search(StubFinancialOpsServer.pageJson(StubFinancialOpsServer.itemJson(fileId), StubFinancialOpsServer.itemJson("ACQ.SFTPV0.TEST.20261020.001")), period.copy(limit = 1)) shouldBe
                FinancialCallResult.Unavailable("RESPONSE_UNREADABLE")
            search(StubFinancialOpsServer.pageJson(StubFinancialOpsServer.itemJson(fileId)), period.copy(needsAttention = true)) shouldBe
                FinancialCallResult.Unavailable("RESPONSE_UNREADABLE")
            StubFinancialOpsServer.reply(StubFinancialOpsServer.recordsPath(fileId), StubFinancialOpsServer.Reply(200, StubFinancialOpsServer.recordsJson(StubFinancialOpsServer.recordJson(1))))
            gateway().records(fileId, AcquisitionRecordSearchCriteria(afterSeq = 1)) shouldBe FinancialCallResult.Unavailable("RESPONSE_UNREADABLE")
            gateway().records(fileId, AcquisitionRecordSearchCriteria(status = "UNKNOWN")) shouldBe FinancialCallResult.Unavailable("RESPONSE_UNREADABLE")
        }
        Then("review MINOR-3: page order and cursors must be the FINANCIAL keyset — out of order, a cursor that is not the last item, a cursor on a short page, a detail record page that is not the first page") {
            val newer = StubFinancialOpsServer.itemJson("ACQ.SFTPV9.TEST.20261020.001")
            val older = StubFinancialOpsServer.itemJson("ACQ.SFTPV1.TEST.20261020.001")
            search(StubFinancialOpsServer.pageJson(older, newer)) shouldBe FinancialCallResult.Unavailable("RESPONSE_UNREADABLE")
            search(StubFinancialOpsServer.pageJson(newer, older)).shouldBeInstanceOf<FinancialCallResult.Found>()
            val cursorOf = { id: String -> """{"businessDate":"2026-10-20","fileId":"$id"}""" }
            search(StubFinancialOpsServer.pageJson(newer, older, nextCursor = cursorOf("ACQ.SFTPV1.TEST.20261020.001")), period.copy(limit = 2))
                .shouldBeInstanceOf<FinancialCallResult.Found>()
            search(StubFinancialOpsServer.pageJson(newer, older, nextCursor = cursorOf("ACQ.SFTPV9.TEST.20261020.001")), period.copy(limit = 2)) shouldBe
                FinancialCallResult.Unavailable("RESPONSE_UNREADABLE")
            search(StubFinancialOpsServer.pageJson(newer, older, nextCursor = cursorOf("ACQ.SFTPV1.TEST.20261020.001")), period.copy(limit = 5)) shouldBe
                FinancialCallResult.Unavailable("RESPONSE_UNREADABLE")
            fun records(body: String, criteria: AcquisitionRecordSearchCriteria = AcquisitionRecordSearchCriteria()): FinancialCallResult {
                StubFinancialOpsServer.reply(StubFinancialOpsServer.recordsPath(fileId), StubFinancialOpsServer.Reply(200, body))
                return gateway().records(fileId, criteria)
            }
            val r = { seq: Int -> StubFinancialOpsServer.recordJson(seq) }
            records(StubFinancialOpsServer.recordsJson(r(2), r(1))) shouldBe
                FinancialCallResult.Unavailable("RESPONSE_UNREADABLE")
            records(StubFinancialOpsServer.recordsJson(r(1), r(2), nextCursor = "1"), AcquisitionRecordSearchCriteria(limit = 2)) shouldBe
                FinancialCallResult.Unavailable("RESPONSE_UNREADABLE")
            records(StubFinancialOpsServer.recordsJson(r(1), r(2), nextCursor = "2"), AcquisitionRecordSearchCriteria(limit = 2))
                .shouldBeInstanceOf<FinancialCallResult.Found>()
            records(StubFinancialOpsServer.recordsJson(r(1), nextCursor = "1")) shouldBe
                FinancialCallResult.Unavailable("RESPONSE_UNREADABLE")
            StubFinancialOpsServer.reply(StubFinancialOpsServer.filePath(fileId), StubFinancialOpsServer.Reply(200,
                StubFinancialOpsServer.detailJson(fileId, StubFinancialOpsServer.recordsJson(StubFinancialOpsServer.recordJson(2), StubFinancialOpsServer.recordJson(1)))))
            gateway().detail(fileId) shouldBe FinancialCallResult.Unavailable("RESPONSE_UNREADABLE")
            StubFinancialOpsServer.reply(StubFinancialOpsServer.filePath(fileId), StubFinancialOpsServer.Reply(200,
                StubFinancialOpsServer.detailJson(fileId, StubFinancialOpsServer.recordsJson(StubFinancialOpsServer.recordJson(1), nextCursor = "1"))))
            gateway().detail(fileId) shouldBe FinancialCallResult.Unavailable("RESPONSE_UNREADABLE")
            // 상세의 레코드 첫 쪽은 기본 조건(afterSeq 0·상태 없음·기본 쪽 크기 20) — 21건은 이 요청의 답이 아니다(모양 상한 50 안이어도).
            StubFinancialOpsServer.reply(StubFinancialOpsServer.filePath(fileId), StubFinancialOpsServer.Reply(200,
                StubFinancialOpsServer.detailJson(fileId, StubFinancialOpsServer.recordsJson(*(1..21).map(r).toTypedArray()))))
            gateway().detail(fileId) shouldBe FinancialCallResult.Unavailable("RESPONSE_UNREADABLE")
            StubFinancialOpsServer.reply(StubFinancialOpsServer.filePath(fileId), StubFinancialOpsServer.Reply(200,
                StubFinancialOpsServer.detailJson(fileId, StubFinancialOpsServer.recordsJson(*(1..20).map(r).toTypedArray(), nextCursor = "20"))))
            gateway().detail(fileId).shouldBeInstanceOf<FinancialCallResult.Found>()
        }
        Then("a body over 128 KiB is unreadable without parsing it") {
            search("""{"items":[],"nextCursor":null}""" + " ".repeat(128 * 1024)) shouldBe FinancialCallResult.Unavailable("RESPONSE_UNREADABLE")
        }
    }

    Given("transport failures, the transaction guard and the dedicated permit") {
        Then("a read timeout and a refused connection are values, not exceptions") {
            StubFinancialOpsServer.reply(StubFinancialOpsServer.filePath("ACQ.SLOW.TEST.20261020.001"), StubFinancialOpsServer.Reply(200, "{}", delayMillis = 1_500))
            gateway(settings(read = Duration.ofMillis(300))).detail("ACQ.SLOW.TEST.20261020.001") shouldBe FinancialCallResult.Unavailable("TIMEOUT")
            gateway(settings(baseUrl = "http://127.0.0.1:1")).detail(fileId) shouldBe FinancialCallResult.Unavailable("CONNECT_FAILED")
        }
        Then("calling inside an active DB transaction is refused before any HTTP call") {
            val before = StubFinancialOpsServer.received.size
            TransactionSynchronizationManager.setActualTransactionActive(true)
            try {
                shouldThrow<IllegalStateException> { gateway().detail(fileId) }
            } finally {
                TransactionSynchronizationManager.setActualTransactionActive(false)
            }
            StubFinancialOpsServer.received.size shouldBe before
        }
        Then("a saturated permit pool answers busy immediately without calling FINANCIAL") {
            StubFinancialOpsServer.reply(StubFinancialOpsServer.filePath("ACQ.HOLD.TEST.20261020.001"), StubFinancialOpsServer.Reply(200, "{}", delayMillis = 1_000))
            val g = gateway(settings(permits = 1))
            val pool = Executors.newSingleThreadExecutor()
            val started = CountDownLatch(1)
            try {
                val holding = pool.submit<FinancialCallResult> { started.countDown(); g.detail("ACQ.HOLD.TEST.20261020.001") }
                started.await(5, TimeUnit.SECONDS)
                Thread.sleep(200)
                g.detail(fileId) shouldBe FinancialCallResult.Unavailable("FINANCIAL_OPS_BUSY")
                holding.get(5, TimeUnit.SECONDS)
            } finally {
                pool.shutdownNow()
            }
        }
        Then("no signing key, signature header or v1 value, file id or MID appears in any log line") {
            StubFinancialOpsServer.reply(StubFinancialOpsServer.filePath(fileId), StubFinancialOpsServer.Reply(200, StubFinancialOpsServer.detailJson(fileId)))
            val logs = captureLogs { gateway().detail(fileId) }
            val signature = StubFinancialOpsServer.requestsForPath(StubFinancialOpsServer.filePath(fileId)).last().signature!!
            withClue("control: the gateway logged this call") { logs.any { it.contains("financial-ops-detail") } shouldBe true }
            logs.forEach { line ->
                listOf(StubFinancialOpsServer.KEY_BASE64, signature, signature.substringAfter("v1="), fileId, "demo-mid-001").forEach { line shouldNotContain it }
            }
        }
    }

    Given("settings and startup checks (no defaults — channel-separate keys)") {
        val paymentKey = "dGVzdC1vbmx5LWFkbWluLWNhbGwtZ29sZGVuLWtleS0wMDAx"
        val masterKey = "dGVzdC1vbmx5LW1hc3Rlci1hZG1pbi1jaGFubmVsLWtleS0wMDAx"
        Then("separate kid and bytes start; the same kid or bytes as the PAYMENT or MASTER ops key do not (messages name the rule only)") {
            FinancialOpsConfiguration.startupGuard("", "DEMO", settings(), listOf("pops-k1" to paymentKey, "mops-test-k1" to masterKey))
            listOf(
                listOf(StubFinancialOpsServer.KEY_ID to paymentKey),
                listOf("pops-k1" to StubFinancialOpsServer.KEY_BASE64),
                listOf("pops-k1" to paymentKey, "mops-test-k1" to StubFinancialOpsServer.KEY_BASE64),
                listOf("pops-k1" to paymentKey, StubFinancialOpsServer.KEY_ID to masterKey),
            ).forEach { others ->
                val e = shouldThrow<IllegalStateException> { FinancialOpsConfiguration.startupGuard("", "DEMO", settings(), others) }
                e.message!! shouldNotContain StubFinancialOpsServer.KEY_BASE64
                e.message!! shouldNotContain StubFinancialOpsServer.KEY_ID
            }
        }
        Then("LIVE with a Demo or test key and a context path are refused; settings have no defaults and hide the key") {
            shouldThrow<IllegalStateException> { FinancialOpsConfiguration.startupGuard("", "LIVE", settings(), emptyList()) }
            shouldThrow<IllegalStateException> { FinancialOpsConfiguration.startupGuard("/admin", "DEMO", settings(), emptyList()) }
            shouldThrow<IllegalArgumentException> { FinancialOpsSettings(null, "k", StubFinancialOpsServer.KEY_BASE64, Duration.ofSeconds(1), Duration.ofSeconds(1), 1, 1) }
            shouldThrow<IllegalArgumentException> { FinancialOpsSettings("http://h/path", "k", StubFinancialOpsServer.KEY_BASE64, Duration.ofSeconds(1), Duration.ofSeconds(1), 1, 1) }
            shouldThrow<IllegalArgumentException> { FinancialOpsSettings("http://h", "k", "c2hvcnQ=", Duration.ofSeconds(1), Duration.ofSeconds(1), 1, 1) }
            shouldThrow<IllegalArgumentException> { FinancialOpsSettings("http://h", "k", StubFinancialOpsServer.KEY_BASE64, Duration.ofSeconds(1), Duration.ofSeconds(1), 0, 1) }
            settings().toString() shouldNotContain StubFinancialOpsServer.KEY_BASE64
        }
    }
})

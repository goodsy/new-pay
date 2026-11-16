package com.paycore.admin.merchantops

import com.paycore.admin.merchantops.adapter.integration.HttpMasterOpsGateway
import com.paycore.admin.merchantops.adapter.integration.MasterCallSigner
import com.paycore.admin.merchantops.adapter.integration.MasterOpsSettings
import com.paycore.admin.merchantops.application.provided.MerchantLabel
import com.paycore.admin.merchantops.application.provided.MerchantLabelFailure
import com.paycore.admin.merchantops.application.provided.MerchantLabelResult
import com.paycore.admin.merchantops.application.provided.MerchantSearchCriteria
import com.paycore.admin.merchantops.application.required.MasterCallResult
import com.paycore.admin.support.StubMasterOpsServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * TXN-LIST-ENRICH-001 E-2 ADMIN → MASTER 라벨 묶음 조회(§4.2·§4.4·§3.6 — 실제 HTTP 대역 [StubMasterOpsServer], 서명은 대역이 앱과 독립 검증):
 * T-E2-8 서명 POST·본문 정확(정렬된 MID 목록만, query 없음)·DB TX 안 거부·전용 permit busy(MASTER 미호출)·timeout(**지연 상한** — 라벨 전용 read timeout)·5xx·401·400·503 busy·과대 → 값,
 * T-E2-9 모양·대응 위반(모르는·빠진 키, 이름 규칙 M-4, 코드 형식, 보내지 않은 MID, 중복, 역순, 중복 JSON 키 N-3) → 라벨 전체 UNREADABLE,
 * T-E2-12(M-1) 라벨 permit 소진이 승인 결정 조회(승인 회복 경로)·가맹점 목록 조회를 막지 않고, 조회 permit 소진이 라벨을 막지 않는다. 설정: 라벨 permit·timeout 기본값 없음·범위.
 */
class MerchantLabelGatewaySpec : BehaviorSpec({

    StubMasterOpsServer.start()
    val path = StubMasterOpsServer.ACCOUNT_LABELS

    fun settings(permits: Int = 2, labelPermits: Int = 2, labelRead: Duration = Duration.ofMillis(400), read: Duration = Duration.ofSeconds(3)) = MasterOpsSettings(
        "http://127.0.0.1:${StubMasterOpsServer.PORT}", StubMasterOpsServer.KEY_ID, StubMasterOpsServer.KEY_BASE64, Duration.ofSeconds(1), read, permits, 30, 10, 2, 20,
        labelPermits, labelRead,
    )
    fun gateway(s: MasterOpsSettings = settings()) = HttpMasterOpsGateway(s, MasterCallSigner(s.keyId, s.signingKey())) { Instant.now() }

    val m1 = "mid_01JC0000000000000000000001"
    val m2 = "mid_01JC0000000000000000000002"
    val m3 = "mid_01JC0000000000000000000003"
    fun reply(body: String, status: Int = 200, delay: Long = 0) = StubMasterOpsServer.reply(path, StubMasterOpsServer.Reply(status, body, delayMillis = delay))
    fun unreadable(result: MerchantLabelResult) =
        result.shouldBeInstanceOf<MerchantLabelResult.Unavailable>().failure shouldBe MerchantLabelFailure.UNREADABLE

    Given("T-E2-8 the label call") {
        Then("a signed POST the stub verifies independently, body exactly the sorted ids, no query; 200 becomes name + code per MID; an absent MID is simply missing") {
            reply(StubMasterOpsServer.labelsJson(Triple(m1, "데모상점", "demoshop01"), Triple(m3, "Demo Shop", "shopxyz789")))
            StubMasterOpsServer.received.clear()
            val result = gateway().accountLabels(listOf(m1, m2, m3))
            result.shouldBeInstanceOf<MerchantLabelResult.Found>().labels shouldBe mapOf(
                m1 to MerchantLabel("데모상점", "demoshop01"), m3 to MerchantLabel("Demo Shop", "shopxyz789"),
            )
            val sent = StubMasterOpsServer.requestsForPath(path).single()
            sent.method shouldBe "POST"
            sent.query shouldBe null
            sent.signatureValid shouldBe true
            String(sent.body, Charsets.UTF_8) shouldBe """{"merchantAccountIds":["$m1","$m2","$m3"]}"""
        }

        Then("the caller must give 1..50 distinct, sorted, well-formed MIDs (the gateway never builds a body from anything else)") {
            listOf(emptyList(), listOf(m2, m1), listOf(m1, m1), listOf("mer_01JC0000000000000000000001"), (1..51).map { "mid_01JC" + it.toString().padStart(22, '0') })
                .forEach { ids -> withClue(ids.take(2)) { shouldThrow<IllegalArgumentException> { gateway().accountLabels(ids) } } }
        }

        Then("inside a database transaction the call is refused before any request") {
            StubMasterOpsServer.received.clear()
            TransactionSynchronizationManager.setActualTransactionActive(true)
            try {
                shouldThrow<IllegalStateException> { gateway().accountLabels(listOf(m1)) }
            } finally {
                TransactionSynchronizationManager.setActualTransactionActive(false)
            }
            StubMasterOpsServer.requestsForPath(path).size shouldBe 0
        }

        Then("M-2 latency bound: a slow MASTER is cut by the label read timeout (not the general read timeout) — Unavailable TIMEOUT well under the general timeout") {
            reply(StubMasterOpsServer.labelsJson(Triple(m1, "데모상점", "demoshop01")), delay = 2_500)
            val started = System.nanoTime()
            val result = gateway(settings(labelRead = Duration.ofMillis(400), read = Duration.ofSeconds(3))).accountLabels(listOf(m1))
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            result.shouldBeInstanceOf<MerchantLabelResult.Unavailable>().let {
                it.failure shouldBe MerchantLabelFailure.UNAVAILABLE
                it.reason shouldBe "TIMEOUT"
            }
            withClue("elapsed=$elapsed") { (elapsed < 1_500) shouldBe true }
        }

        Then("5xx, 401, 400 and 404 are Unavailable; 503 ADMIN_READ_BUSY is BUSY; an oversized body is UNAVAILABLE (spec §8 limit exceeded), not UNREADABLE") {
            listOf(
                Triple(500, """{"error":"X"}""", MerchantLabelFailure.UNAVAILABLE),
                Triple(503, """{"error":"TEMPORARILY_UNAVAILABLE"}""", MerchantLabelFailure.UNAVAILABLE),
                Triple(401, """{"error":"UNAUTHENTICATED"}""", MerchantLabelFailure.UNAVAILABLE),
                Triple(400, """{"error":"REQUEST_MALFORMED"}""", MerchantLabelFailure.UNAVAILABLE),
                Triple(404, """{"error":"MERCHANT_NOT_FOUND"}""", MerchantLabelFailure.UNAVAILABLE),
                Triple(503, """{"error":"ADMIN_READ_BUSY"}""", MerchantLabelFailure.BUSY),
                // 기대값 변경(리뷰 M-a: 명세 §8 상한 초과 = UNAVAILABLE) — 이전 기대값 UNREADABLE은 §8 분류(UNREADABLE = 200 모양·대응 위반만)와 어긋났다.
                Triple(200, " ".repeat(64 * 1024 + 1), MerchantLabelFailure.UNAVAILABLE),
            ).forEach { (status, body, failure) ->
                withClue("$status ${body.take(40)}") {
                    reply(body, status)
                    gateway().accountLabels(listOf(m1)).shouldBeInstanceOf<MerchantLabelResult.Unavailable>().failure shouldBe failure
                }
            }
            reply(" ".repeat(64 * 1024 + 1))
            gateway().accountLabels(listOf(m1)).shouldBeInstanceOf<MerchantLabelResult.Unavailable>().reason shouldBe "RESPONSE_TOO_LARGE"
        }

        Then("a connection failure is Unavailable") {
            val s = MasterOpsSettings(
                "http://127.0.0.1:9", StubMasterOpsServer.KEY_ID, StubMasterOpsServer.KEY_BASE64, Duration.ofSeconds(1), Duration.ofSeconds(1), 2, 30, 10, 2, 20, 2, Duration.ofMillis(500),
            )
            gateway(s).accountLabels(listOf(m1)).shouldBeInstanceOf<MerchantLabelResult.Unavailable>().failure shouldBe MerchantLabelFailure.UNAVAILABLE
        }
    }

    Given("T-E2-9 shape and correspondence (all or nothing)") {
        Then("unknown or missing keys, internal keys, bad codes, ids not sent, duplicates, descending order, too many items and duplicate JSON keys are UNREADABLE") {
            val ok = """{"merchantAccountId":"$m1","merchantName":"데모상점","merchantCode":"demoshop01"}"""
            listOf(
                """{"items":[{"merchantAccountId":"$m1","merchantName":"데모상점","merchantCode":"demoshop01","merchantId":"mer_01JC0000000000000000000001"}]}""",
                """{"items":[{"merchantAccountId":"$m1","merchantName":"데모상점"}]}""",
                """{"items":[$ok],"nextCursor":null}""",
                """{"items":[{"merchantAccountId":"$m1","merchantName":"데모상점","merchantCode":"DEMOSHOP01"}]}""",
                """{"items":[{"merchantAccountId":"$m1","merchantName":"데모상점","merchantCode":"demo"}]}""",
                """{"items":[{"merchantAccountId":"mid_01JC0000000000000000000009","merchantName":"데모상점","merchantCode":"demoshop01"}]}""",
                """{"items":[$ok,$ok]}""",
                """{"items":[{"merchantAccountId":"$m2","merchantName":"B","merchantCode":"shopbbbb02"},$ok]}""",
                """{"items":[$ok,{"merchantAccountId":"$m2","merchantName":"B","merchantCode":"shopbbbb02"},{"merchantAccountId":"$m3","merchantName":"C","merchantCode":"shopcccc03"}]}""",
                """{"items":[{"merchantAccountId":"$m1","merchantName":"데모상점","merchantName":"다른상점","merchantCode":"demoshop01"}]}""",
                """{"items":[$ok],"items":[]}""",
                """{"items":[{"merchantAccountId":"$m1","merchantName":null,"merchantCode":"demoshop01"}]}""",
            ).forEach { body ->
                withClue(body) {
                    reply(body)
                    unreadable(gateway().accountLabels(listOf(m1, m2)))
                }
            }
        }

        Then("M-4 name = MASTER DisplayName rule: 100 code points (supplementary) and U+2028 pass; 101 code points, ISO controls (U+0007, U+0085), edge spaces and empty fail") {
            fun body(name: String) = """{"items":[{"merchantAccountId":"$m1","merchantName":"${name.replace("\\", "\\\\").replace("\"", "\\\"")}","merchantCode":"demoshop01"}]}"""
            fun escaped(name: String) = """{"items":[{"merchantAccountId":"$m1","merchantName":"$name","merchantCode":"demoshop01"}]}"""
            val emoji = "😀"
            reply(body(emoji.repeat(100)))
            gateway().accountLabels(listOf(m1)).shouldBeInstanceOf<MerchantLabelResult.Found>().labels.getValue(m1).name shouldBe emoji.repeat(100)
            reply(escaped("가\\u2028나"))
            gateway().accountLabels(listOf(m1)).shouldBeInstanceOf<MerchantLabelResult.Found>().labels.getValue(m1).name shouldBe "가 나"
            listOf(body(emoji.repeat(101)), escaped("가\\u0007나"), escaped("가\\u0085나"), body(" 가나"), body("가나 "), body(""), escaped("가\\uD800나")).forEach {
                withClue(it) {
                    reply(it)
                    unreadable(gateway().accountLabels(listOf(m1)))
                }
            }
        }
    }

    Given("T-E2-12 (M-1) permit isolation") {
        // NIT-2: 순서는 sleep이 아니라 latch로 맞춘다 — 대역 handler가 요청 도착(= 그 permit을 이미 잡음)을 알리고, 시험이 놓아 줄 때까지 응답을 잡아 둔다.
        // 잡고 있는 호출의 read timeout은 넉넉히(3s) — 단언 시간이 길어져도 holder가 TIMEOUT으로 permit을 먼저 놓지 않게.
        Then("with every label permit held the label call is BUSY at once and MASTER is not called; approval-decision and merchant search still get their permit") {
            val g = gateway(settings(permits = 1, labelPermits = 1, labelRead = Duration.ofSeconds(3), read = Duration.ofSeconds(3)))
            val arrived = CountDownLatch(1)
            val release = CountDownLatch(1)
            StubMasterOpsServer.replyWith(path) {
                arrived.countDown()
                release.await(10, TimeUnit.SECONDS)
                StubMasterOpsServer.Reply(200, StubMasterOpsServer.labelsJson(Triple(m1, "데모상점", "demoshop01")))
            }
            StubMasterOpsServer.reply(StubMasterOpsServer.SEARCH, StubMasterOpsServer.Reply(200, StubMasterOpsServer.EMPTY_DIRECTORY))
            StubMasterOpsServer.replyWith(StubMasterOpsServer.APPROVAL_DECISIONS) { StubMasterOpsServer.Reply(200, """{"decision":"NONE"}""") }
            try {
                val holder = CompletableFuture.supplyAsync { g.accountLabels(listOf(m1)) }
                arrived.await(10, TimeUnit.SECONDS) shouldBe true
                StubMasterOpsServer.received.clear()
                g.accountLabels(listOf(m2)).shouldBeInstanceOf<MerchantLabelResult.Unavailable>().failure shouldBe MerchantLabelFailure.BUSY
                StubMasterOpsServer.requestsForPath(path).size shouldBe 0
                // 승인 회복 경로(결정 조회)·가맹점 목록은 라벨 permit과 무관하다 — 라벨 busy가 회복 상태(BUSY → 다음 시도 미룸)를 만들지 않는다.
                g.approvalDecision("apr_01JC0000000000000000000001").toString() shouldBe "None"
                g.search(MerchantSearchCriteria(limit = 1)).shouldBeInstanceOf<MasterCallResult.Found>()
                release.countDown()
                holder.get(10, TimeUnit.SECONDS).shouldBeInstanceOf<MerchantLabelResult.Found>()
            } finally {
                release.countDown()
                StubMasterOpsServer.clearDynamic(path)
                StubMasterOpsServer.clearDynamic(StubMasterOpsServer.APPROVAL_DECISIONS)
            }
        }

        Then("with the general lookup permit held (slow merchant search) labels still answer") {
            val g = gateway(settings(permits = 1, labelPermits = 1, read = Duration.ofSeconds(3)))
            val arrived = CountDownLatch(1)
            val release = CountDownLatch(1)
            StubMasterOpsServer.replyWith(StubMasterOpsServer.SEARCH) {
                arrived.countDown()
                release.await(10, TimeUnit.SECONDS)
                StubMasterOpsServer.Reply(200, StubMasterOpsServer.EMPTY_DIRECTORY)
            }
            reply(StubMasterOpsServer.labelsJson(Triple(m1, "데모상점", "demoshop01")))
            try {
                val holder = CompletableFuture.supplyAsync { g.search(MerchantSearchCriteria(limit = 1)) }
                arrived.await(10, TimeUnit.SECONDS) shouldBe true
                g.search(MerchantSearchCriteria(limit = 1)).shouldBeInstanceOf<MasterCallResult.Unavailable>().reason shouldBe "MASTER_OPS_BUSY"
                g.accountLabels(listOf(m1)).shouldBeInstanceOf<MerchantLabelResult.Found>()
                release.countDown()
                holder.get(10, TimeUnit.SECONDS).shouldBeInstanceOf<MasterCallResult.Found>()
            } finally {
                release.countDown()
                StubMasterOpsServer.clearDynamic(StubMasterOpsServer.SEARCH)
                StubMasterOpsServer.reply(StubMasterOpsServer.SEARCH, StubMasterOpsServer.Reply(200, StubMasterOpsServer.EMPTY_DIRECTORY))
            }
        }
    }

    Given("settings") {
        Then("label-permits and label-read-timeout have no default, are range-checked, and the label timeout may not exceed the general read timeout") {
            fun s(labelPermits: Int?, labelRead: Duration?, read: Duration = Duration.ofSeconds(2)) =
                MasterOpsSettings("http://h", "k", StubMasterOpsServer.KEY_BASE64, Duration.ofSeconds(1), read, 1, 1, 1, 1, 1, labelPermits, labelRead)
            shouldThrow<IllegalArgumentException> { s(null, Duration.ofSeconds(1)) }
            shouldThrow<IllegalArgumentException> { s(1, null) }
            shouldThrow<IllegalArgumentException> { s(0, Duration.ofSeconds(1)) }
            shouldThrow<IllegalArgumentException> { s(17, Duration.ofSeconds(1)) }
            shouldThrow<IllegalArgumentException> { s(1, Duration.ZERO) }
            shouldThrow<IllegalArgumentException> { s(1, Duration.ofSeconds(3), read = Duration.ofSeconds(2)) }
            s(16, Duration.ofSeconds(2)).let { listOf(it.labelPermits, it.labelReadTimeout) } shouldBe listOf(16, Duration.ofSeconds(2))
        }
    }
})

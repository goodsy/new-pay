package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.MasterDbAccounts
import com.paycore.master.MasterDbTest
import com.paycore.master.column
import com.paycore.master.merchant.adapter.persistence.MasterSchemaRows
import com.paycore.master.merchant.adapter.query.JooqMerchantLabelReader
import com.paycore.master.merchant.application.CapturedLogs
import com.paycore.master.merchant.application.OnboardingFixture
import com.paycore.master.merchant.application.provided.MerchantOnboardingUseCase
import com.paycore.master.merchant.domain.MerchantCodeFixtures
import com.paycore.master.scalar
import com.paycore.master.update
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.micrometer.core.instrument.MeterRegistry
import org.jooq.DSLContext
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.testcontainers.mysql.MySQLContainer
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * MERCHANT-CODE-001 MC-3-17·MC-3-14 MASTER `POST /internal/v1/admin/merchant-labels`(§5.6.3 MC-3b — 실제 보안 체인 + MVC + MySQL, 공식 등록 경로 Named Fixture, Fixture Monkey 미사용):
 * 3 키 → merchantId 오름차순·상호·**현재** 코드(MC-1b 변경 가맹점은 새 코드), 없는 키 빠짐·TERMINATED 포함, 본문 엄격성 400(0개·51개·중복·형식·모르는 키·query),
 * 401·라벨 permit busy 503 + 지표 `busy{endpoint="merchant_labels"}`, 비노출(`businessId`·`biz_`·등록번호·코드 이력 — DB에 값이 있는 대조군), 쓰기 0(전 표 행 수·checksum 불변),
 * 계획 P-1(merchant PK range·배정 PK, 전체 스캔 없음), MC-3-14 라벨 permit과 조회 permit(목록·상세·**승인 결정 조회**)은 서로 막지 않는다. 로그는 분류·항목 수만.
 */
@MasterDbTest
class MerchantLabelApiSpec(
    context: WebApplicationContext,
    private val onboarding: MerchantOnboardingUseCase,
    private val registry: MeterRegistry,
    private val container: MySQLContainer,
    private val permits: AdminChannelPermits,
    private val reader: JooqMerchantLabelReader,
    private val dsl: DSLContext,
) : BehaviorSpec({

    val mvc: MockMvc = MockMvcBuilders.webAppContextSetup(context).apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity()).build()
    val json = JsonMapper.builder().build()
    val labels = "/internal/v1/admin/merchant-labels"

    fun post(path: String, body: String, sign: Boolean = true): MvcResult {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val b = MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON).content(bytes)
        return mvc.perform(if (sign) b.header(AdminCallSignature.HEADER, MasterAdminChannelFixture.springHeader("POST", path, bytes)) else b).andReturn()
    }
    fun get(path: String): MvcResult =
        mvc.perform(MockMvcRequestBuilders.get(path).header(AdminCallSignature.HEADER, MasterAdminChannelFixture.springHeader("GET", path, ByteArray(0)))).andReturn()
    fun MvcResult.body(): String = response.getContentAsString(Charsets.UTF_8)
    fun MvcResult.node(): JsonNode = json.readTree(body())
    fun ids(vararg merchantIds: String) = """{"merchantIds":[${merchantIds.joinToString(",") { "\"$it\"" }}]}"""

    /** 공식 경로로 만든 가맹점(상호를 가맹점마다 다르게). */
    data class Labeled(val merchantId: String, val businessId: String, val name: String, val code: String)
    fun labeled(name: String): Labeled {
        val ready = OnboardingFixture.readyMerchant(onboarding, merchantName = name)
        val code = MasterDbAccounts.root(container).use {
            it.scalar("SELECT merchant_code FROM merchant_code_assignment WHERE merchant_id = ? AND assignment_seq = 1", ready.merchantId.value) as String
        }
        return Labeled(ready.merchantId.value, ready.businessId.value, name, code)
    }

    Given("MC-3-17 five merchants — one whose code was changed once (MC-1b), one terminated") {
        val a = labeled("코드라벨 가")
        val b = labeled("Code Label B")
        val changed = labeled("코드라벨 다")
        val terminated = labeled("코드라벨 라")
        val e = labeled("코드라벨 마")
        val newCode = MerchantCodeFixtures.unique().value
        MasterDbAccounts.root(container).use { c ->
            // 1회 변경(MC-1b) 뒤의 유효한 저장 상태(MerchantAccountLabelApiSpec과 같은 시험 데이터 준비) — 현재 코드 = seq 2.
            c.update(
                "INSERT INTO merchant_code_assignment (merchant_id, assignment_seq, merchant_code, replaces_code, source, approval_id, reason_code, assigned_at, assigned_by) " +
                    "VALUES (?, 2, ?, ?, 'CHANGE', ?, 'TYPO_CORRECTION', '2026-10-09 03:00:00.000', 'admin:adm_01JC00000000000000000000V2')",
                changed.merchantId, newCode, changed.code, MasterSchemaRows.id("apr"),
            )
            c.update("UPDATE merchant SET lifecycle = 'TERMINATED' WHERE merchant_id = ?", terminated.merchantId)
        }
        val unknown = MasterSchemaRows.id("mer")

        Then("MC-3-17 an unsorted request answers in ascending merchantId order with exactly three keys per item, the merchant name and the current code (the changed one shows the new code)") {
            val res = post(labels, ids(changed.merchantId, a.merchantId, b.merchantId))
            res.response.status shouldBe 200
            res.response.getHeader("Cache-Control")!! shouldContain "no-store"
            val node = res.node()
            node.propertyNames().toList() shouldBe listOf("items")
            val items = node.get("items").items()
            items.forEach { it.propertyNames().toList() shouldBe listOf("merchantId", "merchantName", "merchantCode") }
            val expected = listOf(a, b, changed).sortedBy { it.merchantId }.map { listOf(it.merchantId, it.name, if (it === changed) newCode else it.code) }
            items.map { listOf(it.get("merchantId").asString(), it.get("merchantName").asString(), it.get("merchantCode").asString()) } shouldBe expected
        }

        Then("MC-3-17 an unknown merchant key is simply absent; a TERMINATED merchant is included") {
            val node = post(labels, ids(unknown, terminated.merchantId)).node()
            node.get("items").items().map { it.get("merchantId").asString() } shouldBe listOf(terminated.merchantId)
            node.get("items").items().single().get("merchantName").asString() shouldBe terminated.name
            post(labels, ids(unknown)).body() shouldBe """{"items":[]}"""
        }

        Then("MC-3-17 strict body: empty/51/duplicate ids, other formats (MID, business key, lower case), unknown or missing fields, duplicate keys, trailing tokens, non-objects are 400; a query is 400") {
            val fifty = (1..50).map { MasterSchemaRows.id("mer") }
            val bad = listOf(
                "{}",
                """{"merchantIds":[]}""",
                ids(*(fifty + MasterSchemaRows.id("mer")).toTypedArray()),
                ids(a.merchantId, a.merchantId),
                ids(a.businessId),
                ids(MasterSchemaRows.id("mid")),
                ids(a.merchantId.lowercase()),
                ids("mer_x"),
                ids(a.code),
                """{"merchantIds":[1]}""",
                """{"merchantIds":[null]}""",
                """{"merchantIds":"${a.merchantId}"}""",
                """{"merchantIds":["${a.merchantId}"],"limit":1}""",
                """{"merchantAccountIds":["${a.merchantId}"]}""",
                """{"merchantIds":["${a.merchantId}"],"merchantIds":["${b.merchantId}"]}""",
                """{"merchantIds":["${a.merchantId}"]} {}""",
                """["${a.merchantId}"]""",
                "not json",
            )
            bad.forEach { body ->
                withClue(body.take(80)) {
                    val res = post(labels, body)
                    res.response.status shouldBe 400
                    res.body() shouldBe """{"error":"REQUEST_MALFORMED"}"""
                }
            }
            // query 금지(채널 필터 — 서명 경로 = 요청 경로, query는 서명 밖): 본문이 맞아도 400 QUERY_NOT_ALLOWED.
            val bytes = ids(a.merchantId).toByteArray(Charsets.UTF_8)
            val q = mvc.perform(
                MockMvcRequestBuilders.post("$labels?limit=1").contentType(MediaType.APPLICATION_JSON).content(bytes)
                    .header(AdminCallSignature.HEADER, MasterAdminChannelFixture.springHeader("POST", labels, bytes)),
            ).andReturn()
            q.response.status shouldBe 400
            q.body() shouldBe """{"error":"QUERY_NOT_ALLOWED"}"""
            // 대조군: 50개(상한)·서로 다른 형식 맞는 키는 200.
            post(labels, ids(*fifty.toTypedArray())).response.status shouldBe 200
        }

        Then("MC-3-17 unsigned is 401; with every label permit held the call is 503 ADMIN_READ_BUSY at once and counted under endpoint merchant_labels") {
            post(labels, ids(a.merchantId), sign = false).response.status shouldBe 401
            val before = registry.find(AdminChannelPermits.BUSY_METRIC).tags("endpoint", "merchant_labels").counter()?.count() ?: 0.0
            holdingPermits(permits::labels, MasterAdminChannelFixture.springSettings().labelPermits) {
                val started = System.nanoTime()
                val busy = post(labels, ids(a.merchantId))
                busy.response.status shouldBe 503
                busy.body() shouldBe """{"error":"ADMIN_READ_BUSY"}"""
                (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000) shouldBe true
            }
            registry.find(AdminChannelPermits.BUSY_METRIC).tags("endpoint", "merchant_labels").counter()!!.count() shouldBe before + 1.0
            post(labels, ids(a.merchantId)).response.status shouldBe 200
        }

        Then("MC-3-14 label permits (shared with the transaction-list labels) and the read permit never block each other: labels busy leaves approval-decisions, detail and list working; read busy leaves labels working") {
            val approvalId = MasterSchemaRows.id("apr")
            val decisionPath = "/internal/v1/admin/merchant-operations/approval-decisions"
            val decisionBody = """{"approvalId":"$approvalId","correlationId":"mop.$approvalId"}"""
            val decisionBefore = post(decisionPath, decisionBody).let { it.response.status to it.body() }
            decisionBefore.first shouldBe 200
            holdingPermits(permits::labels, MasterAdminChannelFixture.springSettings().labelPermits) {
                post(labels, ids(a.merchantId)).response.status shouldBe 503
                // 거래 목록 라벨도 같은 permit을 나눠 쓴다(C-34) — 둘이 몰리면 라벨만 UNAVAILABLE(L-12).
                post("/internal/v1/admin/merchant-account-labels", """{"merchantAccountIds":["${MasterSchemaRows.id("mid")}"]}""").response.status shouldBe 503
                post(decisionPath, decisionBody).let { it.response.status to it.body() } shouldBe decisionBefore
                get("/internal/v1/admin/merchants/${a.merchantId}").response.status shouldBe 200
                post("/internal/v1/admin/merchant-searches", "{}").response.status shouldBe 200
            }
            holdingPermits(permits::read, MasterAdminChannelFixture.springSettings().readPermits) {
                post(decisionPath, decisionBody).response.status shouldBe 503
                post(labels, ids(a.merchantId)).response.status shouldBe 200
            }
        }

        Then("MC-3-17 non-exposure: no businessId/biz_ key, registration number, business name, lifecycle or code history in the bytes, though the database has them (control)") {
            val body = post(labels, ids(a.merchantId, changed.merchantId, terminated.merchantId)).body()
            val registration = MasterDbAccounts.root(container).use { it.scalar("SELECT registration_number FROM business WHERE business_id = ?", a.businessId) as String }
            MasterDbAccounts.root(container).use { it.scalar("SELECT business_id FROM merchant WHERE merchant_id = ?", a.merchantId) } shouldBe a.businessId
            registration.length shouldBe 10
            // 코드 이력 대조군: changed의 옛 코드(seq 1)는 DB에 있지만 응답에는 현재 코드만.
            listOf("businessId", "biz_", a.businessId, registration, "Demo Business", "TERMINATED", "ACTIVE", changed.code, "replaces", "assignment").forEach { body shouldNotContain it }
            body shouldContain newCode
        }

        Then("MC-3-17 reads only: repeating the same calls returns identical bodies and every MASTER table keeps its row count and checksum") {
            fun snapshot(): Map<String, String> = MasterDbAccounts.root(container).use { c ->
                val tables = c.column(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = '${MasterDbAccounts.MASTER_DATABASE}' AND table_type = 'BASE TABLE' ORDER BY table_name",
                )
                tables.size shouldNotBe 0
                tables.associateWith { t ->
                    val count = c.scalar("SELECT COUNT(*) FROM `$t`").toString()
                    val checksum = c.prepareStatement("CHECKSUM TABLE `$t`").use { ps -> ps.executeQuery().use { rs -> rs.next(); rs.getString(2) } }
                    "$count/$checksum"
                }
            }
            val before = snapshot()
            val calls = listOf(ids(a.merchantId, changed.merchantId), ids(unknown), ids(terminated.merchantId, e.merchantId))
            val first = calls.map { post(labels, it).let { r -> r.response.status to r.body() } }
            val second = calls.map { post(labels, it).let { r -> r.response.status to r.body() } }
            second shouldBe first
            first.map { it.first } shouldBe listOf(200, 200, 200)
            snapshot() shouldBe before
        }

        Then("MC-3-17 plan P-1: one statement — merchant PK range, current code by the assignment PK; no full scan") {
            val merchantIds = listOf(a, b, changed, terminated, e).map { it.merchantId }
            val sql = dsl.renderInlined(reader.statement(merchantIds))
            val plan = MasterDbAccounts.root(container).use { c ->
                listOf("merchant", "merchant_code_assignment").forEach { t -> c.prepareStatement("ANALYZE TABLE `$t`").use { it.execute() } }
                c.prepareStatement("EXPLAIN $sql").use { ps ->
                    ps.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) add(mapOf("table" to rs.getString("table"), "type" to rs.getString("type"), "key" to rs.getString("key")))
                        }
                    }
                }
            }
            withClue("$sql\n$plan") {
                plan.shouldNotBeEmpty()
                plan.filter { it["type"] == "ALL" }.shouldBeEmpty()
                plan.single { it["table"] == "merchant" }.let { listOf(it["type"], it["key"]) } shouldBe listOf("range", "PRIMARY")
                plan.single { it["table"] == "merchant_code_assignment" }["key"] shouldBe "PRIMARY"
            }
        }

        Then("logs carry the classification and the item count only — no merchant key, name or code") {
            val lines = CapturedLogs().use { logs ->
                post(labels, ids(a.merchantId, b.merchantId)).response.status shouldBe 200
                post(labels, "{}").response.status shouldBe 400
                logs.lines()
            }
            lines.any { "operation=MerchantLabels" in it && "items=2" in it } shouldBe true
            lines.forEach { line -> listOf(a.merchantId, b.merchantId, a.name, b.name, a.code, b.code).forEach { line shouldNotContain it } }
        }
    }
})

private fun JsonNode.items(): List<JsonNode> = (0 until size()).map { get(it) }

/** [count]개 permit을 다른 스레드에서 잡은 채로 [block]을 실행한다(대기 0 permit의 busy 시험). */
private fun holdingPermits(acquire: (String, () -> Unit, () -> Unit) -> Unit, count: Int, block: () -> Unit) {
    val held = CountDownLatch(count)
    val release = CountDownLatch(1)
    val pool = Executors.newFixedThreadPool(count)
    try {
        repeat(count) { pool.submit { acquire("hold", { error("permit expected") }) { held.countDown(); release.await(10, TimeUnit.SECONDS) } } }
        held.await(5, TimeUnit.SECONDS) shouldBe true
        block()
    } finally {
        release.countDown()
        pool.shutdown()
        pool.awaitTermination(10, TimeUnit.SECONDS)
    }
}

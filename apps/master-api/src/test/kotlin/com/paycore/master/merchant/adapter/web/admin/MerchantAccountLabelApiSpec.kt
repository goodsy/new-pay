package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.MasterDbAccounts
import com.paycore.master.MasterDbTest
import com.paycore.master.column
import com.paycore.master.merchant.adapter.persistence.MasterSchemaRows
import com.paycore.master.merchant.adapter.query.JooqMerchantAccountLabelReader
import com.paycore.master.merchant.application.CapturedLogs
import com.paycore.master.merchant.application.OnboardingFixture
import com.paycore.master.merchant.application.OnboardingFixture.succeeded
import com.paycore.master.merchant.application.provided.MerchantOnboardingUseCase
import com.paycore.master.merchant.domain.MerchantAccountId
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
 * TXN-LIST-ENRICH-001 E-2 MASTER `POST /internal/v1/admin/merchant-account-labels`(§4.2 — 실제 보안 체인 + MVC + MySQL, 공식 등록 경로 Named Fixture, Fixture Monkey 미사용):
 * T-E2-1 MID 묶음 → 오름차순·상호·**현재** 코드(변경된 가맹점은 새 코드), T-E2-2 없는 MID 빠짐·TERMINATED 포함, T-E2-3 본문 엄격성 400, T-E2-4 401·전용 permit busy 503 + 지표,
 * T-E2-5 비노출(`mer_`·`biz_`·등록번호 — DB에 값이 있는 대조군), T-E2-6 쓰기 없음(전 표 행 수·checksum 불변), T-E2-7 계획 P-1(PK range·eq_ref·배정 PK, 전체 스캔 없음),
 * T-E2-12(M-1) 라벨 permit과 기존 조회 permit(목록·상세·**승인 결정 조회**)은 서로 막지 않는다. 로그는 분류·항목 수만.
 */
@MasterDbTest
class MerchantAccountLabelApiSpec(
    context: WebApplicationContext,
    private val onboarding: MerchantOnboardingUseCase,
    private val registry: MeterRegistry,
    private val container: MySQLContainer,
    private val permits: AdminChannelPermits,
    private val reader: JooqMerchantAccountLabelReader,
    private val dsl: DSLContext,
) : BehaviorSpec({

    val mvc: MockMvc = MockMvcBuilders.webAppContextSetup(context).apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity()).build()
    val json = JsonMapper.builder().build()
    val labels = "/internal/v1/admin/merchant-account-labels"

    fun post(path: String, body: String, sign: Boolean = true): MvcResult {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val b = MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON).content(bytes)
        return mvc.perform(if (sign) b.header(AdminCallSignature.HEADER, MasterAdminChannelFixture.springHeader("POST", path, bytes)) else b).andReturn()
    }
    fun get(path: String): MvcResult =
        mvc.perform(MockMvcRequestBuilders.get(path).header(AdminCallSignature.HEADER, MasterAdminChannelFixture.springHeader("GET", path, ByteArray(0)))).andReturn()
    fun MvcResult.body(): String = response.getContentAsString(Charsets.UTF_8)
    fun MvcResult.node(): JsonNode = json.readTree(body())
    fun ids(vararg mids: String) = """{"merchantAccountIds":[${mids.joinToString(",") { "\"$it\"" }}]}"""

    /** 공식 경로로 MID까지 만든 가맹점(상호를 가맹점마다 다르게). */
    data class Labeled(val mid: String, val merchantId: String, val businessId: String, val name: String, val code: String)
    fun labeled(name: String): Labeled {
        val ready = OnboardingFixture.readyMerchant(onboarding, merchantName = name)
        val mid = MerchantAccountId(succeeded(onboarding.issueFirstDefaultMerchantAccount(ready.issueCommand())))
        val code = MasterDbAccounts.root(container).use {
            it.scalar("SELECT merchant_code FROM merchant_code_assignment WHERE merchant_id = ? AND assignment_seq = 1", ready.merchantId.value) as String
        }
        return Labeled(mid.value, ready.merchantId.value, ready.businessId.value, name, code)
    }

    Given("T-E2-1 five merchants with a MID each — one whose code was changed once, one terminated") {
        val a = labeled("라벨상점 가")
        val b = labeled("Label Shop B")
        val changed = labeled("라벨상점 다")
        val terminated = labeled("라벨상점 라")
        val e = labeled("라벨상점 마")
        val newCode = MerchantCodeFixtures.unique().value
        MasterDbAccounts.root(container).use { c ->
            // 1회 변경(MC-1b) 뒤의 유효한 저장 상태(MerchantCodeApiSpec과 같은 시험 데이터 준비) — 현재 코드 = seq 2.
            c.update(
                "INSERT INTO merchant_code_assignment (merchant_id, assignment_seq, merchant_code, replaces_code, source, approval_id, reason_code, assigned_at, assigned_by) " +
                    "VALUES (?, 2, ?, ?, 'CHANGE', ?, 'TYPO_CORRECTION', '2026-10-09 03:00:00.000', 'admin:adm_01JC00000000000000000000V2')",
                changed.merchantId, newCode, changed.code, MasterSchemaRows.id("apr"),
            )
            c.update("UPDATE merchant SET lifecycle = 'TERMINATED' WHERE merchant_id = ?", terminated.merchantId)
        }
        val unknown = MasterSchemaRows.id("mid")

        Then("T-E2-1 an unsorted request answers in ascending MID order with exactly three keys per item, the merchant name and the current code (the changed one shows the new code)") {
            val res = post(labels, ids(changed.mid, a.mid, b.mid))
            res.response.status shouldBe 200
            res.response.getHeader("Cache-Control")!! shouldContain "no-store"
            val node = res.node()
            node.propertyNames().toList() shouldBe listOf("items")
            val items = node.get("items").elements()
            items.forEach { it.propertyNames().toList() shouldBe listOf("merchantAccountId", "merchantName", "merchantCode") }
            val expected = listOf(a, b, changed).sortedBy { it.mid }.map { listOf(it.mid, it.name, if (it === changed) newCode else it.code) }
            items.map { listOf(it.get("merchantAccountId").asString(), it.get("merchantName").asString(), it.get("merchantCode").asString()) } shouldBe expected
        }

        Then("T-E2-2 an unknown MID is simply absent; a TERMINATED merchant's MID is included") {
            val node = post(labels, ids(unknown, terminated.mid)).node()
            node.get("items").elements().map { it.get("merchantAccountId").asString() } shouldBe listOf(terminated.mid)
            node.get("items").elements().single().get("merchantName").asString() shouldBe terminated.name
            post(labels, ids(unknown)).body() shouldBe """{"items":[]}"""
        }

        Then("T-E2-3 strict body: empty/51/duplicate ids, other formats, extra or missing fields, duplicate keys, trailing tokens and non-objects are 400 REQUEST_MALFORMED") {
            val fifty = (1..50).map { MasterSchemaRows.id("mid") }
            val bad = listOf(
                "{}",
                """{"merchantAccountIds":[]}""",
                ids(*(fifty + MasterSchemaRows.id("mid")).toTypedArray()),
                ids(a.mid, a.mid),
                ids(a.merchantId),
                ids(a.mid.lowercase().replaceFirst("mid_", "mid_")),
                ids("mid_x"),
                """{"merchantAccountIds":[1]}""",
                """{"merchantAccountIds":[null]}""",
                """{"merchantAccountIds":"${a.mid}"}""",
                """{"merchantAccountIds":["${a.mid}"],"limit":1}""",
                """{"merchantAccountIds":["${a.mid}"],"merchantAccountIds":["${b.mid}"]}""",
                """{"merchantAccountIds":["${a.mid}"]} {}""",
                """["${a.mid}"]""",
                "not json",
            )
            bad.forEach { body ->
                withClue(body.take(80)) {
                    val res = post(labels, body)
                    res.response.status shouldBe 400
                    res.body() shouldBe """{"error":"REQUEST_MALFORMED"}"""
                }
            }
            // 대조군: 50개(상한)·서로 다른 형식 맞는 MID는 200.
            post(labels, ids(*fifty.toTypedArray())).response.status shouldBe 200
        }

        Then("T-E2-4 unsigned is 401; with every label permit held the call is 503 ADMIN_READ_BUSY at once and counted under endpoint merchant_account_labels") {
            post(labels, ids(a.mid), sign = false).response.status shouldBe 401
            val before = registry.find(AdminChannelPermits.BUSY_METRIC).tags("endpoint", "merchant_account_labels").counter()?.count() ?: 0.0
            holding(permits::labels, MasterAdminChannelFixture.springSettings().labelPermits) {
                val started = System.nanoTime()
                val busy = post(labels, ids(a.mid))
                busy.response.status shouldBe 503
                busy.body() shouldBe """{"error":"ADMIN_READ_BUSY"}"""
                (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000) shouldBe true
            }
            registry.find(AdminChannelPermits.BUSY_METRIC).tags("endpoint", "merchant_account_labels").counter()!!.count() shouldBe before + 1.0
            post(labels, ids(a.mid)).response.status shouldBe 200
        }

        Then("T-E2-12 (M-1) label permits and the existing read permit never block each other: labels busy leaves approval-decisions and detail working; read busy leaves labels working") {
            val approvalId = MasterSchemaRows.id("apr")
            val decisionPath = "/internal/v1/admin/merchant-operations/approval-decisions"
            val decisionBody = """{"approvalId":"$approvalId","correlationId":"mop.$approvalId"}"""
            val decisionBefore = post(decisionPath, decisionBody).let { it.response.status to it.body() }
            decisionBefore.first shouldBe 200
            holding(permits::labels, MasterAdminChannelFixture.springSettings().labelPermits) {
                post(labels, ids(a.mid)).response.status shouldBe 503
                post(decisionPath, decisionBody).let { it.response.status to it.body() } shouldBe decisionBefore
                get("/internal/v1/admin/merchants/${a.merchantId}").response.status shouldBe 200
            }
            holding(permits::read, MasterAdminChannelFixture.springSettings().readPermits) {
                post(decisionPath, decisionBody).response.status shouldBe 503
                post(labels, ids(a.mid)).response.status shouldBe 200
            }
        }

        Then("T-E2-5 non-exposure: no mer_/biz_ id, registration number, business name or lifecycle in the bytes, though the database has them (control)") {
            val body = post(labels, ids(a.mid, b.mid, terminated.mid)).body()
            val registration = MasterDbAccounts.root(container).use { it.scalar("SELECT registration_number FROM business WHERE business_id = ?", a.businessId) as String }
            MasterDbAccounts.root(container).use { it.scalar("SELECT merchant_id FROM merchant_account WHERE merchant_account_id = ?", a.mid) } shouldBe a.merchantId
            registration.length shouldBe 10
            listOf("mer_", "biz_", registration, "Demo Business", "TERMINATED", "ACTIVE", "institution").forEach { body shouldNotContain it }
        }

        Then("T-E2-6 reads only: repeating the same calls returns identical bodies and every MASTER table keeps its row count and checksum") {
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
            val first = listOf(ids(a.mid, changed.mid), ids(unknown), ids(terminated.mid, e.mid)).map { post(labels, it).let { r -> r.response.status to r.body() } }
            val second = listOf(ids(a.mid, changed.mid), ids(unknown), ids(terminated.mid, e.mid)).map { post(labels, it).let { r -> r.response.status to r.body() } }
            second shouldBe first
            first.map { it.first } shouldBe listOf(200, 200, 200)
            snapshot() shouldBe before
        }

        Then("T-E2-7 plan P-1: one statement — merchant_account PK range, merchant eq_ref PRIMARY, current code by the assignment PK; no full scan") {
            val mids = listOf(a, b, changed, terminated, e).map { it.mid }
            val sql = dsl.renderInlined(reader.statement(mids))
            val plan = MasterDbAccounts.root(container).use { c ->
                listOf("merchant_account", "merchant", "merchant_code_assignment").forEach { t -> c.prepareStatement("ANALYZE TABLE `$t`").use { it.execute() } }
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
                plan.single { it["table"] == "merchant_account" }.let { listOf(it["type"], it["key"]) } shouldBe listOf("range", "PRIMARY")
                plan.single { it["table"] == "merchant" }.let { listOf(it["type"], it["key"]) } shouldBe listOf("eq_ref", "PRIMARY")
                plan.single { it["table"] == "merchant_code_assignment" }["key"] shouldBe "PRIMARY"
            }
        }

        Then("logs carry the classification and the item count only — no MID, merchant name or code") {
            val lines = CapturedLogs().use { logs ->
                post(labels, ids(a.mid, b.mid)).response.status shouldBe 200
                post(labels, "{}").response.status shouldBe 400
                logs.lines()
            }
            lines.any { "operation=MerchantAccountLabels" in it && "items=2" in it } shouldBe true
            lines.forEach { line -> listOf(a.mid, b.mid, a.name, b.name, a.code, b.code).forEach { line shouldNotContain it } }
        }
    }
})

private fun JsonNode.elements(): List<JsonNode> = (0 until size()).map { get(it) }

/** [count]개 permit을 다른 스레드에서 잡은 채로 [block]을 실행한다(대기 0 permit의 busy 시험). */
private fun holding(acquire: (String, () -> Unit, () -> Unit) -> Unit, count: Int, block: () -> Unit) {
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

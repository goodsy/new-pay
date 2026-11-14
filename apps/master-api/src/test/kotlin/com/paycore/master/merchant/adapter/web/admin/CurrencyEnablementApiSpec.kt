package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.MasterDbAccounts
import com.paycore.master.MasterDbTest
import com.paycore.master.merchant.adapter.web.admin.MerchantApprovalBodies.Approval
import com.paycore.master.merchant.application.CapturedLogs
import com.paycore.master.merchant.application.EntitlementCommandFixture
import com.paycore.master.merchant.application.MasterFaultInjector
import com.paycore.master.merchant.application.MasterFaultPoint
import com.paycore.master.merchant.application.OnboardingFixture
import com.paycore.master.merchant.application.provided.MerchantOnboardingUseCase
import com.paycore.master.scalar
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.testcontainers.mysql.MySQLContainer
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MERCHANT-OPS-002 §8.4 B3-2-4(§8.3 B3-2-3, D-MOPS-009 ②) — 실제 보안 필터 체인 + MVC + MySQL(서명 채널 + 실제 Use Case, 요청마다 다른 DB 연결, latch로 시작을 맞춘다 — B3-1-4와 같은 방식).
 *
 * - 채널: `currency-enablements` 성공 200 `cur_…`(첫 실행만 `approval_executed{operation=EnableCurrency}` +1), 형식 위반 400 `REQUEST_MALFORMED`(본문·로그에 값 없음·저장 없음).
 * - 경합(3회씩): 같은 (MID, KRW)에 `AT_EXECUTION` 둘(다른 approvalId) → 1 `cur_…`·1 `CURRENCY_ENABLEMENT_OVERLAP`(merchant 잠금 직렬화 — 두 번째의 확정 시각은 첫 커밋 뒤라
 *   열린 구간과 겹친다), `AT_EXECUTION` 하나 + DATE(내일) 하나 → 1 성공·1 OVERLAP(열린 구간 `to` 없음). 둘 다 열린 기록 1·근거 EXECUTED 1/REJECTED 1.
 * - ③ review MINOR-2 순서 강제: [MasterFaultInjector] `MERCHANT_LOCK` hook으로 A가 merchant 행 잠금을 쥔 채 B가 실제 DB 잠금 대기(performance_schema.data_lock_waits)에
 *   들어갈 때까지 붙잡은 뒤 놓는다(B3-1F ActivationConcurrencySpec ⑦⑧과 같은 방식) → 항상 {A `cur_`, B OVERLAP}. 변이 (e)(merchant·merchant_account 잠금 → 잠금 없는 읽기)가
 *   이 사례에서 대기 미관측으로 죽는다.
 */
@MasterDbTest
class CurrencyEnablementApiSpec(
    context: WebApplicationContext,
    private val container: MySQLContainer,
    private val registry: MeterRegistry,
    private val onboarding: MerchantOnboardingUseCase,
    private val faults: MasterFaultInjector,
) : BehaviorSpec({

    afterEach { faults.reset() }

    val mvc: MockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity()).build()
    val json = JsonMapper.builder().build()

    fun post(path: String, body: String): Pair<Int, String> {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val r = mvc.perform(
            MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON).content(bytes)
                .header(AdminCallSignature.HEADER, MasterAdminChannelFixture.springHeader("POST", path, bytes)),
        ).andReturn().response
        return r.status to r.getContentAsString(Charsets.UTF_8)
    }

    fun outcome(body: String): String = json.readTree(body).let { n -> n.get("rejection")?.asString() ?: n.get("outcome").asString() }
    fun count(sql: String, vararg args: Any?): Long = OnboardingFixture.runtime(container).use { it.scalar(sql, *args).toString().toLong() }
    fun executedMetric() = registry.find(MerchantApprovalController.APPROVAL_EXECUTED_METRIC).tags("operation", "EnableCurrency").counter()?.count() ?: 0.0
    fun mid(): String = EntitlementCommandFixture.issuedWithActivation(onboarding).second.first.value

    /** 실제 DB 잠금 대기 TX 수(ActivationConcurrencySpec ⑦⑧과 같은 관측 — root 계정, performance_schema). */
    fun lockWaiters(): Int = MasterDbAccounts.root(container).use { c ->
        c.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(DISTINCT REQUESTING_ENGINE_TRANSACTION_ID) FROM performance_schema.data_lock_waits").use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

    /** [expected]개 이상 잠금 대기를 10초 안에 관측하면 true. 관측 실패는 예외 대신 false(시험이 `waited`로 실패를 보고 — 붙잡은 TX는 그대로 진행). */
    fun awaitLockWaiters(expected: Int): Boolean {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            if (lockWaiters() >= expected) return true
            Thread.sleep(10)
        }
        return false
    }

    /**
     * review MINOR-2 결정적 순서: [first]가 merchant 행 잠금(`MERCHANT_LOCK` 직후 — 같은 TX·commit 전)을 쥔 것을 확인한 뒤 [second]를 시작하고, [second]가 실제
     * 잠금 대기에 들어간 것을 관측한 다음에야 [first]를 놓는다. 반환: (first 결과, second 결과, 대기 관측 여부).
     */
    fun firstHoldsThenSecond(first: () -> Pair<Int, String>, second: () -> Pair<Int, String>): Triple<Pair<Int, String>, Pair<Int, String>, Boolean> {
        val holding = CountDownLatch(1)
        val held = AtomicBoolean(false)
        val waiterObserved = AtomicBoolean(false)
        faults.after(MasterFaultPoint.MERCHANT_LOCK) {
            if (held.compareAndSet(false, true)) {
                holding.countDown()
                waiterObserved.set(awaitLockWaiters(1))
            }
        }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val firstResult = pool.submit<Pair<Int, String>> { first() }
            holding.await(10, TimeUnit.SECONDS) shouldBe true
            val secondResult = pool.submit<Pair<Int, String>> { second() }
            return Triple(firstResult.get(30, TimeUnit.SECONDS), secondResult.get(30, TimeUnit.SECONDS), waiterObserved.get())
        } finally {
            faults.reset()
            pool.shutdownNow()
        }
    }

    Given("the currency-enablements endpoint (2 persons — evidence path)") {
        Then("AT_EXECUTION → 200 SUCCEEDED cur_…; approval_executed{EnableCurrency} +1 on the first execution only; the same bytes replay") {
            val m = mid()
            val body = CurrencyBodies.body(m, a = Approval())
            val before = executedMetric()
            val (status, response) = post(CurrencyBodies.CURRENCY_ENABLEMENTS, body)
            status shouldBe 200
            val reference = json.readTree(response).get("reference").asString()
            reference shouldStartWith "cur_"
            response shouldBe """{"outcome":"SUCCEEDED","reference":"$reference","replayed":false}"""
            post(CurrencyBodies.CURRENCY_ENABLEMENTS, body) shouldBe (200 to """{"outcome":"SUCCEEDED","reference":"$reference","replayed":true}""")
            executedMetric() shouldBe before + 1.0
        }

        Then("a non-KST-midnight effectiveFrom, a lower-case mode or USD → 400 REQUEST_MALFORMED with no value in the body or the log, nothing stored") {
            val m = mid()
            listOf(
                CurrencyBodies.body(m, effectiveFrom = "2026-10-09T15:00:00.001Z"),
                CurrencyBodies.body(m, effectiveFrom = "at_execution"),
                CurrencyBodies.body(m, currency = "USD"),
            ).forEach { body ->
                withClue(body) {
                    val (result, lines) = CapturedLogs().use { logs -> post(CurrencyBodies.CURRENCY_ENABLEMENTS, body) to logs.lines() }
                    result shouldBe (400 to """{"error":"REQUEST_MALFORMED"}""")
                    lines.joinToString("\n").let { log ->
                        log shouldNotContain "2026-10-09T15:00:00.001Z"
                        log shouldNotContain "at_execution"
                        log shouldNotContain m
                    }
                }
            }
            count("SELECT COUNT(*) FROM currency_enablement WHERE merchant_account_id = ?", m) shouldBe 0L
        }
    }

    Given("B3-2-4 races on real MySQL connections (3 rounds each)") {
        Then("① two AT_EXECUTION approvals of the same (MID, KRW): one cur_, one CURRENCY_ENABLEMENT_OVERLAP; one open record; evidence EXECUTED 1 / REJECTED 1") {
            repeat(3) { round ->
                val m = mid()
                val approvals = listOf(Approval(), Approval())
                val results = OnboardingFixture.concurrently(2) { i -> post(CurrencyBodies.CURRENCY_ENABLEMENTS, CurrencyBodies.body(m, a = approvals[i])) }.map { it.getOrThrow() }
                withClue("round $round") {
                    results.map { it.first } shouldBe listOf(200, 200)
                    results.map { outcome(it.second) } shouldContainExactlyInAnyOrder listOf("SUCCEEDED", "CURRENCY_ENABLEMENT_OVERLAP")
                    results.single { outcome(it.second) == "CURRENCY_ENABLEMENT_OVERLAP" }.second.contains("reference") shouldBe false
                    count("SELECT COUNT(*) FROM currency_enablement WHERE merchant_account_id = ? AND effective_to IS NULL", m) shouldBe 1L
                    count("SELECT COUNT(*) FROM currency_enablement WHERE merchant_account_id = ?", m) shouldBe 1L
                    val a = approvals.map { it.approvalId }
                    count("SELECT COUNT(*) FROM admin_approval_evidence WHERE approval_id IN (?, ?) AND outcome = 'EXECUTED'", a[0], a[1]) shouldBe 1L
                    count(
                        "SELECT COUNT(*) FROM admin_approval_evidence WHERE approval_id IN (?, ?) AND outcome = 'REJECTED' AND result_code = 'CURRENCY_ENABLEMENT_OVERLAP'", a[0], a[1],
                    ) shouldBe 1L
                }
            }
        }

        Then("② one AT_EXECUTION and one DATE (tomorrow 00:00 KST) at once: one succeeds, the other is CURRENCY_ENABLEMENT_OVERLAP (open interval, no end); one open record") {
            repeat(3) { round ->
                val m = mid()
                val bodies = listOf(CurrencyBodies.body(m, a = Approval()), CurrencyBodies.body(m, effectiveFrom = CurrencyBodies.TOMORROW_KST, a = Approval()))
                val results = OnboardingFixture.concurrently(2) { i -> post(CurrencyBodies.CURRENCY_ENABLEMENTS, bodies[i]) }.map { it.getOrThrow() }
                withClue("round $round") {
                    results.map { outcome(it.second) } shouldContainExactlyInAnyOrder listOf("SUCCEEDED", "CURRENCY_ENABLEMENT_OVERLAP")
                    count("SELECT COUNT(*) FROM currency_enablement WHERE merchant_account_id = ? AND effective_to IS NULL", m) shouldBe 1L
                    count("SELECT COUNT(*) FROM currency_enablement WHERE merchant_account_id = ?", m) shouldBe 1L
                }
            }
        }

        Then("③ review MINOR-2 forced order: A holds the merchant lock, B (another AT_EXECUTION of the same MID, KRW) is observed waiting on it, then A commits → A cur_, B CURRENCY_ENABLEMENT_OVERLAP; one open record") {
            repeat(3) { round ->
                val m = mid()
                val a = Approval()
                val b = Approval()
                val (aResult, bResult, waited) = firstHoldsThenSecond(
                    { post(CurrencyBodies.CURRENCY_ENABLEMENTS, CurrencyBodies.body(m, a = a)) },
                    { post(CurrencyBodies.CURRENCY_ENABLEMENTS, CurrencyBodies.body(m, a = b)) },
                )
                withClue("round $round") {
                    // 순서 증거: B가 A의 merchant 잠금을 실제 DB 잠금 대기로 기다렸다(관측) → B의 확정 시각·중첩 판정은 A 커밋 뒤.
                    waited shouldBe true
                    listOf(aResult.first, bResult.first) shouldBe listOf(200, 200)
                    outcome(aResult.second) shouldBe "SUCCEEDED"
                    json.readTree(aResult.second).get("reference").asString() shouldStartWith "cur_"
                    outcome(bResult.second) shouldBe "CURRENCY_ENABLEMENT_OVERLAP"
                    bResult.second.contains("reference") shouldBe false
                    count("SELECT COUNT(*) FROM currency_enablement WHERE merchant_account_id = ? AND effective_to IS NULL", m) shouldBe 1L
                    count("SELECT COUNT(*) FROM currency_enablement WHERE merchant_account_id = ?", m) shouldBe 1L
                    count("SELECT COUNT(*) FROM admin_approval_evidence WHERE approval_id = ? AND outcome = 'EXECUTED'", a.approvalId) shouldBe 1L
                    count(
                        "SELECT COUNT(*) FROM admin_approval_evidence WHERE approval_id = ? AND outcome = 'REJECTED' AND result_code = 'CURRENCY_ENABLEMENT_OVERLAP'", b.approvalId,
                    ) shouldBe 1L
                }
            }
        }
    }
})

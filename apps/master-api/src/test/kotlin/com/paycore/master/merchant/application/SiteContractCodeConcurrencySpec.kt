package com.paycore.master.merchant.application

import com.paycore.master.MasterDbAccounts
import com.paycore.master.MasterDbTest
import com.paycore.master.merchant.application.ApprovedCommandFixture.contractApproval
import com.paycore.master.merchant.application.ApprovedCommandFixture.merchantCodeChange
import com.paycore.master.merchant.application.OnboardingFixture.concurrently
import com.paycore.master.merchant.application.OnboardingFixture.host
import com.paycore.master.merchant.application.OnboardingFixture.readyMerchant
import com.paycore.master.merchant.application.OnboardingFixture.request
import com.paycore.master.merchant.application.OnboardingFixture.runtime
import com.paycore.master.merchant.application.OnboardingFixture.succeeded
import com.paycore.master.merchant.application.OnboardingFixture.t0
import com.paycore.master.merchant.application.provided.AdminCommandResult
import com.paycore.master.merchant.application.provided.MerchantOnboardingUseCase
import com.paycore.master.merchant.application.provided.RegisterContractCommand
import com.paycore.master.merchant.application.provided.RegisterMerchantCommand
import com.paycore.master.merchant.application.provided.RegisterMerchantSiteCommand
import com.paycore.master.merchant.application.provided.ScopeInput
import com.paycore.master.merchant.application.required.PersistenceFailure
import com.paycore.master.merchant.application.required.Versioned
import com.paycore.master.merchant.domain.ApprovalEvidenceFixtures
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.ChannelType
import com.paycore.master.merchant.domain.ContractCode
import com.paycore.master.merchant.domain.ContractId
import com.paycore.master.merchant.domain.DisplayName
import com.paycore.master.merchant.domain.EffectivePeriod
import com.paycore.master.merchant.domain.Merchant
import com.paycore.master.merchant.domain.MerchantCode
import com.paycore.master.merchant.domain.MerchantCodeFixtures
import com.paycore.master.merchant.domain.MerchantId
import com.paycore.master.merchant.domain.SiteCode
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.testcontainers.mysql.MySQLContainer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SITE-CONTRACT-CODE-001 SCODE-1-7·1-8·1-9(실제 MySQL 8.4 — 각자 연결·로컬 TX, Concurrency Control C-13: merchant 행 PESSIMISTIC_WRITE 아래 READ COMMITTED `MAX+1`, UNIQUE는 결함 감지 backstop).
 * - 1-7 같은 가맹점 8스레드 동시 Site 등록(서로 다른 채널) → 모두 SUCCEEDED, 순번 정확히 {1..8}, 1062·Unknown·503 0, 교착 0(2회).
 * - 1-8 같은 가맹점 계약 4 + Site 4 동시 → 종류별 1..4. 다른 가맹점은 서로 막지 않음(가맹점 A 잠금을 쥔 동안 B 등록 완료 — 대기 상한).
 *   계약 등록 ↔ 첫 MID 발급 ↔ 계약 승인 동시 → 모두 성공, 교착 0(3회).
 * - 1-9 가맹점 코드 변경 경합(두 순서 강제 — `MERCHANT_LOCK` hook으로 먼저 잡은 쪽이 잠금을 쥔 채 다른 쪽의 실제 DB 잠금 대기를 본 뒤 놓는다):
 *   변경 먼저 → 뒤 Site·계약 코드 = 새 가맹점 코드, Site 먼저 → 그 Site 코드 = 옛 코드(FK 만족)·이후 Site는 새 코드, 이전 코드 행 불변.
 */
@MasterDbTest
class SiteContractCodeConcurrencySpec(
    private val service: MerchantOnboardingUseCase,
    private val container: MySQLContainer,
    private val faults: MasterFaultInjector,
) : BehaviorSpec({

    beforeSpec { faults.reset() }
    afterAny { faults.reset() }

    fun text(sql: String, vararg args: Any?): String? = runtime(container).use { c ->
        c.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }
    fun column(sql: String, vararg args: Any?): List<String> = runtime(container).use { c ->
        c.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
    }

    /** InnoDB 교착 누계(root — `lock_deadlocks` 지표, 꺼져 있으면 켠다). */
    fun deadlocks(): Long = MasterDbAccounts.root(container).use { c ->
        c.createStatement().use { st ->
            st.execute("SET GLOBAL innodb_monitor_enable = 'lock_deadlocks'")
            st.executeQuery("SELECT `COUNT` FROM information_schema.INNODB_METRICS WHERE NAME = 'lock_deadlocks'").use { rs -> rs.next(); rs.getLong(1) }
        }
    }
    fun lockWaiters(): Int = MasterDbAccounts.root(container).use { c ->
        c.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(DISTINCT REQUESTING_ENGINE_TRANSACTION_ID) FROM performance_schema.data_lock_waits").use { rs -> rs.next(); rs.getInt(1) }
        }
    }
    fun awaitLockWaiters(): Boolean {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            if (lockWaiters() >= 1) return true
            Thread.sleep(10)
        }
        return false
    }

    fun business(): BusinessId = BusinessId(succeeded(OnboardingFixture.registeredBusiness(service, OnboardingFixture.registrationNumber(), "Code Business")))
    fun merchant(b: BusinessId): MerchantId =
        MerchantId(succeeded(service.registerMerchant(RegisterMerchantCommand(request(), b, DisplayName("Code Merchant"), MerchantCodeFixtures.unique()))))
    fun merchantCode(m: MerchantId): String = text("SELECT merchant_code FROM merchant_code_assignment WHERE merchant_id = ? ORDER BY assignment_seq DESC LIMIT 1", m.value)!!
    fun site(m: MerchantId) = service.registerMerchantSite(RegisterMerchantSiteCommand(request(), m, ChannelType.WEB, host()))
    fun contract(b: BusinessId, m: MerchantId) = service.registerContract(RegisterContractCommand(request(), b, ScopeInput(m), EffectivePeriod(t0)))
    fun label(r: Result<AdminCommandResult>): String = r.fold(
        { res -> when (res) { is AdminCommandResult.Succeeded -> "OK"; is AdminCommandResult.Rejected -> res.rejection.name; AdminCommandResult.IdempotencyKeyReused -> "KEY_REUSED" } },
        { e -> "FAILED:${e.javaClass.simpleName}" },
    )
    fun codes(results: List<Result<AdminCommandResult>>): List<String?> = results.map { (it.getOrThrow() as AdminCommandResult.Succeeded).code }
    fun uniqueConflicts(): List<String> = faults.failures.filter { (_, e) -> e is PersistenceFailure.UniqueConflict }.map { (p, e) -> "$p:${(e as PersistenceFailure.UniqueConflict).constraint}" }

    /** [first]가 merchant 행 잠금을 쥔 뒤 [second]를 시작하고, [second]의 실제 잠금 대기를 본 다음 [first]를 놓는다. (first, second, 대기 관측). */
    fun firstHoldsThenSecond(first: () -> AdminCommandResult, second: () -> AdminCommandResult): Triple<AdminCommandResult, AdminCommandResult, Boolean> {
        val holding = CountDownLatch(1)
        val held = AtomicBoolean(false)
        val observed = AtomicBoolean(false)
        faults.after(MasterFaultPoint.MERCHANT_LOCK) {
            if (held.compareAndSet(false, true)) {
                holding.countDown()
                observed.set(awaitLockWaiters())
            }
        }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val a = pool.submit<AdminCommandResult> { first() }
            holding.await(10, TimeUnit.SECONDS) shouldBe true
            val b = pool.submit<AdminCommandResult> { second() }
            return Triple(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS), observed.get())
        } finally {
            faults.reset()
            pool.shutdownNow()
        }
    }

    fun change(m: MerchantId, newCode: String) =
        service.changeMerchantCodeWithEvidence(merchantCodeChange(m, merchantCode(m), newCode, ApprovalEvidenceFixtures.distinctActors(evidenceRef = ApprovedCommandFixture.MERCHANT_REQUEST)))

    Given("SCODE-1-7 eight concurrent Site registrations on the same merchant (different channels)") {
        Then("all succeed with seq exactly {1..8}; no 1062, unknown, 503 or deadlock (2 runs)") {
            repeat(2) { run ->
                faults.reset()
                val m = merchant(business())
                val mc = merchantCode(m)
                val before = deadlocks()
                val results = concurrently(8) { site(m) }
                withClue("run $run") {
                    results.map(::label) shouldBe List(8) { "OK" }
                    codes(results).toSet() shouldBe (1..8).map { SiteCode.issue(MerchantCode(mc), it).value }.toSet()
                    column("SELECT code_seq FROM merchant_site_code WHERE merchant_id = ? ORDER BY code_seq", m.value) shouldBe (1..8).map { it.toString() }
                    uniqueConflicts().shouldBeEmpty()
                    (deadlocks() - before) shouldBe 0L
                }
            }
        }
    }

    Given("SCODE-1-8 mixed concurrency") {
        Then("4 contracts + 4 Sites on the same merchant concurrently → 1..4 per kind, no 1062 / deadlock") {
            val b = business()
            val m = merchant(b)
            val mc = merchantCode(m)
            val before = deadlocks()
            val results = concurrently(8) { i -> if (i % 2 == 0) contract(b, m) else site(m) }
            results.map(::label) shouldBe List(8) { "OK" }
            codes(results).filterNotNull().filter { it.startsWith("ctr-") }.toSet() shouldBe (1..4).map { ContractCode.issue(MerchantCode(mc), it).value }.toSet()
            codes(results).filterNotNull().filter { it.startsWith("site-") }.toSet() shouldBe (1..4).map { SiteCode.issue(MerchantCode(mc), it).value }.toSet()
            uniqueConflicts().shouldBeEmpty()
            (deadlocks() - before) shouldBe 0L
        }

        Then("another merchant is not blocked: while merchant A's row lock is held, merchant B registers a Site and a contract (bounded wait)") {
            val b = business()
            val a = merchant(b)
            val other = merchant(b)
            val holding = CountDownLatch(1)
            val release = CountDownLatch(1)
            val held = AtomicBoolean(false)
            faults.after(MasterFaultPoint.MERCHANT_LOCK) { locked ->
                @Suppress("UNCHECKED_CAST")
                val id = (locked as Versioned<Merchant>?)?.value?.id
                if (id == a && held.compareAndSet(false, true)) {
                    holding.countDown()
                    release.await(20, TimeUnit.SECONDS)
                }
            }
            val pool = Executors.newSingleThreadExecutor()
            try {
                val pending = pool.submit<AdminCommandResult> { site(a) }
                holding.await(10, TimeUnit.SECONDS) shouldBe true
                val started = System.nanoTime()
                label(Result.success(site(other))) shouldBe "OK"
                label(Result.success(contract(b, other))) shouldBe "OK"
                val waitedMs = (System.nanoTime() - started) / 1_000_000
                withClue("B waited $waitedMs ms while A held its merchant lock") { (waitedMs < 5_000) shouldBe true }
                release.countDown()
                label(Result.success(pending.get(30, TimeUnit.SECONDS))) shouldBe "OK"
            } finally {
                release.countDown()
                faults.reset()
                pool.shutdownNow()
            }
        }

        Then("contract registration ∥ first MID issuance ∥ contract approval on the same merchant → all succeed, no deadlock (3 runs)") {
            repeat(3) { run ->
                faults.reset()
                val ready = readyMerchant(service, approved = true)
                val pending = ContractId(succeeded(contract(ready.businessId, ready.merchantId)))
                val before = deadlocks()
                val results = concurrently(3) { i ->
                    when (i) {
                        0 -> contract(ready.businessId, ready.merchantId)
                        1 -> service.issueFirstDefaultMerchantAccount(ready.issueCommand())
                        else -> service.approveContractRevisionWithEvidence(contractApproval(pending))
                    }
                }
                withClue("run $run") {
                    results.map(::label) shouldBe listOf("OK", "OK", "OK")
                    (deadlocks() - before) shouldBe 0L
                    val mc = merchantCode(ready.merchantId)
                    column("SELECT contract_code FROM contract_code WHERE merchant_id = ? ORDER BY code_seq", ready.merchantId.value) shouldBe
                        (1..3).map { ContractCode.issue(MerchantCode(mc), it).value }
                }
            }
        }
    }

    Given("SCODE-1-9 merchant code change ∥ Site / contract registration (both orders forced)") {
        Then("change holds the merchant lock first → the waiting Site gets the new merchant code; the earlier Site's code row is unchanged") {
            val m = merchant(business())
            val old = merchantCode(m)
            val earlier = succeeded(site(m))
            val earlierRow = text("SELECT CONCAT_WS('|', site_code, issued_merchant_code, code_seq, DATE_FORMAT(assigned_at, '%Y-%m-%d %H:%i:%s.%f')) FROM merchant_site_code WHERE merchant_site_id = ?", earlier)
            val newCode = MerchantCodeFixtures.unique().value
            val (changed, registered, waited) = firstHoldsThenSecond({ change(m, newCode) }, { site(m) })
            listOf(label(Result.success(changed)), label(Result.success(registered)), waited) shouldBe listOf("OK", "OK", true)
            (registered as AdminCommandResult.Succeeded).code shouldBe "site-$newCode-02"
            text("SELECT issued_merchant_code FROM merchant_site_code WHERE merchant_site_id = ?", registered.reference) shouldBe newCode
            text("SELECT CONCAT_WS('|', site_code, issued_merchant_code, code_seq, DATE_FORMAT(assigned_at, '%Y-%m-%d %H:%i:%s.%f')) FROM merchant_site_code WHERE merchant_site_id = ?", earlier) shouldBe earlierRow
            earlierRow!!.startsWith("site-$old-01|$old|1|") shouldBe true
        }

        Then("the Site holds the merchant lock first → its code keeps the old merchant code (FK satisfied); the change then succeeds and later Sites use the new code") {
            val m = merchant(business())
            val old = merchantCode(m)
            val newCode = MerchantCodeFixtures.unique().value
            val (registered, changed, waited) = firstHoldsThenSecond({ site(m) }, { change(m, newCode) })
            listOf(label(Result.success(registered)), label(Result.success(changed)), waited) shouldBe listOf("OK", "OK", true)
            (registered as AdminCommandResult.Succeeded).code shouldBe "site-$old-01"
            (site(m) as AdminCommandResult.Succeeded).code shouldBe "site-$newCode-02"
            text("SELECT COUNT(*) FROM merchant_site_code k JOIN merchant_code_assignment a ON a.merchant_id = k.merchant_id AND a.merchant_code = k.issued_merchant_code WHERE k.merchant_id = ?", m.value) shouldBe "2"
        }

        Then("change holds the merchant lock first → the waiting contract registration (business → merchant lock in validScope) gets the new merchant code") {
            val b = business()
            val m = merchant(b)
            val newCode = MerchantCodeFixtures.unique().value
            val (changed, registered, waited) = firstHoldsThenSecond({ change(m, newCode) }, { contract(b, m) })
            listOf(label(Result.success(changed)), label(Result.success(registered)), waited) shouldBe listOf("OK", "OK", true)
            (registered as AdminCommandResult.Succeeded).code shouldBe "ctr-$newCode-01"
        }
    }
})

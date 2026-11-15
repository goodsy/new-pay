package com.paycore.master.merchant.application

import com.paycore.master.MasterDbAccounts
import com.paycore.master.MasterDbTest
import com.paycore.master.merchant.application.ApprovedCommandFixture.contractApproval
import com.paycore.master.merchant.application.ApprovedCommandFixture.merchantCodeChange
import com.paycore.master.merchant.application.OnboardingFixture.concurrently
import com.paycore.master.merchant.application.OnboardingFixture.readyMerchant
import com.paycore.master.merchant.application.OnboardingFixture.request
import com.paycore.master.merchant.application.OnboardingFixture.runtime
import com.paycore.master.merchant.application.OnboardingFixture.seedRequest
import com.paycore.master.merchant.application.OnboardingFixture.succeeded
import com.paycore.master.merchant.application.provided.AdminCommandResult
import com.paycore.master.merchant.application.provided.ApproveContractRevisionCommand
import com.paycore.master.merchant.application.provided.MerchantOnboardingUseCase
import com.paycore.master.merchant.application.provided.RegisterBusinessCommand
import com.paycore.master.merchant.application.provided.RegisterMerchantCommand
import com.paycore.master.merchant.domain.ApprovalEvidenceFixtures
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.DisplayName
import com.paycore.master.merchant.domain.MasterRejection
import com.paycore.master.merchant.domain.MerchantCode
import com.paycore.master.merchant.domain.MerchantCodeFixtures
import com.paycore.master.merchant.domain.MerchantId
import com.paycore.master.scalar
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.testcontainers.mysql.MySQLContainer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MERCHANT-CODE-001 §8 MC-1b-4·12(실제 MySQL 두 연결, 각 3회 — §3.7.3 경합, 리뷰 m-6).
 * ① 계약 승인(근거 경로) vs 코드 변경: [MasterFaultInjector] `MERCHANT_LOCK` hook으로 먼저 잡은 쪽이 merchant 행 잠금을 쥔 채 다른 쪽이 실제 DB 잠금 대기(performance_schema)에 들어간 것을
 *   본 뒤 놓는다(두 순서 모두 강제 재현). 승인이 먼저면 {승인 성공, 변경 `MERCHANT_CODE_LOCKED`}, 변경이 먼저면 {둘 다 성공 ∧ 이력 id 변경 < 승인}. "승인 커밋 뒤 변경 성공" 0회.
 * ② 같은 가맹점 변경 둘(다른 approvalId) → 1 성공·1 `ALREADY_CHANGED`. ③ 두 가맹점이 같은 새 코드 → 1 성공·1 `TAKEN`. ④ 등록(코드 X) vs 변경(→ X) → 1 성공·1 `TAKEN`. 진 쪽 무효과.
 * 12 순서 기록: 근거·seed 두 계약 승인 경로 모두 `merchants.lock` → `contracts.lock` → `contracts.update`.
 */
@MasterDbTest
class MerchantCodeChangeConcurrencySpec(
    private val onboarding: MerchantOnboardingUseCase,
    private val container: MySQLContainer,
    private val faults: MasterFaultInjector,
) : BehaviorSpec({

    afterEach { faults.reset() }

    fun count(sql: String, vararg args: Any?): Long = runtime(container).use { it.scalar(sql, *args).toString().toLong() }
    fun code(m: MerchantId): String = runtime(container).use {
        it.scalar("SELECT merchant_code FROM merchant_code_assignment WHERE merchant_id = ? ORDER BY assignment_seq DESC LIMIT 1", m.value).toString()
    }
    fun change(m: MerchantId, newCode: String = MerchantCodeFixtures.unique().value) =
        merchantCodeChange(m, code(m), newCode, ApprovalEvidenceFixtures.distinctActors(evidenceRef = ApprovedCommandFixture.MERCHANT_REQUEST))
    fun registered(): MerchantId {
        val business = BusinessId(succeeded(onboarding.registerBusiness(RegisterBusinessCommand(request(), OnboardingFixture.registrationNumber(), DisplayName("Demo Business")))))
        return MerchantId(succeeded(onboarding.registerMerchant(RegisterMerchantCommand(request(), business, DisplayName("Demo Merchant"), MerchantCodeFixtures.unique()))))
    }
    fun label(r: AdminCommandResult): String = when (r) {
        is AdminCommandResult.Succeeded -> "OK"
        is AdminCommandResult.Rejected -> r.rejection.name
        AdminCommandResult.IdempotencyKeyReused -> "KEY_REUSED"
    }
    fun history(m: MerchantId, type: String, aggregate: String = m.value): Long? = runtime(container).use {
        it.scalar("SELECT MIN(id) FROM master_change_history WHERE change_type = ? AND aggregate_id = ?", type, aggregate)?.toString()?.toLong()
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

    Given("MC-1b-4 ① contract approval (evidence path) vs code change on the same merchant — both orders forced") {
        Then("approval holds the merchant lock first → approval OK, change MERCHANT_CODE_LOCKED with no assignment / history / Outbox (3 runs)") {
            repeat(3) { run ->
                val ready = readyMerchant(onboarding, approved = false)
                val m = ready.merchantId
                val command = change(m)
                val (approval, changed, waited) = firstHoldsThenSecond({ onboarding.approveContractRevisionWithEvidence(contractApproval(ready.contractId)) }, { onboarding.changeMerchantCodeWithEvidence(command) })
                withClue("run $run") {
                    listOf(label(approval), label(changed), waited) shouldBe listOf("OK", "MERCHANT_CODE_LOCKED", true)
                    count("SELECT COUNT(*) FROM merchant_code_assignment WHERE merchant_id = ?", m.value) shouldBe 1
                    history(m, "MerchantCodeChanged") shouldBe null
                    count("SELECT COUNT(*) FROM outbox_event WHERE event_type = 'MerchantCodeChanged' AND aggregate_id = ?", m.value) shouldBe 0
                }
            }
        }
        Then("change holds the merchant lock first → both OK and the change history id precedes the approval history id (3 runs)") {
            repeat(3) { run ->
                val ready = readyMerchant(onboarding, approved = false)
                val m = ready.merchantId
                val command = change(m)
                val (changed, approval, waited) = firstHoldsThenSecond({ onboarding.changeMerchantCodeWithEvidence(command) }, { onboarding.approveContractRevisionWithEvidence(contractApproval(ready.contractId)) })
                withClue("run $run") {
                    listOf(label(changed), label(approval), waited) shouldBe listOf("OK", "OK", true)
                    (history(m, "MerchantCodeChanged")!! < history(m, "ContractRevisionApproved", ready.contractId.value)!!) shouldBe true
                }
            }
        }
    }

    Given("MC-1b-4 ②~④ plain races (two connections from the same start line, 3 runs each — no deadlock)") {
        Then("② the same merchant, two changes with different approvalIds → 1 OK · 1 ALREADY_CHANGED, seq 2 rows = 1") {
            repeat(3) { run ->
                val m = registered()
                val commands = listOf(change(m), change(m))
                val results = concurrently(2) { onboarding.changeMerchantCodeWithEvidence(commands[it]) }.map { label(it.getOrThrow()) }
                withClue("run $run") {
                    results.sorted() shouldBe listOf("MERCHANT_CODE_ALREADY_CHANGED", "OK")
                    count("SELECT COUNT(*) FROM merchant_code_assignment WHERE merchant_id = ? AND assignment_seq = 2", m.value) shouldBe 1
                }
            }
        }
        Then("③ two merchants changing to the same new code → 1 OK · 1 TAKEN, assignment rows with that code = 1") {
            repeat(3) { run ->
                val target = MerchantCodeFixtures.unique().value
                val commands = listOf(change(registered(), target), change(registered(), target))
                val results = concurrently(2) { onboarding.changeMerchantCodeWithEvidence(commands[it]) }.map { label(it.getOrThrow()) }
                withClue("run $run") {
                    results.sorted() shouldBe listOf("MERCHANT_CODE_TAKEN", "OK")
                    count("SELECT COUNT(*) FROM merchant_code_assignment WHERE merchant_code = ?", target) shouldBe 1
                }
            }
        }
        Then("④ registration with code X vs change to X → 1 OK · 1 TAKEN") {
            repeat(3) { run ->
                val target = MerchantCodeFixtures.unique().value
                val changeCommand = change(registered(), target)
                val business = BusinessId(succeeded(onboarding.registerBusiness(RegisterBusinessCommand(request(), OnboardingFixture.registrationNumber(), DisplayName("Demo Business")))))
                val registration = RegisterMerchantCommand(request(), business, DisplayName("Demo Merchant"), MerchantCode(target))
                val results = concurrently(2) {
                    if (it == 0) onboarding.changeMerchantCodeWithEvidence(changeCommand) else onboarding.registerMerchant(registration)
                }.map { label(it.getOrThrow()) }
                withClue("run $run") {
                    results.sorted() shouldBe listOf("MERCHANT_CODE_TAKEN", "OK")
                    count("SELECT COUNT(*) FROM merchant_code_assignment WHERE merchant_code = ?", target) shouldBe 1
                }
            }
        }
    }

    Given("MC-1b-12 lock order premise (review m-6): every path writing contract_revision_approval locks the merchant first") {
        Then("evidence and seed approval paths both record merchants.lock → contracts.lock → contracts.update") {
            fun recorded(approve: (OnboardingFixture.ReadyMerchant) -> AdminCommandResult): List<MasterFaultPoint> {
                val ready = readyMerchant(onboarding, approved = false)
                val order = CopyOnWriteArrayList<MasterFaultPoint>()
                listOf(MasterFaultPoint.MERCHANT_LOCK, MasterFaultPoint.CONTRACT_LOCK, MasterFaultPoint.CONTRACT_UPDATE).forEach { p -> faults.after(p) { order += p } }
                try {
                    succeeded(approve(ready))
                } finally {
                    faults.reset()
                }
                return order.toList()
            }
            val expected = listOf(MasterFaultPoint.MERCHANT_LOCK, MasterFaultPoint.CONTRACT_LOCK, MasterFaultPoint.CONTRACT_UPDATE)
            recorded { onboarding.approveContractRevisionWithEvidence(contractApproval(it.contractId)) } shouldBe expected
            recorded { onboarding.approveContractRevision(ApproveContractRevisionCommand(seedRequest(), it.contractId, 1)) } shouldBe expected
        }
    }
})

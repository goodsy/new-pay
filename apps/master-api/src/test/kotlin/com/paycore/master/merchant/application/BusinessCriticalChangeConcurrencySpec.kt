package com.paycore.master.merchant.application

import com.paycore.master.MasterDbAccounts
import com.paycore.master.MasterDbTest
import com.paycore.master.merchant.application.BusinessCriticalChangeCommandFixture.apply
import com.paycore.master.merchant.application.BusinessCriticalChangeCommandFixture.propose
import com.paycore.master.merchant.application.OnboardingFixture.concurrently
import com.paycore.master.merchant.application.OnboardingFixture.request
import com.paycore.master.merchant.application.OnboardingFixture.runtime
import com.paycore.master.merchant.application.OnboardingFixture.succeeded
import com.paycore.master.merchant.application.provided.AdminCommandResult
import com.paycore.master.merchant.application.provided.BusinessCriticalChangeUseCase
import com.paycore.master.merchant.application.provided.BusinessProfileCommandUseCase
import com.paycore.master.merchant.application.provided.CompleteContractIntakeCommand
import com.paycore.master.merchant.application.provided.ContractIntakeCommandUseCase
import com.paycore.master.merchant.application.provided.CreateBusinessCommand
import com.paycore.master.merchant.application.provided.MoveContractIntakeCommand
import com.paycore.master.merchant.application.provided.OpenContractIntakeCommand
import com.paycore.master.merchant.application.provided.UpdateBusinessCommand
import com.paycore.master.merchant.domain.BusinessContractTerms
import com.paycore.master.merchant.domain.BusinessFixtures
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.BusinessProfile
import com.paycore.master.merchant.domain.BusinessRegistrationStatus
import com.paycore.master.merchant.domain.BusinessType
import com.paycore.master.merchant.domain.ContractDocumentRef
import com.paycore.master.merchant.domain.ContractIntakeChannel
import com.paycore.master.merchant.domain.ContractIntakeItems
import com.paycore.master.merchant.domain.ContractIntakeKind
import com.paycore.master.merchant.domain.ContractIntakeStatus
import com.paycore.master.merchant.domain.CorporateRegistrationNumber
import com.paycore.master.merchant.domain.DesiredService
import com.paycore.master.merchant.domain.DisplayName
import com.paycore.master.merchant.domain.PhoneNumber
import com.paycore.master.merchant.domain.SeoulMidnight
import com.paycore.master.merchant.domain.ServiceKind
import com.paycore.master.scalar
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.testcontainers.mysql.MySQLContainer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * BIZ-CRIT-APPROVAL-001 S2 동시성(§6·§7.2 BusinessCriticalChangeConcurrencySpec — 실제 MySQL 다중 연결):
 * ① 같은 사업자에 제안 vs 1인 일반 수정 동시 → 둘 다 성공(교착 없음 — 같은 잠금 순서), 그 제안의 실행은 `BUSINESS_VERSION_CONFLICT`.
 * ② 정정 실행 vs 사업자 계약 생성(계약 완료) — 사업자 행 잠금 직렬화: 정정이 먼저 잠그면 정정 성공 + 계약은 그 뒤에 생성, 계약이 먼저 잠그면 정정은
 *    `BUSINESS_SUBJECT_CHANGE_NOT_ALLOWED`(잠금 뒤 이력 재판정) + 법인등록번호 불변. 순서는 잠금 직후 hook으로 강제하고 두 번째 쪽이 실제 DB 잠금 대기에 들어간 것을 관찰한다.
 */
@MasterDbTest
class BusinessCriticalChangeConcurrencySpec(
    private val changes: BusinessCriticalChangeUseCase,
    private val businesses: BusinessProfileCommandUseCase,
    private val intakes: ContractIntakeCommandUseCase,
    private val container: MySQLContainer,
    private val faults: MasterFaultInjector,
    private val clock: MutableMasterClock,
) : BehaviorSpec({

    afterEach { faults.reset(); clock.reset() }

    val corp = BusinessFixtures.corporationProfile()
    fun text(sql: String, vararg args: Any?): String? = runtime(container).use { it.scalar(sql, *args)?.toString() }
    fun created(): String = succeeded(
        businesses.create(CreateBusinessCommand(request(), BusinessRegistrationStatus.REGISTERED, BusinessType.CORPORATION, BusinessFixtures.registrationNumber(), DisplayName("법인 상호"), corp)),
    )
    fun label(r: Result<AdminCommandResult?>): String = r.fold(
        onSuccess = {
            when (it) {
                is AdminCommandResult.Succeeded -> "OK"
                is AdminCommandResult.Rejected -> it.rejection.name
                AdminCommandResult.IdempotencyKeyReused -> "KEY_REUSED"
                null -> "-"
            }
        },
        onFailure = { "FAILURE:" + it::class.simpleName },
    )
    /** NIT-2: [table] 행 잠금을 기다리는 TX 수(data_lock_waits ⨝ data_locks — 기다리는 쪽 잠금의 OBJECT_NAME). 다른 표 잠금 대기는 세지 않는다. */
    fun lockWaiters(table: String): Int = MasterDbAccounts.root(container).use { c ->
        c.prepareStatement(
            "SELECT COUNT(DISTINCT w.REQUESTING_ENGINE_TRANSACTION_ID) FROM performance_schema.data_lock_waits w " +
                "JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID WHERE l.OBJECT_NAME = ?",
        ).use { ps -> ps.setString(1, table); ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) } }
    }
    fun awaitLockWaiter(table: String = "business"): Boolean {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            if (lockWaiters(table) >= 1) return true
            Thread.sleep(10)
        }
        return false
    }
    fun count(sql: String, vararg args: Any?): Long = text(sql, *args)!!.toLong()
    fun corpNumber(p: BusinessProfile) = p.copy(registration = p.registration.copy(corporateRegistrationNumber = CorporateRegistrationNumber("1101110000002")))
    fun digestOf(pid: String) = text("SELECT content_digest FROM business_change_proposal WHERE proposal_id = ?", pid)!!

    /** 접수 → 계약서 접수까지(계약 완료 직전). */
    fun readyToComplete(id: String) {
        val today = clock.now().atZone(SeoulMidnight.ZONE).toLocalDate()
        succeeded(intakes.open(OpenContractIntakeCommand(request(), BusinessId(id), ContractIntakeKind.NEW,
            ContractIntakeItems(today, ContractIntakeChannel.PHONE, OnboardingFixture.operator, setOf(DesiredService.CARD_AUTH)), null)))
        succeeded(intakes.move(MoveContractIntakeCommand(request(), BusinessId(id), 1, 0, ContractIntakeStatus.DOCUMENT_RECEIVED, null)))
    }
    fun completeContract(id: String): AdminCommandResult {
        val today = clock.now().atZone(SeoulMidnight.ZONE).toLocalDate()
        return intakes.complete(CompleteContractIntakeCommand(request(), BusinessId(id), 1, 1, ContractDocumentRef("TEST-CONTRACT-0001"),
            BusinessContractTerms(today, null, setOf(ServiceKind.CARD_PG_PAYMENT)), null))
    }

    Given("① a proposal and a 1-person general update on the same business at once") {
        Then("the proposal locks first and the update waits on the business row: both succeed (no deadlock, the proposal changes no version); the proposal's later execution is BUSINESS_VERSION_CONFLICT") {
            repeat(3) { round ->
                val id = created()
                val locked = CountDownLatch(1)
                val release = CountDownLatch(1)
                faults.after(MasterFaultPoint.BUSINESS_LOCK_WITH_PROFILE) {
                    if (locked.count > 0) { locked.countDown(); release.await(20, TimeUnit.SECONDS) }
                }
                val results = concurrently(3) { i ->
                    when (i) {
                        0 -> changes.propose(propose(id, 0, "새 법인 상호", corp))
                        1 -> {
                            locked.await(20, TimeUnit.SECONDS)
                            businesses.update(UpdateBusinessCommand(request(), BusinessId(id), 0, null, BusinessType.CORPORATION, DisplayName("법인 상호"),
                                corp.copy(registration = corp.registration.copy(phoneNumber = PhoneNumber("0255554444")))))
                        }
                        else -> { locked.await(20, TimeUnit.SECONDS); check(awaitLockWaiter()) { "the update never waited on the business row lock" }; release.countDown(); null }
                    }
                }
                faults.reset()
                results[2].getOrThrow()
                withClue("round=$round ${results.take(2).map { label(it) }}") { results.take(2).map { label(it) } shouldBe listOf("OK", "OK") }
                val pid = (results[0].getOrThrow() as AdminCommandResult.Succeeded).reference
                label(Result.success(changes.applyWithEvidence(apply(id, 0, pid, digestOf(pid))))) shouldBe "BUSINESS_VERSION_CONFLICT"
            }
        }

        Then("control: the update locks first → the proposal sees the new version → stored BUSINESS_VERSION_CONFLICT (a proposal never pins a stale base)") {
            val id = created()
            succeeded(businesses.update(UpdateBusinessCommand(request(), BusinessId(id), 0, null, BusinessType.CORPORATION, DisplayName("법인 상호"),
                corp.copy(registration = corp.registration.copy(phoneNumber = PhoneNumber("0255554444"))))))
            label(Result.success(changes.propose(propose(id, 0, "새 법인 상호", corp)))) shouldBe "BUSINESS_VERSION_CONFLICT"
        }
    }

    Given("② a corporate-number correction vs a business contract completion (business row lock serialises them)") {
        Then("correction locks first → correction OK, the contract completes after it; the corporate number is the corrected value") {
            val id = created()
            val pid = succeeded(changes.propose(propose(id, 0, "법인 상호", corpNumber(corp))))
            readyToComplete(id)
            val locked = CountDownLatch(1)
            val release = CountDownLatch(1)
            faults.after(MasterFaultPoint.BUSINESS_LOCK_WITH_PROFILE) {
                if (locked.count > 0) { locked.countDown(); release.await(20, TimeUnit.SECONDS) }
            }
            val all = concurrently(3) { i ->
                when (i) {
                    0 -> changes.applyWithEvidence(apply(id, 0, pid, digestOf(pid)))
                    1 -> { locked.await(20, TimeUnit.SECONDS); completeContract(id) }
                    else -> { locked.await(20, TimeUnit.SECONDS); check(awaitLockWaiter()) { "the contract completion never waited on the business row lock" }; release.countDown(); null }
                }
            }
            all[2].getOrThrow()
            all.take(2).map { label(it) } shouldBe listOf("OK", "OK")
            text("SELECT corporate_registration_number FROM business_registration WHERE business_id = ?", id) shouldBe "1101110000002"
            text("SELECT COUNT(*) FROM business_contract WHERE business_id = ?", id) shouldBe "1"
        }

        Then("contract locks first → contract OK, the correction re-judges history after the lock → BUSINESS_SUBJECT_CHANGE_NOT_ALLOWED; corporate number unchanged") {
            val id = created()
            val pid = succeeded(changes.propose(propose(id, 0, "법인 상호", corpNumber(corp))))
            readyToComplete(id)
            val locked = CountDownLatch(1)
            val release = CountDownLatch(1)
            faults.after(MasterFaultPoint.BUSINESS_LOCK) {
                if (locked.count > 0) { locked.countDown(); release.await(20, TimeUnit.SECONDS) }
            }
            val all = concurrently(3) { i ->
                when (i) {
                    0 -> completeContract(id)
                    1 -> { locked.await(20, TimeUnit.SECONDS); changes.applyWithEvidence(apply(id, 0, pid, digestOf(pid))) }
                    else -> { locked.await(20, TimeUnit.SECONDS); check(awaitLockWaiter()) { "the correction never waited on the business row lock" }; release.countDown(); null }
                }
            }
            all[2].getOrThrow()
            all.take(2).map { label(it) } shouldBe listOf("OK", "BUSINESS_SUBJECT_CHANGE_NOT_ALLOWED")
            text("SELECT corporate_registration_number FROM business_registration WHERE business_id = ?", id) shouldBe "1101110000001"
        }
    }
    Given("MAJOR-1 (a) approved execution vs a 1-person general update with the same base version (real two connections)") {
        listOf("execution", "update").forEach { first ->
            Then("$first locks the business first → only it is applied; the other is BUSINESS_VERSION_CONFLICT; version +1 once; one history and one Outbox row") {
                val id = created()
                val pid = succeeded(changes.propose(propose(id, 0, "새 법인 상호", corp)))
                val locked = CountDownLatch(1)
                val release = CountDownLatch(1)
                faults.after(MasterFaultPoint.BUSINESS_LOCK_WITH_PROFILE) {
                    if (locked.count > 0) { locked.countDown(); release.await(20, TimeUnit.SECONDS) }
                }
                fun execute() = changes.applyWithEvidence(apply(id, 0, pid, digestOf(pid)))
                fun update() = businesses.update(UpdateBusinessCommand(request(), BusinessId(id), 0, null, BusinessType.CORPORATION, DisplayName("법인 상호"),
                    corp.copy(registration = corp.registration.copy(phoneNumber = PhoneNumber("0255554444")))))
                val all = concurrently(3) { i ->
                    when (i) {
                        0 -> if (first == "execution") execute() else update()
                        1 -> { locked.await(20, TimeUnit.SECONDS); if (first == "execution") update() else execute() }
                        else -> { locked.await(20, TimeUnit.SECONDS); check(awaitLockWaiter()) { "the second command never waited on the business row lock" }; release.countDown(); null }
                    }
                }
                faults.reset()
                all[2].getOrThrow()
                all.take(2).map { label(it) } shouldBe listOf("OK", "BUSINESS_VERSION_CONFLICT")
                listOf(
                    count("SELECT version FROM business WHERE business_id = ?", id),
                    count("SELECT COUNT(*) FROM master_change_history WHERE aggregate_id = ? AND change_type = 'BusinessProfileUpdated'", id),
                    count("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'BusinessProfileUpdated'", id),
                ) shouldBe listOf(1L, 1L, 1L)
                text("SELECT display_name FROM business WHERE business_id = ?", id) shouldBe if (first == "execution") "새 법인 상호" else "법인 상호"
            }
        }
    }

    Given("MAJOR-1 (b) the same approvalId and the same bytes resent concurrently") {
        Then("executed once: the other replays (or is RequestInProgress); one evidence row; version +1 once") {
            val id = created()
            val pid = succeeded(changes.propose(propose(id, 0, "새 법인 상호", corp)))
            val command = apply(id, 0, pid, digestOf(pid))
            val locked = CountDownLatch(1)
            val release = CountDownLatch(1)
            faults.after(MasterFaultPoint.BUSINESS_LOCK_WITH_PROFILE) {
                if (locked.count > 0) { locked.countDown(); release.await(20, TimeUnit.SECONDS) }
            }
            val all = concurrently(3) { i ->
                when (i) {
                    0 -> changes.applyWithEvidence(command)
                    1 -> { locked.await(20, TimeUnit.SECONDS); changes.applyWithEvidence(command) }
                    else -> { locked.await(20, TimeUnit.SECONDS); check(awaitLockWaiter("admin_request")) { "the resend never waited on the idempotency record" }; release.countDown(); null }
                }
            }
            faults.reset()
            all[2].getOrThrow()
            label(all[0]) shouldBe "OK"
            val second = all[1]
            val secondOk = second.getOrNull() == AdminCommandResult.Succeeded(id, replayed = true) ||
                second.exceptionOrNull() is com.paycore.master.merchant.application.provided.AdminCommandFailure.RequestInProgress
            withClue(label(second)) { secondOk shouldBe true }
            listOf(
                count("SELECT COUNT(*) FROM admin_approval_evidence WHERE approval_id = ?", command.evidence.approvalId),
                count("SELECT version FROM business WHERE business_id = ?", id),
                count("SELECT COUNT(*) FROM master_change_history WHERE aggregate_id = ? AND change_type = 'BusinessProfileUpdated'", id),
            ) shouldBe listOf(1L, 1L, 1L)
        }
    }
})

package com.paycore.master.merchant.application

import com.paycore.master.merchant.application.OnboardingFixture
import com.paycore.master.MasterDbAccounts
import com.paycore.master.MasterDbTest
import com.paycore.master.merchant.application.OnboardingFixture.concurrently
import com.paycore.master.merchant.application.OnboardingFixture.readyMerchant
import com.paycore.master.merchant.application.OnboardingFixture.request
import com.paycore.master.merchant.application.OnboardingFixture.rowsOf
import com.paycore.master.merchant.application.OnboardingFixture.succeeded
import com.paycore.master.merchant.application.provided.AdminCommandFailure
import com.paycore.master.merchant.application.provided.AdminCommandResult
import com.paycore.master.merchant.application.provided.MerchantOnboardingUseCase
import com.paycore.master.merchant.application.provided.RegisterMerchantSiteCommand
import com.paycore.master.merchant.application.required.AdminReservationOutcome
import com.paycore.master.merchant.application.required.PersistenceFailure
import com.paycore.master.merchant.application.required.StoreConstraint
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.ChannelType
import io.kotest.matchers.shouldNotBe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import com.paycore.master.merchant.domain.MasterRejection
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.testcontainers.mysql.MySQLContainer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 최초 Default 발급의 원자성·경합을 실제 MySQL 8.4.11 **다중 연결**로 확인한다(Mock 아님, runtime 계정 권한).
 * - 원자성(구조 경계 §1): MID·Default·PRIMARY·이력·Outbox·요청 결과·entitlement revision은 한 MASTER 로컬 TX. 각 저장 지점이 SQL을
 *   flush한 **뒤** 실패를 주입해도 잔존 MID/부분 지정이 남지 않는다. commit 직전(beforeCommit) 실패도 같다.
 * - 경합(초기 "다른 키의 동시 최초 발급은 하나만 성공"): 다른 키 8개가 동시에 같은 Merchant의 최초 발급 → 정확히 하나 성공, 나머지는 merchant
 *   행(business → merchant 순서) 잠금 대기 후 DEFAULT_ALREADY_ISSUED(근거 = 성공한 MID). 같은 키 8개 동시 → 하나만 실행, 나머지는 같은 결과 재생.
 * 경합이 실제로 일어났다는 관찰 증거로 InnoDB 잠금 대기 트랜잭션 수(performance_schema.data_lock_waits)를 확인한 뒤 먼저 들어간 TX를 놓는다.
 */
@MasterDbTest
class FirstDefaultIssuanceAtomicitySpec(
    private val service: MerchantOnboardingUseCase,
    private val faults: MasterFaultInjector,
    private val ids: ScriptedIdGenerator,
    private val clock: MutableMasterClock,
    private val container: MySQLContainer,
) : BehaviorSpec({

    afterEach {
        faults.reset()
        ids.reset()
        clock.reset()
    }

    val n = 8

    class Injected(point: Any) : RuntimeException("injected failure after $point")

    fun lockWaiters(): Int = MasterDbAccounts.root(container).use { c ->
        c.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(DISTINCT REQUESTING_ENGINE_TRANSACTION_ID) FROM performance_schema.data_lock_waits").use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

    fun awaitLockWaiters(expected: Int) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            if (lockWaiters() >= expected) return
            Thread.sleep(10)
        }
        error("expected $expected lock-waiting transactions, observed ${lockWaiters()}")
    }

    /** [point]를 처음 통과한 TX를 나머지 [waiters]개 TX가 실제 DB 잠금 대기에 들어갈 때까지 붙잡는다. */
    fun holdFirstUntilWaiters(point: MasterFaultPoint, waiters: Int, isWinner: (Any?) -> Boolean = { true }) {
        val held = AtomicBoolean(false)
        faults.after(point) { result -> if (isWinner(result) && held.compareAndSet(false, true)) awaitLockWaiters(waiters) }
    }

    Given("a failure injected after each storage step of the first Default issuance") {
        val points = listOf(
            MasterFaultPoint.ADMIN_RESERVE, MasterFaultPoint.FIRST_DEFAULT_INSERT, MasterFaultPoint.MERCHANT_UPDATE,
            MasterFaultPoint.CHANGE_LOG_APPEND, MasterFaultPoint.OUTBOX_APPEND, MasterFaultPoint.ADMIN_COMPLETE,
        )
        points.forEach { point ->
            Then("after $point: nothing of the issuance remains, and the same key then succeeds normally") {
                val ready = readyMerchant(service)
                val before = rowsOf(container, ready.merchantId)
                val command = ready.issueCommand()
                faults.after(point) { throw Injected(point) }

                shouldThrow<Injected> { service.issueFirstDefaultMerchantAccount(command) }
                rowsOf(container, ready.merchantId) shouldBe before
                OnboardingFixture.runtime(container).use { c ->
                    c.prepareStatement("SELECT COUNT(*) FROM admin_request WHERE idempotency_key = ?").use { ps ->
                        ps.setString(1, command.request.idempotencyKey.value)
                        ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
                    }
                } shouldBe 0L

                faults.reset()
                service.issueFirstDefaultMerchantAccount(command).shouldBeInstanceOf<AdminCommandResult.Succeeded>().replayed shouldBe false
                rowsOf(container, ready.merchantId) shouldBe before.copy(
                    accounts = 1, defaults = 1, assignments = 1, outbox = 3, history = 3, entitlement = before.entitlement + 1,
                )
            }
        }

        Then("a failure right before commit (beforeCommit) is reported as OutcomeUnknown (retry with the same key) and leaves nothing") {
            val ready = readyMerchant(service)
            val before = rowsOf(container, ready.merchantId)
            faults.after(MasterFaultPoint.ADMIN_COMPLETE) {
                TransactionSynchronizationManager.registerSynchronization(
                    object : TransactionSynchronization {
                        override fun beforeCommit(readOnly: Boolean) = throw Injected("beforeCommit")
                    },
                )
            }
            val command = ready.issueCommand()
            val failure = CapturedLogs().use { logs ->
                shouldThrow<AdminCommandFailure.OutcomeUnknown> { service.issueFirstDefaultMerchantAccount(command) }.also {
                    // 운영 로그(review m-8): 연산·상관 ID만, 민감값 없음.
                    logs.lines().filter { it.contains("outcome unknown") } shouldBe listOf(
                        "com.paycore.master.merchant.application.AdminCommandExecutor admin command outcome unknown " +
                            "operation=IssueFirstDefaultMerchantAccount correlationId=${command.request.correlationId} failure=commit",
                    )
                }
            }
            generateSequence<Throwable>(failure) { it.cause }.any { it is Injected } shouldBe true
            rowsOf(container, ready.merchantId) shouldBe before
            faults.reset()
            service.issueFirstDefaultMerchantAccount(command).shouldBeInstanceOf<AdminCommandResult.Succeeded>().replayed shouldBe false
        }
    }

    Given("B2-3 §8.2 B2-3-2 (review M-1): the approved first Default issuance with a failure injected after the evidence row and at the request result") {
        fun evidenceRows(approvalId: String): Long = OnboardingFixture.runtime(container).use { c ->
            c.prepareStatement("SELECT COUNT(*) FROM admin_approval_evidence WHERE approval_id = ?").use { ps ->
                ps.setString(1, approvalId)
                ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
            }
        }
        fun requestRows(key: String): Long = OnboardingFixture.runtime(container).use { c ->
            c.prepareStatement("SELECT COUNT(*) FROM admin_request WHERE idempotency_key = ?").use { ps ->
                ps.setString(1, key)
                ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
            }
        }
        listOf(MasterFaultPoint.APPROVAL_EVIDENCE_RECORD, MasterFaultPoint.ADMIN_COMPLETE).forEach { point ->
            Then("after $point: MID·Default·PRIMARY·entitlement revision·Snapshot·history·Outbox unchanged, evidence 0, admin_request 0; the retry executes once with one EXECUTED evidence row") {
                val ready = readyMerchant(service)
                val before = rowsOf(container, ready.merchantId)
                val command = ApprovedCommandFixture.firstDefault(ready)
                faults.after(point) { throw Injected(point) }

                shouldThrow<Injected> { service.issueFirstDefaultMerchantAccountWithEvidence(command) }
                rowsOf(container, ready.merchantId) shouldBe before
                listOf(evidenceRows(command.evidence.approvalId), requestRows(command.request.idempotencyKey.value)) shouldBe listOf(0L, 0L)

                faults.reset()
                service.issueFirstDefaultMerchantAccountWithEvidence(command).shouldBeInstanceOf<AdminCommandResult.Succeeded>().replayed shouldBe false
                service.issueFirstDefaultMerchantAccountWithEvidence(command).shouldBeInstanceOf<AdminCommandResult.Succeeded>().replayed shouldBe true
                rowsOf(container, ready.merchantId) shouldBe before.copy(
                    accounts = 1, defaults = 1, assignments = 1, outbox = 3, history = 3, entitlement = before.entitlement + 1,
                )
                OnboardingFixture.runtime(container).use { ApprovedCommandFixture.evidenceRow(it, command.evidence.approvalId)!!.outcome } shouldBe "EXECUTED"
                listOf(evidenceRows(command.evidence.approvalId), requestRows(command.request.idempotencyKey.value)) shouldBe listOf(1L, 1L)
            }
        }
    }

    Given("$n different keys issue the first Default of the same Merchant at once") {
        Then("exactly one succeeds; the others wait on the business/merchant row locks and are rejected with the winner as reference") {
            val ready = readyMerchant(service)
            val before = rowsOf(container, ready.merchantId)
            holdFirstUntilWaiters(MasterFaultPoint.FIRST_DEFAULT_INSERT, n - 1)

            val results = concurrently(n) { i ->
                service.issueFirstDefaultMerchantAccount(ready.issueCommand(site = if (i % 2 == 0) ready.siteId else ready.secondSiteId))
            }.map { it.getOrThrow() }

            val winners = results.filterIsInstance<AdminCommandResult.Succeeded>()
            winners shouldHaveSize 1
            results.filterIsInstance<AdminCommandResult.Rejected>().map { it.rejection to it.reference }.toSet() shouldBe
                setOf(MasterRejection.DEFAULT_ALREADY_ISSUED to winners.single().reference)
            rowsOf(container, ready.merchantId) shouldBe before.copy(
                accounts = 1, defaults = 1, assignments = 1, outbox = 3, history = 3, entitlement = before.entitlement + 1,
            )
        }
    }

    Given("the same key with the same content is sent by $n connections at once") {
        Then("one executes; the others wait on the admin request row and replay the same result") {
            val ready = readyMerchant(service)
            val command = ready.issueCommand()
            holdFirstUntilWaiters(MasterFaultPoint.ADMIN_RESERVE, n - 1) { it == AdminReservationOutcome.Reserved }

            val results = concurrently(n) { service.issueFirstDefaultMerchantAccount(command) }
                .map { it.getOrThrow().shouldBeInstanceOf<AdminCommandResult.Succeeded>() }

            results.count { !it.replayed } shouldBe 1
            results.map { it.reference }.toSet() shouldHaveSize 1
            rowsOf(container, ready.merchantId).accounts shouldBe 1
        }
    }

    Given("a command that waits on the merchant lock while the server clock advances (review n-1)") {
        val t0 = OnboardingFixture.t0
        val later = t0.plusSeconds(2)

        /** 첫 명령이 merchant 잠금을 쥔 채, 두 번째 명령이 잠금 대기에 들어가면 시계를 [later]로 옮기고 놓는다. */
        fun <T> whileMerchantHeld(holder: () -> Any?, waiter: () -> T): T {
            val holding = CountDownLatch(1)
            val held = AtomicBoolean(false)
            faults.after(MasterFaultPoint.MERCHANT_LOCK) {
                if (held.compareAndSet(false, true)) {
                    holding.countDown()
                    awaitLockWaiters(1)
                    clock.set(later)
                }
            }
            val pool = Executors.newFixedThreadPool(2)
            try {
                val first = pool.submit<Any?> { holder() }
                holding.await()
                val second = pool.submit<T> { waiter() }
                first.get(30, TimeUnit.SECONDS)
                return second.get(30, TimeUnit.SECONDS)
            } finally {
                pool.shutdownNow()
            }
        }

        Then("approval decides with the post-lock instant: a revision that ended while the command waited is rejected (D-MD-014)") {
            val ready = readyMerchant(service)
            succeeded(
                service.registerContractRevision(
                    com.paycore.master.merchant.application.provided.RegisterContractRevisionCommand(
                        request(), ready.contractId, com.paycore.master.merchant.application.provided.ScopeInput(ready.merchantId),
                        com.paycore.master.merchant.domain.EffectivePeriod(t0, t0.plusSeconds(1)),
                    ),
                ),
            )
            val result = whileMerchantHeld(
                holder = { service.registerMerchantSite(RegisterMerchantSiteCommand(request(), ready.merchantId, ChannelType.WEB, OnboardingFixture.host())) },
                waiter = {
                    service.approveContractRevision(
                        com.paycore.master.merchant.application.provided.ApproveContractRevisionCommand(OnboardingFixture.seedRequest(), ready.contractId, 2), // D-MOPS-008 ②: seed 경로 행위자
                    )
                },
            )
            result shouldBe AdminCommandResult.Rejected(MasterRejection.REVISION_ENDED, null, replayed = false)
        }

        Then("the first Default issuance stores the post-lock instant as issuance time and PRIMARY effective_from (D-MD-005)") {
            val ready = readyMerchant(service)
            val mid = whileMerchantHeld(
                holder = { service.registerMerchantSite(RegisterMerchantSiteCommand(request(), ready.merchantId, ChannelType.WEB, OnboardingFixture.host())) },
                waiter = { succeeded(service.issueFirstDefaultMerchantAccount(ready.issueCommand())) },
            )
            OnboardingFixture.runtime(container).use { c ->
                c.prepareStatement(
                    "SELECT CONCAT(CAST(a.issued_at AS CHAR), '/', CAST(s.effective_from AS CHAR), '/', CAST(s.created_at AS CHAR)) " +
                        "FROM merchant_account a JOIN merchant_account_site_assignment s ON s.merchant_account_id = a.merchant_account_id " +
                        "WHERE a.merchant_account_id = ?",
                ).use { ps ->
                    ps.setString(1, mid)
                    ps.executeQuery().use { rs -> rs.next(); rs.getString(1) }
                }
            } shouldBe "2026-10-09 02:00:02.000/2026-10-09 02:00:02.000/2026-10-09 02:00:02.000"
        }
    }

    Given("a deterministic UNIQUE race on the business registration number (review M-2, M-3)") {
        Then("the second insert waits on the first one's index entry, gets 1062, retries and converges to the duplicate rejection; no log line contains the number") {
            val number = OnboardingFixture.registrationNumber()
            val holding = CountDownLatch(1)
            val held = AtomicBoolean(false)
            // 기대값 변경(BUSINESS-PROFILE-001 BIZ-4 C-27): 옛 Root 전용 insert 경로 제거 — 사업자 생성은 profile 포함 insert(fault point BUSINESS_PROFILE_INSERT). 같은 경합·같은 단언.
            faults.after(MasterFaultPoint.BUSINESS_PROFILE_INSERT) {
                if (held.compareAndSet(false, true)) {
                    holding.countDown()
                    awaitLockWaiters(1)
                }
            }
            val pool = Executors.newFixedThreadPool(2)
            try {
                CapturedLogs().use { logs ->
                    val first = pool.submit<AdminCommandResult> { OnboardingFixture.registeredBusiness(service, number, "First") }
                    holding.await()
                    val second = pool.submit<AdminCommandResult> { OnboardingFixture.registeredBusiness(service, number, "Second") }
                    val created = succeeded(first.get(30, TimeUnit.SECONDS))
                    second.get(30, TimeUnit.SECONDS) shouldBe
                        AdminCommandResult.Rejected(MasterRejection.BUSINESS_REGISTRATION_NUMBER_DUPLICATE, created, replayed = false)

                    // 경합이 실제로 일어났다: 두 번째 TX의 INSERT가 1062 → UniqueConflict(BUSINESS_REGISTRATION_NUMBER)로 분류되고 재판정됐다.
                    faults.failures.map { (point, e) -> point to (e as? PersistenceFailure.UniqueConflict)?.constraint } shouldBe
                        listOf(MasterFaultPoint.BUSINESS_PROFILE_INSERT to StoreConstraint.BUSINESS_REGISTRATION_NUMBER)
                    logs.lines().filter { it.contains(number.value) } shouldBe emptyList()
                }
            } finally {
                pool.shutdownNow()
            }
        }
    }

    Given("a deterministic UNIQUE race on an active WEB channel across two Merchants (review M-3, D-MD-008)") {
        Then("the second Merchant's insert gets 1062, retries and is rejected with the first Site as reference; the host is never logged") {
            val a = readyMerchant(service)
            val b = readyMerchant(service)
            val host = OnboardingFixture.host()
            val holding = CountDownLatch(1)
            val held = AtomicBoolean(false)
            faults.after(MasterFaultPoint.SITE_INSERT) {
                if (held.compareAndSet(false, true)) {
                    holding.countDown()
                    awaitLockWaiters(1)
                }
            }
            val pool = Executors.newFixedThreadPool(2)
            try {
                CapturedLogs().use { logs ->
                    val first = pool.submit<AdminCommandResult> {
                        service.registerMerchantSite(RegisterMerchantSiteCommand(request(), a.merchantId, ChannelType.WEB, host))
                    }
                    holding.await()
                    val second = pool.submit<AdminCommandResult> {
                        service.registerMerchantSite(RegisterMerchantSiteCommand(request(), b.merchantId, ChannelType.WEB, host))
                    }
                    val site = succeeded(first.get(30, TimeUnit.SECONDS))
                    second.get(30, TimeUnit.SECONDS) shouldBe
                        AdminCommandResult.Rejected(MasterRejection.SITE_CHANNEL_DUPLICATE, site, replayed = false)
                    faults.failures.map { (point, e) -> point to (e as? PersistenceFailure.UniqueConflict)?.constraint } shouldBe
                        listOf(MasterFaultPoint.SITE_INSERT to StoreConstraint.SITE_ACTIVE_CHANNEL)
                    logs.lines().filter { it.contains(host) } shouldBe emptyList()
                }
            } finally {
                pool.shutdownNow()
            }
        }
    }

    Given("retry policy (review M-3): transient conflicts are retried in a new transaction up to 3 attempts") {
        fun failOnce(point: MasterFaultPoint, failure: () -> RuntimeException): AtomicInteger {
            val calls = AtomicInteger()
            faults.after(point) { if (calls.incrementAndGet() == 1) throw failure() }
            return calls
        }

        Then("a deadlock reported after the MID insert rolls the attempt back; the retry issues exactly one MID") {
            val ready = readyMerchant(service)
            val calls = failOnce(MasterFaultPoint.FIRST_DEFAULT_INSERT) { PersistenceFailure.Deadlock(RuntimeException("injected deadlock")) }
            service.issueFirstDefaultMerchantAccount(ready.issueCommand()).shouldBeInstanceOf<AdminCommandResult.Succeeded>()
            calls.get() shouldBe 2
            rowsOf(container, ready.merchantId).let { listOf(it.accounts, it.defaults, it.assignments, it.outbox) } shouldBe listOf(1L, 1L, 1L, 3L)
        }

        Then("an optimistic version conflict on the merchant update is retried the same way") {
            val ready = readyMerchant(service)
            val calls = failOnce(MasterFaultPoint.MERCHANT_UPDATE) { PersistenceFailure.OptimisticConflict("merchant") }
            service.issueFirstDefaultMerchantAccount(ready.issueCommand()).shouldBeInstanceOf<AdminCommandResult.Succeeded>()
            calls.get() shouldBe 2
            rowsOf(container, ready.merchantId).accounts shouldBe 1
        }

        Then("a conflict on every attempt exhausts the retries: TemporarilyUnavailable, nothing stored, one structured log line without sensitive values") {
            val ready = readyMerchant(service)
            val calls = AtomicInteger()
            faults.after(MasterFaultPoint.FIRST_DEFAULT_INSERT) {
                calls.incrementAndGet()
                throw PersistenceFailure.Deadlock(RuntimeException("injected deadlock"))
            }
            val command = ready.issueCommand()
            CapturedLogs().use { logs ->
                shouldThrow<AdminCommandFailure.TemporarilyUnavailable> { service.issueFirstDefaultMerchantAccount(command) }
                logs.lines().filter { it.contains("retries exhausted") } shouldBe listOf(
                    "com.paycore.master.merchant.application.AdminCommandExecutor admin command retries exhausted " +
                        "operation=IssueFirstDefaultMerchantAccount correlationId=${command.request.correlationId} attempts=3 failure=Deadlock",
                )
            }
            calls.get() shouldBe 3
            rowsOf(container, ready.merchantId).let { listOf(it.accounts, it.defaults, it.assignments, it.outbox, it.history) } shouldBe
                listOf(0L, 0L, 0L, 0L, 0L)
        }

        Then("a server-generated id that collides with an existing row is IdentifierCollision (not retried, nothing stored, logged without values)") {
            val existing = succeeded(OnboardingFixture.registeredBusiness(service, OnboardingFixture.registrationNumber(), "Existing"))
            ids.forceNext(BusinessId.PREFIX, existing)
            val number = OnboardingFixture.registrationNumber()
            // 기대값 변경(BUSINESS-PROFILE-001 BIZ-4 C-27): 옛 RegisterBusiness 제거 — 같은 서버 생성 ID 충돌을 새 생성 명령(CreateBusiness REGISTERED)으로 시험한다(operation 이름만 다름).
            val command = OnboardingFixture.request()
            fun create() = OnboardingFixture.registeredBusiness(service, number, "Collides", command)
            CapturedLogs().use { logs ->
                shouldThrow<AdminCommandFailure.IdentifierCollision> { create() }.kind shouldBe "IDENTIFIER"
                logs.lines().filter { it.contains("identifier collision") } shouldBe listOf(
                    "com.paycore.master.merchant.application.AdminCommandExecutor admin command generated identifier collision " +
                        "operation=CreateBusiness correlationId=${command.correlationId} constraint=IDENTIFIER",
                )
                logs.lines().filter { it.contains(number.value) || it.contains(existing) } shouldBe emptyList()
            }
            // 같은 키 재요청은 새 ID로 정상 처리된다(충돌한 요청은 아무것도 남기지 않았다).
            succeeded(create()) shouldNotBe existing
        }
    }

    Given("$n different keys register the same business registration number at once (UNIQUE race)") {
        Then("one Business is created; every other request converges to the duplicate rejection referencing it") {
            val number = OnboardingFixture.registrationNumber()
            val results = concurrently(n) { OnboardingFixture.registeredBusiness(service, number, "Race") }
                .map { it.getOrThrow() }
            val created = results.filterIsInstance<AdminCommandResult.Succeeded>()
            created shouldHaveSize 1
            results.filterIsInstance<AdminCommandResult.Rejected>().map { it.rejection to it.reference }.toSet() shouldBe
                setOf(MasterRejection.BUSINESS_REGISTRATION_NUMBER_DUPLICATE to created.single().reference)
        }
    }

    Given("the same key is still held by an uncommitted request") {
        Then("a second request with that key times out on the row lock and reports RequestInProgress (nothing stored by it)") {
            val ready = readyMerchant(service)
            val command = ready.issueCommand()
            val release = java.util.concurrent.CountDownLatch(1)
            val holding = java.util.concurrent.CountDownLatch(1)
            faults.after(MasterFaultPoint.ADMIN_RESERVE) { result ->
                if (result == AdminReservationOutcome.Reserved && holding.count == 1L) {
                    holding.countDown()
                    release.await()
                }
            }
            val first = java.util.concurrent.Executors.newSingleThreadExecutor().submit<AdminCommandResult> {
                service.issueFirstDefaultMerchantAccount(command)
            }
            holding.await()
            shouldThrow<AdminCommandFailure.RequestInProgress> { service.issueFirstDefaultMerchantAccount(command) }
            release.countDown()
            succeeded(first.get())
            rowsOf(container, ready.merchantId).accounts shouldBe 1
        }
    }
})

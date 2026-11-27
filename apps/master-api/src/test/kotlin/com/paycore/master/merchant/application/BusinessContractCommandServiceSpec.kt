package com.paycore.master.merchant.application

import com.paycore.master.MasterDbAccounts
import com.paycore.master.MasterDbTest
import com.paycore.master.merchant.adapter.persistence.JpaBusinessContractRepository
import com.paycore.master.merchant.application.OnboardingFixture.request
import com.paycore.master.merchant.application.provided.AdminCommandResult
import com.paycore.master.merchant.application.provided.AdminRequest
import com.paycore.master.merchant.application.provided.BusinessContractCommandUseCase
import com.paycore.master.merchant.application.provided.BusinessContractEventCommand
import com.paycore.master.merchant.application.provided.BusinessProfileCommandUseCase
import com.paycore.master.merchant.application.provided.BusinessProfileQueryUseCase
import com.paycore.master.merchant.application.provided.CompleteContractIntakeCommand
import com.paycore.master.merchant.application.provided.ContractIntakeCommandUseCase
import com.paycore.master.merchant.application.provided.MerchantOnboardingUseCase
import com.paycore.master.merchant.application.provided.MoveContractIntakeCommand
import com.paycore.master.merchant.application.provided.OpenContractIntakeCommand
import com.paycore.master.merchant.domain.ActorRef
import com.paycore.master.merchant.domain.BusinessChangeReason
import com.paycore.master.merchant.domain.BusinessContractEventKind
import com.paycore.master.merchant.domain.BusinessContractStatus
import com.paycore.master.merchant.domain.BusinessContractTerms
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.ContractDocumentRef
import com.paycore.master.merchant.domain.ContractIntakeChannel
import com.paycore.master.merchant.domain.ContractIntakeItems
import com.paycore.master.merchant.domain.ContractIntakeKind
import com.paycore.master.merchant.domain.ContractIntakeStatus
import com.paycore.master.merchant.domain.DesiredService
import com.paycore.master.merchant.domain.MasterRejection
import com.paycore.master.merchant.domain.ServiceKind
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.mysql.MySQLContainer
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MERCHANT-FLOW-001 FLOW-2c(§4B.4·§4B.7·§4B.9 — Application, 실제 MySQL 8.4·runtime 계정): [BusinessContractCommandUseCase] 정지·정지 해제·해지.
 * - OPERATOR 1인, '사유 및 비고' 필수, 근거 참조 선택(D-GOV-022 — 해지와 같은 규칙), 즉시 효력, 효력 전 계약도 정지·해지(BR-BIZ-344), 해지·만료 뒤 모든 명령 거절(BR-BIZ-342),
 *   기대 사건 수 불일치 `BUSINESS_CONTRACT_STATE_CONFLICT`, 최신 계약이 아니면 `BUSINESS_CONTRACT_NOT_FOUND`, 사건 시각 = judgedAt(max(lockedNow, 마지막 사건 시각)).
 * - 멱등: 같은 키 재전송 → 재생(행 수 불변), 같은 키 + 다른 사유·근거 = 409.
 * - Financial Consistency(M-1·BR-BIZ-321): `outbox_event`·권리 Snapshot·가맹점 `entitlement_revision`·사업자 version 불변. 연결 가맹점 수 = 종료 제외(BR-BIZ-312).
 * - Concurrency(§4B.9 — 2c 해당, 실제 두 연결 경합 강제): 같은 계약 동시 해지 → 1 성공·`STATE_CONFLICT`, 정지 vs 변경 계약 완료 → 둘 다 성공(정지 유지 — BR-BIZ-326),
 *   해지(먼저) vs 변경 계약 완료 → `BUSINESS_CONTRACT_NOT_CHANGEABLE`(BR-BIZ-333).
 */
@MasterDbTest
class BusinessContractCommandServiceSpec(
    private val service: BusinessContractCommandUseCase,
    private val intakes: ContractIntakeCommandUseCase,
    private val businesses: BusinessProfileCommandUseCase,
    private val onboarding: MerchantOnboardingUseCase,
    private val query: BusinessProfileQueryUseCase,
    private val contracts: JpaBusinessContractRepository,
    private val clock: MutableMasterClock,
    private val faults: MasterFaultInjector,
    private val transactionManager: PlatformTransactionManager,
    private val container: MySQLContainer,
) : BehaviorSpec({

    afterEach {
        clock.reset()
        faults.reset()
    }

    val now: Instant = Instant.parse("2026-11-26T01:00:00.000Z") // 10:00 KST
    val today: LocalDate = LocalDate.parse("2026-11-26")
    val reason = BusinessChangeReason("거래 확인 필요")
    val evidence = ContractDocumentRef("CASE/2026-11:77")
    val document = ContractDocumentRef("DOC-2026-11-0001")

    fun <T> runtime(block: (Connection) -> T): T = MasterDbAccounts.connect(container, MasterDbAccounts.MASTER_DATABASE, MasterDbAccounts.MASTER_RUNTIME_USER).use(block)

    fun count(sql: String, vararg args: Any?): Long = runtime { c ->
        c.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
            ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }
    }

    fun rows(sql: String, vararg args: Any?): List<String> = runtime { c ->
        c.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
    }

    fun ok(r: AdminCommandResult): String = r.shouldBeInstanceOf<AdminCommandResult.Succeeded>().reference
    fun rejected(r: AdminCommandResult): MasterRejection = r.shouldBeInstanceOf<AdminCommandResult.Rejected>().rejection

    fun latest(id: BusinessId) = TransactionTemplate(transactionManager).apply { isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED }
        .execute { contracts.latest(id) }

    fun items() = ContractIntakeItems(today, ContractIntakeChannel.PHONE, ActorRef("admin:adm_01JCFL0000000000000000OP01"), setOf(DesiredService.CARD_AUTH))

    /** 신규 접수 → 계약서 접수 → 계약 완료(시작일 [start], 종료일 [end]) — 공식 Use Case 경로. 접수 건 순번 [seq]. */
    fun contracted(id: BusinessId, seq: Int = 1, start: LocalDate = today, end: LocalDate? = null, kind: ContractIntakeKind = ContractIntakeKind.NEW) {
        ok(intakes.open(OpenContractIntakeCommand(request(), id, kind, items(), null)))
        ok(intakes.move(MoveContractIntakeCommand(request(), id, seq, 0, ContractIntakeStatus.DOCUMENT_RECEIVED, null)))
        ok(intakes.complete(CompleteContractIntakeCommand(request(), id, seq, 1, document, BusinessContractTerms(start, end, setOf(ServiceKind.CARD_PG_PAYMENT)), null)))
    }

    fun business(): BusinessId = BusinessCommandFixture.withoutMerchants(businesses).businessId

    fun cmd(id: BusinessId, expected: Int, seq: Int = 1, why: BusinessChangeReason = reason, ev: ContractDocumentRef? = null, req: AdminRequest = request()) =
        BusinessContractEventCommand(req, id, seq, expected, why, ev)

    fun untouched(id: BusinessId): List<Long> = listOf(
        count("SELECT COUNT(*) FROM outbox_event"), count("SELECT COUNT(*) FROM entitlement_snapshot_issue"),
        count("SELECT COALESCE(SUM(entitlement_revision), 0) FROM merchant"), count("SELECT version FROM business WHERE business_id = ?", id.value),
    )

    Given("FLOW-2c commands: success paths") {
        Then("suspend → resume → terminate (evidence optional on each — D-GOV-022): events 1..3, status follows, history rows only, no outbox / entitlement / business version change") {
            clock.set(now)
            val id = business()
            contracted(id)
            val before = untouched(id)
            ok(service.suspend(cmd(id, 0, ev = evidence))) shouldBe id.value
            checkNotNull(latest(id)).statusAt(now) shouldBe BusinessContractStatus.SUSPENDED
            ok(service.resume(cmd(id, 1)))
            checkNotNull(latest(id)).statusAt(now) shouldBe BusinessContractStatus.ACTIVE
            ok(service.terminate(cmd(id, 2, ev = evidence)))
            val c = checkNotNull(latest(id))
            listOf(c.statusAt(now), c.events.map { it.kind }, c.events.map { it.evidence }, c.events.map { it.reason }) shouldBe listOf(
                BusinessContractStatus.TERMINATED, listOf(BusinessContractEventKind.SUSPEND, BusinessContractEventKind.RESUME, BusinessContractEventKind.TERMINATE),
                listOf(evidence, null, evidence), List(3) { reason },
            )
            untouched(id) shouldBe before
            rows(
                "SELECT CONCAT_WS('|', operation, aggregate_type, change_type) FROM master_change_history WHERE aggregate_type = 'BusinessContract' AND aggregate_id = ? ORDER BY id",
                id.value,
            ) shouldBe listOf(
                "SuspendBusinessContract|BusinessContract|BusinessContractSuspended", "ResumeBusinessContract|BusinessContract|BusinessContractResumed",
                "TerminateBusinessContract|BusinessContract|BusinessContractTerminated",
            )
        }

        Then("BR-BIZ-344: a not-yet-effective contract can be suspended and terminated immediately") {
            clock.set(now)
            val id = business()
            contracted(id, start = today.plusDays(7))
            checkNotNull(latest(id)).statusAt(now) shouldBe BusinessContractStatus.NOT_YET_EFFECTIVE
            ok(service.suspend(cmd(id, 0)))
            ok(service.terminate(cmd(id, 1)))
            checkNotNull(latest(id)).statusAt(now) shouldBe BusinessContractStatus.TERMINATED
        }

        Then("the event time is judgedAt: a command judged before the last event (clock behind) is recorded at the last event time") {
            clock.set(now)
            val id = business()
            contracted(id)
            ok(service.suspend(cmd(id, 0)))
            clock.set(now.minus(Duration.ofHours(1)))
            ok(service.resume(cmd(id, 1)))
            checkNotNull(latest(id)).events.map { it.occurredAt } shouldBe listOf(now, now)
        }
    }

    Given("FLOW-2c commands: refusals, replay, key reuse") {
        Then("closed contracts refuse every command; RESUME on an active contract; stale event count; non-latest / unknown contract; unknown business") {
            clock.set(now)
            val id = business()
            contracted(id)
            rejected(service.resume(cmd(id, 0))) shouldBe MasterRejection.BUSINESS_CONTRACT_TRANSITION_NOT_ALLOWED
            rejected(service.suspend(cmd(id, 3))) shouldBe MasterRejection.BUSINESS_CONTRACT_STATE_CONFLICT
            rejected(service.suspend(cmd(id, 0, seq = 9))) shouldBe MasterRejection.BUSINESS_CONTRACT_NOT_FOUND
            rejected(service.suspend(cmd(BusinessId("biz_01JC0000000000000000000000"), 0))) shouldBe MasterRejection.BUSINESS_NOT_FOUND
            ok(service.terminate(cmd(id, 0)))
            listOf(service.suspend(cmd(id, 1)), service.resume(cmd(id, 1)), service.terminate(cmd(id, 1))).map(::rejected) shouldBe
                List(3) { MasterRejection.BUSINESS_CONTRACT_TRANSITION_NOT_ALLOWED }
            // 해지 뒤 신규 접수 → 계약 #2: 옛 계약 #1은 명령 대상이 아니다(최신 계약만 — §4B.7 구분 기준).
            contracted(id, seq = 2)
            rejected(service.suspend(cmd(id, 1, seq = 1))) shouldBe MasterRejection.BUSINESS_CONTRACT_NOT_FOUND
            ok(service.suspend(cmd(id, 0, seq = 2)))

            // 만료: 종료일 오늘인 계약, 내일 판정 → 모든 명령 거절.
            val ending = business()
            contracted(ending, end = today)
            clock.set(now.plus(Duration.ofDays(1)))
            listOf(service.suspend(cmd(ending, 0)), service.terminate(cmd(ending, 0))).map(::rejected) shouldBe List(2) { MasterRejection.BUSINESS_CONTRACT_TRANSITION_NOT_ALLOWED }
        }

        Then("the same key replays the stored result without new rows; the same key with another reason / evidence is 409") {
            clock.set(now)
            val id = business()
            contracted(id)
            val key = request()
            ok(service.suspend(cmd(id, 0, ev = evidence, req = key)))
            val events = count("SELECT COUNT(*) FROM business_contract_event WHERE business_id = ?", id.value)
            val history = count("SELECT COUNT(*) FROM master_change_history WHERE aggregate_type = 'BusinessContract' AND aggregate_id = ?", id.value)
            service.suspend(cmd(id, 0, ev = evidence, req = key)).shouldBeInstanceOf<AdminCommandResult.Succeeded>().replayed shouldBe true
            mapOf(
                "reason" to { service.suspend(cmd(id, 0, why = BusinessChangeReason("다른 사유"), ev = evidence, req = key)) },
                "evidence" to { service.suspend(cmd(id, 0, ev = null, req = key)) },
                "count" to { service.suspend(cmd(id, 1, ev = evidence, req = key)) },
            ).forEach { (field, call) -> withClue(field) { call() shouldBe AdminCommandResult.IdempotencyKeyReused } }
            listOf(
                count("SELECT COUNT(*) FROM business_contract_event WHERE business_id = ?", id.value),
                count("SELECT COUNT(*) FROM master_change_history WHERE aggregate_type = 'BusinessContract' AND aggregate_id = ?", id.value),
            ) shouldBe listOf(events, history)
        }
    }

    Given("BR-BIZ-312·321: linked merchants (termination notice) and no effect on existing merchants") {
        Then("the section's linked merchant count excludes terminated merchants; termination leaves merchant rows and entitlement revisions unchanged") {
            clock.set(now)
            val (created, merchant) = BusinessCommandFixture.withMerchant(businesses, onboarding)
            val id = created.businessId
            BusinessCommandFixture.withMerchant(businesses, onboarding).let { } // 다른 사업자의 가맹점은 세지 않는다
            val second = OnboardingFixture.succeeded(
                onboarding.registerMerchant(
                    com.paycore.master.merchant.application.provided.RegisterMerchantCommand(
                        request(), id, com.paycore.master.merchant.domain.DisplayName("종료될 가맹점"), com.paycore.master.merchant.domain.MerchantCodeFixtures.unique(),
                    ),
                ),
            )
            runtime { c -> c.prepareStatement("UPDATE merchant SET lifecycle = 'TERMINATED' WHERE merchant_id = ?").use { it.setString(1, second); it.executeUpdate() } }
            // FLOW-3 입력 정리: 가맹점 등록 Fixture(withMerchant)가 이미 유효 사업자 계약 #1을 만든다(관문) — 여기서 다시 신규 접수하지 않는다.
            checkNotNull(checkNotNull(query.contractIntakes(id)).businessContract).linkedMerchantCount shouldBe 1
            val merchants = rows("SELECT CONCAT_WS('|', merchant_id, lifecycle, entitlement_revision, version) FROM merchant WHERE business_id = ? ORDER BY merchant_id", id.value)
            ok(service.terminate(cmd(id, 0)))
            rows("SELECT CONCAT_WS('|', merchant_id, lifecycle, entitlement_revision, version) FROM merchant WHERE business_id = ? ORDER BY merchant_id", id.value) shouldBe merchants
            merchant.value.startsWith("mer_") shouldBe true
        }
    }

    Given("§4B.9 concurrency on real MySQL (two connections, contention forced)") {
        fun race(first: () -> AdminCommandResult, second: () -> AdminCommandResult): List<AdminCommandResult> {
            val held = AtomicBoolean(false)
            val firstHolding = CountDownLatch(1)
            faults.after(MasterFaultPoint.CHANGE_LOG_APPEND) {
                if (held.compareAndSet(false, true)) {
                    firstHolding.countDown()
                    val deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos()
                    while (true) {
                        val waiting = MasterDbAccounts.root(container).use { c ->
                            c.createStatement().use { st ->
                                st.executeQuery(
                                    "SELECT COUNT(*) FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l " +
                                        "ON l.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID WHERE l.OBJECT_SCHEMA = 'paycore_master' AND l.OBJECT_NAME = 'business'",
                                ).use { rs -> rs.next(); rs.getInt(1) }
                            }
                        }
                        if (waiting > 0) break
                        check(System.nanoTime() < deadline) { "the second command never waited for the business row lock" }
                        Thread.onSpinWait()
                    }
                }
            }
            val pool = Executors.newFixedThreadPool(2)
            val a = pool.submit<AdminCommandResult> { first() }
            check(firstHolding.await(30, TimeUnit.SECONDS)) { "the first command never reached the history write" }
            val b = pool.submit<AdminCommandResult> { second() }
            val results = listOf(a.get(60, TimeUnit.SECONDS), b.get(60, TimeUnit.SECONDS))
            pool.shutdown()
            faults.reset()
            return results
        }

        /** 계약 #1(오늘 시작) + 계약서 접수 상태의 변경 접수 건(순번 2). */
        fun withOpenChange(): BusinessId {
            val id = business()
            contracted(id)
            ok(intakes.open(OpenContractIntakeCommand(request(), id, ContractIntakeKind.CHANGE, items(), null)))
            ok(intakes.move(MoveContractIntakeCommand(request(), id, 2, 0, ContractIntakeStatus.DOCUMENT_RECEIVED, null)))
            return id
        }

        fun completeChange(id: BusinessId) =
            intakes.complete(CompleteContractIntakeCommand(request(), id, 2, 1, document, BusinessContractTerms(today.plusDays(1), null, setOf(ServiceKind.CARD_PG_PAYMENT)), null))

        Then("the same contract terminated twice at once with the same expected count → one success, the other BUSINESS_CONTRACT_STATE_CONFLICT; one TERMINATE event") {
            clock.set(now)
            val id = business()
            contracted(id)
            val (a, b) = race({ service.terminate(cmd(id, 0)) }, { service.terminate(cmd(id, 0)) })
            ok(a)
            rejected(b) shouldBe MasterRejection.BUSINESS_CONTRACT_STATE_CONFLICT
            count("SELECT COUNT(*) FROM business_contract_event WHERE business_id = ? AND kind = 'TERMINATE'", id.value) shouldBe 1
        }

        Then("BR-BIZ-326: suspend vs change completion → both succeed; the new version exists and the suspension stays") {
            clock.set(now)
            val id = withOpenChange()
            val (a, b) = race({ service.suspend(cmd(id, 0)) }, { completeChange(id) })
            ok(a); ok(b)
            val c = checkNotNull(latest(id))
            listOf(c.versions.size, c.statusAt(now.plus(Duration.ofDays(2)))) shouldBe listOf(2, BusinessContractStatus.SUSPENDED)
        }

        Then("BR-BIZ-333: terminate first vs change completion → the completion is BUSINESS_CONTRACT_NOT_CHANGEABLE (the intake stays DOCUMENT_RECEIVED)") {
            clock.set(now)
            val id = withOpenChange()
            val (a, b) = race({ service.terminate(cmd(id, 0)) }, { completeChange(id) })
            ok(a)
            rejected(b) shouldBe MasterRejection.BUSINESS_CONTRACT_NOT_CHANGEABLE
            rows("SELECT status FROM contract_intake WHERE business_id = ? AND intake_seq = 2", id.value) shouldBe listOf("DOCUMENT_RECEIVED")
            count("SELECT COUNT(*) FROM business_contract_version WHERE business_id = ?", id.value) shouldBe 1
        }
    }
})

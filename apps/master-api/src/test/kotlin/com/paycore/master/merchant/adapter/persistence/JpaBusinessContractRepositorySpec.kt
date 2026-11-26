package com.paycore.master.merchant.adapter.persistence

import com.paycore.master.MasterDbAccounts
import com.paycore.master.MasterDbTest
import com.paycore.master.merchant.application.required.BusinessRepository
import com.paycore.master.merchant.application.required.PersistenceFailure
import com.paycore.master.merchant.application.required.StoreConstraint
import com.paycore.master.merchant.domain.Business
import com.paycore.master.merchant.domain.BusinessContract
import com.paycore.master.merchant.domain.BusinessContractEvent
import com.paycore.master.merchant.domain.BusinessContractEventKind
import com.paycore.master.merchant.domain.BusinessContractFixtures
import com.paycore.master.merchant.domain.BusinessContractStatus
import com.paycore.master.merchant.domain.BusinessFixtures
import com.paycore.master.merchant.domain.ContractIntake
import com.paycore.master.merchant.domain.ContractIntakeCompletion
import com.paycore.master.merchant.domain.ContractIntakeFixtures
import com.paycore.master.merchant.domain.ContractIntakeKind
import com.paycore.master.merchant.domain.ContractIntakeStatus
import com.paycore.master.merchant.domain.ContractIntakeTransitionInput
import com.paycore.master.merchant.domain.MasterRejection
import com.paycore.master.merchant.domain.MasterRuleViolation
import com.paycore.master.merchant.domain.ServiceKind
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.mysql.MySQLContainer
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * MERCHANT-FLOW-001 FLOW-2a(§4B.12 JPA 선택·§4B.9 동시성 — 저장 adapter, 실제 MySQL 8.4·runtime 계정): [JpaBusinessContractRepository]를 Application과 같은 순서
 * (READ COMMITTED TX → 사업자 행 잠금 → 최신 접수 건·최신 사업자 계약 → Domain 판정 → 저장)로 부른다. 명령 서비스·멱등·감사는 FLOW-2b·2c.
 * - load 왕복(신규 계약 + 버전 1 → 변경 버전 2 + 닫기 → 해지 사건), SQL 수 고정(머리 1 + 하위 표 4, 계약 없음 = 1), INSERT만(UPDATE·DELETE 문 0).
 * - 잠금 뒤 직접 읽기(MINOR-5): 같은 TX에서 앞서 load한 뒤 다른 TX가 해지를 커밋하고 이 TX가 잠금을 얻으면 최신 해지를 본다(영속성 컨텍스트 재사용 없음).
 * - 경합(§4B.9 중 저장 계층 2종): 같은 접수 건 동시 계약 완료 → 계약·버전 1개, 같은 계약 동시 해지 → 1 성공·나머지 `BUSINESS_CONTRACT_STATE_CONFLICT`(사건 1행).
 * - Transaction Safety: 버전 INSERT 실패 주입(잠금 우회 결함 — 계약 PK 중복) → 접수 건 상태·전이 이력 rollback. 해지 2회 우회 → DB `uk_bce_terminate`도 막는다(결함 분류).
 * - Financial Consistency(M-1): 사업자 version·`outbox_event`·권리 Snapshot·가맹점 `entitlement_revision` 불변, 그 표에 쓰는 SQL 0.
 */
@MasterDbTest
class JpaBusinessContractRepositorySpec(
    private val contracts: JpaBusinessContractRepository,
    private val intakes: JpaContractIntakeRepository,
    private val transitions: JpaContractIntakeTransitionStore,
    private val businesses: BusinessRepository,
    private val transactionManager: PlatformTransactionManager,
    private val container: MySQLContainer,
) : BehaviorSpec({

    val capture = MasterSqlCapture(container)
    val bf = BusinessContractFixtures
    val operator = bf.operator
    val at = bf.t0

    /** 명령과 같은 READ COMMITTED TX(`LocalTransactions` 관례). */
    fun <T> tx(block: () -> T): T = TransactionTemplate(transactionManager).apply { isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED }.execute { block() }

    fun query(sql: String, vararg args: Any?): List<List<String?>> =
        MasterDbAccounts.connect(container, MasterDbAccounts.MASTER_DATABASE, MasterDbAccounts.MASTER_RUNTIME_USER).use { c ->
            c.prepareStatement(sql).use { ps ->
                args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
                ps.executeQuery().use { rs -> buildList { while (rs.next()) add((1..rs.metaData.columnCount).map { rs.getString(it) }) } }
            }
        }

    /**
     * 경합 강제(리뷰 NIT-4): 사업자 행 잠금을 쥔 첫 TX가 둘째 TX가 **그 잠금을 기다리는 것**을 InnoDB(`performance_schema.data_lock_waits`)에서 본 뒤에야 진행한다.
     * 그래서 두 TX가 순서대로(경합 없이) 실행돼 우연히 통과하는 일이 없다. 20초 안에 대기가 보이지 않으면 시험 실패.
     */
    fun awaitLockWaiter() {
        val deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos()
        while (true) {
            val waiting = MasterDbAccounts.root(container).use { c ->
                c.createStatement().use { st -> st.executeQuery(
                    "SELECT COUNT(*) FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l " +
                        "ON l.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID WHERE l.OBJECT_SCHEMA = 'paycore_master' AND l.OBJECT_NAME = 'business'",
                ).use { rs -> rs.next(); rs.getInt(1) } }
            }
            if (waiting > 0) return
            check(System.nanoTime() < deadline) { "the second transaction never waited for the business row lock" }
            Thread.onSpinWait()
        }
    }

    /** 두 작업을 경합시킨다: 첫째는 잠금 뒤 [awaitLockWaiter]까지 멈추고, 둘째는 첫째가 잠금을 쥔 뒤 시작한다. 각 작업은 잠금 직후 부를 hook을 받는다. */
    fun race(work: (afterLock: () -> Unit) -> Any): List<Any> {
        val firstLocked = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val first = pool.submit<Any> { work { firstLocked.countDown(); awaitLockWaiter() } }
        check(firstLocked.await(30, TimeUnit.SECONDS)) { "the first transaction never took the lock" }
        val second = pool.submit<Any> { work {} }
        val outcomes = listOf(first, second).map { it.get(60, TimeUnit.SECONDS) }
        pool.shutdown()
        return outcomes
    }

    fun storedBusiness(): Business = BusinessFixtures.individualRegistered().also { tx { businesses.insert(it.business, it.profile) } }.business

    /** Application 순서: 잠금 → 최신 접수 건·최신 계약 → 새 접수 건(종류 = 계약 상태로). */
    fun open(business: Business, when_: Instant = at): ContractIntake = tx {
        val locked = checkNotNull(businesses.lock(business.id))
        val latestContract = contracts.latest(business.id)
        val kind = if (latestContract?.hasContractAt(when_) == true) ContractIntakeKind.CHANGE else ContractIntakeKind.NEW
        val change = ContractIntake.open(locked, intakes.latest(business.id), latestContract, kind, ContractIntakeFixtures.items(), when_, operator)
        intakes.insert(change.intake)
        transitions.append(change.transition)
        change.intake
    }

    fun move(business: Business, target: ContractIntakeStatus, when_: Instant = at, terms: () -> com.paycore.master.merchant.domain.BusinessContractTerms = { bf.terms() }) = tx {
        val locked = checkNotNull(businesses.lock(business.id))
        val latest = checkNotNull(intakes.latest(business.id))
        val input = if (target == ContractIntakeStatus.COMPLETED) {
            ContractIntakeTransitionInput(document = bf.document, completion = ContractIntakeCompletion(terms(), contracts.latest(business.id)))
        } else {
            ContractIntakeTransitionInput.NONE
        }
        val change = latest.transitionTo(locked, target, latest.version, input, when_, operator)
        intakes.update(change.intake)
        transitions.append(change.transition)
        change.contract?.let(contracts::insert)
        change
    }

    /** 신규 접수 → 계약서 접수 → 계약 완료(사업자 계약 + 버전 1). */
    fun completedNew(business: Business): BusinessContract {
        open(business)
        move(business, ContractIntakeStatus.DOCUMENT_RECEIVED)
        return checkNotNull(move(business, ContractIntakeStatus.COMPLETED).contract).contract
    }

    fun terminate(business: Business, expectedEvents: Int, when_: Instant, afterLock: () -> Unit = {}) = tx {
        val locked = checkNotNull(businesses.lock(business.id))
        afterLock()
        val change = checkNotNull(contracts.latest(business.id)).terminate(locked, expectedEvents, bf.reason, null, when_, operator)
        contracts.insert(change)
        change
    }

    fun snapshot(contract: BusinessContract?): List<Any?>? = contract?.let { c ->
        listOf(c.id, c.originIntakeSeq, c.migrated, c.createdAt, c.createdBy, c.versions, c.closes, c.events)
    }

    Given("FLOW-2a JPA adapter: store → read back (Aggregate loaded at once)") {
        Then("NEW completion → contract + version 1; CHANGE completion → version 2 + close; terminate → event; each latest() equals the domain result") {
            val business = storedBusiness()
            tx { contracts.latest(business.id) } shouldBe null
            val first = completedNew(business)
            snapshot(tx { contracts.latest(business.id) }) shouldBe snapshot(first)
            first.versions.single().let { listOf(it.versionNo, it.startDate, it.services, it.document, it.originIntakeSeq) } shouldBe
                listOf(1, bf.today, setOf(ServiceKind.CARD_PG_PAYMENT), bf.document, 1)

            val later = at.plus(Duration.ofMinutes(5))
            open(business, later).kind shouldBe ContractIntakeKind.CHANGE
            move(business, ContractIntakeStatus.DOCUMENT_RECEIVED, later)
            val second = checkNotNull(move(business, ContractIntakeStatus.COMPLETED, later) { bf.terms(bf.today.plusDays(3), bf.today.plusDays(400)) }.contract).contract
            snapshot(tx { contracts.latest(business.id) }) shouldBe snapshot(second)
            second.closes.single().let { listOf(it.versionNo, it.closedByVersionNo, it.closedAt) } shouldBe listOf(1, 2, bf.midnight(bf.today.plusDays(3)))

            val terminated = terminate(business, 0, later.plusSeconds(60)).contract
            val read = checkNotNull(tx { contracts.latest(business.id) })
            snapshot(read) shouldBe snapshot(terminated)
            read.statusAt(later.plusSeconds(61)) shouldBe BusinessContractStatus.TERMINATED
            query("SELECT COUNT(*) FROM business_contract_version_service WHERE business_id = ?", business.id.value).single() shouldBe listOf("2")
        }

        Then("§4B.12 N+1: latest() is exactly 5 SELECT statements on the contract tables (head + 4 child tables), 1 when there is none; inserts are INSERT only") {
            val business = storedBusiness()
            val (_, inserts) = capture.capture { completedNew(business) }
            val contractSql = { s: String -> Regex("\\bbusiness_contract", RegexOption.IGNORE_CASE).containsMatchIn(s) }
            inserts.filter(contractSql).filter { Regex("^\\s*(update|delete)", RegexOption.IGNORE_CASE).containsMatchIn(it) }.shouldBeEmpty()
            inserts.filter(contractSql).count { Regex("^\\s*insert", RegexOption.IGNORE_CASE).containsMatchIn(it) } shouldBe 3 // 계약 + 버전 + 범위

            val (_, reads) = capture.capture { tx { contracts.latest(business.id) } }
            reads.filter(contractSql).count { Regex("^\\s*select", RegexOption.IGNORE_CASE).containsMatchIn(it) } shouldBe 5
            val empty = storedBusiness()
            val (_, none) = capture.capture { tx { contracts.latest(empty.id) } }
            none.filter(contractSql).count { Regex("^\\s*select", RegexOption.IGNORE_CASE).containsMatchIn(it) } shouldBe 1
        }
    }

    Given("§4B.8 MINOR-5 read after lock goes to the database (no stale managed entity)") {
        Then("a TX loads the contract, another TX terminates and commits, the first TX then locks the business and latest() sees the TERMINATE event") {
            val business = storedBusiness()
            completedNew(business)
            val seen = tx {
                val before = checkNotNull(contracts.latest(business.id)) // 잠금 전 읽기(같은 TX 안)
                val pool = Executors.newSingleThreadExecutor()
                pool.submit { terminate(business, 0, at.plusSeconds(120)) }.get(30, TimeUnit.SECONDS)
                pool.shutdown()
                checkNotNull(businesses.lock(business.id))
                val after = checkNotNull(contracts.latest(business.id))
                listOf(before.events.size, after.events.map { it.kind }, after.statusAt(at.plusSeconds(121)))
            }
            seen shouldBe listOf(0, listOf(BusinessContractEventKind.TERMINATE), BusinessContractStatus.TERMINATED)
        }
    }

    Given("§4B.9 concurrency on real MySQL (business row lock serializes)") {
        Then("the same intake completed by two TXs at once → exactly one contract and one version; the other is refused after the lock (stale version)") {
            val business = storedBusiness()
            open(business)
            move(business, ContractIntakeStatus.DOCUMENT_RECEIVED)
            val expected = checkNotNull(tx { intakes.latest(business.id) }).version
            val outcomes = race { afterLock ->
                    try {
                        tx {
                            val locked = checkNotNull(businesses.lock(business.id))
                            afterLock()
                            val latest = checkNotNull(intakes.latest(business.id))
                            val input = ContractIntakeTransitionInput(
                                document = bf.document, completion = ContractIntakeCompletion(bf.terms(), contracts.latest(business.id)),
                            )
                            val change = latest.transitionTo(locked, ContractIntakeStatus.COMPLETED, expected, input, at, operator)
                            intakes.update(change.intake)
                            transitions.append(change.transition)
                            contracts.insert(checkNotNull(change.contract))
                        }
                        "OK"
                    } catch (e: MasterRuleViolation) {
                        e.rejection
                    }
            }
            // 먼저 잠근 쪽이 성공, 기다린 쪽은 잠금 뒤 저장된 version이 바뀐 것을 본다.
            outcomes.map { it.toString() } shouldBe listOf("OK", MasterRejection.CONTRACT_INTAKE_VERSION_CONFLICT.name)
            query("SELECT COUNT(*) FROM business_contract WHERE business_id = ?", business.id.value).single() shouldBe listOf("1")
            query("SELECT COUNT(*) FROM business_contract_version WHERE business_id = ?", business.id.value).single() shouldBe listOf("1")
        }

        Then("the same contract terminated by two TXs with the same expected event count → 1 success, the other BUSINESS_CONTRACT_STATE_CONFLICT; one event row") {
            val business = storedBusiness()
            completedNew(business)
            val outcomes = race { afterLock ->
                try {
                    terminate(business, 0, at.plusSeconds(30), afterLock)
                    "OK"
                } catch (e: MasterRuleViolation) {
                    e.rejection
                }
            }
            outcomes.map { it.toString() } shouldBe listOf("OK", MasterRejection.BUSINESS_CONTRACT_STATE_CONFLICT.name)
            query("SELECT COUNT(*) FROM business_contract_event WHERE business_id = ?", business.id.value).single() shouldBe listOf("1")
        }
    }

    Given("Transaction Safety and DB double defense") {
        Then("a version INSERT failure in the completion TX (lock-bypass defect: contract PK taken) rolls back the intake state and the transition row") {
            val business = storedBusiness()
            open(business)
            move(business, ContractIntakeStatus.DOCUMENT_RECEIVED)
            val before = query("SELECT status, version FROM contract_intake WHERE business_id = ?", business.id.value) to
                query("SELECT COUNT(*) FROM contract_intake_transition WHERE business_id = ?", business.id.value)
            // 다른 경로가 계약 순번 1을 이미 썼다(시험 준비 — 사업자 계약 행만 SQL로).
            MasterDbAccounts.connect(container, MasterDbAccounts.MASTER_DATABASE, MasterDbAccounts.MASTER_RUNTIME_USER).use { c ->
                c.prepareStatement(
                    "INSERT INTO business_contract (business_id, contract_seq, origin_intake_seq, migrated, created_at, created_by) VALUES (?, 1, 1, 0, NOW(3), 'schema-test')",
                ).use { it.setString(1, business.id.value); it.executeUpdate() }
            }
            val failure = shouldThrow<PersistenceFailure.UniqueConflict> {
                tx {
                    val locked = checkNotNull(businesses.lock(business.id))
                    val latest = checkNotNull(intakes.latest(business.id))
                    // 결함 주입: 저장된 계약을 무시(latest = null)한 Domain 판정 → 순번 1 계약 INSERT가 PK 1062.
                    val input = ContractIntakeTransitionInput(document = bf.document, completion = ContractIntakeCompletion(bf.terms(), null))
                    val change = latest.transitionTo(locked, ContractIntakeStatus.COMPLETED, latest.version, input, at, operator)
                    intakes.update(change.intake)
                    transitions.append(change.transition)
                    contracts.insert(checkNotNull(change.contract))
                }
            }
            listOf(failure.constraint, failure.constraint.businessKey) shouldBe listOf(StoreConstraint.BUSINESS_CONTRACT_ROW, false)
            (
                query("SELECT status, version FROM contract_intake WHERE business_id = ?", business.id.value) to
                    query("SELECT COUNT(*) FROM contract_intake_transition WHERE business_id = ?", business.id.value)
                ) shouldBe before
            query("SELECT COUNT(*) FROM business_contract_version WHERE business_id = ?", business.id.value).single() shouldBe listOf("0")
        }

        Then("a second TERMINATE that bypasses the domain (stale aggregate) is stopped by uk_bce_terminate — a BUSINESS_CONTRACT_ROW defect, nothing committed") {
            val business = storedBusiness()
            val contract = completedNew(business)
            terminate(business, 0, at.plusSeconds(30))
            // 결함 주입: 해지 사건을 모르는(정지 1건으로 잘못 복원한) Aggregate로 해지 → 사건 순번 2 TERMINATE.
            val stale = BusinessContract.reconstitute(
                contract.id, contract.originIntakeSeq, contract.migrated, contract.createdAt, contract.createdBy, contract.versions, contract.closes,
                listOf(BusinessContractEvent(1, BusinessContractEventKind.SUSPEND, bf.reason, null, operator, at.plusSeconds(10))),
            )
            val failure = shouldThrow<PersistenceFailure.UniqueConflict> {
                tx {
                    val locked = checkNotNull(businesses.lock(business.id))
                    contracts.insert(stale.terminate(locked, 1, bf.reason, null, at.plusSeconds(40), operator))
                }
            }
            failure.constraint shouldBe StoreConstraint.BUSINESS_CONTRACT_ROW
            query("SELECT event_seq, kind FROM business_contract_event WHERE business_id = ?", business.id.value) shouldBe listOf(listOf("1", "TERMINATE"))
            failure.shouldBeInstanceOf<PersistenceFailure>()
        }

        Then("M-1 / m-4: business version, outbox_event, entitlement snapshots and merchant entitlement_revision unchanged; no SQL writes them") {
            val business = storedBusiness()
            fun counts() = listOf(
                query("SELECT version FROM business WHERE business_id = ?", business.id.value).single(),
                query("SELECT COUNT(*) FROM outbox_event").single(),
                query("SELECT COUNT(*) FROM entitlement_snapshot_issue").single(),
                query("SELECT COALESCE(SUM(entitlement_revision), 0) FROM merchant").single(),
            )
            val before = counts()
            val (_, sql) = capture.capture {
                completedNew(business)
                terminate(business, 0, at.plusSeconds(30))
            }
            counts() shouldBe before
            sql.filter { s -> listOf("outbox_event", "entitlement_snapshot", "merchant ").any { s.contains(it, ignoreCase = true) } && !s.startsWith("select", true) }
                .shouldBeEmpty()
            sql.filter { Regex("^\\s*update\\s+business\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) }.shouldBeEmpty()
        }
    }
})

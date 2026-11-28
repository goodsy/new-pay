package com.paycore.master.merchant.adapter.persistence

import com.paycore.master.MasterDbAccounts
import com.paycore.master.MasterDbTest
import com.paycore.master.merchant.application.required.BusinessRepository
import com.paycore.master.merchant.application.required.PersistenceFailure
import com.paycore.master.merchant.application.required.StoreConstraint
import com.paycore.master.merchant.domain.Business
import com.paycore.master.merchant.domain.BusinessFixtures
import com.paycore.master.merchant.domain.ContractIntake
import com.paycore.master.merchant.domain.ContractIntakeChannel
import com.paycore.master.merchant.domain.ContractIntakeFixtures
import com.paycore.master.merchant.domain.ContractIntakeKind
import com.paycore.master.merchant.domain.ContractIntakeStatus
import com.paycore.master.merchant.domain.ContractIntakeTransition
import com.paycore.master.merchant.domain.ContractIntakeTransitionInput
import com.paycore.master.merchant.domain.DesiredService
import com.paycore.master.merchant.domain.MasterRejection
import com.paycore.master.merchant.domain.MasterRuleViolation
import com.paycore.master.merchant.domain.ActorRef
import com.paycore.master.merchant.domain.BusinessChangeReason
import com.paycore.master.merchant.domain.ContractIntakeItems
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.mysql.MySQLContainer
import java.time.LocalDate

/**
 * MERCHANT-FLOW-001 FLOW-1(§4A.3·§4A.15 — JPA adapter): 실제 MySQL 8.4(runtime 계정 권한)에서 [JpaContractIntakeRepository]·[JpaContractIntakeTransitionStore]를
 * Application과 같은 순서(사업자 행 잠금 → 최신 접수 건 → Domain 판정 → 저장)로 부른다. 명령 서비스·멱등·감사는 FLOW-2.
 * - 저장 → 재조회(접수 항목·희망 서비스·순번·version), 최신 = 순번 최대, 전이 = version 정확히 +1·이력 순번 1, 2, …
 * - stale version 저장 → OptimisticConflict(덮어쓰기 없음), 열린 건 중복 insert(잠금 우회 결함) → `CONTRACT_INTAKE_ROW`.
 * - 사업자 version 불변(`PESSIMISTIC_WRITE` 잠금 — m-4), 전이 이력 표에 UPDATE·DELETE 문 0(@Immutable·persist만 — 실제 SQL 포획), outbox·권리 표를 건드리지 않음.
 */
@MasterDbTest
class JpaContractIntakeRepositorySpec(
    private val intakes: JpaContractIntakeRepository,
    private val transitions: JpaContractIntakeTransitionStore,
    private val businesses: BusinessRepository,
    private val transactionManager: PlatformTransactionManager,
    private val container: MySQLContainer,
) : BehaviorSpec({

    val capture = MasterSqlCapture(container)
    val fixtures = ContractIntakeFixtures
    val operator = fixtures.operator

    fun <T> tx(block: () -> T): T = TransactionTemplate(transactionManager).execute { block() }

    fun query(sql: String, vararg args: Any?): List<List<String?>> =
        MasterDbAccounts.connect(container, MasterDbAccounts.MASTER_DATABASE, MasterDbAccounts.MASTER_RUNTIME_USER).use { c ->
            c.prepareStatement(sql).use { ps ->
                args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
                ps.executeQuery().use { rs -> buildList { while (rs.next()) add((1..rs.metaData.columnCount).map { rs.getString(it) }) } }
            }
        }

    fun storedBusiness(): Business = BusinessFixtures.individualRegistered().also { tx { businesses.insert(it.business, it.profile) } }.business

    /** Application 순서(FLOW-2 명령과 같음): 사업자 잠금 → 최신 → Domain 생성 → insert + 이력. */
    fun open(business: Business, items: com.paycore.master.merchant.domain.ContractIntakeItems = fixtures.items()): ContractIntake = tx {
        val locked = checkNotNull(businesses.lock(business.id))
        val change = ContractIntake.open(locked, intakes.latest(business.id), null, ContractIntakeKind.NEW, items, fixtures.t0, operator)
        intakes.insert(change.intake)
        transitions.append(change.transition) shouldBe 1
        change.intake
    }

    fun move(business: Business, target: ContractIntakeStatus): Pair<ContractIntake, Int> = tx {
        val locked = checkNotNull(businesses.lock(business.id))
        val latest = checkNotNull(intakes.latest(business.id))
        // FLOW-2a: 입력 없음(NONE) — 사유가 필요한 전이(계약 불가)는 Domain이 먼저 막는다(MINOR-1). DB CHECK(ck_cint_reason_required)는 ContractIntakeSchemaSpec이 검사한다.
        val change = latest.transitionTo(locked, target, latest.version, ContractIntakeTransitionInput.NONE, fixtures.t0.plusSeconds(60), operator)
        intakes.update(change.intake)
        change.intake to transitions.append(change.transition)
    }

    fun history(businessId: String): List<List<String?>> = query(
        "SELECT intake_seq, transition_seq, IFNULL(from_status, '-'), to_status, migrated, actor_ref FROM contract_intake_transition WHERE business_id = ? " +
            "ORDER BY intake_seq, transition_seq",
        businessId,
    )

    Given("FLOW-1 JPA adapter: store → read back") {
        Then("insert + latest return the same intake (items, desired services, sequence 1, version 0) and one creation history row") {
            val business = storedBusiness()
            val items = fixtures.items(LocalDate.parse("2026-11-20"), ContractIntakeChannel.PHONE, desired = setOf(DesiredService.CARD_AUTH, DesiredService.CARD_KEYIN))
            val created = open(business, items)
            val read = checkNotNull(tx { intakes.latest(business.id) })
            with(read) { listOf(businessId, seq, status, migrated, this.items, version, createdAt, createdBy, updatedAt, updatedBy) } shouldBe
                listOf(business.id, 1, ContractIntakeStatus.CONSULTING, false, items, 0L, created.createdAt, operator, created.updatedAt, operator)
            history(business.id.value) shouldBe listOf(listOf("1", "1", "-", "CONSULTING", "0", operator.value))
            tx { intakes.latest(storedBusiness().id) } shouldBe null
        }

        Then("a transition advances version exactly +1 and appends history sequence 2; a new intake after 계약 불가 is sequence 2 and becomes the latest") {
            val business = storedBusiness()
            open(business)
            val (received, seq2) = move(business, ContractIntakeStatus.DOCUMENT_RECEIVED)
            listOf(received.version, seq2, received.status) shouldBe listOf(1L, 2, ContractIntakeStatus.DOCUMENT_RECEIVED)
            query("SELECT status, version FROM contract_intake WHERE business_id = ? AND intake_seq = 1", business.id.value).single() shouldBe listOf("DOCUMENT_RECEIVED", "1")
            // 계약 불가 전이는 '사유 및 비고' 필수(Domain이 먼저 막는다 — MINOR-1, DB CHECK는 ContractIntakeSchemaSpec이 검사; 사유 입력 명령은 FLOW-2b) → 저장된 상태를 SQL로 만든다
            // (runtime 계정 — 접수 건 UPDATE 권한 범위).
            MasterDbAccounts.connect(container, MasterDbAccounts.MASTER_DATABASE, MasterDbAccounts.MASTER_RUNTIME_USER).use { c ->
                c.prepareStatement("UPDATE contract_intake SET status = 'REJECTED', version = version + 1 WHERE business_id = ? AND intake_seq = 1").use {
                    it.setString(1, business.id.value); it.executeUpdate()
                }
                c.prepareStatement(
                    "INSERT INTO contract_intake_transition (business_id, intake_seq, transition_seq, from_status, to_status, migrated, reason, document_ref, actor_ref, occurred_at) " +
                        "VALUES (?, 1, 3, 'DOCUMENT_RECEIVED', 'REJECTED', 0, '서류 미비', NULL, ?, NOW(3))",
                ).use { it.setString(1, business.id.value); it.setString(2, operator.value); it.executeUpdate() }
            }
            checkNotNull(tx { intakes.latest(business.id) }).let { listOf(it.seq, it.status, it.version) } shouldBe listOf(1, ContractIntakeStatus.REJECTED, 2L)

            val second = open(business)
            second.seq shouldBe 2
            checkNotNull(tx { intakes.latest(business.id) }).let { listOf(it.seq, it.status) } shouldBe listOf(2, ContractIntakeStatus.CONSULTING)
            history(business.id.value).map { it.take(4) } shouldBe listOf(
                listOf("1", "1", "-", "CONSULTING"), listOf("1", "2", "CONSULTING", "DOCUMENT_RECEIVED"), listOf("1", "3", "DOCUMENT_RECEIVED", "REJECTED"),
                listOf("2", "1", "-", "CONSULTING"),
            )
        }

        // FLOW-2a MINOR-1: 계약 불가 + 사유 없음은 Domain이 먼저 막는다(CONTRACT_INTAKE_REASON_REQUIRED). DB CHECK(ck_cint_reason_required)는 ContractIntakeSchemaSpec이 검사한다.
        Then("계약 불가 without '사유 및 비고' is refused by the domain first (CONTRACT_INTAKE_REASON_REQUIRED) and nothing is written (the DB CHECK is checked by ContractIntakeSchemaSpec)") {
            val business = storedBusiness()
            open(business)
            shouldThrow<MasterRuleViolation> { move(business, ContractIntakeStatus.REJECTED) }.rejection shouldBe MasterRejection.CONTRACT_INTAKE_REASON_REQUIRED
            query("SELECT status, version FROM contract_intake WHERE business_id = ?", business.id.value).single() shouldBe listOf("CONSULTING", "0")
            history(business.id.value).size shouldBe 1
        }
    }

    Given("FLOW-1 JPA adapter: double defense and side effects") {
        Then("a stale intake (version already advanced) is not written: OptimisticConflict, the stored row keeps the newer state") {
            val business = storedBusiness()
            val created = open(business)
            move(business, ContractIntakeStatus.DOCUMENT_RECEIVED)
            val stale = created.transitionTo(business, ContractIntakeStatus.DOCUMENT_RECEIVED, 0, ContractIntakeTransitionInput.NONE, fixtures.t0, operator).intake // 저장 전 version 0 기대 — 저장된 행은 이미 1
            shouldThrow<PersistenceFailure.OptimisticConflict> { tx { intakes.update(stale) } }
            query("SELECT status, version FROM contract_intake WHERE business_id = ?", business.id.value).single() shouldBe listOf("DOCUMENT_RECEIVED", "1")
        }

        Then("a second open intake that bypasses the domain check is a CONTRACT_INTAKE_ROW defect (uk_cin_open — V21), not a business key; nothing is committed") {
            val business = storedBusiness()
            open(business)
            val bypass = ContractIntake.open(business, null, null, ContractIntakeKind.NEW, fixtures.items(), fixtures.t0, operator).intake // 최신 건을 무시한 잠금 우회(결함) — 순번 1 중복
            val again = ContractIntake.reconstitute(business.id, 2, ContractIntakeStatus.CONSULTING, false, fixtures.items(), fixtures.t0, operator, fixtures.t0, operator, 0)
            listOf(bypass, again).forEach { duplicate ->
                val failure = shouldThrow<PersistenceFailure.UniqueConflict> { tx { intakes.insert(duplicate) } }
                listOf(failure.constraint, failure.constraint.businessKey) shouldBe listOf(StoreConstraint.CONTRACT_INTAKE_ROW, false)
            }
            query("SELECT COUNT(*) FROM contract_intake WHERE business_id = ?", business.id.value).single() shouldBe listOf("1")
            // 같은 접수 건에 생성 행을 두 번 쓰려 하면 순번 부여 단계에서 막힌다(생성 행 = 순번 1만 — DB ck_cint_first와 같은 규칙).
            shouldThrow<IllegalStateException> {
                tx { transitions.append(ContractIntakeTransition(business.id, 1, null, ContractIntakeStatus.CONSULTING, false, fixtures.t0, operator)) }
            }
        }

        Then("m-4 / M-2 / M-1: business version unchanged, no UPDATE/DELETE statement on the history table, no outbox or entitlement statement") {
            val business = storedBusiness()
            val before = query("SELECT version FROM business WHERE business_id = ?", business.id.value).single()
            val (_, sql) = capture.capture {
                open(business)
                move(business, ContractIntakeStatus.DOCUMENT_RECEIVED)
            }
            query("SELECT version FROM business WHERE business_id = ?", business.id.value).single() shouldBe before
            sql.filter { it.contains("contract_intake_transition", ignoreCase = true) && Regex("^\\s*(update|delete)", RegexOption.IGNORE_CASE).containsMatchIn(it) }
                .shouldBeEmpty()
            sql.filter { s -> listOf("outbox_event", "entitlement_snapshot", "merchant ").any { s.contains(it, ignoreCase = true) } && !s.startsWith("select", true) }
                .shouldBeEmpty()
            // 사업자 행은 FOR UPDATE로만 잠그고(version 증가 UPDATE 없음) 접수 건 UPDATE는 version 조건부(@Version).
            sql.filter { Regex("^\\s*update\\s+business\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) }.shouldBeEmpty()
            sql.filter { Regex("^\\s*update\\s+contract_intake\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) }.forEach { it.contains("version", true) shouldBe true }
            // FLOW-2a V22 주석: 종류·대상 열은 INSERT 때만 쓴다 — 전이 UPDATE의 SET에 kind·target_contract_seq가 없다(Entity updatable = false).
            val intakeUpdates = sql.filter { Regex("^\\s*update\\s+contract_intake\\s", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            (intakeUpdates.size >= 1) shouldBe true
            intakeUpdates.filter { Regex("\\b(kind|target_contract_seq)\\b", RegexOption.IGNORE_CASE).containsMatchIn(it.lowercase().substringBefore(" where ")) }.shouldBeEmpty()
            sql.any { Regex("^\\s*select.+from\\s+business\\b.+for update", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).containsMatchIn(it) } shouldBe true
        }

        Then("FLOW-2d review MINOR-1: the item update is one UPDATE contract_intake with a version condition; its SET has the 5 item columns + updated_* + version only (no kind, target, migrated, creation or key column); no transition INSERT") {
            val business = storedBusiness()
            val opened = open(business)
            val next = ContractIntakeItems(LocalDate.parse("2026-11-24"), ContractIntakeChannel.WEBSITE, ActorRef("admin:adm_01JCFL0000000000000000OP02"), setOf(DesiredService.CARD_KEYIN))
            val (_, sql) = capture.capture {
                tx {
                    val locked = checkNotNull(businesses.lock(business.id))
                    val latest = checkNotNull(intakes.latest(business.id))
                    intakes.updateItems(latest.updateItems(locked, latest.version, next, BusinessChangeReason("정정"), fixtures.t0.plusSeconds(60), operator))
                }
            }
            val updates = sql.filter { Regex("^\\s*update\\s+contract_intake\\s", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            updates.size shouldBe 1
            val set = updates.single().lowercase().substringAfter(" set ").substringBefore(" where ")
            val where = updates.single().lowercase().substringAfter(" where ")
            Regex("([a-z_]+)\\s*=").findAll(set).map { it.groupValues[1] }.toSet() shouldBe
                setOf("contract_manager", "desires_card_auth", "desires_card_keyin", "intake_channel", "received_on", "status", "updated_at", "updated_by", "version")
            (where.contains("version") && where.contains("business_id") && where.contains("intake_seq")) shouldBe true
            sql.filter { it.contains("contract_intake_transition", ignoreCase = true) && !it.trimStart().startsWith("select", true) }.shouldBeEmpty()
            query("SELECT received_on, intake_channel, contract_manager, desires_card_auth, desires_card_keyin, status, version FROM contract_intake WHERE business_id = ?", business.id.value)
                .single() shouldBe listOf("2026-11-24", "WEBSITE", "admin:adm_01JCFL0000000000000000OP02", "0", "1", opened.status.name, "1")
        }

        Then("FLOW-2d review MINOR-2: the adapter's defensive branches — stale version (OptimisticConflict), a migrated row, a status change — refuse and leave the row unchanged") {
            fun row(id: String) = query("SELECT CONCAT_WS('|', status, received_on, intake_channel, version) FROM contract_intake WHERE business_id = ?", id).single()
            val changed = ContractIntakeItems(LocalDate.parse("2026-11-24"), ContractIntakeChannel.OTHER, operator, setOf(DesiredService.CARD_AUTH))
            // ① stale version: 저장 version 0에 version 5인 결과를 쓰려 한다.
            val b1 = storedBusiness()
            val o1 = open(b1)
            val before1 = row(b1.id.value)
            val stale = ContractIntake.reconstitute(b1.id, o1.seq, o1.status, false, changed, o1.createdAt, o1.createdBy, fixtures.t0.plusSeconds(60), operator, 5, o1.kind, o1.targetContractSeq)
            shouldThrow<PersistenceFailure.OptimisticConflict> { tx { intakes.updateItems(stale) } }
            row(b1.id.value) shouldBe before1
            // ② 이관 행(SQL 모양 — 항목 없음): Domain이 막는 경로를 우회해도 adapter가 거부한다.
            val b2 = storedBusiness()
            MasterDbAccounts.connect(container, MasterDbAccounts.MASTER_DATABASE, MasterDbAccounts.MASTER_RUNTIME_USER).use { c ->
                c.prepareStatement(
                    "INSERT INTO contract_intake (business_id, intake_seq, status, kind, migrated, received_on, intake_channel, contract_manager, desires_card_auth, desires_card_keyin, " +
                        "created_at, created_by, updated_at, updated_by, version) VALUES (?, 1, 'COMPLETED', 'NEW', 1, NULL, NULL, NULL, 0, 0, NOW(3), 'system:migration', NOW(3), " +
                        "'system:migration', 0)",
                ).use { it.setString(1, b2.id.value); it.executeUpdate() }
            }
            val migratedRow = query("SELECT CONCAT_WS('|', status, COALESCE(received_on, '-'), COALESCE(intake_channel, '-'), version) FROM contract_intake WHERE business_id = ?", b2.id.value)
            val asOpen = ContractIntake.reconstitute(b2.id, 1, ContractIntakeStatus.COMPLETED, false, changed, fixtures.t0, operator, fixtures.t0, operator, 1, ContractIntakeKind.NEW, null)
            shouldThrowAny { tx { intakes.updateItems(asOpen) } }
            query("SELECT CONCAT_WS('|', status, COALESCE(received_on, '-'), COALESCE(intake_channel, '-'), version) FROM contract_intake WHERE business_id = ?", b2.id.value) shouldBe migratedRow
            // ③ 상태가 다른 결과(항목 수정은 상태를 바꾸지 않는다).
            val b3 = storedBusiness()
            val o3 = open(b3)
            val before3 = row(b3.id.value)
            val moved = ContractIntake.reconstitute(
                b3.id, o3.seq, ContractIntakeStatus.DOCUMENT_RECEIVED, false, changed, o3.createdAt, o3.createdBy, fixtures.t0.plusSeconds(60), operator, 1, o3.kind, o3.targetContractSeq,
            )
            shouldThrowAny { tx { intakes.updateItems(moved) } }
            row(b3.id.value) shouldBe before3
        }

        Then("reconstitution of a stored migration-shaped intake (SQL row like V19) reads back as migrated COMPLETED without items") {
            val business = storedBusiness()
            MasterDbAccounts.connect(container, MasterDbAccounts.MASTER_DATABASE, MasterDbAccounts.MASTER_RUNTIME_USER).use { c ->
                c.prepareStatement(
                    // 행 작성 열 목록만 갱신(FLOW-2a V21 — kind NOT NULL, 기본값 없음): 이관 건 = 신규. 기대값은 바뀌지 않는다.
                    "INSERT INTO contract_intake (business_id, intake_seq, status, kind, migrated, received_on, intake_channel, contract_manager, desires_card_auth, desires_card_keyin, " +
                        "created_at, created_by, updated_at, updated_by, version) VALUES (?, 1, 'COMPLETED', 'NEW', 1, NULL, NULL, NULL, 0, 0, NOW(3), 'system:migration', NOW(3), " +
                        "'system:migration', 0)",
                ).use { it.setString(1, business.id.value); it.executeUpdate() }
            }
            val read = checkNotNull(tx { intakes.latest(business.id) })
            listOf(read.migrated, read.items, read.status, read.seq, read.createdBy.value) shouldBe listOf(true, null, ContractIntakeStatus.COMPLETED, 1, "system:migration")
            read.shouldBeInstanceOf<ContractIntake>()
        }
    }
})

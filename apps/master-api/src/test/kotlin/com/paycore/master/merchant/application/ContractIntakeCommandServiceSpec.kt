package com.paycore.master.merchant.application

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.paycore.master.MasterDbAccounts
import com.paycore.master.MasterDbTest
import com.paycore.master.merchant.adapter.persistence.JpaBusinessContractRepository
import com.paycore.master.merchant.application.OnboardingFixture.request
import com.paycore.master.merchant.application.provided.AdminCommandResult
import com.paycore.master.merchant.application.provided.AdminIdempotencyKey
import com.paycore.master.merchant.application.provided.AdminRequest
import com.paycore.master.merchant.application.provided.BusinessProfileCommandUseCase
import com.paycore.master.merchant.application.provided.CompleteContractIntakeCommand
import com.paycore.master.merchant.application.provided.ContractIntakeCommandUseCase
import com.paycore.master.merchant.application.provided.MoveContractIntakeCommand
import com.paycore.master.merchant.application.provided.OpenContractIntakeCommand
import com.paycore.master.merchant.application.provided.UpdateContractIntakeItemsCommand
import com.paycore.master.merchant.domain.ActorRef
import com.paycore.master.merchant.domain.BusinessChangeReason
import com.paycore.master.merchant.domain.BusinessContractStatus
import com.paycore.master.merchant.domain.BusinessContractTerms
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.BusinessRegistrationStatus
import com.paycore.master.merchant.domain.ContractDocumentRef
import com.paycore.master.merchant.domain.ContractIntakeChannel
import com.paycore.master.merchant.domain.ContractIntakeItems
import com.paycore.master.merchant.domain.ContractIntakeKind
import com.paycore.master.merchant.domain.ContractIntakeStatus
import com.paycore.master.merchant.domain.DesiredService
import com.paycore.master.merchant.domain.MasterRejection
import com.paycore.master.merchant.domain.ServiceKind
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.slf4j.LoggerFactory
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.mysql.MySQLContainer
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MERCHANT-FLOW-001 FLOW-2b(§4B.6·§4B.7·§4B.9·§4B.12 — Application, 실제 MySQL 8.4·runtime 계정): [ContractIntakeCommandUseCase] 명령 3종.
 * - 성공: 신규 접수 → 계약서 접수 → 계약 완료 = 사업자 계약 + 버전 1(BR-BIZ-314), 이관 계약 사업자의 변경 접수 → 계약 완료 = 새 버전 + 닫기(BR-BIZ-324), DRAFT 사업자 계약 완료(BR-BIZ-332).
 * - 거절(저장·같은 키 재생): 종류 불일치(BR-BIZ-325), 열린 건 있음, 접수일 미래(n-4), 대상 없음, version 충돌, 사유 필수, 소급(BR-BIZ-335), 예정 버전 있음(BR-BIZ-345), 기간(BR-BIZ-346),
 *   사업자 없음. 같은 키 + 다른 내용 = 409(IdempotencyKeyReused).
 * - Financial Consistency(M-1): 명령 전후 `outbox_event` 증가 0, `entitlement_revision`·Snapshot 불변, 사업자 version 불변(m-4). 변경 이력 1행/명령(`ContractIntake`).
 * - Transaction Safety: 이력 쓰기 실패 주입 → 접수 건·전이 이력·사업자 계약 행 rollback.
 * - Concurrency(§4B.9 — 2b 해당, 실제 두 연결 경합 강제): 같은 사업자 접수 건 동시 생성 → 1건, 같은 접수 건 동시 계약 완료 → 계약·버전 1개.
 * - NIT-5: V21 이관 모양 행(SQL)을 `BusinessContractRepository.latest`가 이관 계약(ACTIVE)으로 읽고, 변경 접수가 그 계약을 대상으로 한다.
 * - Observability(§4B.12): 저장 행 불변식 위반 load 실패 → 값 없는 ERROR 경보 로그(`alert=BUSINESS_CONTRACT_LOAD_INVARIANT`) + 결함(저장 거절 없음).
 */
@MasterDbTest
class ContractIntakeCommandServiceSpec(
    private val service: ContractIntakeCommandUseCase,
    private val businesses: BusinessProfileCommandUseCase,
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
    val manager = ActorRef("admin:adm_01JCFL0000000000000000OP01")
    val document = ContractDocumentRef("DOC-2026-11-0001")
    val reason = BusinessChangeReason("서류 보완 요청")

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

    fun business(status: BusinessRegistrationStatus = BusinessRegistrationStatus.REGISTERED): BusinessId =
        BusinessCommandFixture.withoutMerchants(businesses, status = status).businessId

    fun items(receivedOn: LocalDate = today) = ContractIntakeItems(receivedOn, ContractIntakeChannel.PHONE, manager, setOf(DesiredService.CARD_AUTH))

    fun open(id: BusinessId, kind: ContractIntakeKind = ContractIntakeKind.NEW, req: AdminRequest = request(), receivedOn: LocalDate = today) =
        service.open(OpenContractIntakeCommand(req, id, kind, items(receivedOn), null))

    fun move(id: BusinessId, seq: Int, version: Long, target: ContractIntakeStatus, why: BusinessChangeReason? = null, req: AdminRequest = request()) =
        service.move(MoveContractIntakeCommand(req, id, seq, version, target, why))

    fun complete(id: BusinessId, seq: Int, version: Long, start: LocalDate = today, end: LocalDate? = null, req: AdminRequest = request()) =
        service.complete(CompleteContractIntakeCommand(req, id, seq, version, document, BusinessContractTerms(start, end, setOf(ServiceKind.CARD_PG_PAYMENT)), null))

    fun update(id: BusinessId, seq: Int, version: Long, next: ContractIntakeItems, why: BusinessChangeReason? = null, req: AdminRequest = request()) =
        service.updateItems(UpdateContractIntakeItemsCommand(req, id, seq, version, next, why))

    fun intakeRow(id: BusinessId, seq: Int = 1): List<String> = rows(
        "SELECT CONCAT_WS('|', status, kind, COALESCE(target_contract_seq, '-'), received_on, intake_channel, contract_manager, desires_card_auth, desires_card_keyin, version, updated_by) " +
            "FROM contract_intake WHERE business_id = ? AND intake_seq = ?",
        id.value, seq,
    )

    fun rejected(result: AdminCommandResult): MasterRejection = result.shouldBeInstanceOf<AdminCommandResult.Rejected>().rejection
    fun ok(result: AdminCommandResult): String = result.shouldBeInstanceOf<AdminCommandResult.Succeeded>().reference

    fun latest(id: BusinessId) = TransactionTemplate(transactionManager).apply { isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED }
        .execute { contracts.latest(id) }

    /** 영향 없어야 하는 표(outbox·권리 Snapshot·가맹점 권리 revision·사업자 version). */
    fun untouched(id: BusinessId): List<Long> = listOf(
        count("SELECT COUNT(*) FROM outbox_event"), count("SELECT COUNT(*) FROM entitlement_snapshot_issue"),
        count("SELECT COALESCE(SUM(entitlement_revision), 0) FROM merchant"), count("SELECT version FROM business WHERE business_id = ?", id.value),
    )

    /** 이관 모양 행(V21 문장 5와 같은 모양 — 접수 건 순번 1·계약 순번 1·버전 1·범위 카드 PG 결제, `system:migration`). */
    fun migrated(id: BusinessId, at: String = "2026-11-21 03:00:00.000") = runtime { c ->
        listOf(
            "INSERT INTO contract_intake (business_id, intake_seq, status, kind, migrated, received_on, intake_channel, contract_manager, desires_card_auth, desires_card_keyin, " +
                "created_at, created_by, updated_at, updated_by, version) VALUES (?, 1, 'COMPLETED', 'NEW', 1, NULL, NULL, NULL, 0, 0, '$at', 'system:migration', '$at', 'system:migration', 0)",
            "INSERT INTO contract_intake_transition (business_id, intake_seq, transition_seq, from_status, to_status, migrated, reason, document_ref, actor_ref, occurred_at) " +
                "VALUES (?, 1, 1, NULL, 'COMPLETED', 1, NULL, NULL, 'system:migration', '$at')",
            "INSERT INTO business_contract (business_id, contract_seq, origin_intake_seq, migrated, created_at, created_by) VALUES (?, 1, 1, 1, '$at', 'system:migration')",
            "INSERT INTO business_contract_version (business_id, contract_seq, version_no, origin_intake_seq, migrated, starts_at, start_date, end_date, document_ref, reason, " +
                "created_at, created_by) VALUES (?, 1, 1, 1, 1, '$at', NULL, NULL, NULL, NULL, '$at', 'system:migration')",
            "INSERT INTO business_contract_version_service (business_id, contract_seq, version_no, service_kind) VALUES (?, 1, 1, 'CARD_PG_PAYMENT')",
        ).forEach { sql -> c.prepareStatement(sql).use { it.setString(1, id.value); it.executeUpdate() } }
    }

    Given("FLOW-2b commands: success paths") {
        Then("BR-BIZ-314: NEW intake → 계약서 접수 → 계약 완료 creates business contract #1 + version 1; history rows only (no outbox), business version unchanged") {
            clock.set(now)
            val id = business()
            val before = untouched(id)
            ok(open(id)) shouldBe id.value
            ok(move(id, 1, 0, ContractIntakeStatus.DOCUMENT_RECEIVED)) shouldBe id.value
            ok(complete(id, 1, 1, end = today.plusDays(364))) shouldBe id.value
            untouched(id) shouldBe before
            rows("SELECT CONCAT_WS('|', intake_seq, status, kind, IFNULL(target_contract_seq, '-'), version) FROM contract_intake WHERE business_id = ?", id.value) shouldBe
                listOf("1|COMPLETED|NEW|-|2")
            rows(
                "SELECT CONCAT_WS('|', transition_seq, IFNULL(from_status, '-'), to_status, IFNULL(document_ref, '-')) FROM contract_intake_transition WHERE business_id = ? " +
                    "ORDER BY transition_seq",
                id.value,
            ) shouldBe listOf("1|-|CONSULTING|-", "2|CONSULTING|DOCUMENT_RECEIVED|-", "3|DOCUMENT_RECEIVED|COMPLETED|${document.value}")
            val contract = checkNotNull(latest(id))
            listOf(contract.id.contractSeq, contract.versions.map { it.versionNo }, contract.versions.single().endDate, contract.statusAt(now)) shouldBe
                listOf(1, listOf(1), today.plusDays(364), BusinessContractStatus.ACTIVE)
            rows(
                "SELECT CONCAT_WS('|', operation, aggregate_type, aggregate_id, change_type) FROM master_change_history WHERE aggregate_type = 'ContractIntake' AND aggregate_id = ? " +
                    "ORDER BY id",
                id.value,
            ) shouldBe listOf(
                "OpenContractIntake|ContractIntake|${id.value}|ContractIntakeOpened", "MoveContractIntake|ContractIntake|${id.value}|ContractIntakeMoved",
                "CompleteContractIntake|ContractIntake|${id.value}|ContractIntakeCompleted",
            )
        }

        Then("NIT-5 / BR-BIZ-324·326: a V21 migrated contract is read by latest() as ACTIVE; a CHANGE intake completes as version 2 + close of version 1; same-day new version is refused") {
            clock.set(now)
            val id = business()
            migrated(id)
            val read = checkNotNull(latest(id))
            listOf(read.migrated, read.id.contractSeq, read.versions.single().migrated, read.statusAt(now), read.applyingVersionAt(now)?.services) shouldBe
                listOf(true, 1, true, BusinessContractStatus.ACTIVE, setOf(ServiceKind.CARD_PG_PAYMENT))
            rejected(open(id, ContractIntakeKind.NEW)) shouldBe MasterRejection.CONTRACT_INTAKE_KIND_MISMATCH
            ok(open(id, ContractIntakeKind.CHANGE))
            rows("SELECT CONCAT_WS('|', intake_seq, kind, target_contract_seq) FROM contract_intake WHERE business_id = ? AND intake_seq = 2", id.value) shouldBe listOf("2|CHANGE|1")
            ok(move(id, 2, 0, ContractIntakeStatus.DOCUMENT_RECEIVED))
            ok(complete(id, 2, 1, start = today.plusDays(1)))
            val two = checkNotNull(latest(id))
            listOf(two.versions.map { it.versionNo }, two.closes.single().closedAt, two.scheduledVersionAt(now)?.versionNo) shouldBe
                listOf(listOf(1, 2), Instant.parse("2026-11-26T15:00:00Z"), 2)
            // 예정 버전이 있으면 다음 변경 완료는 거절(BR-BIZ-345) — 접수 건은 열린 채 남는다.
            ok(open(id, ContractIntakeKind.CHANGE))
            ok(move(id, 3, 0, ContractIntakeStatus.DOCUMENT_RECEIVED))
            rejected(complete(id, 3, 1, start = today.plusDays(2))) shouldBe MasterRejection.BUSINESS_CONTRACT_SCHEDULED_VERSION_EXISTS
            rows("SELECT status FROM contract_intake WHERE business_id = ? AND intake_seq = 3", id.value) shouldBe listOf("DOCUMENT_RECEIVED")
        }

        Then("BR-BIZ-332: a DRAFT business can complete a contract (the merchant registration gate is a separate check)") {
            clock.set(now)
            val id = business(BusinessRegistrationStatus.DRAFT)
            ok(open(id)); ok(move(id, 1, 0, ContractIntakeStatus.DOCUMENT_RECEIVED)); ok(complete(id, 1, 1))
            checkNotNull(latest(id)).statusAt(now) shouldBe BusinessContractStatus.ACTIVE
        }
    }

    Given("FLOW-2b commands: stored rejections, replay, key reuse") {
        Then("each rejection is stored with a fixed code; the same key replays it; the same key with other content is 409 (IdempotencyKeyReused)") {
            clock.set(now)
            val id = business()
            rejected(open(BusinessId("biz_01JC0000000000000000000000"))) shouldBe MasterRejection.BUSINESS_NOT_FOUND
            rejected(open(id, receivedOn = today.plusDays(1))) shouldBe MasterRejection.CONTRACT_INTAKE_RECEIVED_ON_FUTURE
            rejected(open(id, ContractIntakeKind.CHANGE)) shouldBe MasterRejection.CONTRACT_INTAKE_KIND_MISMATCH
            val key = request()
            ok(open(id, req = key))
            open(id, req = key).shouldBeInstanceOf<AdminCommandResult.Succeeded>().replayed shouldBe true
            open(id, req = key, receivedOn = today.minusDays(1)) shouldBe AdminCommandResult.IdempotencyKeyReused
            rejected(open(id)) shouldBe MasterRejection.CONTRACT_INTAKE_OPEN_EXISTS
            rejected(move(id, 9, 0, ContractIntakeStatus.DOCUMENT_RECEIVED)) shouldBe MasterRejection.CONTRACT_INTAKE_NOT_FOUND
            rejected(move(id, 1, 5, ContractIntakeStatus.DOCUMENT_RECEIVED)) shouldBe MasterRejection.CONTRACT_INTAKE_VERSION_CONFLICT
            rejected(move(id, 1, 0, ContractIntakeStatus.REJECTED)) shouldBe MasterRejection.CONTRACT_INTAKE_REASON_REQUIRED
            rejected(complete(id, 1, 0)) shouldBe MasterRejection.CONTRACT_INTAKE_TRANSITION_NOT_ALLOWED // 상담 중 → 계약 완료(D-FLOW-005 ①)
            ok(move(id, 1, 0, ContractIntakeStatus.DOCUMENT_RECEIVED))
            rejected(complete(id, 1, 1, start = today.minusDays(1))) shouldBe MasterRejection.BUSINESS_CONTRACT_START_RETROACTIVE
            rejected(complete(id, 1, 1, start = today.plusDays(3), end = today.plusDays(2))) shouldBe MasterRejection.BUSINESS_CONTRACT_PERIOD_INVALID
            val stored = request()
            rejected(complete(id, 1, 1, start = today.minusDays(1), req = stored))
            complete(id, 1, 1, start = today.minusDays(1), req = stored).shouldBeInstanceOf<AdminCommandResult.Rejected>().replayed shouldBe true
            ok(move(id, 1, 1, ContractIntakeStatus.REJECTED, reason))
            count("SELECT COUNT(*) FROM business_contract WHERE business_id = ?", id.value) shouldBe 0
            rows("SELECT reason FROM contract_intake_transition WHERE business_id = ? AND to_status = 'REJECTED'", id.value) shouldBe listOf(reason.value)
        }
    }

    Given("Idempotency (리뷰 MAJOR-1): same key + same content = the stored result without new rows; same key + different content = 409") {
        /** 그 사업자의 접수 건·전이 이력·사업자 계약·버전·변경 이력 5표 행 수. */
        fun tables(id: BusinessId): List<Long> = listOf(
            count("SELECT COUNT(*) FROM contract_intake WHERE business_id = ?", id.value),
            count("SELECT COUNT(*) FROM contract_intake_transition WHERE business_id = ?", id.value),
            count("SELECT COUNT(*) FROM business_contract WHERE business_id = ?", id.value),
            count("SELECT COUNT(*) FROM business_contract_version WHERE business_id = ?", id.value),
            count("SELECT COUNT(*) FROM master_change_history WHERE aggregate_type = 'ContractIntake' AND aggregate_id = ?", id.value),
        )

        fun completeWith(
            id: BusinessId,
            req: AdminRequest,
            start: LocalDate = today,
            end: LocalDate? = today.plusDays(30),
            doc: ContractDocumentRef = document,
            services: Set<ServiceKind> = setOf(ServiceKind.CARD_PG_PAYMENT),
        ) = service.complete(CompleteContractIntakeCommand(req, id, 1, 1, doc, BusinessContractTerms(start, end, services), null))

        Then("open / move / complete resend with the same key replay (replayed = true) and leave the 5 tables unchanged; changed fields under the same key are 409") {
            clock.set(now)
            val id = business()
            val openKey = request()
            ok(open(id, req = openKey))
            val afterOpen = tables(id)
            open(id, req = openKey).shouldBeInstanceOf<AdminCommandResult.Succeeded>().replayed shouldBe true
            tables(id) shouldBe afterOpen

            val moveKey = request()
            ok(move(id, 1, 0, ContractIntakeStatus.DOCUMENT_RECEIVED, reason, req = moveKey))
            val afterMove = tables(id)
            move(id, 1, 0, ContractIntakeStatus.DOCUMENT_RECEIVED, reason, req = moveKey).shouldBeInstanceOf<AdminCommandResult.Succeeded>().replayed shouldBe true
            move(id, 1, 0, ContractIntakeStatus.DOCUMENT_RECEIVED, BusinessChangeReason("다른 사유"), req = moveKey) shouldBe AdminCommandResult.IdempotencyKeyReused
            move(id, 1, 0, ContractIntakeStatus.DOCUMENT_RECEIVED, null, req = moveKey) shouldBe AdminCommandResult.IdempotencyKeyReused
            tables(id) shouldBe afterMove

            val completeKey = request()
            ok(completeWith(id, completeKey))
            val afterComplete = tables(id)
            afterComplete shouldBe listOf(1L, 3L, 1L, 1L, 3L)
            completeWith(id, completeKey).shouldBeInstanceOf<AdminCommandResult.Succeeded>().replayed shouldBe true
            mapOf(
                "startDate" to { completeWith(id, completeKey, start = today.plusDays(1)) },
                "documentRef" to { completeWith(id, completeKey, doc = ContractDocumentRef("DOC-2026-11-0002")) },
                "endDate" to { completeWith(id, completeKey, end = null) },
                "services" to { completeWith(id, completeKey, services = emptySet()) },
            ).forEach { (field, call) -> withClue(field) { call() shouldBe AdminCommandResult.IdempotencyKeyReused } }
            tables(id) shouldBe afterComplete
        }
    }

    Given("Transaction Safety and Observability") {
        Then("a history write failure in the completion TX rolls back the intake update, the transition row and the business contract rows") {
            clock.set(now)
            val id = business()
            ok(open(id)); ok(move(id, 1, 0, ContractIntakeStatus.DOCUMENT_RECEIVED))
            faults.after(MasterFaultPoint.CHANGE_LOG_APPEND) { throw IllegalStateException("injected history failure") }
            shouldThrowAny { complete(id, 1, 1) }
            faults.reset()
            rows("SELECT CONCAT_WS('|', status, version) FROM contract_intake WHERE business_id = ?", id.value) shouldBe listOf("DOCUMENT_RECEIVED|1")
            count("SELECT COUNT(*) FROM contract_intake_transition WHERE business_id = ?", id.value) shouldBe 2
            count("SELECT COUNT(*) FROM business_contract WHERE business_id = ?", id.value) shouldBe 0
            count("SELECT COUNT(*) FROM business_contract_version WHERE business_id = ?", id.value) shouldBe 0
        }

        Then("§4B.12: stored rows that break a domain invariant fail the command (no stored rejection) with a value-free ERROR alert log") {
            clock.set(now)
            val id = business()
            migrated(id)
            // 결함 데이터: 정지 없이 정지 해제 사건만 있다(FK·CHECK로는 막지 못하는 행 사이 불변식 — Domain 복원이 거부).
            runtime { c ->
                c.prepareStatement(
                    "INSERT INTO business_contract_event (business_id, contract_seq, event_seq, kind, reason, evidence_ref, actor_ref, occurred_at) " +
                        "VALUES (?, 1, 1, 'RESUME', 'CANARY-RESUME-REASON', NULL, 'admin:x', '2026-11-25 00:00:00.000')",
                ).use { it.setString(1, id.value); it.executeUpdate() }
            }
            val logger = LoggerFactory.getLogger(ContractIntakeCommandService::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().also { it.start() }
            logger.addAppender(appender)
            val key = request()
            try {
                shouldThrowAny { open(id, ContractIntakeKind.CHANGE, req = key) }
            } finally {
                logger.detachAppender(appender)
            }
            val alerts = appender.list.filter { it.level == Level.ERROR && it.formattedMessage.contains("alert=BUSINESS_CONTRACT_LOAD_INVARIANT") }
            alerts.size shouldBe 1
            alerts.single().formattedMessage.contains("CANARY-RESUME-REASON") shouldBe false
            count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key = ? AND result_kind = 'REJECTED'", key.idempotencyKey.value) shouldBe 0
        }
    }

    Given("FLOW-2d 접수 항목 수정(§4A.3 — D-FLOW-007 ④·D-FLOW-009 ①, UPDATE_CONTRACT_INTAKE_ITEMS)") {
        val other = ActorRef("admin:adm_01JCFL0000000000000000OP02")
        val changed = ContractIntakeItems(today, ContractIntakeChannel.WEBSITE, other, setOf(DesiredService.CARD_AUTH, DesiredService.CARD_KEYIN))

        Then("an open intake's channel / manager / desired services change (reason optional): items updated, version + 1, status·kind·target kept, no transition row, one history row, nothing financial") {
            clock.set(now)
            val id = business()
            ok(open(id))
            val before = untouched(id)
            val transitionsBefore = count("SELECT COUNT(*) FROM contract_intake_transition WHERE business_id = ?", id.value)
            val actor = ActorRef("admin:adm_01JCFL0000000000000000OP03")
            ok(update(id, 1, 0, changed, req = request(actor = actor))) shouldBe id.value
            intakeRow(id) shouldBe listOf("CONSULTING|NEW|-|2026-11-26|WEBSITE|${other.value}|1|1|1|${actor.value}")
            count("SELECT COUNT(*) FROM contract_intake_transition WHERE business_id = ?", id.value) shouldBe transitionsBefore
            rows(
                "SELECT CONCAT_WS('|', change_type, JSON_EXTRACT(detail, '$.intakeSeq'), JSON_EXTRACT(detail, '$.version'), JSON_EXTRACT(detail, '$.changedFields'), " +
                    "JSON_CONTAINS_PATH(detail, 'one', '$.reason')) FROM master_change_history WHERE aggregate_type = 'ContractIntake' AND aggregate_id = ? AND change_type = 'ContractIntakeItemsUpdated'",
                id.value,
            ) shouldBe listOf("ContractIntakeItemsUpdated|1|1|[\"intakeChannel\", \"contractManager\", \"desiredServices\"]|0")
            untouched(id) shouldBe before
        }

        Then("D-FLOW-009 ①: changing the received date needs '사유 및 비고' (stored CONTRACT_INTAKE_REASON_REQUIRED, same key replays); with a reason it changes and the reason is in the history") {
            clock.set(now)
            val id = business()
            ok(open(id))
            val earlier = ContractIntakeItems(today.minusDays(3), ContractIntakeChannel.PHONE, manager, setOf(DesiredService.CARD_AUTH))
            val key = request()
            update(id, 1, 0, earlier, req = key) shouldBe AdminCommandResult.Rejected(MasterRejection.CONTRACT_INTAKE_REASON_REQUIRED, null, false)
            update(id, 1, 0, earlier, req = key) shouldBe AdminCommandResult.Rejected(MasterRejection.CONTRACT_INTAKE_REASON_REQUIRED, null, true)
            ok(update(id, 1, 0, earlier, BusinessChangeReason("접수일 정정")))
            intakeRow(id).single().split('|')[3] shouldBe "2026-11-23"
            rows(
                "SELECT JSON_UNQUOTE(JSON_EXTRACT(detail, '$.reason')) FROM master_change_history WHERE aggregate_id = ? AND change_type = 'ContractIntakeItemsUpdated'", id.value,
            ) shouldBe listOf("접수일 정정")
        }

        Then("stored rejections: closed intake → CONTRACT_INTAKE_ITEMS_NOT_EDITABLE, stale version → CONTRACT_INTAKE_VERSION_CONFLICT, unchanged → CONTRACT_INTAKE_ITEMS_UNCHANGED, unknown intake → CONTRACT_INTAKE_NOT_FOUND, future date → CONTRACT_INTAKE_RECEIVED_ON_FUTURE") {
            clock.set(now)
            val id = business()
            ok(open(id))
            rejected(update(id, 1, 5, changed)) shouldBe MasterRejection.CONTRACT_INTAKE_VERSION_CONFLICT
            rejected(update(id, 1, 0, items())) shouldBe MasterRejection.CONTRACT_INTAKE_ITEMS_UNCHANGED
            rejected(update(id, 9, 0, changed)) shouldBe MasterRejection.CONTRACT_INTAKE_NOT_FOUND
            rejected(update(id, 1, 0, ContractIntakeItems(today.plusDays(1), ContractIntakeChannel.PHONE, manager, setOf(DesiredService.CARD_AUTH)), reason)) shouldBe
                MasterRejection.CONTRACT_INTAKE_RECEIVED_ON_FUTURE
            ok(move(id, 1, 0, ContractIntakeStatus.REJECTED, reason))
            rejected(update(id, 1, 1, changed)) shouldBe MasterRejection.CONTRACT_INTAKE_ITEMS_NOT_EDITABLE
            intakeRow(id).single().split('|').let { listOf(it[0], it[4], it[8]) } shouldBe listOf("REJECTED", "PHONE", "1")
        }

        Then("§4A.6 n-4: a stored success replays with the same key after the clock moves on (no time-dependent re-check on resend)") {
            clock.set(now)
            val id = business()
            ok(open(id))
            val key = request()
            val first = update(id, 1, 0, changed, req = key).shouldBeInstanceOf<AdminCommandResult.Succeeded>()
            clock.set(now.plus(Duration.ofDays(2)))
            update(id, 1, 0, changed, req = key) shouldBe first.copy(replayed = true)
            update(id, 1, 0, changed.copy(channel = ContractIntakeChannel.OTHER), req = key) shouldBe AdminCommandResult.IdempotencyKeyReused
        }

        Then("Transaction Safety: a history write failure rolls the items and version back") {
            clock.set(now)
            val id = business()
            ok(open(id))
            val before = intakeRow(id)
            faults.after(MasterFaultPoint.CHANGE_LOG_APPEND) { throw IllegalStateException("injected history failure") }
            shouldThrowAny { update(id, 1, 0, changed) }
            faults.reset()
            intakeRow(id) shouldBe before
        }
    }

    Given("§4B.9 concurrency on real MySQL (two connections, contention forced)") {
        /**
         * 첫 명령은 변경 이력 쓰기 직후(같은 TX — 사업자 행 잠금을 쥔 채) 둘째 명령이 그 잠금을 기다리는 것을 볼 때까지 멈춘다(경합 강제 — 순서대로 실행돼 우연히 통과하지 않게).
         */
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

        Then("two intakes opened for the same business at once → exactly one intake; the other is CONTRACT_INTAKE_OPEN_EXISTS after the lock") {
            clock.set(now)
            val id = business()
            val (a, b) = race({ open(id) }, { open(id) })
            ok(a)
            rejected(b) shouldBe MasterRejection.CONTRACT_INTAKE_OPEN_EXISTS
            count("SELECT COUNT(*) FROM contract_intake WHERE business_id = ?", id.value) shouldBe 1
        }

        Then("the same intake completed by two commands at once → one business contract and one version; the other is CONTRACT_INTAKE_VERSION_CONFLICT") {
            clock.set(now)
            val id = business()
            ok(open(id)); ok(move(id, 1, 0, ContractIntakeStatus.DOCUMENT_RECEIVED))
            val (a, b) = race({ complete(id, 1, 1) }, { complete(id, 1, 1) })
            ok(a)
            rejected(b) shouldBe MasterRejection.CONTRACT_INTAKE_VERSION_CONFLICT
            count("SELECT COUNT(*) FROM business_contract WHERE business_id = ?", id.value) shouldBe 1
            count("SELECT COUNT(*) FROM business_contract_version WHERE business_id = ?", id.value) shouldBe 1
        }

        Then("FLOW-2d two item updates on the same intake with the same expected version → one applies; the other is CONTRACT_INTAKE_VERSION_CONFLICT after the lock") {
            clock.set(now)
            val id = business()
            ok(open(id))
            val first = ContractIntakeItems(today, ContractIntakeChannel.WEBSITE, manager, setOf(DesiredService.CARD_AUTH))
            val second = ContractIntakeItems(today, ContractIntakeChannel.OTHER, manager, setOf(DesiredService.CARD_AUTH))
            val (a, b) = race({ update(id, 1, 0, first) }, { update(id, 1, 0, second) })
            ok(a)
            rejected(b) shouldBe MasterRejection.CONTRACT_INTAKE_VERSION_CONFLICT
            intakeRow(id).single().split('|').let { listOf(it[4], it[8]) } shouldBe listOf("WEBSITE", "1")
        }
    }

    Given("request hygiene") {
        Then("the stored fingerprint is an h1 HMAC (no raw reason / document in admin_request)") {
            clock.set(now)
            val id = business()
            val key = AdminRequest(AdminIdempotencyKey("ci-" + UUID.randomUUID().toString().take(20)), OnboardingFixture.operator, "corr-ci")
            ok(service.open(OpenContractIntakeCommand(key, id, ContractIntakeKind.NEW, items(), reason)))
            val stored = rows("SELECT request_fingerprint FROM admin_request WHERE idempotency_key = ?", key.idempotencyKey.value).single()
            withClue(stored) { stored.startsWith("h1:") shouldBe true }
            rows("SELECT COUNT(*) FROM admin_request WHERE request_fingerprint LIKE ?", "%${reason.value}%") shouldBe listOf("0")
            rows("SELECT reason FROM contract_intake_transition WHERE business_id = ? AND transition_seq = 1", id.value) shouldBe listOf(reason.value)
        }
    }
})

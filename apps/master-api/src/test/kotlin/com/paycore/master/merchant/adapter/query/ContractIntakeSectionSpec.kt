package com.paycore.master.merchant.adapter.query

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.paycore.master.MasterDbAccounts
import com.paycore.master.MasterDbTest
import com.paycore.master.merchant.adapter.persistence.JpaBusinessContractRepository
import com.paycore.master.merchant.adapter.persistence.JpaContractIntakeRepository
import com.paycore.master.merchant.adapter.persistence.JpaContractIntakeTransitionStore
import com.paycore.master.merchant.adapter.web.admin.AdminCallSignature
import com.paycore.master.merchant.adapter.web.admin.BusinessBodies
import com.paycore.master.merchant.adapter.web.admin.BusinessProfileJson
import com.paycore.master.merchant.adapter.web.admin.MasterAdminChannelFixture
import com.paycore.master.merchant.application.JooqStatementRecorder
import com.paycore.master.merchant.application.provided.BusinessProfileQueryUseCase
import com.paycore.master.merchant.application.provided.ContractIntakeSectionView
import com.paycore.master.merchant.application.required.BusinessRepository
import com.paycore.master.merchant.domain.Business
import com.paycore.master.merchant.domain.BusinessFixtures
import com.paycore.master.merchant.domain.BusinessChangeReason
import com.paycore.master.merchant.domain.BusinessContractTerms
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.ContractDocumentRef
import com.paycore.master.merchant.domain.ContractIntake
import com.paycore.master.merchant.domain.ContractIntakeChannel
import com.paycore.master.merchant.domain.ContractIntakeCompletion
import com.paycore.master.merchant.domain.ContractIntakeFixtures
import com.paycore.master.merchant.domain.ContractIntakeKind
import com.paycore.master.merchant.domain.ContractIntakeStatus
import com.paycore.master.merchant.domain.ContractIntakeTransitionInput
import com.paycore.master.merchant.domain.DesiredService
import com.paycore.master.merchant.domain.ServiceKind
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.slf4j.LoggerFactory
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.context.WebApplicationContext
import org.testcontainers.mysql.MySQLContainer
import tools.jackson.databind.json.JsonMapper
import java.sql.Connection
import java.time.Instant
import java.time.LocalDate

/**
 * MERCHANT-FLOW-001 FLOW-1(§6 — jOOQ 상세 조회 + MASTER 조회 endpoint, 실제 MySQL 8.4·실제 서명 채널): 사업자 상세 '계약 진행' 섹션.
 * - Reader: 접수 건 최신부터·이력 순번 오름차순, 이관 건은 접수 항목·근거·담당자 null(R-6 — ADMIN이 '이관'으로 표시), 근거 참조 원문(D-FLOW-004 ④), 한 읽기 전용 REPEATABLE READ TX,
 *   '사유 및 비고'(`reason`) 열을 SELECT하지 않음, 접수 건 없는 사업자 = 빈 목록(FLOW-1 뒤 만든 사업자 — m-12), 없는 사업자 = null.
 * - HTTP: `GET /internal/v1/admin/businesses/{id}/contract-intakes` — 200 모양 = 골든 `contract-intake-section-v2`(FLOW-2b 새 판 — MASTER 생산자 판, ADMIN이 바이트 동일 사본을 둔다),
 *   400 REQUEST_MALFORMED(형식 위반, 값 미반환)·404 BUSINESS_NOT_FOUND·401(서명 없음 — BusinessProfileApiSpec allPaths).
 * - Observability(§4A.14): 섹션 조회 처리 중 MASTER 로그(ROOT appender 캡처)에 근거 참조 원문·'사유 및 비고' 원문이 없다(리뷰 MINOR-3).
 */
@MasterDbTest
class ContractIntakeSectionSpec(
    context: WebApplicationContext,
    private val query: BusinessProfileQueryUseCase,
    private val businesses: BusinessRepository,
    private val intakes: JpaContractIntakeRepository,
    private val transitions: JpaContractIntakeTransitionStore,
    private val contracts: JpaBusinessContractRepository,
    private val transactionManager: PlatformTransactionManager,
    private val recorder: JooqStatementRecorder,
    private val container: MySQLContainer,
    private val clock: com.paycore.master.merchant.application.MutableMasterClock,
) : BehaviorSpec({

    afterAny {
        recorder.recording = false
        clock.reset()
    }

    val mvc: MockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity()).build()
    val json = JsonMapper.builder().build()
    val fixtures = ContractIntakeFixtures
    val operator = fixtures.operator
    val reasonCanary = "캔어리해지사유"
    // FLOW-2a: 근거 참조가 있는 행은 일반 계약 완료 전이뿐이다(해지 전이 삭제 — D-GOV-018 ②ⓑ). 캔어리 근거는 변경 계약 완료의 근거로 둔다.
    val documentCanary = "DEMO-DOC-0001"

    fun <T> tx(block: () -> T): T = TransactionTemplate(transactionManager).execute { block() }

    fun <T> runtime(block: (Connection) -> T): T =
        MasterDbAccounts.connect(container, MasterDbAccounts.MASTER_DATABASE, MasterDbAccounts.MASTER_RUNTIME_USER).use(block)

    fun exec(c: Connection, sql: String, vararg args: Any?) = c.prepareStatement(sql).use { ps -> args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }; ps.executeUpdate() }

    fun storedBusiness(): Business = BusinessFixtures.individualRegistered().also { tx { businesses.insert(it.business, it.profile) } }.business

    /**
     * 기대값 변경(§4B.15 ContractIntakeSectionSpec.kt:101-139 — 강화 + 허용 확대, D-GOV-018 ②ⓑ·D-GOV-014 ①): 이관 건(V19 문장 3·4와 같은 행)은 `COMPLETED` 그대로 +
     * 그 사업자 계약 이관 행(V21 문장 5와 같은 행 — 순번 1·버전 1·범위 카드 PG 결제) → 새 접수 건(JPA adapter 경로) = 계약 있음이라 `CHANGE`. 결과: 접수 건 2개, 이력 2행.
     * 해지 전이 행은 더 이상 만들 수 없다(V21 CHECK 3819).
     */
    fun migratedThenChangeIntake(business: Business) {
        runtime { c ->
            exec(
                c,
                "INSERT INTO contract_intake (business_id, intake_seq, status, kind, migrated, received_on, intake_channel, contract_manager, desires_card_auth, desires_card_keyin, " +
                    "created_at, created_by, updated_at, updated_by, version) VALUES (?, 1, 'COMPLETED', 'NEW', 1, NULL, NULL, NULL, 0, 0, '2026-10-01 00:00:00.000', " +
                    "'system:migration', '2026-10-01 00:00:00.000', 'system:migration', 0)",
                business.id.value,
            )
            exec(
                c,
                "INSERT INTO contract_intake_transition (business_id, intake_seq, transition_seq, from_status, to_status, migrated, reason, document_ref, actor_ref, occurred_at) " +
                    "VALUES (?, 1, 1, NULL, 'COMPLETED', 1, NULL, NULL, 'system:migration', '2026-10-01 00:00:00.000')",
                business.id.value,
            )
            exec(
                c,
                "INSERT INTO business_contract (business_id, contract_seq, origin_intake_seq, migrated, created_at, created_by) " +
                    "VALUES (?, 1, 1, 1, '2026-11-01 00:00:00.000', 'system:migration')",
                business.id.value,
            )
            exec(
                c,
                "INSERT INTO business_contract_version (business_id, contract_seq, version_no, origin_intake_seq, migrated, starts_at, start_date, end_date, document_ref, " +
                    "reason, created_at, created_by) VALUES (?, 1, 1, 1, 1, '2026-11-01 00:00:00.000', NULL, NULL, NULL, NULL, '2026-11-01 00:00:00.000', 'system:migration')",
                business.id.value,
            )
            exec(
                c,
                "INSERT INTO business_contract_version_service (business_id, contract_seq, version_no, service_kind) VALUES (?, 1, 1, 'CARD_PG_PAYMENT')",
                business.id.value,
            )
        }
        tx {
            val locked = checkNotNull(businesses.lock(business.id))
            val items = fixtures.items(LocalDate.parse("2026-11-25"), ContractIntakeChannel.PHONE, desired = setOf(DesiredService.CARD_KEYIN, DesiredService.CARD_AUTH))
            val change = ContractIntake.open(locked, intakes.latest(business.id), contracts.latest(business.id), ContractIntakeKind.CHANGE, items, fixtures.t0, operator)
            intakes.insert(change.intake)
            transitions.append(change.transition)
        }
    }

    /**
     * [migratedThenChangeIntake] 뒤 그 변경 접수 건을 계약서 접수('사유 및 비고' = [reasonCanary] — 선택 사유) → 계약 완료(근거 = [documentCanary], 새 버전 2 + 닫기)까지
     * Domain + JPA adapter 경로로 진행한다(입력 데이터만 — HTTP·로그 시험의 기대값은 그대로: 사유 원문 없음·근거·사유 원문이 로그에 없음).
     */
    fun migratedThenCompletedChange(business: Business) {
        migratedThenChangeIntake(business)
        // Application 순서(FLOW-2b 명령과 같음): 사업자 잠금 → 최신 접수 건·최신 사업자 계약 → Domain 전이 → 저장(접수 건·이력·사업자 계약 새 행).
        fun move(target: ContractIntakeStatus, input: (com.paycore.master.merchant.domain.BusinessContract?) -> ContractIntakeTransitionInput) = tx {
            val locked = checkNotNull(businesses.lock(business.id))
            val latest = checkNotNull(intakes.latest(business.id))
            val change = latest.transitionTo(locked, target, latest.version, input(contracts.latest(business.id)), fixtures.t0.plusSeconds(60), operator)
            intakes.update(change.intake)
            transitions.append(change.transition)
            change.contract?.let(contracts::insert)
        }
        move(ContractIntakeStatus.DOCUMENT_RECEIVED) { ContractIntakeTransitionInput(reason = BusinessChangeReason(reasonCanary)) }
        val terms = BusinessContractTerms(LocalDate.parse("2026-11-26"), null, setOf(ServiceKind.CARD_PG_PAYMENT))
        move(ContractIntakeStatus.COMPLETED) { latest ->
            ContractIntakeTransitionInput(document = ContractDocumentRef(documentCanary), completion = ContractIntakeCompletion(terms, latest))
        }
    }

    fun get(path: String) = mvc.perform(
        MockMvcRequestBuilders.get(path).header(AdminCallSignature.HEADER, MasterAdminChannelFixture.springHeader("GET", path)),
    ).andReturn()

    Given("FLOW-1 the '계약 진행' section reader (jOOQ, CQRS)") {
        Then("intakes newest first with their transitions in order (kind NEW / CHANGE); the migrated intake has no items / document / manager and stays COMPLETED; no reason column is read") {
            val business = storedBusiness()
            migratedThenChangeIntake(business)
            clock.set(Instant.parse("2026-11-26T01:00:00Z")) // 조회 시각(사업자 계약 칸 계산 — 이관 시각 2026-11-01 뒤)
            recorder.statements.clear(); recorder.observations.clear()
            recorder.recording = true
            val view = checkNotNull(query.contractIntakes(business.id))
            recorder.recording = false

            view.intakes.map { listOf(it.intakeSeq, it.status, it.kind, it.migrated, it.receivedOn, it.intakeChannel, it.contractManager, it.desiredServices, it.version) } shouldBe
                listOf(
                    listOf(2, "CONSULTING", "CHANGE", false, LocalDate.parse("2026-11-25"), "PHONE", operator.value, listOf("CARD_AUTH", "CARD_KEYIN"), 0L),
                    listOf(1, "COMPLETED", "NEW", true, null, null, null, emptyList<String>(), 0L),
                )
            view.intakes[1].transitions shouldBe listOf(
                ContractIntakeSectionView.Transition(1, null, "COMPLETED", true, null, "system:migration", Instant.parse("2026-10-01T00:00:00Z")),
            )
            view.intakes[0].transitions.map { listOf(it.transitionSeq, it.fromStatus, it.toStatus) } shouldBe listOf(listOf(1, null, "CONSULTING"))
            // FLOW-2b(§4B.11 1): 사업자 계약 칸 — 이관 계약(V21 모양 행)은 조회 시각에 유효, 적용 중 버전 1(이관), 예정 없음, 연결 가맹점 0.
            checkNotNull(view.businessContract).let {
                listOf(it.contractSeq, it.migrated, it.status, it.applyingVersionNo, it.scheduledVersionNo, it.linkedMerchantCount, it.versions.map { v -> v.migrated })
            } shouldBe listOf(1, true, "ACTIVE", 1, null, 0, listOf(true))

            recorder.observations.isNotEmpty() shouldBe true
            recorder.observations.map { listOf(it.inTransaction, it.readOnly, it.isolation) }.toSet() shouldBe
                setOf(listOf(true, true, java.sql.Connection.TRANSACTION_REPEATABLE_READ))
            recorder.statements.filter { it.contains("`reason`") }.shouldBeEmpty()
            view.toString() shouldNotContain documentCanary
        }

        Then("a business created after the FLOW-1 migration has no intake (empty list — m-12); an unknown business is null") {
            checkNotNull(query.contractIntakes(storedBusiness().id)).intakes.shouldBeEmpty()
            query.contractIntakes(BusinessId("biz_01JC0000000000000000000000")) shouldBe null
        }
    }

    Given("FLOW-1 GET /internal/v1/admin/businesses/{id}/contract-intakes (signed channel)") {
        Then("200 with the section shape; the stored '사유 및 비고' never appears; 400 for a malformed id (no echo); 404 for an unknown id") {
            val business = storedBusiness()
            migratedThenCompletedChange(business)
            val ok = get(BusinessBodies.contractIntakes(business.id.value))
            ok.response.status shouldBe 200
            val body = ok.response.getContentAsString(Charsets.UTF_8)
            body shouldNotContain reasonCanary
            // 양성 대조(NIT-2): 근거 참조 원문은 섹션에 보인다(D-FLOW-004 ④) — 캔어리 데이터가 실제로 저장·조회됐다.
            body shouldContain documentCanary
            val tree = json.readTree(body)
            // 기대값 변경(§4B.15 ADMIN golden 행 — FLOW-2b golden v2, 추가형): 판정 시각·사업자 계약 칸, 접수 건 kind·targetContractSeq.
            tree.propertyNames().toList() shouldBe listOf("businessId", "judgedAt", "businessContract", "intakes")
            val items = tree.get("intakes")
            (0 until items.size()).map { items.get(it).get("intakeSeq").asInt() } shouldBe listOf(2, 1)
            tree.get("intakes").get(1).propertyNames().toList() shouldBe listOf(
                "intakeSeq", "status", "kind", "targetContractSeq", "migrated", "receivedOn", "intakeChannel", "contractManager", "desiredServices", "version", "createdAt",
                "updatedAt", "transitions",
            )
            // 입력 데이터 변경(FLOW-2a — 해지 전이 삭제): 두 번째 이력 행이 있는 건은 변경 접수 건(index 0)이다. 기대 열 목록은 그대로.
            tree.get("intakes").get(0).get("transitions").get(1).propertyNames().toList() shouldBe
                listOf("transitionSeq", "fromStatus", "toStatus", "migrated", "documentRef", "actorRef", "occurredAt")

            listOf("biz_short", "mer_01JC0000000000000000000001").forEach { bad ->
                val r = get(BusinessBodies.contractIntakes(bad))
                listOf(r.response.status, r.response.getContentAsString(Charsets.UTF_8)) shouldBe listOf(400, """{"error":"REQUEST_MALFORMED"}""")
            }
            val nf = get(BusinessBodies.contractIntakes("biz_01JC0000000000000000000000"))
            listOf(nf.response.status, nf.response.getContentAsString(Charsets.UTF_8)) shouldBe listOf(404, """{"error":"BUSINESS_NOT_FOUND"}""")
        }

        Then("§4A.14 Observability: while the section is read (reader and HTTP 200/400/404) no MASTER log event carries the document reference or the stored reason") {
            val business = storedBusiness()
            migratedThenCompletedChange(business)
            val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
            val appender = ListAppender<ILoggingEvent>().also { it.start() }
            root.addAppender(appender)
            try {
                // 캡처 연결 확인(대상 0건 공허 통과 방지): 실행 설정의 수준 그대로 MASTER 패키지 INFO가 잡힌다.
                LoggerFactory.getLogger("com.paycore.master.observability.probe").info("contract-intake-section-log-probe")
                // 양성 대조(NIT-2): 조회 결과에는 근거 캔어리가 있다(로그에만 없어야 한다 — 공허 통과 방지). 사유 캔어리는 DB 전이 이력에 저장돼 있다.
                checkNotNull(query.contractIntakes(business.id)).intakes.flatMap { it.transitions }.mapNotNull { it.documentRef } shouldBe listOf(documentCanary)
                runtime { c -> c.prepareStatement("SELECT COUNT(*) FROM contract_intake_transition WHERE business_id = ? AND reason = ?").use { ps ->
                    ps.setString(1, business.id.value); ps.setString(2, reasonCanary); ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) } } } shouldBe 1
                val ok = get(BusinessBodies.contractIntakes(business.id.value))
                ok.response.status shouldBe 200
                ok.response.getContentAsString(Charsets.UTF_8) shouldContain documentCanary
                get(BusinessBodies.contractIntakes("biz_short")).response.status shouldBe 400
                get(BusinessBodies.contractIntakes("biz_01JC0000000000000000000000")).response.status shouldBe 404
            } finally {
                root.detachAppender(appender)
            }
            val events = appender.list.toList()
            events.any { it.formattedMessage == "contract-intake-section-log-probe" } shouldBe true
            fun texts(e: ILoggingEvent): List<String> = buildList {
                add(e.formattedMessage ?: ""); add(e.message ?: "")
                e.argumentArray?.forEach { add(it.toString()) }
                addAll(e.mdcPropertyMap.values)
                var t = e.throwableProxy
                while (t != null) { add(t.message ?: ""); t = t.cause }
            }
            events.flatMap(::texts).filter { it.contains(documentCanary) || it.contains(reasonCanary) }.shouldBeEmpty()
        }

        // 기대값 변경(§4B.15 ContractIntakeSectionSpec.kt:222-225 — rev 0.4b 배치 2b, ADMIN golden 새 판과 함께): golden v1 → v2(TERMINATED 행 삭제, kind·targetContractSeq·
        // 판정 시각·사업자 계약 칸 추가 — 추가형, 기존 필드 기대값 유지).
        Then("the response generator output for fixed Demo values equals the MASTER golden contract-intake-section-v2 (keys, order, values)") {
            val golden = json.readTree(ContractIntakeSectionSpec::class.java.getResource("/contracts/contract-intake-section-v2.golden.json")!!.readText(Charsets.UTF_8))
            val actor = "admin:adm_01JC00000000000000000000V1"
            val migratedAt = Instant.parse("2026-11-21T03:00:00Z")
            val v2Start = Instant.parse("2026-11-26T15:00:00Z")
            val view = ContractIntakeSectionView(
                "biz_01JC0000000000000000000001",
                listOf(
                    ContractIntakeSectionView.Intake(
                        2, "COMPLETED", "CHANGE", 1, false, LocalDate.parse("2026-11-25"), "PHONE", actor, listOf("CARD_AUTH", "CARD_KEYIN"), 2,
                        Instant.parse("2026-11-25T01:00:00Z"), Instant.parse("2026-11-26T01:00:00Z"),
                        listOf(
                            ContractIntakeSectionView.Transition(1, null, "CONSULTING", false, null, actor, Instant.parse("2026-11-25T01:00:00Z")),
                            ContractIntakeSectionView.Transition(2, "CONSULTING", "DOCUMENT_RECEIVED", false, null, actor, Instant.parse("2026-11-25T02:00:00Z")),
                            ContractIntakeSectionView.Transition(3, "DOCUMENT_RECEIVED", "COMPLETED", false, "DEMO-DOC-0001", actor, Instant.parse("2026-11-26T01:00:00Z")),
                        ),
                    ),
                    ContractIntakeSectionView.Intake(
                        1, "COMPLETED", "NEW", null, true, null, null, null, emptyList(), 0, Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-01T00:00:00Z"),
                        listOf(ContractIntakeSectionView.Transition(1, null, "COMPLETED", true, null, "system:migration", Instant.parse("2026-10-01T00:00:00Z"))),
                    ),
                ),
                judgedAt = Instant.parse("2026-11-26T01:00:00Z"),
                businessContract = ContractIntakeSectionView.BusinessContractView(
                    1, true, "ACTIVE", 1, 2, 2, migratedAt, "system:migration",
                    listOf(
                        ContractIntakeSectionView.Version(1, true, 1, migratedAt, null, null, listOf("CARD_PG_PAYMENT"), null, v2Start, migratedAt, "system:migration"),
                        ContractIntakeSectionView.Version(
                            2, false, 2, v2Start, LocalDate.parse("2026-11-27"), LocalDate.parse("2027-11-26"), listOf("CARD_PG_PAYMENT"), "DEMO-DOC-0001", null,
                            Instant.parse("2026-11-26T01:00:00Z"), actor,
                        ),
                    ),
                    emptyList(),
                ),
            )
            json.writeValueAsString(BusinessProfileJson.contractIntakes(view)) shouldBe json.writeValueAsString(golden)
        }
    }
})

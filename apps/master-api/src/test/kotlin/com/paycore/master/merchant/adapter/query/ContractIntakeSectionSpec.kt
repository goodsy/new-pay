package com.paycore.master.merchant.adapter.query

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.paycore.master.MasterDbAccounts
import com.paycore.master.MasterDbTest
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
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.ContractIntake
import com.paycore.master.merchant.domain.ContractIntakeChannel
import com.paycore.master.merchant.domain.ContractIntakeFixtures
import com.paycore.master.merchant.domain.DesiredService
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
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
 * - HTTP: `GET /internal/v1/admin/businesses/{id}/contract-intakes` — 200 모양 = 골든 `contract-intake-section-v1`(MASTER 생산자 판 — ADMIN 사본은 ADMIN 섹션 작업이 더한다),
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
    private val transactionManager: PlatformTransactionManager,
    private val recorder: JooqStatementRecorder,
    private val container: MySQLContainer,
) : BehaviorSpec({

    afterAny { recorder.recording = false }

    val mvc: MockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity()).build()
    val json = JsonMapper.builder().build()
    val fixtures = ContractIntakeFixtures
    val operator = fixtures.operator
    val reasonCanary = "캔어리해지사유"

    fun <T> tx(block: () -> T): T = TransactionTemplate(transactionManager).execute { block() }

    fun <T> runtime(block: (Connection) -> T): T =
        MasterDbAccounts.connect(container, MasterDbAccounts.MASTER_DATABASE, MasterDbAccounts.MASTER_RUNTIME_USER).use(block)

    fun exec(c: Connection, sql: String, vararg args: Any?) = c.prepareStatement(sql).use { ps -> args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }; ps.executeUpdate() }

    fun storedBusiness(): Business = BusinessFixtures.individualRegistered().also { tx { businesses.insert(it.business, it.profile) } }.business

    /**
     * 이관 건(V19 문장 3·4와 같은 행) → 해지(사유·근거 — 해지 명령은 FLOW-2라 SQL로) → 새 상담 중 건(FLOW-1 JPA adapter 경로). 결과: 접수 건 2개, 이력 3행.
     */
    fun migratedTerminatedThenConsulting(business: Business) {
        runtime { c ->
            exec(
                c,
                "INSERT INTO contract_intake (business_id, intake_seq, status, migrated, received_on, intake_channel, contract_manager, desires_card_auth, desires_card_keyin, " +
                    "created_at, created_by, updated_at, updated_by, version) VALUES (?, 1, 'COMPLETED', 1, NULL, NULL, NULL, 0, 0, '2026-10-01 00:00:00.000', 'system:migration', " +
                    "'2026-10-01 00:00:00.000', 'system:migration', 0)",
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
                "UPDATE contract_intake SET status = 'TERMINATED', version = 1, updated_at = '2026-11-24 01:00:00.000', updated_by = ? WHERE business_id = ? AND intake_seq = 1",
                operator.value, business.id.value,
            )
            exec(
                c,
                "INSERT INTO contract_intake_transition (business_id, intake_seq, transition_seq, from_status, to_status, migrated, reason, document_ref, actor_ref, occurred_at) " +
                    "VALUES (?, 1, 2, 'COMPLETED', 'TERMINATED', 0, ?, 'DEMO-TERM-0001', ?, '2026-11-24 01:00:00.000')",
                business.id.value, reasonCanary, operator.value,
            )
        }
        tx {
            val locked = checkNotNull(businesses.lock(business.id))
            val items = fixtures.items(LocalDate.parse("2026-11-25"), ContractIntakeChannel.PHONE, desired = setOf(DesiredService.CARD_KEYIN, DesiredService.CARD_AUTH))
            val change = ContractIntake.open(locked, intakes.latest(business.id), items, fixtures.t0, operator)
            intakes.insert(change.intake)
            transitions.append(change.transition)
        }
    }

    fun get(path: String) = mvc.perform(
        MockMvcRequestBuilders.get(path).header(AdminCallSignature.HEADER, MasterAdminChannelFixture.springHeader("GET", path)),
    ).andReturn()

    Given("FLOW-1 the '계약 진행' section reader (jOOQ, CQRS)") {
        Then("intakes newest first with their transitions in order; the migrated intake has no items / document / manager; the terminate document is shown; no reason column is read") {
            val business = storedBusiness()
            migratedTerminatedThenConsulting(business)
            recorder.statements.clear(); recorder.observations.clear()
            recorder.recording = true
            val view = checkNotNull(query.contractIntakes(business.id))
            recorder.recording = false

            view.intakes.map { listOf(it.intakeSeq, it.status, it.migrated, it.receivedOn, it.intakeChannel, it.contractManager, it.desiredServices, it.version) } shouldBe listOf(
                listOf(2, "CONSULTING", false, LocalDate.parse("2026-11-25"), "PHONE", operator.value, listOf("CARD_AUTH", "CARD_KEYIN"), 0L),
                listOf(1, "TERMINATED", true, null, null, null, emptyList<String>(), 1L),
            )
            view.intakes[1].transitions shouldBe listOf(
                ContractIntakeSectionView.Transition(1, null, "COMPLETED", true, null, "system:migration", Instant.parse("2026-10-01T00:00:00Z")),
                ContractIntakeSectionView.Transition(2, "COMPLETED", "TERMINATED", false, "DEMO-TERM-0001", operator.value, Instant.parse("2026-11-24T01:00:00Z")),
            )
            view.intakes[0].transitions.map { listOf(it.transitionSeq, it.fromStatus, it.toStatus) } shouldBe listOf(listOf(1, null, "CONSULTING"))

            recorder.observations.isNotEmpty() shouldBe true
            recorder.observations.map { listOf(it.inTransaction, it.readOnly, it.isolation) }.toSet() shouldBe
                setOf(listOf(true, true, java.sql.Connection.TRANSACTION_REPEATABLE_READ))
            recorder.statements.filter { it.contains("`reason`") }.shouldBeEmpty()
            view.toString() shouldNotContain "DEMO-TERM-0001"
        }

        Then("a business created after the FLOW-1 migration has no intake (empty list — m-12); an unknown business is null") {
            checkNotNull(query.contractIntakes(storedBusiness().id)).intakes.shouldBeEmpty()
            query.contractIntakes(BusinessId("biz_01JC0000000000000000000000")) shouldBe null
        }
    }

    Given("FLOW-1 GET /internal/v1/admin/businesses/{id}/contract-intakes (signed channel)") {
        Then("200 with the section shape; the stored '사유 및 비고' never appears; 400 for a malformed id (no echo); 404 for an unknown id") {
            val business = storedBusiness()
            migratedTerminatedThenConsulting(business)
            val ok = get(BusinessBodies.contractIntakes(business.id.value))
            ok.response.status shouldBe 200
            val body = ok.response.getContentAsString(Charsets.UTF_8)
            body shouldNotContain reasonCanary
            val tree = json.readTree(body)
            tree.propertyNames().toList() shouldBe listOf("businessId", "intakes")
            val items = tree.get("intakes")
            (0 until items.size()).map { items.get(it).get("intakeSeq").asInt() } shouldBe listOf(2, 1)
            tree.get("intakes").get(1).propertyNames().toList() shouldBe listOf(
                "intakeSeq", "status", "migrated", "receivedOn", "intakeChannel", "contractManager", "desiredServices", "version", "createdAt", "updatedAt", "transitions",
            )
            tree.get("intakes").get(1).get("transitions").get(1).propertyNames().toList() shouldBe
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
            migratedTerminatedThenConsulting(business)
            val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
            val appender = ListAppender<ILoggingEvent>().also { it.start() }
            root.addAppender(appender)
            try {
                // 캡처 연결 확인(대상 0건 공허 통과 방지): 실행 설정의 수준 그대로 MASTER 패키지 INFO가 잡힌다.
                LoggerFactory.getLogger("com.paycore.master.observability.probe").info("contract-intake-section-log-probe")
                checkNotNull(query.contractIntakes(business.id))
                get(BusinessBodies.contractIntakes(business.id.value)).response.status shouldBe 200
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
            events.flatMap(::texts).filter { it.contains("DEMO-TERM-0001") || it.contains(reasonCanary) }.shouldBeEmpty()
        }

        Then("the response generator output for fixed Demo values equals the MASTER golden contract-intake-section-v1 (keys, order, values)") {
            val golden = json.readTree(ContractIntakeSectionSpec::class.java.getResource("/contracts/contract-intake-section-v1.golden.json")!!.readText(Charsets.UTF_8))
            val actor = "admin:adm_01JC00000000000000000000V1"
            val view = ContractIntakeSectionView(
                "biz_01JC0000000000000000000001",
                listOf(
                    ContractIntakeSectionView.Intake(
                        2, "CONSULTING", false, LocalDate.parse("2026-11-25"), "PHONE", actor, listOf("CARD_AUTH", "CARD_KEYIN"), 0,
                        Instant.parse("2026-11-25T01:00:00Z"), Instant.parse("2026-11-25T01:00:00Z"),
                        listOf(ContractIntakeSectionView.Transition(1, null, "CONSULTING", false, null, actor, Instant.parse("2026-11-25T01:00:00Z"))),
                    ),
                    ContractIntakeSectionView.Intake(
                        1, "TERMINATED", true, null, null, null, emptyList(), 1, Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-11-24T01:00:00Z"),
                        listOf(
                            ContractIntakeSectionView.Transition(1, null, "COMPLETED", true, null, "system:migration", Instant.parse("2026-10-01T00:00:00Z")),
                            ContractIntakeSectionView.Transition(2, "COMPLETED", "TERMINATED", false, "DEMO-TERM-0001", actor, Instant.parse("2026-11-24T01:00:00Z")),
                        ),
                    ),
                ),
            )
            json.writeValueAsString(BusinessProfileJson.contractIntakes(view)) shouldBe json.writeValueAsString(golden)
        }
    }
})

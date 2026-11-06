package com.paycore.admin.financialops

import com.paycore.admin.account.application.provided.AdminRequestContext
import com.paycore.admin.account.application.provided.AdminSessionPolicy
import com.paycore.admin.account.application.provided.AdminSessionQuery
import com.paycore.admin.account.application.provided.AdminSessionView
import com.paycore.admin.audit.application.provided.AdminAuditRecorder
import com.paycore.admin.audit.application.provided.AuditRecord
import com.paycore.admin.financialops.application.AcquisitionLookupService
import com.paycore.admin.financialops.application.provided.AcquisitionFileCursor
import com.paycore.admin.financialops.application.provided.AcquisitionFileSearchCriteria
import com.paycore.admin.financialops.application.provided.AcquisitionLookupResult
import com.paycore.admin.financialops.application.provided.AcquisitionRecordSearchCriteria
import com.paycore.admin.financialops.application.required.FinancialCallResult
import com.paycore.admin.financialops.application.required.FinancialOpsGateway
import com.paycore.admin.financialops.application.required.FinancialOpsLimits
import com.paycore.admin.financialops.application.required.FinancialOpsMetrics
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Duration

/**
 * 매입 현황 조회 Use Case 판정·감사 표(ACQ-VIEW-001 §4.6 — 대역 Port, Spring 없음): 역할 행렬(D-ACQV-001: VIEW_MASKED 4역할 — UNMASK 단독·역할 없음·비활성 거부),
 * **파일 상세·레코드 쪽만 감사**(`ACQUISITION_FILE_VIEWED` — OK·NOT_FOUND, 반환 전, FINANCIAL 불가 시 감사 없음), 목록 감사 없음, 감사 실패 → AUDIT_UNAVAILABLE·결과 미반환,
 * 형식·모드·기간 위반은 FINANCIAL 호출 전 거부, 속도 제한, FINANCIAL 호출은 TX 밖·감사는 TX 안.
 */
class AcquisitionLookupServiceSpec : BehaviorSpec({

    val fileId = "ACQ.SFTPV1.TEST.20261020.001"
    val ctx = AdminRequestContext("corr-1", "127.0.0.1")
    val period = AcquisitionFileSearchCriteria(businessDateFrom = "2026-10-20", businessDateTo = "2026-10-20")

    class Fixture(roles: Set<String> = setOf("VIEWER"), status: String = "ACTIVE", lookupPerMinute: Int = 100) {
        val audits = mutableListOf<AuditRecord>()
        var auditFails = false
        val calls = mutableListOf<String>()
        val callInTx = mutableListOf<Boolean>()
        val auditInTx = mutableListOf<Boolean>()
        var rateLimited = 0
        var reply: FinancialCallResult = FinancialCallResult.Found(mapOf("items" to emptyList<Any>(), "nextCursor" to null))
        val view = AdminSessionView("adm_01JC00000000000000000000V1", "Viewer", status, 1, 1, roles)
        val tx = object : AbstractPlatformTransactionManager() {
            override fun doGetTransaction(): Any = Any()
            override fun doBegin(transaction: Any, definition: TransactionDefinition) = Unit
            override fun doCommit(status: DefaultTransactionStatus) = Unit
            override fun doRollback(status: DefaultTransactionStatus) = Unit
        }

        private fun answer(call: String): FinancialCallResult {
            calls += call
            callInTx += TransactionSynchronizationManager.isActualTransactionActive()
            return reply
        }

        val service = AcquisitionLookupService(
            tx,
            object : AdminSessionQuery {
                override fun current(actorId: String) = view.takeIf { it.actorId == actorId }
                override fun policy() = AdminSessionPolicy(Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofMinutes(1))
            },
            object : FinancialOpsGateway {
                override fun search(criteria: AcquisitionFileSearchCriteria) = answer("search")
                override fun detail(fileId: String) = answer("detail:$fileId")
                override fun records(fileId: String, criteria: AcquisitionRecordSearchCriteria) = answer("records:$fileId")
            },
            object : AdminAuditRecorder {
                override fun record(record: AuditRecord) {
                    auditInTx += TransactionSynchronizationManager.isActualTransactionActive()
                    if (auditFails) throw IllegalStateException("injected audit failure")
                    audits += record
                }
            },
            object : FinancialOpsLimits {
                override val lookupPerMinute = lookupPerMinute
            },
            object : FinancialOpsMetrics {
                override fun rateLimited() {
                    rateLimited++
                }
            },
        )
        val actor get() = view.actorId

        fun auditRows() = audits.map { listOf(it.action, it.resultCode, it.targetRef, it.actorId, it.correlationId) }
    }

    Given("the view roles (D-ACQV-001)") {
        Then("VIEWER, OPERATOR, APPROVER and SECURITY_ADMIN may search, view and page records") {
            listOf("VIEWER", "OPERATOR", "APPROVER", "SECURITY_ADMIN").forEach { role ->
                val f = Fixture(setOf(role))
                f.service.search(f.actor, period).shouldBeInstanceOf<AcquisitionLookupResult.Found>()
                f.service.detail(f.actor, fileId, ctx).shouldBeInstanceOf<AcquisitionLookupResult.Found>()
                f.service.records(f.actor, fileId, AcquisitionRecordSearchCriteria(), ctx).shouldBeInstanceOf<AcquisitionLookupResult.Found>()
                f.calls shouldBe listOf("search", "detail:$fileId", "records:$fileId")
            }
        }
        Then("UNMASK-only, no role, an inactive account and an unknown actor are forbidden without a FINANCIAL call or audit") {
            listOf(Fixture(setOf("UNMASK")), Fixture(emptySet()), Fixture(setOf("VIEWER"), status = "SUSPENDED")).forEach { f ->
                f.service.search(f.actor, period) shouldBe AcquisitionLookupResult.Forbidden
                f.service.detail(f.actor, fileId, ctx) shouldBe AcquisitionLookupResult.Forbidden
                f.service.records(f.actor, fileId, AcquisitionRecordSearchCriteria(), ctx) shouldBe AcquisitionLookupResult.Forbidden
                f.service.detail("adm_unknown", fileId, ctx) shouldBe AcquisitionLookupResult.Forbidden
                f.calls.shouldBeEmpty()
                f.audits.shouldBeEmpty()
            }
        }
    }

    Given("audit (D-ACQV-001: file detail and record pages only, written before the result is returned)") {
        Then("search is never audited; detail and records answered FOUND are audited OK with the file as target, inside a TX, with FINANCIAL called outside one") {
            val f = Fixture()
            f.service.search(f.actor, period).shouldBeInstanceOf<AcquisitionLookupResult.Found>()
            f.audits.shouldBeEmpty()
            f.service.detail(f.actor, fileId, ctx).shouldBeInstanceOf<AcquisitionLookupResult.Found>()
            f.service.records(f.actor, fileId, AcquisitionRecordSearchCriteria(status = "UNKNOWN"), ctx).shouldBeInstanceOf<AcquisitionLookupResult.Found>()
            f.auditRows() shouldBe listOf(
                listOf("ACQUISITION_FILE_VIEWED", "OK", fileId, f.actor, "corr-1"),
                listOf("ACQUISITION_FILE_VIEWED", "OK", fileId, f.actor, "corr-1"),
            )
            f.audits.map { it.detail } shouldBe listOf(null, null)
            f.auditInTx shouldBe listOf(true, true)
            f.callInTx shouldBe listOf(false, false, false)
        }
        Then("NOT_FOUND is audited NOT_FOUND; FINANCIAL unavailable is not audited (nothing was seen)") {
            val f = Fixture().apply { reply = FinancialCallResult.NotFound }
            f.service.detail(f.actor, fileId, ctx) shouldBe AcquisitionLookupResult.NotFound
            f.service.records(f.actor, fileId, AcquisitionRecordSearchCriteria(), ctx) shouldBe AcquisitionLookupResult.NotFound
            f.auditRows().map { it.take(3) } shouldBe listOf(listOf("ACQUISITION_FILE_VIEWED", "NOT_FOUND", fileId), listOf("ACQUISITION_FILE_VIEWED", "NOT_FOUND", fileId))
            val down = Fixture().apply { reply = FinancialCallResult.Unavailable("TIMEOUT") }
            down.service.detail(down.actor, fileId, ctx) shouldBe AcquisitionLookupResult.Unavailable("TIMEOUT")
            down.service.records(down.actor, fileId, AcquisitionRecordSearchCriteria(), ctx) shouldBe AcquisitionLookupResult.Unavailable("TIMEOUT")
            down.service.search(down.actor, period) shouldBe AcquisitionLookupResult.Unavailable("TIMEOUT")
            down.audits.shouldBeEmpty()
        }
        Then("an audit failure withholds the result (AUDIT_UNAVAILABLE) for detail and records") {
            val f = Fixture().apply { auditFails = true }
            f.service.detail(f.actor, fileId, ctx) shouldBe AcquisitionLookupResult.AuditUnavailable
            f.service.records(f.actor, fileId, AcquisitionRecordSearchCriteria(), ctx) shouldBe AcquisitionLookupResult.AuditUnavailable
            f.service.search(f.actor, period).shouldBeInstanceOf<AcquisitionLookupResult.Found>()
        }
        Then("a NotFound answer to a search is not a page (Unavailable)") {
            val f = Fixture().apply { reply = FinancialCallResult.NotFound }
            f.service.search(f.actor, period).shouldBeInstanceOf<AcquisitionLookupResult.Unavailable>()
        }
    }

    Given("input checked before FINANCIAL is called (same rules as the FINANCIAL parser)") {
        Then("malformed search criteria are InvalidInput without a call or audit") {
            val f = Fixture()
            mapOf(
                "no period" to AcquisitionFileSearchCriteria(),
                "one end" to AcquisitionFileSearchCriteria(businessDateFrom = "2026-10-20"),
                "32 days" to AcquisitionFileSearchCriteria(businessDateFrom = "2026-09-19", businessDateTo = "2026-10-20"),
                "reversed" to AcquisitionFileSearchCriteria(businessDateFrom = "2026-10-21", businessDateTo = "2026-10-20"),
                "impossible date" to AcquisitionFileSearchCriteria(businessDateFrom = "2026-02-30", businessDateTo = "2026-03-01"),
                "file id with a period" to period.copy(fileId = fileId),
                "file id grammar" to AcquisitionFileSearchCriteria(fileId = "ACQ.sftpv1.TEST.20261020.001"),
                "environment" to period.copy(environment = "test"),
                "acquirer" to period.copy(acquirerCode = "sim acq"),
                "transmission state" to period.copy(transmissionState = "SENT"),
                "receipt state" to period.copy(receiptState = "TRANSMITTED"),
                "result state" to period.copy(resultState = "UNKNOWN"),
                "needsAttention false" to period.copy(needsAttention = false),
                "limit 0" to period.copy(limit = 0),
                "limit 51" to period.copy(limit = 51),
                "cursor date" to period.copy(after = AcquisitionFileCursor("2026-13-01", fileId)),
                "cursor file id" to period.copy(after = AcquisitionFileCursor("2026-10-20", "x")),
            ).forEach { (name, criteria) -> withClue(name) { f.service.search(f.actor, criteria) shouldBe AcquisitionLookupResult.InvalidInput } }
            f.service.search(f.actor, AcquisitionFileSearchCriteria(businessDateFrom = "2026-09-20", businessDateTo = "2026-10-20")).shouldBeInstanceOf<AcquisitionLookupResult.Found>()
            f.service.search(f.actor, AcquisitionFileSearchCriteria(fileId = fileId)).shouldBeInstanceOf<AcquisitionLookupResult.Found>()
            f.calls shouldBe listOf("search", "search")
        }
        Then("a malformed file id or record criteria are InvalidInput without a call or audit") {
            val f = Fixture()
            f.service.detail(f.actor, "../x", ctx) shouldBe AcquisitionLookupResult.InvalidInput
            f.service.records(f.actor, "ACQ.SFTPV1.TEST.20261020.1", AcquisitionRecordSearchCriteria(), ctx) shouldBe AcquisitionLookupResult.InvalidInput
            listOf(AcquisitionRecordSearchCriteria(status = "PENDING"), AcquisitionRecordSearchCriteria(afterSeq = -1), AcquisitionRecordSearchCriteria(limit = 51)).forEach {
                f.service.records(f.actor, fileId, it, ctx) shouldBe AcquisitionLookupResult.InvalidInput
            }
            f.calls.shouldBeEmpty()
            f.audits.shouldBeEmpty()
        }
    }

    Given("a body that did not parse (review MINOR-2 — the role is judged before the input, as TransactionLookupService.rejectMalformedSearch)") {
        Then("a viewer gets InvalidInput; UNMASK-only, no role, an inactive account and an unknown actor get Forbidden; never a call, an audit or a rate-limit slot") {
            val viewer = Fixture(lookupPerMinute = 1)
            viewer.service.rejectMalformedSearch(viewer.actor) shouldBe AcquisitionLookupResult.InvalidInput
            viewer.service.rejectMalformedSearch(viewer.actor) shouldBe AcquisitionLookupResult.InvalidInput
            viewer.rateLimited shouldBe 0
            listOf(Fixture(setOf("UNMASK")), Fixture(emptySet()), Fixture(setOf("VIEWER"), status = "SUSPENDED")).forEach { f ->
                f.service.rejectMalformedSearch(f.actor) shouldBe AcquisitionLookupResult.Forbidden
                f.service.rejectMalformedSearch("adm_unknown") shouldBe AcquisitionLookupResult.Forbidden
                f.calls.shouldBeEmpty()
                f.audits.shouldBeEmpty()
            }
            viewer.calls.shouldBeEmpty()
            viewer.audits.shouldBeEmpty()
        }
    }

    Given("the per-actor rate limit") {
        Then("over the limit is RateLimited without a call, counted") {
            val f = Fixture(lookupPerMinute = 2)
            f.service.search(f.actor, period).shouldBeInstanceOf<AcquisitionLookupResult.Found>()
            f.service.detail(f.actor, fileId, ctx).shouldBeInstanceOf<AcquisitionLookupResult.Found>()
            f.service.records(f.actor, fileId, AcquisitionRecordSearchCriteria(), ctx) shouldBe AcquisitionLookupResult.RateLimited
            f.calls.size shouldBe 2
            f.rateLimited shouldBe 1
        }
    }
})

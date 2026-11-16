package com.paycore.admin.paymentops

import com.paycore.admin.account.application.provided.AdminRequestContext
import com.paycore.admin.account.application.provided.AdminSessionPolicy
import com.paycore.admin.account.application.provided.AdminSessionQuery
import com.paycore.admin.account.application.provided.AdminSessionView
import com.paycore.admin.audit.application.provided.AdminAuditRecorder
import com.paycore.admin.audit.application.provided.AuditRecord
import com.paycore.admin.merchantops.application.provided.MerchantLabelClient
import com.paycore.admin.merchantops.application.provided.MerchantLabelResult
import com.paycore.admin.paymentops.application.TransactionLookupService
import com.paycore.admin.paymentops.application.provided.TransactionLookupResult
import com.paycore.admin.paymentops.application.provided.TransactionSearchCriteria
import com.paycore.admin.paymentops.application.required.PaymentOpsTimings
import com.paycore.admin.paymentops.application.required.PaymentReadGateway
import com.paycore.admin.paymentops.application.required.PaymentReadResult
import com.paycore.admin.paymentops.application.required.TransactionOpsMetrics
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
 * 거래 조회 Use Case 판정·감사 표(TXN-OPS-001 §4.1·§5·AC 7·8 — 대역 Port, Spring 없음): 조회 역할 4개(I-1), 형식·모드 규칙은 PAYMENT 호출 전(기간 필수·31일 Demo 상한·역전·결제 ID
 * 단독·주문번호는 MID 필수), 속도 제한, **상세만 감사**(`PAYMENT_VIEWED` OK·NOT_FOUND, targetRef = paymentId, PAYMENT 불가는 감사 없음), 감사가 끝난 뒤에만 결과 반환·감사 실패 →
 * AUDIT_UNAVAILABLE, PAYMENT 호출은 TX 밖·감사는 TX 안.
 */
class TransactionLookupServiceSpec : BehaviorSpec({

    val pay = "pay_01JC0000000000000000000001"
    val ctx = AdminRequestContext("corr-1", "127.0.0.1")
    val period = TransactionSearchCriteria(createdFrom = "2026-11-01T00:00:00.000Z", createdTo = "2026-11-02T00:00:00.000Z")

    class Fixture(roles: Set<String> = setOf("VIEWER"), status: String = "ACTIVE", perMinute: Int = 100) {
        val audits = mutableListOf<AuditRecord>()
        var auditFails = false
        val events = mutableListOf<String>()
        val callInTx = mutableListOf<Boolean>()
        val auditInTx = mutableListOf<Boolean>()
        var rateLimited = 0
        var detailReply: PaymentReadResult = PaymentReadResult.Found(mapOf("payment" to mapOf("paymentId" to "x")))
        val view = AdminSessionView("adm_01JC00000000000000000000V1", "Viewer", status, 1, 1, roles)
        val tx = object : AbstractPlatformTransactionManager() {
            override fun doGetTransaction(): Any = Any()
            override fun doBegin(transaction: Any, definition: TransactionDefinition) = Unit
            override fun doCommit(status: DefaultTransactionStatus) = Unit
            override fun doRollback(status: DefaultTransactionStatus) = Unit
        }
        val service = TransactionLookupService(
            tx,
            object : AdminSessionQuery {
                override fun current(actorId: String) = view.takeIf { it.actorId == actorId }
                override fun policy() = AdminSessionPolicy(Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofMinutes(1))
            },
            object : PaymentReadGateway {
                override fun searchPayments(criteria: TransactionSearchCriteria): PaymentReadResult {
                    events += "search"
                    callInTx += TransactionSynchronizationManager.isActualTransactionActive()
                    return PaymentReadResult.Found(mapOf("items" to emptyList<Any>(), "nextCursor" to null))
                }

                override fun paymentDetail(paymentId: String): PaymentReadResult {
                    events += "detail"
                    callInTx += TransactionSynchronizationManager.isActualTransactionActive()
                    return detailReply
                }
            },
            // TXN-LIST-ENRICH-001 E-3: 라벨 client(이 시험의 결제 문서에는 MID 형식 값이 없어 부르지 않는다 — 부르면 기록된다).
            object : MerchantLabelClient {
                override fun labels(merchantAccountIds: List<String>): MerchantLabelResult {
                    events += "labels"
                    return MerchantLabelResult.Found(emptyMap())
                }
            },
            object : AdminAuditRecorder {
                override fun record(record: AuditRecord) {
                    events += "audit"
                    auditInTx += TransactionSynchronizationManager.isActualTransactionActive()
                    if (auditFails) throw IllegalStateException("injected audit failure")
                    audits += record
                }
            },
            object : PaymentOpsTimings {
                override val lookupPerMinute = perMinute
                override val executionWindow: Duration = Duration.ofHours(24)
                override val backoffInitial: Duration = Duration.ofSeconds(30)
                override val backoffMax: Duration = Duration.ofMinutes(1)
                override val staleAfter: Duration = Duration.ofSeconds(33)
                override val deadlineSkew: Duration = Duration.ofMinutes(5)
                override val recoveryBatchSize = 50
                override val decisionStableDelay: Duration = Duration.ofMinutes(2)
                override val reconciliationMaxPages = 10
                override val reconciliationFullScanInterval: Duration = Duration.ofDays(1)
            },
            object : TransactionOpsMetrics {
                override fun rateLimited() {
                    rateLimited++
                }

                override fun merchantLabels(result: String) = Unit
            },
        )
        val actor get() = view.actorId

        fun auditRows() = audits.map { listOf(it.action, it.resultCode, it.detail, it.targetRef, it.actorId, it.correlationId) }
    }

    Given("the view roles (I-1)") {
        Then("VIEWER, OPERATOR, APPROVER and SECURITY_ADMIN may search and view; UNMASK-only, no role and inactive accounts are forbidden without a PAYMENT call or audit") {
            listOf("VIEWER", "OPERATOR", "APPROVER", "SECURITY_ADMIN").forEach { role ->
                val f = Fixture(setOf(role))
                f.service.search(f.actor, period).shouldBeInstanceOf<TransactionLookupResult.Found>()
                f.service.detail(f.actor, pay, ctx).shouldBeInstanceOf<TransactionLookupResult.Found>()
                f.events shouldBe listOf("search", "detail", "audit")
            }
            listOf(Fixture(setOf("UNMASK")), Fixture(emptySet()), Fixture(setOf("OPERATOR"), status = "SUSPENDED")).forEach { f ->
                f.service.search(f.actor, period) shouldBe TransactionLookupResult.Forbidden
                f.service.detail(f.actor, pay, ctx) shouldBe TransactionLookupResult.Forbidden
                f.service.rejectMalformedSearch(f.actor) shouldBe TransactionLookupResult.Forbidden
                f.events.shouldBeEmpty()
            }
            Fixture().let { f -> f.service.rejectMalformedSearch(f.actor) shouldBe TransactionLookupResult.InvalidInput }
        }
    }

    Given("search criteria") {
        Then("the period is required (except the exact paymentId and order lookups), at most 31 days and not inverted; values are checked before PAYMENT") {
            val f = Fixture()
            listOf(
                TransactionSearchCriteria(),
                TransactionSearchCriteria(environment = "TEST"),
                period.copy(createdTo = null),
                period.copy(createdFrom = "2026-11-02T00:00:00.000Z", createdTo = "2026-11-01T00:00:00.000Z"),
                period.copy(createdTo = "2026-11-01T00:00:00.000Z"),
                period.copy(createdTo = "2026-12-02T00:00:00.001Z"),
                period.copy(createdFrom = "2026-11-01"),
                period.copy(createdFrom = "2026-11-01T00:00:00Z"),
                period.copy(environment = "test"),
                period.copy(status = "CANCELLED"),
                period.copy(merchantAccountId = "mid 1"),
                period.copy(limit = 0), period.copy(limit = 51),
                period.copy(afterCreatedAt = "2026-11-01T12:00:00.000Z"),
                period.copy(afterCreatedAt = "2026-11-01T12:00:00.000Z", afterPaymentId = "pay_x"),
                TransactionSearchCriteria(paymentId = "pay_x"),
                TransactionSearchCriteria(paymentId = pay, environment = "TEST"),
                TransactionSearchCriteria(paymentId = pay, createdFrom = period.createdFrom, createdTo = period.createdTo),
                TransactionSearchCriteria(merchantOrderId = "order-1"),
                TransactionSearchCriteria(merchantAccountId = "mid-1", merchantOrderId = "order 1"),
            ).forEach { c -> withClue(c.toString()) { f.service.search(f.actor, c) shouldBe TransactionLookupResult.InvalidInput } }
            f.service.detail(f.actor, "pay_x", ctx) shouldBe TransactionLookupResult.InvalidInput
            f.events.shouldBeEmpty()
            listOf(
                period.copy(createdTo = "2026-12-02T00:00:00.000Z", environment = "LIVE", merchantAccountId = "mid_01:a.b-c", status = "AUTHORIZED", limit = 50),
                period.copy(afterCreatedAt = "2026-11-01T12:00:00.000Z", afterPaymentId = pay),
                TransactionSearchCriteria(paymentId = pay),
                TransactionSearchCriteria(merchantAccountId = "mid-1", merchantOrderId = "order:1_A.b-c"),
            ).forEach { c -> withClue(c.toString()) { f.service.search(f.actor, c).shouldBeInstanceOf<TransactionLookupResult.Found>() } }
            f.audits.shouldBeEmpty()
        }
        Then("searches, including the exact paymentId lookup, are never audited (D-TXOPS-002)") {
            val f = Fixture()
            f.service.search(f.actor, period).shouldBeInstanceOf<TransactionLookupResult.Found>()
            f.service.search(f.actor, TransactionSearchCriteria(paymentId = pay)).shouldBeInstanceOf<TransactionLookupResult.Found>()
            f.audits.shouldBeEmpty()
        }
    }

    Given("the detail (D-TXOPS-002)") {
        Then("FOUND and NOT_FOUND are audited as PAYMENT_VIEWED with the payment as target before the result is returned; PAYMENT unavailable is not audited") {
            val f = Fixture()
            f.service.detail(f.actor, pay, ctx).shouldBeInstanceOf<TransactionLookupResult.Found>()
            f.detailReply = PaymentReadResult.NotFound
            f.service.detail(f.actor, pay, ctx) shouldBe TransactionLookupResult.NotFound
            f.detailReply = PaymentReadResult.Unavailable("TIMEOUT")
            f.service.detail(f.actor, pay, ctx) shouldBe TransactionLookupResult.Unavailable("TIMEOUT")
            f.auditRows() shouldBe listOf(
                listOf("PAYMENT_VIEWED", "OK", null, pay, f.actor, "corr-1"),
                listOf("PAYMENT_VIEWED", "NOT_FOUND", null, pay, f.actor, "corr-1"),
            )
            f.events shouldBe listOf("detail", "audit", "detail", "audit", "detail")
        }
        Then("PAYMENT is called outside a transaction and the audit inside one; an audit failure withholds the detail (AUDIT_UNAVAILABLE)") {
            val f = Fixture()
            f.auditFails = true
            f.service.detail(f.actor, pay, ctx) shouldBe TransactionLookupResult.AuditUnavailable
            f.detailReply = PaymentReadResult.NotFound
            f.service.detail(f.actor, pay, ctx) shouldBe TransactionLookupResult.AuditUnavailable
            f.callInTx shouldBe listOf(false, false)
            f.auditInTx shouldBe listOf(true, true)
            f.audits.shouldBeEmpty()
        }
    }

    Given("the per-actor rate limit (Demo value)") {
        Then("calls beyond the limit are RATE_LIMITED without a PAYMENT call or audit, and counted") {
            val f = Fixture(perMinute = 2)
            f.service.search(f.actor, period).shouldBeInstanceOf<TransactionLookupResult.Found>()
            f.service.detail(f.actor, pay, ctx).shouldBeInstanceOf<TransactionLookupResult.Found>()
            f.service.search(f.actor, period) shouldBe TransactionLookupResult.RateLimited
            f.service.detail(f.actor, pay, ctx) shouldBe TransactionLookupResult.RateLimited
            f.events shouldBe listOf("search", "detail", "audit")
            f.rateLimited shouldBe 2
        }
    }
})

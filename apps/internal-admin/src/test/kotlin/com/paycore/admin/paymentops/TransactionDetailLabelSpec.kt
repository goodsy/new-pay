package com.paycore.admin.paymentops

import com.paycore.admin.account.application.provided.AdminRequestContext
import com.paycore.admin.account.application.provided.AdminSessionPolicy
import com.paycore.admin.account.application.provided.AdminSessionQuery
import com.paycore.admin.account.application.provided.AdminSessionView
import com.paycore.admin.audit.application.provided.AdminAuditRecorder
import com.paycore.admin.audit.application.provided.AuditRecord
import com.paycore.admin.merchantops.application.provided.MerchantLabel
import com.paycore.admin.merchantops.application.provided.MerchantLabelClient
import com.paycore.admin.merchantops.application.provided.MerchantLabelFailure
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
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Duration

/**
 * TXN-LIST-ENRICH-001 E-4 상세 라벨(§4.5 — rev 0.1a M-3, 대역 Port): T-E4-1 FOUND → 라벨(실패해도 진행, TX 밖) → `PAYMENT_VIEWED` 감사 → 200, `payment.merchant`는 payment 끝·
 * `merchantLabels`는 최상위 끝(PAYMENT 키 순서 불변), T-E4-2 감사 실패 → AUDIT_UNAVAILABLE(라벨·상세 미반환), T-E4-3 NOT_FOUND·PAYMENT 불가 → MASTER 0회.
 */
class TransactionDetailLabelSpec : BehaviorSpec({

    val pay = "pay_01JC0000000000000000000001"
    val mid = "mid_01JC0000000000000000000001"
    val ctx = AdminRequestContext("corr-1", "127.0.0.1")

    fun document(merchantAccountId: String) = linkedMapOf<String, Any?>(
        "payment" to linkedMapOf<String, Any?>("paymentId" to pay, "merchantAccountId" to merchantAccountId, "paymentMethod" to "CARD", "status" to "AUTHORIZED"),
        "onHold" to false,
        "returnBalance" to null,
    )

    class Fixture {
        val events = mutableListOf<String>()
        val labelCalls = mutableListOf<List<String>>()
        val labelInTx = mutableListOf<Boolean>()
        val metrics = mutableListOf<String>()
        var auditFails = false
        var detailReply: PaymentReadResult = PaymentReadResult.NotFound
        var labelReply: MerchantLabelResult = MerchantLabelResult.Found(emptyMap())
        val view = AdminSessionView("adm_01JC00000000000000000000V1", "Viewer", "ACTIVE", 1, 1, setOf("VIEWER"))
        val service = TransactionLookupService(
            object : AbstractPlatformTransactionManager() {
                override fun doGetTransaction(): Any = Any()
                override fun doBegin(transaction: Any, definition: TransactionDefinition) = Unit
                override fun doCommit(status: DefaultTransactionStatus) = Unit
                override fun doRollback(status: DefaultTransactionStatus) = Unit
            },
            object : AdminSessionQuery {
                override fun current(actorId: String) = view.takeIf { it.actorId == actorId }
                override fun policy() = AdminSessionPolicy(Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofMinutes(1))
            },
            object : PaymentReadGateway {
                override fun searchPayments(criteria: TransactionSearchCriteria): PaymentReadResult = error("not used")
                override fun paymentDetail(paymentId: String): PaymentReadResult = detailReply.also { events += "detail" }
            },
            object : MerchantLabelClient {
                override fun labels(merchantAccountIds: List<String>): MerchantLabelResult {
                    events += "labels"
                    labelCalls += merchantAccountIds
                    labelInTx += TransactionSynchronizationManager.isActualTransactionActive()
                    return labelReply
                }
            },
            object : AdminAuditRecorder {
                override fun record(record: AuditRecord) {
                    events += "audit"
                    if (auditFails) throw IllegalStateException("injected audit failure")
                }
            },
            object : PaymentOpsTimings {
                override val lookupPerMinute = 100
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
                override fun rateLimited() = Unit
                override fun merchantLabels(result: String) {
                    metrics += result
                }
            },
        )
        val actor get() = view.actorId
    }

    Given("T-E4-1 a found payment") {
        Then("label → audit → 200: merchant at the end of payment, merchantLabels at the end of the document, PAYMENT keys unchanged; MASTER outside a transaction") {
            val f = Fixture()
            f.detailReply = PaymentReadResult.Found(document(mid))
            f.labelReply = MerchantLabelResult.Found(mapOf(mid to MerchantLabel("데모상점", "demoshop01")))
            val doc = (f.service.detail(f.actor, pay, ctx) as TransactionLookupResult.Found).document
            f.events shouldBe listOf("detail", "labels", "audit")
            f.labelCalls shouldBe listOf(listOf(mid))
            f.labelInTx shouldBe listOf(false)
            doc.keys.toList() shouldBe listOf("payment", "onHold", "returnBalance", "merchantLabels")
            doc["merchantLabels"] shouldBe "OK"
            val payment = doc["payment"] as Map<*, *>
            payment.keys.toList() shouldBe listOf("paymentId", "merchantAccountId", "paymentMethod", "status", "merchant")
            payment["merchant"] shouldBe mapOf("name" to "데모상점", "code" to "demoshop01")
            f.metrics shouldBe listOf("OK")
        }

        Then("a label failure still audits and returns the detail with merchant null and merchantLabels UNAVAILABLE; an unknown MID is OK with merchant null; a malformed MID skips MASTER") {
            listOf(
                Triple(MerchantLabelResult.Unavailable(MerchantLabelFailure.UNAVAILABLE, "TIMEOUT") as MerchantLabelResult, mid, "UNAVAILABLE"),
                Triple(MerchantLabelResult.Found(emptyMap()), mid, "OK"),
                Triple(MerchantLabelResult.Found(emptyMap()), "mid-demo-1", "OK"),
            ).forEach { (reply, merchantAccountId, labels) ->
                withClue("$reply $merchantAccountId") {
                    val f = Fixture()
                    f.detailReply = PaymentReadResult.Found(document(merchantAccountId))
                    f.labelReply = reply
                    val doc = (f.service.detail(f.actor, pay, ctx) as TransactionLookupResult.Found).document
                    doc["merchantLabels"] shouldBe labels
                    (doc["payment"] as Map<*, *>)["merchant"] shouldBe null
                    f.events.last() shouldBe "audit"
                    if (merchantAccountId == mid) f.labelCalls.size shouldBe 1 else f.labelCalls.shouldBeEmpty()
                }
            }
        }
    }

    Given("T-E4-2 an audit failure") {
        Then("nothing is returned (AUDIT_UNAVAILABLE) even though the label was read") {
            val f = Fixture()
            f.auditFails = true
            f.detailReply = PaymentReadResult.Found(document(mid))
            f.labelReply = MerchantLabelResult.Found(mapOf(mid to MerchantLabel("데모상점", "demoshop01")))
            f.service.detail(f.actor, pay, ctx) shouldBe TransactionLookupResult.AuditUnavailable
        }
    }

    Given("T-E4-3 NOT_FOUND and PAYMENT unavailable") {
        Then("MASTER is never called; NOT_FOUND is still audited; unavailable is not") {
            val f = Fixture()
            f.service.detail(f.actor, pay, ctx) shouldBe TransactionLookupResult.NotFound
            f.detailReply = PaymentReadResult.Unavailable("TIMEOUT")
            f.service.detail(f.actor, pay, ctx) shouldBe TransactionLookupResult.Unavailable("TIMEOUT")
            f.events shouldBe listOf("detail", "audit", "detail")
            f.labelCalls.shouldBeEmpty()
            f.metrics.shouldBeEmpty()
        }
    }
})

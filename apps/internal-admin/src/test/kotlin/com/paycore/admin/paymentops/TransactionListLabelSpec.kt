package com.paycore.admin.paymentops

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
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Duration

/**
 * TXN-LIST-ENRICH-001 E-3 목록 라벨 붙이기(§3.2·§3.3·§4.3·§8 — 대역 Port, Spring 없음): T-E3-1 **PAYMENT 응답 항목의** 고유 MID만 정렬해 한 번(사용자 입력 MID 미전달, 0개면 미호출),
 * T-E3-2 형식 밖 MID는 그 항목만 null, T-E3-3 MASTER 실패 분류마다 200 목록 그대로 + UNAVAILABLE + 지표, T-E3-4 PAYMENT 실패 → MASTER 0회, T-E3-5 권한, T-E3-6 감사 0·로그 비노출,
 * T-E3-7 순서(PAYMENT → MASTER)·TX 밖, T-E3-8 응답 키 정확(항목 11 + merchant, 최상위 + merchantLabels), T-E3-9 속도 창 1만 소비.
 */
class TransactionListLabelSpec : BehaviorSpec({

    val m1 = "mid_01JC0000000000000000000001"
    val m2 = "mid_01JC0000000000000000000002"
    val typed = "mid_01JC00000000000000000000X9"
    val period = TransactionSearchCriteria(createdFrom = "2026-11-01T00:00:00.000Z", createdTo = "2026-11-02T00:00:00.000Z")
    val itemKeys = listOf(
        "paymentId", "environment", "merchantAccountId", "merchantOrderId", "orderNamePreview", "orderNameTruncated", "amountMinor", "currency", "paymentMethod", "status", "createdAt",
    )

    fun item(paymentId: String, mid: String) = linkedMapOf<String, Any?>(
        "paymentId" to paymentId, "environment" to "LIVE", "merchantAccountId" to mid, "merchantOrderId" to "order-1", "orderNamePreview" to null, "orderNameTruncated" to false,
        "amountMinor" to 10_000L, "currency" to "KRW", "paymentMethod" to "CARD", "status" to "AUTHORIZED", "createdAt" to "2026-11-01T01:02:03.456Z",
    )

    class Fixture(perMinute: Int = 100) {
        val events = mutableListOf<String>()
        val labelCalls = mutableListOf<List<String>>()
        val labelInTx = mutableListOf<Boolean>()
        val metrics = mutableListOf<String>()
        val audits = mutableListOf<AuditRecord>()
        var page: PaymentReadResult = PaymentReadResult.Found(linkedMapOf("items" to emptyList<Any>(), "nextCursor" to null))
        var labelReply: MerchantLabelResult = MerchantLabelResult.Found(emptyMap())
        val view = AdminSessionView("adm_01JC00000000000000000000V1", "Viewer", "ACTIVE", 1, 1, setOf("VIEWER"))
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
                override fun searchPayments(criteria: TransactionSearchCriteria): PaymentReadResult = page.also { events += "search" }
                override fun paymentDetail(paymentId: String): PaymentReadResult = error("not used")
            },
            object : MerchantLabelClient {
                override fun labels(merchantAccountIds: List<String>): MerchantLabelResult {
                    events += "labels"
                    labelCalls += merchantAccountIds
                    labelInTx += TransactionSynchronizationManager.isActualTransactionActive()
                    return labelReply
                }
                // MERCHANT-CODE-001 MC-3b: 거래 조회는 가맹점 내부 키 라벨을 부르지 않는다(대역 메서드 추가 — 부르면 실패).
                override fun merchantLabels(merchantIds: List<String>): MerchantLabelResult = error("transaction lookup never asks merchant-key labels")
            },
            object : AdminAuditRecorder {
                override fun record(record: AuditRecord) {
                    events += "audit"
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
                    metrics += "RATE_LIMITED"
                }

                override fun merchantLabels(result: String) {
                    metrics += result
                }
            },
        )
        val actor get() = view.actorId

        @Suppress("UNCHECKED_CAST")
        fun search(c: TransactionSearchCriteria): Map<String, Any?> = (service.search(actor, c) as TransactionLookupResult.Found).document

        @Suppress("UNCHECKED_CAST")
        fun Map<String, Any?>.items(): List<Map<String, Any?>> = this["items"] as List<Map<String, Any?>>
    }

    Given("T-E3-1·T-E3-2·T-E3-8 a PAYMENT page with repeated, unsorted and malformed MIDs") {
        Then("one label call with the distinct well-formed MIDs of the PAYMENT answer, sorted — never the MID the user typed; labels attach per item; exact keys") {
            val f = Fixture()
            f.page = PaymentReadResult.Found(
                linkedMapOf("items" to listOf(item("pay_3", m2), item("pay_2", m1), item("pay_1", m2), item("pay_0", "mid-demo-1")), "nextCursor" to null),
            )
            f.labelReply = MerchantLabelResult.Found(mapOf(m1 to MerchantLabel("데모상점", "demoshop01")))
            val doc = with(f) { search(period.copy(merchantAccountId = typed)) }
            f.labelCalls shouldBe listOf(listOf(m1, m2))
            doc.keys.toList() shouldBe listOf("items", "nextCursor", "merchantLabels")
            doc["merchantLabels"] shouldBe "OK"
            val items = with(f) { doc.items() }
            items.forEach { it.keys.toList() shouldBe itemKeys + "merchant" }
            items.map { it["merchant"] } shouldBe listOf(null, mapOf("name" to "데모상점", "code" to "demoshop01"), null, null)
            (items[1]["merchant"] as Map<*, *>).keys.toList() shouldBe listOf("name", "code")
            items.map { it["paymentMethod"] } shouldBe listOf("CARD", "CARD", "CARD", "CARD")
            doc.toString() shouldNotContain "mer_"
            f.metrics shouldBe listOf("OK")
        }

        Then("no items, or only malformed MIDs: MASTER is not called, labels are OK (nothing to label), counted as SKIPPED") {
            val f = Fixture()
            with(f) { search(period) }.let {
                it["merchantLabels"] shouldBe "OK"
                it["items"] shouldBe emptyList<Any>()
            }
            f.page = PaymentReadResult.Found(linkedMapOf("items" to listOf(item("pay_0", "mid-demo-1")), "nextCursor" to null))
            with(f) { search(period).items().single()["merchant"] } shouldBe null
            f.labelCalls.shouldBeEmpty()
            f.metrics shouldBe listOf("SKIPPED", "SKIPPED")
        }
    }

    Given("T-E3-3 every MASTER failure") {
        Then("busy, unavailable and unreadable labels keep the PAYMENT list (200) with merchantLabels UNAVAILABLE, every merchant null, and count the class") {
            listOf(
                MerchantLabelResult.Unavailable(MerchantLabelFailure.BUSY, "MASTER_LABELS_BUSY") to "BUSY",
                MerchantLabelResult.Unavailable(MerchantLabelFailure.UNAVAILABLE, "TIMEOUT") to "UNAVAILABLE",
                MerchantLabelResult.Unavailable(MerchantLabelFailure.UNAVAILABLE, "HTTP_401") to "UNAVAILABLE",
                MerchantLabelResult.Unavailable(MerchantLabelFailure.UNREADABLE, "RESPONSE_UNREADABLE") to "UNREADABLE",
            ).forEach { (reply, metric) ->
                withClue(reply.toString()) {
                    val f = Fixture()
                    val page = linkedMapOf<String, Any?>("items" to listOf(item("pay_2", m1), item("pay_1", m2)), "nextCursor" to null)
                    f.page = PaymentReadResult.Found(page)
                    f.labelReply = reply
                    val doc = with(f) { search(period) }
                    doc["merchantLabels"] shouldBe "UNAVAILABLE"
                    with(f) { doc.items() }.map { it["merchant"] } shouldBe listOf(null, null)
                    with(f) { doc.items() }.map { it - "merchant" } shouldBe listOf(item("pay_2", m1), item("pay_1", m2))
                    f.metrics shouldBe listOf(metric)
                }
            }
        }
    }

    Given("T-E3-4·T-E3-5·T-E3-6·T-E3-7·T-E3-9") {
        Then("T-E3-4 a PAYMENT failure is 503 as before and MASTER is never called") {
            val f = Fixture()
            f.page = PaymentReadResult.Unavailable("TIMEOUT")
            f.service.search(f.actor, period) shouldBe TransactionLookupResult.Unavailable("TIMEOUT")
            f.labelCalls.shouldBeEmpty()
            f.metrics.shouldBeEmpty()
        }

        Then("T-E3-5 a forbidden viewer reaches neither PAYMENT nor MASTER") {
            val f = Fixture()
            f.service.search("adm_other", period) shouldBe TransactionLookupResult.Forbidden
            f.events.shouldBeEmpty()
        }

        Then("T-E3-6·T-E3-7 PAYMENT first, then MASTER outside any transaction; no audit row") {
            val f = Fixture()
            f.page = PaymentReadResult.Found(linkedMapOf("items" to listOf(item("pay_1", m1)), "nextCursor" to null))
            with(f) { search(period) }
            f.events shouldBe listOf("search", "labels")
            f.labelInTx shouldBe listOf(false)
            f.audits.shouldBeEmpty()
        }

        Then("T-E3-9 the label call does not consume the per-actor search window (one search = one slot)") {
            val f = Fixture(perMinute = 1)
            f.page = PaymentReadResult.Found(linkedMapOf("items" to listOf(item("pay_1", m1)), "nextCursor" to null))
            f.service.search(f.actor, period).shouldBeInstanceOf<TransactionLookupResult.Found>()
            f.service.search(f.actor, period) shouldBe TransactionLookupResult.RateLimited
            f.labelCalls.size shouldBe 1
        }
    }
})

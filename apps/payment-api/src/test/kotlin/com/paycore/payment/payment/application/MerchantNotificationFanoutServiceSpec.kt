package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.provided.FanOutMerchantNotificationsCommand
import com.paycore.payment.payment.application.required.MerchantNotificationFacts
import com.paycore.payment.payment.application.required.MerchantNotificationFanoutStore
import com.paycore.payment.payment.application.required.MerchantWebhookDeliveryPlan
import com.paycore.payment.payment.application.required.MerchantWebhookDeliveryRepository
import com.paycore.payment.payment.application.required.MerchantWebhookEndpoint
import com.paycore.payment.payment.application.required.MerchantWebhookEndpointDirectory
import com.paycore.payment.payment.application.required.MerchantWebhookEventRecord
import com.paycore.payment.payment.application.required.MerchantWebhookEventStore
import com.paycore.payment.payment.application.required.MerchantWebhookIdentifierGenerator
import com.paycore.payment.payment.application.required.NotificationSubject
import com.paycore.payment.payment.application.required.NotifiedPayment
import com.paycore.payment.payment.application.required.OutboxNotificationCandidate
import com.paycore.payment.payment.application.required.OutboxNotificationMark
import com.paycore.payment.payment.application.required.PaymentClock
import com.paycore.payment.payment.application.required.PendingOutboxNotification
import com.paycore.payment.payment.application.required.UnreadableNotificationSourceException
import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.MerchantAccountId
import com.paycore.payment.payment.domain.MerchantNotificationType
import com.paycore.payment.payment.domain.MerchantOrderId
import com.paycore.payment.payment.domain.MerchantWebhookDelivery
import com.paycore.payment.payment.domain.MerchantWebhookDeliveryId
import com.paycore.payment.payment.domain.MerchantWebhookDeliveryStatus
import com.paycore.payment.payment.domain.MerchantWebhookEventId
import com.paycore.payment.payment.domain.PaymentId
import com.paycore.payment.payment.domain.PaymentStatus
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import java.time.Duration
import java.time.Instant

/**
 * fan-out Use Case 단위 시험(WEBHOOK-001 §5.0 rev 0.2 — H-3·m-3, WH-04·WH-10·WH-12·WH-18, D-WH-001·002·004). 저장 Port는 상태를 가진 대역이다(실 DB 시험은 S2).
 * 기대값은 명세 §4.1 표와 §5.0에서 직접 적는다: 행마다 한 로컬 TX(guard 먼저), Outbox `id` 순, 대상만 sequence·이벤트, endpoint 있을 때만 전달 행,
 * 실패 행은 그 행만 FANOUT_FAILED(비종결 — SUPPRESSED 아님)로 두고 같은 Payment의 뒤 행만 이번 회차에서 건너뛴다(다른 Payment 계속).
 */
class MerchantNotificationFanoutServiceSpec : BehaviorSpec({

    val t0 = Instant.parse("2026-10-21T01:00:00Z")
    val midA = MerchantAccountId("mid_webhook_a")
    val midLive = MerchantAccountId("mid_webhook_live")
    val retry = Duration.ofMinutes(1)

    data class Row(val pending: PendingOutboxNotification, var mark: OutboxNotificationMark? = null, var failure: String? = null, var retryAt: Instant? = null, var failures: Int = 0)

    class Fakes {
        val calls = mutableListOf<String>()
        val rows = linkedMapOf<Long, Row>()
        val sequences = mutableMapOf<String, Int>()
        val events = mutableListOf<MerchantWebhookEventRecord>()
        val deliveries = mutableListOf<MerchantWebhookDelivery>()
        val endpoints = mutableMapOf<Pair<Environment, String>, MerchantWebhookEndpoint>()
        /** 다른 인스턴스가 앞 행을 실패로 커밋한 상황을 시험이 지정한다(H-1). */
        val earlierOpen = mutableSetOf<Long>()
        var failEventAppendFor: String? = null
        var commits = 0
        var rollbacks = 0
        var n = 0

        // 대역 TX: 커밋 전 쓰기는 버퍼에 두고 rollback이면 버린다(실패 행의 부분 쓰기가 남지 않는다는 성질을 시험할 수 있게).
        private val buffered = mutableListOf<() -> Unit>()

        val store = object : MerchantNotificationFanoutStore {
            override fun candidates(now: Instant, limit: Int): List<OutboxNotificationCandidate> {
                calls += "candidates:$limit"
                return rows.values.filter { it.mark == null && (it.retryAt == null || !it.retryAt!!.isAfter(now)) }
                    .sortedBy { it.pending.outboxId }.take(limit)
                    .map { OutboxNotificationCandidate(it.pending.outboxId, it.pending.subject?.paymentId?.value) }
            }

            override fun lockGuard() {
                calls += "guard"
            }

            override fun hasEarlierOpenRow(outboxId: Long, paymentKey: String): Boolean {
                calls += "earlier:$outboxId"
                return outboxId in earlierOpen
            }

            override fun load(outboxId: Long, now: Instant): PendingOutboxNotification? {
                calls += "load:$outboxId"
                val r = rows.getValue(outboxId)
                if (r.mark != null) return null
                if (r.pending.eventType == "Unreadable") throw UnreadableNotificationSourceException("PAYLOAD_UNREADABLE")
                return r.pending
            }

            override fun mark(outboxId: Long, mark: OutboxNotificationMark, at: Instant) {
                buffered += { rows.getValue(outboxId).mark = mark }
            }

            override fun markFailed(outboxId: Long, failureCode: String, at: Instant, retryAt: Instant) {
                buffered += {
                    val r = rows.getValue(outboxId)
                    r.failure = failureCode
                    r.retryAt = retryAt
                    r.failures++
                }
            }

            override fun nextSequence(paymentId: PaymentId): Int {
                val next = (sequences[paymentId.value] ?: 0) + 1
                buffered += { sequences[paymentId.value] = next }
                return next
            }
        }
        val eventStore = object : MerchantWebhookEventStore {
            override fun append(record: MerchantWebhookEventRecord) {
                if (record.sourceEventId == failEventAppendFor) error("injected store defect")
                buffered += { events += record }
            }
        }
        val deliveryRepository = object : MerchantWebhookDeliveryRepository {
            override fun insertNew(delivery: MerchantWebhookDelivery) {
                buffered += { deliveries += delivery }
            }

            override fun claimDue(now: Instant, limit: Int, owner: String, claimUntil: Instant) = error("fan-out never claims deliveries")

            override fun releaseClaim(id: com.paycore.payment.payment.domain.MerchantWebhookDeliveryId, owner: String, attemptNo: Int): Boolean =
                error("fan-out never releases claims")

            override fun recordResult(
                id: com.paycore.payment.payment.domain.MerchantWebhookDeliveryId, owner: String, attemptNo: Int,
                result: com.paycore.payment.payment.application.required.DeliveryAttemptResult, at: Instant,
                schedule: com.paycore.payment.payment.domain.WebhookRetrySchedule, jitterFraction: Double,
            ) = error("fan-out never records delivery results")
        }
        val directory = object : MerchantWebhookEndpointDirectory {
            override fun find(environment: Environment, merchantAccountId: MerchantAccountId): MerchantWebhookEndpoint? = endpoints[environment to merchantAccountId.value]
        }
        val ids = object : MerchantWebhookIdentifierGenerator {
            override fun eventId(at: Instant) = MerchantWebhookEventId("whe_01JB%022d".format(++n))
            override fun deliveryId(at: Instant) = MerchantWebhookDeliveryId("whd_01JB%022d".format(++n))
        }
        val tm = object : PlatformTransactionManager {
            override fun getTransaction(definition: TransactionDefinition?): TransactionStatus {
                buffered.clear()
                return SimpleTransactionStatus()
            }

            override fun commit(status: TransactionStatus) {
                commits++
                buffered.forEach { it() }
                buffered.clear()
            }

            override fun rollback(status: TransactionStatus) {
                rollbacks++
                buffered.clear()
            }
        }
        val service = MerchantNotificationFanoutService(
            tm, store, eventStore, deliveryRepository, directory, ids, object : PaymentClock {
                override fun now(): Instant = t0
            },
        )

        fun add(r: PendingOutboxNotification) {
            rows[r.outboxId] = Row(r)
        }
    }

    fun subject(paymentId: String, status: PaymentStatus?, facts: MerchantNotificationFacts, mid: MerchantAccountId = midA, env: Environment = Environment.TEST) =
        NotificationSubject(env, mid, PaymentId(paymentId), MerchantOrderId("order-$paymentId"), status?.let { NotifiedPayment(it, 10_000, "KRW") }, facts)

    val authorizedFacts = MerchantNotificationFacts.Authorized("pau_1", 10_000, "KRW", "LUMP_SUM", null, "CREDIT", "12345678", t0.minusSeconds(5))
    fun cancelledFacts(initiator: String, merchantCancelId: String?) = MerchantNotificationFacts.Cancelled(
        "pcn_1", initiator, merchantCancelId, 3_000, "KRW", "ptx_c1", "C0001", t0.minusSeconds(3), 10_000, 3_000, "PARTIALLY_CANCELLED",
    )
    val cancelFailedOperator = MerchantNotificationFacts.CancelFailed("pcn_2", "OPERATOR", null, 1_000, "KRW", "DECLINED")

    fun row(id: Long, type: String, subject: NotificationSubject?) = PendingOutboxNotification(id, "evt-$id", type, t0.minusSeconds(60 - id), subject)
    fun command(batch: Int = 50) = FanOutMerchantNotificationsCommand(batch, retry)

    Given("a mixed batch (creation, authorization, internal risk event, P1 operator cancel and cancel failure, LIVE expiry, operator cancel on AUTHORIZED)") {
        val f = Fakes()
        f.endpoints[Environment.TEST to midA.value] = MerchantWebhookEndpoint("ep-test-a", "https://merchant-a.example.test/webhooks")
        listOf(
            row(1, "PaymentRequested", null),
            row(2, "PaymentAuthorized", subject("pay_A", PaymentStatus.AUTHORIZED, authorizedFacts)),
            row(3, "AuthorizationIncidentDetected", null),
            row(4, "PaymentCancelled", subject("pay_P1", PaymentStatus.FAILED, cancelledFacts("OPERATOR", null))),
            row(5, "PaymentExpired", subject("pay_L", PaymentStatus.EXPIRED, MerchantNotificationFacts.Expired, mid = midLive, env = Environment.LIVE)),
            row(6, "PaymentCancelled", subject("pay_A", PaymentStatus.AUTHORIZED, cancelledFacts("OPERATOR", null))),
            row(7, "PaymentCancelFailed", subject("pay_P1", PaymentStatus.FAILED, cancelFailedOperator)),
            row(8, "PaymentCancelFailed", subject("pay_A", PaymentStatus.AUTHORIZED, cancelFailedOperator)),
        ).forEach(f::add)
        val report = f.service.fanOut(command())

        Then("one local TX per row, each taking the guard before reading the row, in Outbox id order") {
            f.commits shouldBe 8
            f.rollbacks shouldBe 0
            f.calls.first() shouldBe "candidates:50"
            // 행마다 guard → 같은 Payment의 앞 미종결 행 확인(H-1 — 주체가 있는 행만, paymentKey 없는 행은 확인할 수 없다 n-3) → 재읽기.
            f.calls.drop(1) shouldBe (1L..8L).flatMap { id -> listOf("guard") + (if (id in setOf(1L, 3L)) emptyList() else listOf("earlier:$id")) + listOf("load:$id") }
        }
        Then("marks follow the classification table (P1 operator cancel and cancel failure suppressed, operator results on AUTHORIZED notified — D-WH-001)") {
            f.rows.values.map { it.mark } shouldBe listOf(
                OutboxNotificationMark.NOT_APPLICABLE, OutboxNotificationMark.NOTIFIED, OutboxNotificationMark.NOT_APPLICABLE, OutboxNotificationMark.SUPPRESSED,
                OutboxNotificationMark.NOTIFIED, OutboxNotificationMark.NOTIFIED, OutboxNotificationMark.SUPPRESSED, OutboxNotificationMark.NOTIFIED,
            )
        }
        Then("merchant events get a per-payment contiguous sequence; suppressed events take no number") {
            f.events.map { Triple(it.sourceEventId, it.envelope.paymentId.value, it.envelope.sequence) } shouldBe listOf(
                Triple("evt-2", "pay_A", 1), Triple("evt-5", "pay_L", 1), Triple("evt-6", "pay_A", 2), Triple("evt-8", "pay_A", 3),
            )
            f.events.map { it.envelope.type } shouldBe listOf(
                MerchantNotificationType.PAYMENT_AUTHORIZED, MerchantNotificationType.PAYMENT_EXPIRED, MerchantNotificationType.PAYMENT_CANCELLED,
                MerchantNotificationType.PAYMENT_CANCEL_FAILED,
            )
            (f.events[2].envelope.facts as MerchantNotificationFacts.Cancelled).initiator shouldBe "OPERATOR"
        }
        Then("the envelope time is the fan-out time (never the business fact time — m-1), with the payment's own amount and a new public id") {
            val e = f.events[0].envelope
            e.createdAt shouldBe t0
            e.eventId.value.startsWith("whe_") shouldBe true
            e.facts shouldBe authorizedFacts
            e.payment shouldBe NotifiedPayment(PaymentStatus.AUTHORIZED, 10_000, "KRW")
            f.events[0].recordedAt shouldBe t0
        }
        Then("a delivery row exists only when the (environment, MID) has an endpoint — LIVE has none (fail-closed, NO_ENDPOINT)") {
            f.events.map { it.deliveryPlan } shouldBe listOf(
                MerchantWebhookDeliveryPlan.DELIVER, MerchantWebhookDeliveryPlan.NO_ENDPOINT, MerchantWebhookDeliveryPlan.DELIVER, MerchantWebhookDeliveryPlan.DELIVER,
            )
            f.deliveries.map { it.webhookEventId } shouldBe listOf(f.events[0].envelope.eventId, f.events[2].envelope.eventId, f.events[3].envelope.eventId)
            f.deliveries.forEach {
                it.status shouldBe MerchantWebhookDeliveryStatus.PENDING
                it.nextAttemptAt shouldBe t0
                it.environment shouldBe Environment.TEST
            }
        }
        Then("the report counts each outcome") {
            report.claimed shouldBe 8
            report.notified shouldBe 4
            report.noEndpoint shouldBe 1
            report.suppressed shouldBe 2
            report.notApplicable shouldBe 2
            report.failed shouldBe 0
            report.deferred shouldBe 0
        }
    }

    Given("a poison row (an impossible combination) between rows of other payments and a later row of the same payment") {
        val f = Fakes()
        f.endpoints[Environment.TEST to midA.value] = MerchantWebhookEndpoint("ep-test-a", "https://merchant-a.example.test/webhooks")
        listOf(
            row(10, "PaymentAuthorized", subject("pay_B", PaymentStatus.AUTHORIZED, authorizedFacts)),
            row(11, "PaymentAuthorized", subject("pay_C", PaymentStatus.AUTHORIZING, authorizedFacts)),
            row(12, "PaymentCancelled", subject("pay_C", PaymentStatus.AUTHORIZED, cancelledFacts("MERCHANT", "mc-1"))),
            row(13, "PaymentExpired", subject("pay_D", PaymentStatus.EXPIRED, MerchantNotificationFacts.Expired)),
        ).forEach(f::add)
        val report = f.service.fanOut(command())

        Then("only that row becomes FANOUT_FAILED (non-terminal, with a retry time) — never SUPPRESSED, and nothing of it is written") {
            f.rows.getValue(11).mark shouldBe null
            f.rows.getValue(11).failure shouldBe "UNEXPECTED_COMBINATION"
            f.rows.getValue(11).retryAt shouldBe t0.plus(retry)
            f.events.none { it.sourceEventId == "evt-11" } shouldBe true
            f.sequences.containsKey("pay_C") shouldBe false
        }
        Then("the later row of the same payment is deferred (order preserved) while other payments continue") {
            f.rows.getValue(12).mark shouldBe null
            f.rows.getValue(12).failure shouldBe null
            f.rows.getValue(10).mark shouldBe OutboxNotificationMark.NOTIFIED
            f.rows.getValue(13).mark shouldBe OutboxNotificationMark.NOTIFIED
            report.failed shouldBe 1
            report.deferred shouldBe 1
            report.notified shouldBe 2
        }
        Then("the next round before the retry time does not pick the failed row again") {
            f.service.fanOut(command()).claimed shouldBe 1 // 행 12만 후보(대역은 Payment 단위 제외를 하지 않는다 — 실제 SQL 제외는 S2 DB 시험)
        }
    }

    Given("H-1: another instance committed an earlier row of the same payment as FANOUT_FAILED after this round read its candidates") {
        val f = Fakes()
        listOf(
            row(40, "PaymentExpired", subject("pay_X", PaymentStatus.EXPIRED, MerchantNotificationFacts.Expired)),
            row(41, "PaymentExpired", subject("pay_X", PaymentStatus.EXPIRED, MerchantNotificationFacts.Expired)),
            row(42, "PaymentExpired", subject("pay_Y", PaymentStatus.EXPIRED, MerchantNotificationFacts.Expired)),
        ).forEach(f::add)
        f.earlierOpen += 40L
        val report = f.service.fanOut(command())

        Then("the row is DEFERRED (not notified, no sequence) and the rest of that payment is blocked in this round; other payments continue") {
            f.rows.getValue(40).mark shouldBe null
            f.rows.getValue(41).mark shouldBe null
            f.rows.getValue(42).mark shouldBe OutboxNotificationMark.NOTIFIED
            f.events.map { it.sourceEventId } shouldBe listOf("evt-42")
            f.sequences.containsKey("pay_X") shouldBe false
            report.deferred shouldBe 2
            report.failed shouldBe 0
        }
    }

    Given("failures of other kinds") {
        Then("an unreadable payload, a missing payment row, mismatched facts and a store defect each fail only their row with a fixed code") {
            val f = Fakes()
            listOf(
                row(20, "Unreadable", null),
                row(21, "PaymentExpired", subject("pay_E", null, MerchantNotificationFacts.Expired)),
                row(22, "PaymentAuthorized", subject("pay_F", PaymentStatus.AUTHORIZED, cancelledFacts("MERCHANT", "mc-1"))),
                row(23, "PaymentExpired", null),
                row(24, "PaymentExpired", subject("pay_G", PaymentStatus.EXPIRED, MerchantNotificationFacts.Expired)),
            ).forEach(f::add)
            f.failEventAppendFor = "evt-24"
            val report = f.service.fanOut(command())
            f.rows.values.map { it.failure } shouldBe listOf("PAYLOAD_UNREADABLE", "UNEXPECTED_COMBINATION", "FACTS_MISMATCH", "UNEXPECTED_COMBINATION", "DEFECT")
            f.rows.values.all { it.mark == null } shouldBe true
            f.events shouldBe emptyList()
            f.sequences shouldBe emptyMap() // 실패한 TX의 sequence 증가는 rollback됐다
            report.failed shouldBe 5
        }
    }

    Given("nothing pending") {
        Then("the round reads candidates and opens no row TX") {
            val f = Fakes()
            val report = f.service.fanOut(command(10))
            report.claimed shouldBe 0
            f.calls shouldBe listOf("candidates:10")
            f.commits shouldBe 0
        }
    }
})

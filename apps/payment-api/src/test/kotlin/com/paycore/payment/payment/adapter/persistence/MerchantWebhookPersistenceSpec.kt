package com.paycore.payment.payment.adapter.persistence

import com.paycore.payment.payment.adapter.persistence.PersistenceTestData.newOrder
import com.paycore.payment.payment.application.CreatePaymentFixture.command
import com.paycore.payment.payment.application.SecureRandomMerchantWebhookIdentifierGenerator
import com.paycore.payment.payment.application.provided.CreatePaymentResult
import com.paycore.payment.payment.application.provided.CreatePaymentUseCase
import com.paycore.payment.payment.application.required.MerchantNotificationFacts
import com.paycore.payment.payment.application.required.MerchantWebhookDeliveryPlan
import com.paycore.payment.payment.application.required.MerchantWebhookEnvelope
import com.paycore.payment.payment.application.required.MerchantWebhookEventRecord
import com.paycore.payment.payment.application.required.NotifiedPayment
import com.paycore.payment.payment.application.required.PersistenceFailure
import com.paycore.payment.payment.application.required.StoreConstraint
import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.MerchantNotificationType
import com.paycore.payment.payment.domain.MerchantWebhookDelivery
import com.paycore.payment.payment.domain.MerchantWebhookDeliveryStatus
import com.paycore.payment.payment.domain.OrderKey
import com.paycore.payment.payment.domain.PaymentId
import com.paycore.payment.payment.domain.PaymentStatus
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.HexFormat

/**
 * 가맹점 Webhook 표(WEBHOOK-001 V21·V22, §5.0 rev 0.2) 실제 MySQL 8.4.11 시험: 본문 바이트 보존·SHA-256, Outbox 이벤트당 하나·Payment 안 순번 UNIQUE(fan-out 재실행 수렴의 DB 방어),
 * LIVE는 NO_ENDPOINT만·전달 행 TEST만(D-WH-004 fail-closed — DB 이중 방어), insert-only 이벤트(runtime 1142), 전달 행 복원, TX 필수.
 * 주체 Payment·Outbox 이벤트는 운영 CreatePayment 경로로 만든다(FK — 가맹점 이벤트는 실제 Outbox 이벤트를 가리킨다).
 */
@PaymentDbTest
class MerchantWebhookPersistenceSpec(
    private val createPayment: CreatePaymentUseCase,
    private val events: JpaMerchantWebhookEventStore,
    private val deliveries: JpaMerchantWebhookDeliveryRepository,
    private val transactionManager: PlatformTransactionManager,
    private val db: DbAccess,
) : BehaviorSpec({

    val tx = TransactionTemplate(transactionManager)
    val ids = SecureRandomMerchantWebhookIdentifierGenerator()
    val at: Instant = Instant.parse("2026-10-21T01:00:00.123Z")

    data class Subject(val order: OrderKey, val paymentId: PaymentId, val outboxEventId: String)

    // 주체 Payment는 TEST로 만든다(시험 eligibility 대역 범위). LIVE 행 시험은 봉투 environment만 LIVE로 둔다(표 CHECK는 행 environment를 본다).
    fun subject(): Subject {
        val order = newOrder()
        val created = createPayment.create(command(order)).shouldBeInstanceOf<CreatePaymentResult.Accepted>()
        val eventId = db.withApp { c ->
            with(db) { c.scalar("SELECT event_id FROM outbox_event WHERE aggregate_id = ? AND event_type = 'PaymentRequested'", created.payment.paymentId.value) }
        } as String
        return Subject(order, created.payment.paymentId, eventId)
    }

    fun record(
        s: Subject, sequence: Int = 1, plan: MerchantWebhookDeliveryPlan = MerchantWebhookDeliveryPlan.DELIVER, sourceEventId: String = s.outboxEventId,
        environment: Environment = s.order.environment,
    ) =
        MerchantWebhookEventRecord(
            MerchantWebhookEnvelope(
                ids.eventId(at), MerchantNotificationType.PAYMENT_EXPIRED, environment, s.order.merchantAccountId, at, sequence, s.paymentId, s.order.merchantOrderId,
                NotifiedPayment(PaymentStatus.EXPIRED, 10_000, "KRW"), MerchantNotificationFacts.Expired,
            ),
            sourceEventId, plan, at,
        )

    fun append(r: MerchantWebhookEventRecord) = tx.executeWithoutResult { events.append(r) }

    fun eventRow(id: String): Map<String, Any?> = db.withApp { c ->
        with(db) {
            c.rows(
                "SELECT body, body_sha256, payment_sequence, event_type, delivery_plan, DATE_FORMAT(created_at, '%Y-%m-%dT%H:%i:%s.%fZ') AS created FROM merchant_webhook_event WHERE webhook_event_id = ?",
                id,
            ).single()
        }
    }

    Given("a merchant webhook event appended in a local TX") {
        val s = subject()
        val r = record(s)
        append(r)

        Then("the stored body is exactly the v1 bytes and the SHA-256 is of those bytes (MEDIUMBLOB keeps bytes — JSON columns would not, F-9)") {
            val row = eventRow(r.envelope.eventId.value)
            val expected = MerchantWebhookBodyJson.serialize(r.envelope)
            (row["body"] as ByteArray).contentEquals(expected) shouldBe true
            row["body_sha256"] shouldBe HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(expected))
            row["payment_sequence"] shouldBe 1
            row["event_type"] shouldBe "payment.expired"
            row["delivery_plan"] shouldBe "DELIVER"
            row["created"] shouldBe "2026-10-21T01:00:00.123000Z"
        }
        Then("a second event from the same Outbox event is a unique conflict (fan-out re-run converges)") {
            shouldThrow<PersistenceFailure.UniqueConflict> { append(record(s, sequence = 2)) }.constraint shouldBe StoreConstraint.MERCHANT_WEBHOOK_EVENT_SOURCE
        }
        Then("the same payment sequence from another Outbox event is a unique conflict") {
            val other = subject()
            shouldThrow<PersistenceFailure.UniqueConflict> { append(record(s, sequence = 1, sourceEventId = other.outboxEventId)) }.constraint shouldBe
                StoreConstraint.MERCHANT_WEBHOOK_EVENT_SEQUENCE
        }
        Then("the runtime account cannot change or delete it (insert-only, 1142)") {
            db.withApp { c ->
                listOf(
                    "UPDATE merchant_webhook_event SET body = 0x00 WHERE webhook_event_id = '${r.envelope.eventId.value}'",
                    "DELETE FROM merchant_webhook_event WHERE webhook_event_id = '${r.envelope.eventId.value}'",
                    "DELETE FROM merchant_webhook_delivery WHERE delivery_id = 'none'",
                    "DELETE FROM merchant_webhook_stream WHERE payment_id = 'none'",
                ).map { sql -> with(db) { c.errorCodeOf(sql) } }
            } shouldBe listOf(1142, 1142, 1142, 1142)
        }
    }

    Given("a LIVE payment (no endpoint source — D-WH-004 fail-closed)") {
        val s = subject()
        Then("its event can only be NO_ENDPOINT — DELIVER is rejected by the table CHECK") {
            shouldThrowAny { append(record(s, plan = MerchantWebhookDeliveryPlan.DELIVER, environment = Environment.LIVE)) }
            db.count("SELECT COUNT(*) FROM merchant_webhook_event WHERE payment_id = ?", s.paymentId.value) shouldBe 0
            val ok = record(s, plan = MerchantWebhookDeliveryPlan.NO_ENDPOINT, environment = Environment.LIVE)
            append(ok)
            eventRow(ok.envelope.eventId.value)["delivery_plan"] shouldBe "NO_ENDPOINT"
        }
        Then("a LIVE delivery row is rejected by the table CHECK even if an event existed") {
            val e = record(subject())
            append(e)
            val live = MerchantWebhookDelivery.pending(ids.deliveryId(at), e.envelope.eventId, Environment.LIVE, s.order.merchantAccountId, at)
            shouldThrowAny { tx.executeWithoutResult { deliveries.insertNew(live) } }
        }
    }

    Given("a new delivery") {
        val s = subject()
        val e = record(s)
        append(e)
        val d = MerchantWebhookDelivery.pending(ids.deliveryId(at), e.envelope.eventId, Environment.TEST, s.order.merchantAccountId, at.truncatedTo(ChronoUnit.MILLIS))
        tx.executeWithoutResult { deliveries.insertNew(d) }

        Then("it is stored PENDING, due at its creation time, with no attempt, and restores through the domain") {
            val restored = checkNotNull(tx.execute { deliveries.find(d.id) })
            restored.status shouldBe MerchantWebhookDeliveryStatus.PENDING
            restored.nextAttemptAt shouldBe at
            restored.attemptCount shouldBe 0
            restored.webhookEventId shouldBe e.envelope.eventId
        }
        Then("a second delivery for the same event is a unique conflict (one endpoint per MID and environment)") {
            val again = MerchantWebhookDelivery.pending(ids.deliveryId(at), e.envelope.eventId, Environment.TEST, s.order.merchantAccountId, at)
            shouldThrow<PersistenceFailure.UniqueConflict> { tx.executeWithoutResult { deliveries.insertNew(again) } }.constraint shouldBe
                StoreConstraint.MERCHANT_WEBHOOK_DELIVERY_EVENT
        }
    }

    Given("the stores outside a transaction") {
        Then("they refuse (MANDATORY — the caller owns the local TX)") {
            val s = subject()
            shouldThrow<IllegalTransactionStateException> { events.append(record(s)) }
        }
    }

    Given("the fan-out guard row") {
        Then("V21 seeds exactly one guard row the runtime account can lock") {
            db.count("SELECT COUNT(*) FROM merchant_webhook_fanout_guard") shouldBe 1
            db.withApp { c ->
                c.autoCommit = false
                try {
                    try {
                        c.createStatement().use { it.executeQuery("SELECT guard_name FROM merchant_webhook_fanout_guard WHERE guard_name = 'FANOUT' FOR UPDATE").use { rs -> rs.next() } }
                        null
                    } catch (e: java.sql.SQLException) {
                        e.errorCode
                    }
                } finally {
                    c.rollback()
                }
            } shouldBe null
        }
    }
})

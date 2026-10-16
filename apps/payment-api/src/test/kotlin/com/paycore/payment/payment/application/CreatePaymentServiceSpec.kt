package com.paycore.payment.payment.application

import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.adapter.persistence.FaultInjector
import com.paycore.payment.payment.adapter.persistence.JpaOrderPaymentControlRepository
import com.paycore.payment.payment.adapter.persistence.JpaPaymentRepository
import com.paycore.payment.payment.adapter.persistence.PaymentDbTest
import com.paycore.payment.payment.adapter.persistence.PersistenceTestData.USD
import com.paycore.payment.payment.adapter.persistence.PersistenceTestData.intent
import com.paycore.payment.payment.adapter.persistence.PersistenceTestData.newOrder
import com.paycore.payment.payment.adapter.persistence.PersistenceTestData.t0
import com.paycore.payment.payment.application.CreatePaymentFixture.command
import com.paycore.payment.payment.application.CreatePaymentFixture.newIdentifiers
import com.paycore.payment.payment.application.CreatePaymentFixture.newKey
import com.paycore.payment.payment.application.CreatePaymentFixture.rows
import com.paycore.payment.payment.application.CreatePaymentFixture.rowsOf
import com.paycore.payment.payment.application.provided.CreatePaymentFailure
import com.paycore.payment.payment.application.provided.CreatePaymentOutcome
import com.paycore.payment.payment.application.provided.CreatePaymentRejection
import com.paycore.payment.payment.application.provided.CreatePaymentResult
import com.paycore.payment.payment.application.provided.CreatePaymentUseCase
import com.paycore.payment.payment.application.provided.GeneratedIdentifier
import com.paycore.payment.payment.application.provided.ReportSuccessAfterTerminationUseCase
import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.IdempotencyKey
import com.paycore.payment.payment.domain.MerchantAccountId
import com.paycore.payment.payment.domain.OrderKey
import com.paycore.payment.payment.domain.OrderPaymentControl
import com.paycore.payment.payment.domain.Payment
import com.paycore.payment.payment.domain.PaymentId
import com.paycore.payment.payment.domain.PaymentStatus
import com.paycore.payment.payment.domain.TerminationEvidence
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration

/**
 * CreatePayment 로컬 TX 행위(실제 MySQL 8.4, 순차 요청). 동시 요청·장애 주입은 CreatePaymentConcurrencySpec·CreatePaymentRollbackSpec.
 * 초안 CP-01·CP-03/04(순차 의미)·CP-05·CP-06·CP-10(식별자 충돌), D-CPC-004(30일)·D-CPC-007(거절 재생)·D-CPD-004(hold).
 */
@PaymentDbTest
class CreatePaymentServiceSpec(
    private val service: CreatePaymentUseCase,
    private val incidents: ReportSuccessAfterTerminationUseCase,
    private val payments: JpaPaymentRepository,
    private val controls: JpaOrderPaymentControlRepository,
    private val transactionManager: PlatformTransactionManager,
    private val faults: FaultInjector,
    private val eligibility: RecordingEligibilityPort,
    private val db: DbAccess,
) : BehaviorSpec({

    afterEach { faults.reset() }

    val tx = TransactionTemplate(transactionManager)

    /** 저장된 Payment·control을 domain 전이로 바꾼다(승인·종료는 AUTH 범위라 시험 준비만 Port로 한다 — control → Payment 잠금 순서). */
    fun change(order: OrderKey, paymentId: PaymentId, block: (Payment, OrderPaymentControl) -> Unit) {
        tx.executeWithoutResult {
            val control = controls.lock(order)!!
            val payment = payments.lock(paymentId)!!
            block(payment.value, control.value)
            payments.update(payment.value, payment.version)
            controls.update(control.value, control.version, t0)
        }
    }

    fun created(result: CreatePaymentResult) = result.shouldBeInstanceOf<CreatePaymentResult.Accepted>()

    Given("CP-01: a new order") {
        When("CreatePayment runs") {
            Then("one REQUESTED payment, one control, one success record and one PaymentRequested event are committed together") {
                val order = newOrder()
                val evaluated = eligibility.callsFor(order).size
                val cmd = command(order, intent(amount = 1050, currency = USD), orderName = "주문명 🍀")
                val accepted = created(service.create(cmd))

                accepted.outcome shouldBe CreatePaymentOutcome.CREATED
                accepted.replayed shouldBe false
                accepted.payment.paymentId shouldBe cmd.newIdentifiers.paymentId
                accepted.payment.paymentKey shouldBe cmd.newIdentifiers.paymentKey
                accepted.payment.lifecycleAtCreation shouldBe PaymentStatus.REQUESTED
                accepted.payment.paymentCreatedAt shouldBe t0
                accepted.payment.money shouldBe cmd.orderIntent.money
                eligibility.callsFor(order).size shouldBe evaluated + 1
                db.rowsOf(order) shouldBe rows(payment = 1, control = 1, idempotency = 1, outbox = 1)

                val payment = db.withApp { c -> with(db) { c.rows("SELECT * FROM payment WHERE payment_id = ?", cmd.newIdentifiers.paymentId.value) } }.single()
                payment["status"] shouldBe "REQUESTED"
                payment["slot_generation"].toString() shouldBe "1"
                payment["order_intent_fingerprint"] shouldBe cmd.orderIntent.fingerprint().value
                payment["eligibility_snapshot_version"] shouldBe "demo-snapshot-1"
                payment["order_name"] shouldBe "주문명 🍀"

                val control = db.withApp { c ->
                    with(db) { c.rows("SELECT * FROM order_payment_control WHERE merchant_account_id = ?", order.merchantAccountId.value) }
                }.single()
                control["current_payment_id"] shouldBe cmd.newIdentifiers.paymentId.value
                control["generation"].toString() shouldBe "1"
                control["fixed_intent_fingerprint"] shouldBe cmd.orderIntent.fingerprint().value

                val record = db.withApp { c ->
                    with(db) { c.rows("SELECT * FROM idempotency_request WHERE idempotency_key = ?", cmd.idempotencyKey.value) }
                }.single()
                record["result_kind"] shouldBe "SUCCESS"
                record["result_code"] shouldBe "CREATED"
                record["result_payment_id"] shouldBe cmd.newIdentifiers.paymentId.value
                record["request_record_id"] shouldBe cmd.newIdentifiers.idempotencyRecordId.value

                val event = db.withApp { c ->
                    with(db) { c.rows("SELECT * FROM outbox_event WHERE aggregate_id = ?", cmd.newIdentifiers.paymentId.value) }
                }.single()
                event["event_id"] shouldBe cmd.newIdentifiers.eventId.value
                event["effect_key"] shouldBe "PaymentRequested:${cmd.newIdentifiers.paymentId.value}"
                event["event_category"] shouldBe "INTEGRATION_NON_FINANCIAL"
                event["aggregate_version"].toString() shouldBe "0"
                event["correlation_id"] shouldBe cmd.correlationId
                event["causation_id"] shouldBe cmd.newIdentifiers.idempotencyRecordId.value
            }
        }
    }

    Given("CP-03 (sequential): the same idempotency key is sent again") {
        val order = newOrder()
        val first = command(order)
        val original = created(service.create(first))

        When("with the same content (e.g. the first response was lost after commit)") {
            Then("the stored first result is replayed: same payment, no new rows, eligibility not re-run") {
                val evaluated = eligibility.callsFor(order).size
                val again = first.copy(newIdentifiers = newIdentifiers(), correlationId = "req-retry")
                val replay = created(service.create(again))
                replay.replayed shouldBe true
                replay.outcome shouldBe CreatePaymentOutcome.CREATED
                replay.payment shouldBe original.payment
                eligibility.callsFor(order).size shouldBe evaluated
                db.rowsOf(order) shouldBe rows(payment = 1, control = 1, idempotency = 1, outbox = 1)
            }
        }

        When("with different content (only the display name changed)") {
            Then("it is IdempotencyKeyReused and nothing changes") {
                val changed = command(order, key = first.idempotencyKey, orderName = "different name")
                service.create(changed) shouldBe CreatePaymentResult.IdempotencyKeyReused
                db.rowsOf(order) shouldBe rows(payment = 1, control = 1, idempotency = 1, outbox = 1)
            }
        }

        When("the key differs only in letter case (D-CP-001)") {
            Then("it is a different key: the active payment is reused, not replayed") {
                val upper = IdempotencyKey(first.idempotencyKey.value.uppercase())
                upper shouldNotBe first.idempotencyKey
                val result = created(service.create(command(order, key = upper)))
                result.outcome shouldBe CreatePaymentOutcome.EXISTING_ACTIVE_PAYMENT
                result.replayed shouldBe false
            }
        }
    }

    Given("CP-04 (sequential): a different idempotency key for an order with an active payment") {
        When("the fixed input is the same (only the display name differs)") {
            Then("the active payment is reused (EXISTING_ACTIVE_PAYMENT), recorded for the new key, no event") {
                val order = newOrder()
                val first = created(service.create(command(order)))
                val second = command(order, orderName = "other display name")
                val result = created(service.create(second))
                result.outcome shouldBe CreatePaymentOutcome.EXISTING_ACTIVE_PAYMENT
                result.payment.paymentId shouldBe first.payment.paymentId
                result.payment.paymentCreatedAt shouldBe first.payment.paymentCreatedAt
                db.rowsOf(order) shouldBe rows(payment = 1, control = 1, idempotency = 2, outbox = 1)

                // 그 키의 재요청도 같은 재사용 결과를 재생한다.
                val replay = created(service.create(second.copy(newIdentifiers = newIdentifiers())))
                replay.replayed shouldBe true
                replay.outcome shouldBe CreatePaymentOutcome.EXISTING_ACTIVE_PAYMENT
            }
        }

        When("the fixed input differs (amount, currency or site)") {
            Then("each is FIXED_INTENT_CONFLICT referencing the current payment, stored and replayed for that key (D-CPC-007)") {
                val order = newOrder()
                val first = created(service.create(command(order)))
                listOf(intent(amount = 10_001), intent(currency = USD), intent(site = com.paycore.payment.payment.domain.MerchantSiteId("demo-site-002")))
                    .forEach { changed ->
                        val cmd = command(order, changed)
                        val rejected = service.create(cmd).shouldBeInstanceOf<CreatePaymentResult.Rejected>()
                        rejected.rejection shouldBe CreatePaymentRejection.FIXED_INTENT_CONFLICT
                        rejected.referencePaymentId shouldBe first.payment.paymentId
                        rejected.replayed shouldBe false

                        val evaluated = eligibility.callsFor(order).size
                        val replay = service.create(cmd.copy(newIdentifiers = newIdentifiers())).shouldBeInstanceOf<CreatePaymentResult.Rejected>()
                        replay.replayed shouldBe true
                        replay.rejection shouldBe CreatePaymentRejection.FIXED_INTENT_CONFLICT
                        eligibility.callsFor(order).size shouldBe evaluated
                    }
                db.rowsOf(order) shouldBe rows(payment = 1, control = 1, idempotency = 4, outbox = 1)
            }
        }
    }

    Given("CP-05: the current payment is in progress or authorized") {
        When("it is AUTHORIZING or UNKNOWN") {
            Then("a new key reuses it (no new payment)") {
                listOf(PaymentStatus.AUTHORIZING, PaymentStatus.UNKNOWN).forEach { status ->
                    val order = newOrder()
                    val first = created(service.create(command(order)))
                    change(order, first.payment.paymentId) { p, _ ->
                        p.startAuthorization()
                        if (status == PaymentStatus.UNKNOWN) p.markUnknown()
                    }
                    val result = created(service.create(command(order)))
                    result.outcome shouldBe CreatePaymentOutcome.EXISTING_ACTIVE_PAYMENT
                    result.payment.paymentId shouldBe first.payment.paymentId
                    db.rowsOf(order)["payment"] shouldBe 1
                }
            }
        }

        When("it was authorized (even if later fully refunded)") {
            Then("a new key is AUTHORIZED_NEW_ORDER_REQUIRED referencing the authorized payment") {
                val order = newOrder()
                val first = created(service.create(command(order)))
                change(order, first.payment.paymentId) { p, c ->
                    p.startAuthorization()
                    p.markAuthorized()
                    c.recordAuthorized(p)
                }
                val rejected = service.create(command(order)).shouldBeInstanceOf<CreatePaymentResult.Rejected>()
                rejected.rejection shouldBe CreatePaymentRejection.AUTHORIZED_NEW_ORDER_REQUIRED
                rejected.referencePaymentId shouldBe first.payment.paymentId
                db.rowsOf(order) shouldBe rows(payment = 1, control = 1, idempotency = 2, outbox = 1)
            }
        }
    }

    Given("CP-06: the current payment ended FAILED/EXPIRED") {
        When("with sufficient evidence (D-CPD-002) and a new key") {
            Then("a second payment occupies generation 2 with its own event; the original key still replays the first result") {
                val order = newOrder()
                val firstCmd = command(order)
                val first = created(service.create(firstCmd))
                change(order, first.payment.paymentId) { p, _ ->
                    p.startAuthorization()
                    p.fail(TerminationEvidence.INSTITUTION_CONFIRMED_DECLINE)
                }
                val secondCmd = command(order)
                val second = created(service.create(secondCmd))
                second.outcome shouldBe CreatePaymentOutcome.CREATED
                second.payment.paymentId shouldBe secondCmd.newIdentifiers.paymentId
                db.rowsOf(order) shouldBe rows(payment = 2, control = 1, idempotency = 2, outbox = 2)
                db.count(
                    "SELECT COUNT(*) FROM order_payment_control WHERE merchant_account_id = ? AND generation = 2 AND current_payment_id = ?",
                    order.merchantAccountId.value, second.payment.paymentId.value,
                ) shouldBe 1
                db.count("SELECT COUNT(*) FROM payment WHERE payment_id = ? AND slot_generation = 2", second.payment.paymentId.value) shouldBe 1
                db.count("SELECT COUNT(*) FROM payment WHERE payment_id = ? AND status = 'FAILED'", first.payment.paymentId.value) shouldBe 1

                val replay = created(service.create(firstCmd.copy(newIdentifiers = newIdentifiers())))
                replay.replayed shouldBe true
                replay.payment.paymentId shouldBe first.payment.paymentId
            }
        }

        When("without sufficient evidence (timeout only, or none)") {
            Then("a new key is TERMINATION_EVIDENCE_INSUFFICIENT and no payment is created") {
                val order = newOrder()
                val first = created(service.create(command(order)))
                change(order, first.payment.paymentId) { p, _ -> p.fail(TerminationEvidence.TIMEOUT) }
                val rejected = service.create(command(order)).shouldBeInstanceOf<CreatePaymentResult.Rejected>()
                rejected.rejection shouldBe CreatePaymentRejection.TERMINATION_EVIDENCE_INSUFFICIENT
                rejected.referencePaymentId shouldBe first.payment.paymentId
                db.rowsOf(order)["payment"] shouldBe 1
            }
        }
    }

    Given("D-CPD-004: the order is on incident hold") {
        Then("even while a newer attempt is active, a same-input request is INCIDENT_HOLD referencing the current payment") {
            val order = newOrder()
            val first = created(service.create(command(order)))
            change(order, first.payment.paymentId) { p, _ -> p.fail(TerminationEvidence.PRE_TRANSMISSION_FAILURE_CONFIRMED) }
            val second = created(service.create(command(order)))
            second.outcome shouldBe CreatePaymentOutcome.CREATED
            // 첫 시도(FAILED)에 대해 늦은 승인 성공이 보고됐다 → 주문 사건 보류.
            incidents.report(order, first.payment.paymentId, t0)

            val rejected = service.create(command(order)).shouldBeInstanceOf<CreatePaymentResult.Rejected>()
            rejected.rejection shouldBe CreatePaymentRejection.INCIDENT_HOLD
            rejected.referencePaymentId shouldBe second.payment.paymentId
            db.rowsOf(order) shouldBe rows(payment = 2, control = 1, idempotency = 3, outbox = 2, hold = 1)
        }
    }

    Given("D-CPC-004: idempotency records are kept for 30 days") {
        When("the same key and content arrive just before and exactly at expiry") {
            Then("before expiry the result is replayed; at expiry it is a new request (renewed record, order rules still apply)") {
                val order = newOrder()
                val cmd = command(order)
                val first = created(service.create(cmd))

                val beforeExpiry = created(service.create(cmd.copy(requestedAt = t0.plus(Duration.ofDays(30)).minusMillis(1))))
                beforeExpiry.replayed shouldBe true

                val evaluated = eligibility.callsFor(order).size
                val atExpiry = cmd.copy(requestedAt = t0.plus(Duration.ofDays(30)), newIdentifiers = newIdentifiers())
                val renewed = created(service.create(atExpiry))
                renewed.replayed shouldBe false
                eligibility.callsFor(order).size shouldBe evaluated + 1
                // 주문 통제는 영구: 활성 시도가 있으므로 재사용(동시 활성 Payment·이중 생성 없음).
                renewed.outcome shouldBe CreatePaymentOutcome.EXISTING_ACTIVE_PAYMENT
                renewed.payment.paymentId shouldBe first.payment.paymentId
                db.rowsOf(order) shouldBe rows(payment = 1, control = 1, idempotency = 1, outbox = 1)
                db.count(
                    "SELECT COUNT(*) FROM idempotency_request WHERE idempotency_key = ? AND request_record_id = ? AND result_code = 'EXISTING_ACTIVE_PAYMENT'",
                    cmd.idempotencyKey.value, atExpiry.newIdentifiers.idempotencyRecordId.value,
                ) shouldBe 1
            }
        }

        When("the original attempt was safely terminated and the same key returns after expiry") {
            Then("it legitimately creates the second payment (generation 2), as for any new request") {
                val order = newOrder()
                val cmd = command(order)
                val first = created(service.create(cmd))
                change(order, first.payment.paymentId) { p, _ -> p.expire(TerminationEvidence.NOT_TRANSMITTED_AND_SESSION_DISCARDED) }
                val later = cmd.copy(requestedAt = t0.plus(Duration.ofDays(31)), newIdentifiers = newIdentifiers())
                val second = created(service.create(later))
                second.outcome shouldBe CreatePaymentOutcome.CREATED
                second.payment.paymentId shouldBe later.newIdentifiers.paymentId
                db.rowsOf(order) shouldBe rows(payment = 2, control = 1, idempotency = 1, outbox = 2)
            }
        }
    }

    Given("merchant and environment scope") {
        Then("the same order id and key under another MID or environment are independent orders") {
            val order = newOrder()
            val key = newKey()
            created(service.create(command(order, key = key))).outcome shouldBe CreatePaymentOutcome.CREATED
            val otherMid = OrderKey(order.environment, MerchantAccountId(order.merchantAccountId.value + "x"), order.merchantOrderId)
            created(service.create(command(otherMid, key = key))).outcome shouldBe CreatePaymentOutcome.CREATED
            val live = OrderKey(Environment.LIVE, order.merchantAccountId, order.merchantOrderId)
            created(service.create(command(live, key = key))).outcome shouldBe CreatePaymentOutcome.CREATED
        }
    }

    Given("CP-10: a generated identifier collides with an existing one") {
        Then("it is IdentifierCollision (not a payload conflict), nothing is stored, and the same key succeeds with new identifiers") {
            val existing = created(service.create(command(newOrder())))
            val order = newOrder()
            val key = newKey()
            val collidingId = command(order, key = key, ids = newIdentifiers().copy(paymentId = existing.payment.paymentId))
            shouldThrow<CreatePaymentFailure.IdentifierCollision> { service.create(collidingId) }
                .identifier shouldBe GeneratedIdentifier.PAYMENT_ID
            val collidingKey = command(order, key = key, ids = newIdentifiers().copy(paymentKey = existing.payment.paymentKey))
            shouldThrow<CreatePaymentFailure.IdentifierCollision> { service.create(collidingKey) }
                .identifier shouldBe GeneratedIdentifier.PAYMENT_KEY
            db.rowsOf(order) shouldBe rows(payment = 0, control = 0, idempotency = 0, outbox = 0)

            created(service.create(command(order, key = key))).outcome shouldBe CreatePaymentOutcome.CREATED
        }
    }

    Given("the use case is called inside an existing transaction") {
        Then("it refuses (it owns its local transaction)") {
            shouldThrow<IllegalStateException> {
                tx.executeWithoutResult { service.create(command(newOrder())) }
            }
        }
    }
})

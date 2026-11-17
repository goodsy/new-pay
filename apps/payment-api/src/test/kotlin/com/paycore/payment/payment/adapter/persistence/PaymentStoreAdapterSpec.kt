package com.paycore.payment.payment.adapter.persistence

import com.paycore.payment.payment.domain.fail
import com.paycore.payment.payment.domain.expire
import com.paycore.payment.payment.domain.addTerminationEvidence
import com.paycore.payment.payment.domain.reportSuccessAfterTermination
import com.paycore.payment.payment.adapter.persistence.DbAccess.Companion.unique
import com.paycore.payment.payment.adapter.persistence.PersistenceTestData.USD
import com.paycore.payment.payment.adapter.persistence.PersistenceTestData.attributes
import com.paycore.payment.payment.adapter.persistence.PersistenceTestData.intent
import com.paycore.payment.payment.adapter.persistence.PersistenceTestData.newOrder
import com.paycore.payment.payment.adapter.persistence.PersistenceTestData.requested
import com.paycore.payment.payment.adapter.persistence.PersistenceTestData.t0
import com.paycore.payment.payment.application.provided.CreatePaymentOutcome
import com.paycore.payment.payment.application.provided.CreatePaymentRejection
import com.paycore.payment.payment.application.provided.EventId
import com.paycore.payment.payment.application.provided.IdempotencyRecordId
import com.paycore.payment.payment.application.provided.RequestFingerprint
import com.paycore.payment.payment.application.required.ControlAcquisition
import com.paycore.payment.payment.application.required.IdempotencyReservation
import com.paycore.payment.payment.application.required.IdempotencyReservationOutcome
import com.paycore.payment.payment.application.required.IdempotencyScope
import com.paycore.payment.payment.application.required.IdempotentOperation
import com.paycore.payment.payment.application.required.OutboxEvent
import com.paycore.payment.payment.application.required.PaymentRequestedV1Payload
import com.paycore.payment.payment.application.required.PersistenceFailure
import com.paycore.payment.payment.application.required.StoreConstraint
import com.paycore.payment.payment.application.required.StoredRequestResult
import com.paycore.payment.payment.domain.IdempotencyKey
import com.paycore.payment.payment.domain.IncidentHold
import com.paycore.payment.payment.domain.IncidentReason
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.OrderPaymentControl
import com.paycore.payment.payment.domain.PaymentStatus
import com.paycore.payment.payment.domain.TerminationEvidence
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.UUID

/**
 * Port 구현(JPA adapter)을 실제 MySQL 8.4에서 시험한다: Domain ↔ Entity 왕복, @Version 기반 오래된 domain 덮어쓰기 거부,
 * append-only 자식 행, insert-first 경쟁 처리, 제약 위반·잠금 timeout 분류, TX 밖 호출 거부.
 */
@PaymentDbTest
class PaymentStoreAdapterSpec(
    private val payments: JpaPaymentRepository,
    private val controls: JpaOrderPaymentControlRepository,
    private val idempotency: JpaIdempotencyRecordStore,
    private val outbox: JpaOutboxStore,
    private val transactionManager: PlatformTransactionManager,
    private val db: DbAccess,
) : BehaviorSpec({

    val tx = TransactionTemplate(transactionManager)
    fun <T> inTx(block: () -> T): T {
        val holder = mutableListOf<T>()
        tx.executeWithoutResult { holder += block() }
        return holder.single()
    }

    Given("a new REQUESTED payment") {
        When("it is inserted and its creation record is read back") {
            Then("the creation values round-trip exactly (minor-unit Long, upper-case currency, UTF-8 order name)") {
                val order = newOrder()
                val i = intent(amount = Long.MAX_VALUE, currency = USD)
                val payment = requested(order, i)
                val attrs = attributes(i, orderName = "주문 테스트 🍀")
                inTx { payments.insertNew(payment, attrs) } shouldBe 0L

                val record = inTx { payments.findCreationRecord(payment.id) }.shouldNotBeNull()
                record.paymentId shouldBe payment.id
                record.orderKey shouldBe order
                record.slotGeneration shouldBe 1L
                record.attributes shouldBe attrs
                record.attributes.money shouldBe Money(Long.MAX_VALUE, USD)
            }

            Then("an absent order name stays absent") {
                val payment = requested(newOrder())
                inTx { payments.insertNew(payment, attributes(orderName = null)) }
                inTx { payments.findCreationRecord(payment.id) }!!.attributes.orderName shouldBe null
            }
        }

        When("the same payment id, payment key or order generation is inserted again") {
            Then("each is a UniqueConflict naming the business key, and nothing of the failed insert remains") {
                val order = newOrder()
                val first = requested(order)
                val firstAttrs = attributes()
                inTx { payments.insertNew(first, firstAttrs) }

                shouldThrow<PersistenceFailure.UniqueConflict> {
                    inTx { payments.insertNew(requested(newOrder(), id = first.id), attributes()) }
                }.constraint shouldBe StoreConstraint.PAYMENT_ID
                shouldThrow<PersistenceFailure.UniqueConflict> {
                    inTx { payments.insertNew(requested(newOrder()), attributes(paymentKey = firstAttrs.paymentKey)) }
                }.constraint shouldBe StoreConstraint.PAYMENT_KEY
                shouldThrow<PersistenceFailure.UniqueConflict> {
                    inTx { payments.insertNew(requested(order), attributes()) }
                }.constraint shouldBe StoreConstraint.PAYMENT_ORDER_GENERATION
                db.count("SELECT COUNT(*) FROM payment WHERE merchant_account_id = ?", order.merchantAccountId.value) shouldBe 1
            }
        }
    }

    Given("a stored payment that changes state") {
        When("it is locked, transitioned and updated with the version it was read at") {
            Then("status and appended evidence persist, the version grows, and evidence stays append-only") {
                val payment = requested(newOrder())
                inTx { payments.insertNew(payment, attributes()) }

                val v1 = inTx {
                    val locked = payments.lock(payment.id)!!
                    locked.version shouldBe 0L
                    locked.value.startAuthorization()
                    locked.value.markUnknown()
                    locked.value.fail(TerminationEvidence.INSTITUTION_CONFIRMED_DECLINE)
                    payments.update(locked.value, locked.version)
                }
                v1 shouldBe 1L

                val v2 = inTx {
                    val locked = payments.lock(payment.id)!!
                    locked.value.status shouldBe PaymentStatus.FAILED
                    locked.value.terminationEvidence shouldContainExactly listOf(TerminationEvidence.INSTITUTION_CONFIRMED_DECLINE)
                    locked.value.hasSafeTermination() shouldBe true
                    // 상태는 그대로이고 자식(근거)만 추가돼도 Aggregate version이 오른다.
                    locked.value.addTerminationEvidence(TerminationEvidence.INSTITUTION_CONFIRMED_NO_EFFECT)
                    payments.update(locked.value, locked.version)
                }
                v2 shouldBe 2L

                val rows = db.withApp { c ->
                    with(db) { c.rows("SELECT seq, evidence FROM payment_termination_evidence WHERE payment_id = ? ORDER BY seq", payment.id.value) }
                }
                rows.map { (it["seq"] as Number).toInt() to it["evidence"] } shouldContainExactly listOf(
                    0 to "INSTITUTION_CONFIRMED_DECLINE",
                    1 to "INSTITUTION_CONFIRMED_NO_EFFECT",
                )
                inTx { payments.update(payments.lock(payment.id)!!.value, 2L) } shouldBe 2L
            }
        }

        When("a stale domain object (read at an older version) is written after another transaction committed") {
            Then("the write is an OptimisticConflict and the newer state is kept") {
                val payment = requested(newOrder())
                inTx { payments.insertNew(payment, attributes()) }
                val stale = inTx { payments.lock(payment.id)!! }

                inTx {
                    val fresh = payments.lock(payment.id)!!
                    fresh.value.startAuthorization()
                    payments.update(fresh.value, fresh.version)
                }

                stale.value.expire(null)
                shouldThrow<PersistenceFailure.OptimisticConflict> { inTx { payments.update(stale.value, stale.version) } }
                db.count("SELECT COUNT(*) FROM payment WHERE payment_id = ? AND status = 'AUTHORIZING' AND version = 1", payment.id.value) shouldBe 1
            }
        }

        When("a stored key no longer satisfies the domain format") {
            Then("restoring reports it instead of skipping it") {
                val mid = unique("mid")
                db.withApp { c ->
                    with(db) { c.update(PaymentSchemaRows.INSERT_PAYMENT, *PaymentSchemaRows.paymentArgs("$mid-p", mid = mid, orderId = "bad order")) }
                }
                shouldThrow<IllegalArgumentException> {
                    inTx { payments.lock(com.paycore.payment.payment.domain.PaymentId("$mid-p")) }
                }
            }
        }

        When("another connection holds the row lock longer than the lock wait timeout") {
            Then("lock() reports LockTimeout") {
                val payment = requested(newOrder())
                inTx { payments.insertNew(payment, attributes()) }
                db.app().use { holder ->
                    holder.autoCommit = false
                    with(db) { holder.scalar("SELECT payment_id FROM payment WHERE payment_id = ? FOR UPDATE", payment.id.value) }
                    val started = System.nanoTime()
                    shouldThrow<PersistenceFailure.LockTimeout> { inTx { payments.lock(payment.id) } }
                    Duration.ofNanos(System.nanoTime() - started).toMillis() shouldBeGreaterThanOrEqual 7_500
                    holder.rollback()
                }
            }
        }
    }

    Given("Port methods are called outside a transaction") {
        Then("they are refused (Port calls belong to an application local transaction)") {
            shouldThrow<IllegalTransactionStateException> { payments.lock(requested(newOrder()).id) }
            shouldThrow<IllegalTransactionStateException> { controls.lock(newOrder()) }
        }
    }

    Given("an order payment control") {
        When("it is opened for a new order and then a second request tries to open the same order") {
            Then("the first insert wins and the second gets the stored control without overwriting it") {
                val order = newOrder()
                val first = requested(order)
                val opened = inTx { controls.openOrLock(OrderPaymentControl.open(first), t0) }
                opened.shouldBeInstanceOf<ControlAcquisition.Opened>().control.version shouldBe 0L

                val other = requested(order, intent(amount = 999))
                val existing = inTx { controls.openOrLock(OrderPaymentControl.open(other), t0.plusSeconds(1)) }
                val stored = existing.shouldBeInstanceOf<ControlAcquisition.Existing>().control
                stored.value.currentPaymentId shouldBe first.id
                stored.value.fixedIntent shouldBe first.intent
                stored.value.generation shouldBe 1L
            }
        }

        When("a new payment occupies the slot after a safe termination, and a late success is reported") {
            Then("generation, current payment and the incident hold persist; holds are append-only and stored once") {
                val order = newOrder()
                val first = requested(order)
                inTx {
                    payments.insertNew(first, attributes())
                    controls.openOrLock(OrderPaymentControl.open(first), t0)
                }
                first.startAuthorization()
                first.fail(TerminationEvidence.INSTITUTION_CONFIRMED_DECLINE)
                val second = requested(order, slotGeneration = 2)

                val v1 = inTx {
                    val locked = controls.lock(order)!!
                    locked.value.occupy(second, first)
                    locked.value.reportSuccessAfterTermination(first)
                    controls.update(locked.value, locked.version, t0.plusSeconds(5))
                }
                v1 shouldBe 1L

                val restored = inTx { controls.lock(order)!! }
                restored.value.generation shouldBe 2L
                restored.value.currentPaymentId shouldBe second.id
                restored.value.incidentHolds shouldContainExactly listOf(IncidentHold.reconstitute(first.id, IncidentReason.SUCCESS_AFTER_TERMINATION, 1, null))

                // 같은 늦은 성공 재보고: domain이 no-op, 저장도 그대로(행 1, version 불변).
                inTx {
                    val locked = controls.lock(order)!!
                    locked.value.reportSuccessAfterTermination(first)
                    controls.update(locked.value, locked.version, t0.plusSeconds(6))
                } shouldBe 1L
                db.count("SELECT COUNT(*) FROM incident_hold WHERE merchant_account_id = ?", order.merchantAccountId.value) shouldBe 1
            }
        }

        When("a stale control (read before another transaction's update) is written") {
            Then("the write is an OptimisticConflict") {
                val order = newOrder()
                val first = requested(order)
                inTx { controls.openOrLock(OrderPaymentControl.open(first), t0) }
                val stale = inTx { controls.lock(order)!! }
                inTx {
                    val fresh = controls.lock(order)!!
                    fresh.value.reportSuccessAfterTermination(first.also { it.expire(null) })
                    controls.update(fresh.value, fresh.version, t0.plusSeconds(1))
                }
                stale.value.reportSuccessAfterTermination(first)
                shouldThrow<PersistenceFailure.OptimisticConflict> { inTx { controls.update(stale.value, stale.version, t0.plusSeconds(2)) } }
            }
        }
    }

    Given("idempotency records") {
        fun scope(mid: String = unique("mid")) = IdempotencyScope(
            com.paycore.payment.payment.domain.Environment.TEST,
            com.paycore.payment.payment.domain.MerchantAccountId(mid),
            IdempotentOperation.CREATE_PAYMENT,
            IdempotencyKey(unique("key")),
        )

        fun reservation(scope: IdempotencyScope, at: java.time.Instant = t0, fingerprint: String = "a") = IdempotencyReservation(
            scope,
            IdempotencyRecordId(UUID.randomUUID().toString()),
            RequestFingerprint("r1:" + fingerprint.repeat(64).take(64)),
            at,
            at.plus(Duration.ofDays(30)),
        )

        When("a key is reserved, completed, and requested again") {
            Then("the second request sees the stored record with its result and original record id") {
                val s = scope()
                val first = reservation(s)
                val paymentId = com.paycore.payment.payment.domain.PaymentId(unique("pay"))
                inTx {
                    idempotency.reserveOrLock(first) shouldBe IdempotencyReservationOutcome.Reserved
                    idempotency.complete(s, StoredRequestResult.Succeeded(CreatePaymentOutcome.CREATED, paymentId), t0)
                }
                val again = inTx { idempotency.reserveOrLock(reservation(s, fingerprint = "b")) }
                val record = again.shouldBeInstanceOf<IdempotencyReservationOutcome.Existing>().record
                record.recordId shouldBe first.recordId
                record.requestFingerprint shouldBe first.requestFingerprint
                record.result shouldBe StoredRequestResult.Succeeded(CreatePaymentOutcome.CREATED, paymentId)
                record.isExpiredAt(t0.plus(Duration.ofDays(30)).minusMillis(1)) shouldBe false
            }
        }

        When("MERCHANT-CODE-001 C-37: a create result is stored with the merchant code read at that request") {
            Then("the code is stored in the same row and read back with the result; a result without a code reads back null (pre-MC-4 rows too)") {
                val coded = scope()
                val plain = scope()
                val paymentId = com.paycore.payment.payment.domain.PaymentId(unique("pay"))
                inTx {
                    idempotency.reserveOrLock(reservation(coded))
                    idempotency.complete(coded, StoredRequestResult.Succeeded(CreatePaymentOutcome.CREATED, paymentId, "storeshop01"), t0)
                    idempotency.reserveOrLock(reservation(plain))
                    idempotency.complete(plain, StoredRequestResult.Succeeded(CreatePaymentOutcome.EXISTING_ACTIVE_PAYMENT, paymentId), t0)
                }
                inTx { idempotency.reserveOrLock(reservation(coded)) }.shouldBeInstanceOf<IdempotencyReservationOutcome.Existing>().record.result shouldBe
                    StoredRequestResult.Succeeded(CreatePaymentOutcome.CREATED, paymentId, "storeshop01")
                inTx { idempotency.reserveOrLock(reservation(plain)) }.shouldBeInstanceOf<IdempotencyReservationOutcome.Existing>().record.result shouldBe
                    StoredRequestResult.Succeeded(CreatePaymentOutcome.EXISTING_ACTIVE_PAYMENT, paymentId, null)
                db.withApp { c -> with(db) { c.scalar("SELECT result_merchant_code FROM idempotency_request WHERE idempotency_key = ?", coded.key.value) } } shouldBe "storeshop01"
                // 형식 밖 값은 결과 타입이 받지 않는다(저장 결함이면 fail-closed).
                shouldThrow<IllegalArgumentException> { StoredRequestResult.Succeeded(CreatePaymentOutcome.CREATED, paymentId, "Bad_Code") }
            }
        }

        When("a business rejection is stored") {
            Then("it is read back as a rejection with its reference payment") {
                val s = scope()
                val ref = com.paycore.payment.payment.domain.PaymentId(unique("pay"))
                inTx {
                    idempotency.reserveOrLock(reservation(s))
                    idempotency.complete(s, StoredRequestResult.Rejected(CreatePaymentRejection.INCIDENT_HOLD, ref), t0)
                }
                val record = inTx { idempotency.reserveOrLock(reservation(s)) }
                    .shouldBeInstanceOf<IdempotencyReservationOutcome.Existing>().record
                record.result shouldBe StoredRequestResult.Rejected(CreatePaymentRejection.INCIDENT_HOLD, ref)
            }
        }

        When("the record has expired (30 days) but is not deleted yet") {
            Then("the expired row is renewed in place for the new request; a live row cannot be renewed") {
                val s = scope()
                inTx {
                    idempotency.reserveOrLock(reservation(s))
                    // MERCHANT-CODE-001 C-37: 옛 결과에 코드를 둬서 만료 갱신이 그 코드도 지우는지 함께 본다(아래 새 결과 기대값은 그대로 — 코드 없음).
                    idempotency.complete(s, StoredRequestResult.Succeeded(CreatePaymentOutcome.CREATED, com.paycore.payment.payment.domain.PaymentId("p-old"), "oldshop01"), t0)
                }
                val later = t0.plus(Duration.ofDays(30))
                val renewal = reservation(s, at = later, fingerprint = "c")
                inTx {
                    val existing = idempotency.reserveOrLock(renewal).shouldBeInstanceOf<IdempotencyReservationOutcome.Existing>()
                    existing.record.isExpiredAt(later) shouldBe true
                    idempotency.renewExpired(renewal)
                    idempotency.complete(s, StoredRequestResult.Succeeded(CreatePaymentOutcome.CREATED, com.paycore.payment.payment.domain.PaymentId("p-new")), later)
                }
                val record = inTx { idempotency.reserveOrLock(reservation(s, at = later)) }
                    .shouldBeInstanceOf<IdempotencyReservationOutcome.Existing>().record
                record.recordId shouldBe renewal.recordId
                record.createdAt shouldBe later
                record.result shouldBe StoredRequestResult.Succeeded(CreatePaymentOutcome.CREATED, com.paycore.payment.payment.domain.PaymentId("p-new"))
                db.count("SELECT COUNT(*) FROM idempotency_request WHERE idempotency_key = ?", s.key.value) shouldBe 1

                shouldThrow<IllegalStateException> {
                    inTx {
                        idempotency.reserveOrLock(reservation(s, at = later.plusSeconds(1)))
                        idempotency.renewExpired(reservation(s, at = later.plusSeconds(1)))
                    }
                }
            }
        }
    }

    Given("an outbox event") {
        fun event(paymentId: String = unique("pay"), eventId: EventId = EventId(UUID.randomUUID().toString())) = OutboxEvent(
            eventId = eventId,
            eventType = "PaymentRequested",
            schemaVersion = 1,
            eventCategory = "INTEGRATION_NON_FINANCIAL",
            producer = "payment-api/payment",
            aggregateType = "Payment",
            aggregateId = paymentId,
            aggregateVersion = 0,
            occurredAt = t0,
            correlationId = "req-1",
            causationId = "cause-1",
            effectKey = "PaymentRequested:$paymentId",
            payload = PaymentRequestedV1Payload(
                environment = com.paycore.payment.payment.domain.Environment.TEST,
                merchantAccountId = "demo-mid",
                merchantSiteId = "demo-site-001",
                merchantOrderId = "order-1",
                paymentId = paymentId,
                amountMinor = 9_007_199_254_740_993,
                currency = "KRW",
                paymentMethod = "CARD",
                slotGeneration = 1,
                orderIntentFingerprint = "v1:abc",
                lifecycle = "REQUESTED",
                requestedAt = t0,
            ),
        )

        When("it is appended") {
            Then("the envelope columns and the v1 payload (without paymentKey/orderName) are stored as PENDING") {
                val e = event()
                inTx { outbox.append(e) }
                val row = db.withApp { c -> with(db) { c.rows("SELECT * FROM outbox_event WHERE event_id = ?", e.eventId.value) } }.single()
                row["effect_key"] shouldBe e.effectKey
                row["publish_status"] shouldBe "PENDING"
                row["aggregate_version"].toString() shouldBe "0"
                val payload = JsonMapper.builder().build().readTree(row["payload"].toString())
                payload.propertyNames().toSet() shouldBe setOf(
                    "environment", "merchantAccountId", "merchantSiteId", "merchantOrderId", "paymentId", "amount", "currency",
                    "paymentMethod", "slotGeneration", "orderIntentFingerprint", "lifecycle", "requestedAt",
                )
                // 2^53+1: JSON 정수로 정확히 보존(부동소수점 변환 없음).
                payload.get("amount").asLong() shouldBe 9_007_199_254_740_993
                payload.get("amount").isIntegralNumber shouldBe true
                payload.get("requestedAt").asString() shouldBe "2026-10-07T01:02:03.456Z"
            }
        }

        When("the same effect key or event id is appended again") {
            Then("it is a UniqueConflict naming the outbox key") {
                val e = event()
                inTx { outbox.append(e) }
                shouldThrow<PersistenceFailure.UniqueConflict> {
                    inTx { outbox.append(event(paymentId = e.aggregateId)) }
                }.constraint shouldBe StoreConstraint.OUTBOX_EFFECT_KEY
                shouldThrow<PersistenceFailure.UniqueConflict> {
                    inTx { outbox.append(event(eventId = e.eventId)) }
                }.constraint shouldBe StoreConstraint.OUTBOX_EVENT_ID
            }
        }
    }
})

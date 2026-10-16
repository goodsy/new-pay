package com.paycore.payment.payment.application

import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.adapter.persistence.FaultInjector
import com.paycore.payment.payment.adapter.persistence.FaultPoint
import com.paycore.payment.payment.adapter.persistence.JpaPaymentRepository
import com.paycore.payment.payment.adapter.persistence.PaymentDbTest
import com.paycore.payment.payment.adapter.persistence.PersistenceTestData.newOrder
import com.paycore.payment.payment.adapter.persistence.PersistenceTestData.t0
import com.paycore.payment.payment.application.CreatePaymentFixture.command
import com.paycore.payment.payment.application.CreatePaymentFixture.newIdentifiers
import com.paycore.payment.payment.application.CreatePaymentFixture.rows
import com.paycore.payment.payment.application.CreatePaymentFixture.rowsOf
import com.paycore.payment.payment.application.provided.CreatePaymentFailure
import com.paycore.payment.payment.application.provided.CreatePaymentOutcome
import com.paycore.payment.payment.application.provided.CreatePaymentResult
import com.paycore.payment.payment.application.provided.CreatePaymentUseCase
import com.paycore.payment.payment.domain.OrderKey
import com.paycore.payment.payment.domain.PaymentId
import com.paycore.payment.payment.domain.TerminationEvidence
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration

/**
 * 초안 CP-08: 저장 각 지점 실패 → 멱등 결과·control·Payment·Outbox가 함께 rollback(부분 성공 없음). 각 지점의 Port는 실제로 SQL을
 * 실행·flush한 **뒤**(행이 이 TX 연결에 쓰인 상태) 실패를 주입한다. commit 직전(beforeCommit) 실패도 본다. 실패 뒤 같은 키 재요청은
 * 정상 처리된다(커밋된 흔적이 없어 새 요청으로 시작).
 */
@PaymentDbTest
class CreatePaymentRollbackSpec(
    private val service: CreatePaymentUseCase,
    private val faults: FaultInjector,
    private val payments: JpaPaymentRepository,
    private val transactionManager: PlatformTransactionManager,
    private val db: DbAccess,
) : BehaviorSpec({

    afterEach { faults.reset() }

    class Injected(point: Any) : RuntimeException("injected failure after $point")

    fun failAfter(point: FaultPoint) = faults.after(point) { throw Injected(point) }

    fun failBeforeCommit() = faults.after(FaultPoint.IDEMPOTENCY_COMPLETE) {
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun beforeCommit(readOnly: Boolean) = throw Injected("beforeCommit")
            },
        )
    }

    val tx = TransactionTemplate(transactionManager)

    fun terminate(paymentId: PaymentId) = tx.executeWithoutResult {
        val p = payments.lock(paymentId)!!
        p.value.fail(TerminationEvidence.INSTITUTION_CONFIRMED_DECLINE)
        payments.update(p.value, p.version)
    }

    fun createdThenTerminated(): Pair<OrderKey, PaymentId> {
        val order = newOrder()
        val first = service.create(command(order)).shouldBeInstanceOf<CreatePaymentResult.Accepted>()
        terminate(first.payment.paymentId)
        return order to first.payment.paymentId
    }

    val newOrderPoints = listOf(
        FaultPoint.IDEMPOTENCY_RESERVE, FaultPoint.CONTROL_OPEN, FaultPoint.PAYMENT_INSERT, FaultPoint.OUTBOX_APPEND,
        FaultPoint.IDEMPOTENCY_COMPLETE, FaultPoint.PAYMENT_FIND,
    )

    Given("a new order (first control insert, generation 1)") {
        newOrderPoints.forEach { point ->
            When("the transaction fails right after $point") {
                Then("nothing of the request remains, and the same key then creates exactly once") {
                    val order = newOrder()
                    val cmd = command(order)
                    failAfter(point)
                    shouldThrow<Injected> { service.create(cmd) }
                    faults.reset()
                    db.rowsOf(order) shouldBe rows(payment = 0, control = 0, idempotency = 0, outbox = 0)

                    val result = service.create(cmd).shouldBeInstanceOf<CreatePaymentResult.Accepted>()
                    result.outcome shouldBe CreatePaymentOutcome.CREATED
                    result.replayed shouldBe false
                    db.rowsOf(order) shouldBe rows(payment = 1, control = 1, idempotency = 1, outbox = 1)
                }
            }
        }

        When("the commit phase itself fails (beforeCommit callback)") {
            Then("the caller gets OutcomeUnknown (commit-phase failures are never assumed rolled back), nothing remains, and the same key then creates") {
                val order = newOrder()
                val cmd = command(order)
                failBeforeCommit()
                val failure = shouldThrow<CreatePaymentFailure.OutcomeUnknown> { service.create(cmd) }
                generateSequence<Throwable>(failure) { it.cause }.any { it is Injected } shouldBe true
                faults.reset()
                db.rowsOf(order) shouldBe rows(payment = 0, control = 0, idempotency = 0, outbox = 0)
                service.create(cmd).shouldBeInstanceOf<CreatePaymentResult.Accepted>().outcome shouldBe CreatePaymentOutcome.CREATED
            }
        }
    }

    Given("an existing order whose payment ended safely (new payment occupies generation 2)") {
        listOf(
            FaultPoint.CONTROL_OPEN, FaultPoint.PAYMENT_LOCK, FaultPoint.CONTROL_UPDATE, FaultPoint.PAYMENT_INSERT, FaultPoint.OUTBOX_APPEND,
            FaultPoint.IDEMPOTENCY_COMPLETE,
        ).forEach { point ->
            When("the transaction fails right after $point") {
                Then("the slot stays at generation 1 on the first payment and no second payment, event or record remains") {
                    val (order, firstId) = createdThenTerminated()
                    failAfter(point)
                    shouldThrow<Injected> { service.create(command(order)) }
                    faults.reset()
                    db.rowsOf(order) shouldBe rows(payment = 1, control = 1, idempotency = 1, outbox = 1)
                    db.count(
                        "SELECT COUNT(*) FROM order_payment_control WHERE merchant_account_id = ? AND generation = 1 AND current_payment_id = ? AND version = 0",
                        order.merchantAccountId.value, firstId.value,
                    ) shouldBe 1
                }
            }
        }
    }

    Given("an existing order with an active payment (reuse path)") {
        Then("a failure after the result is written leaves no record for the new key") {
            val order = newOrder()
            service.create(command(order))
            failAfter(FaultPoint.IDEMPOTENCY_COMPLETE)
            shouldThrow<Injected> { service.create(command(order)) }
            db.rowsOf(order) shouldBe rows(payment = 1, control = 1, idempotency = 1, outbox = 1)
        }
    }

    Given("an expired idempotency record being renewed") {
        Then("a failure after the renewal keeps the original record unchanged") {
            val order = newOrder()
            val cmd = command(order)
            service.create(cmd)
            failAfter(FaultPoint.IDEMPOTENCY_RENEW)
            val later = cmd.copy(requestedAt = t0.plus(Duration.ofDays(30)), newIdentifiers = newIdentifiers())
            shouldThrow<Injected> { service.create(later) }
            db.count(
                "SELECT COUNT(*) FROM idempotency_request WHERE idempotency_key = ? AND request_record_id = ? AND result_code = 'CREATED'",
                cmd.idempotencyKey.value, cmd.newIdentifiers.idempotencyRecordId.value,
            ) shouldBe 1
        }
    }
})

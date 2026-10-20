package com.paycore.payment.payment.application

import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.adapter.persistence.FaultInjector
import com.paycore.payment.payment.adapter.persistence.FaultPoint
import com.paycore.payment.payment.adapter.web.PaymentApiTest
import com.paycore.payment.payment.application.provided.CancelOutcome
import com.paycore.payment.payment.application.provided.CancelRejection
import com.paycore.payment.payment.application.provided.ConfirmPaymentUseCase
import com.paycore.payment.payment.application.provided.OperatorCancelRejectionCode
import com.paycore.payment.payment.application.provided.OperatorCorrectionCancelResult
import com.paycore.payment.payment.application.provided.OperatorCorrectionCancelUseCase
import com.paycore.payment.payment.application.required.AuthenticationSessionRepository
import com.paycore.payment.payment.application.required.CheckoutSessionStore
import com.paycore.payment.payment.application.required.CheckoutTokenStore
import com.paycore.payment.payment.application.required.OrderPaymentControlRepository
import com.paycore.payment.payment.application.required.PaymentRepository
import com.paycore.payment.payment.domain.Money
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.springframework.transaction.PlatformTransactionManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 취소 TX1 경합(CANCEL-001 §9.1 K1~K5·K12, rev 0.3 m-3) — 실 MySQL 다중 연결. 첫 TX가 control을 잡은 채 나머지가 **실제로 잠금을 기다리는 것**을 관찰한 뒤 풀어 준다(경합 증거).
 * 기대값은 명세 §9.1 표에서 직접 적는다. VAN 수신 수는 S6(전송)에서 본다(rev 0.3 m-1).
 */
@PaymentApiTest
class CancelStartConcurrencySpec(
    private val start: CancelStartService,
    private val operator: OperatorCorrectionCancelUseCase,
    private val admission: ScriptedAdmissionPort,
    private val faults: FaultInjector,
    confirm: ConfirmPaymentUseCase,
    payments: PaymentRepository,
    controls: OrderPaymentControlRepository,
    sessions: AuthenticationSessionRepository,
    tokens: CheckoutTokenStore,
    checkoutSessions: CheckoutSessionStore,
    transactionManager: PlatformTransactionManager,
    private val db: DbAccess,
) : BehaviorSpec({

    val confirmFixture = ConfirmFixture(payments, controls, sessions, tokens, checkoutSessions, transactionManager, db)
    val fixture = CancelFixture(confirmFixture, confirm, admission, controls, payments, transactionManager)
    val n = 8

    afterEach { faults.reset() }

    /** 첫 TX가 control 잠금을 잡은 뒤 나머지 [waiters]개가 잠금 대기에 들어갈 때까지 붙잡는다. */
    fun holdFirstControlLock(waiters: Int) {
        val held = AtomicBoolean(false)
        faults.after(FaultPoint.CONTROL_LOCK) { if (held.compareAndSet(false, true)) db.awaitLockWaiters(waiters) }
    }

    fun outcomes(results: List<Result<CancelStartOutcome>>): List<String> = results.map { r ->
        when (val o = r.getOrThrow()) {
            is CancelStartOutcome.Accepted -> o.outcome.name
            is CancelStartOutcome.Rejected -> o.rejection.name
            else -> o.toString()
        }
    }

    Given("K1 — N different merchantCancelIds on one payment at once (sum above the authorized amount)") {
        Then("exactly one starts and the others are CANCEL_IN_PROGRESS (stored); one reservation") {
            val a = fixture.authorized()
            holdFirstControlLock(n - 1)
            val r = outcomes(CreatePaymentFixture.concurrently(n) { start.start(a.command(money = Money(3_000, a.money.currency))) })
            r.count { it == "ACCEPTED" } shouldBe 1
            r.count { it == CancelRejection.CANCEL_IN_PROGRESS.name } shouldBe n - 1
            fixture.cancellationRows(a) shouldBe 1
            fixture.count("SELECT COUNT(*) FROM return_reservation WHERE payment_id = ? AND state = 'RESERVED'", a.paymentId.value) shouldBe 1
            fixture.idempotencyRows(a).size shouldBe n
        }
    }

    Given("K2 — the same Idempotency-Key N times at once") {
        Then("one cancellation and one attempt; every caller gets the same cancellationId") {
            val a = fixture.authorized()
            val c = a.command()
            // 나머지는 멱등 행 잠금을 기다린다(멱등 기록이 control보다 먼저 — §4.7).
            holdFirstControlLock(n - 1)
            val results = CreatePaymentFixture.concurrently(n) { start.start(c) }.map { it.getOrThrow() as CancelStartOutcome.Accepted }
            results.map { it.cancellationId }.toSet().size shouldBe 1
            results.count { !it.replayed } shouldBe 1
            fixture.cancellationRows(a) shouldBe 1
            fixture.count("SELECT COUNT(*) FROM cancel_attempt t JOIN payment_cancellation c ON c.cancellation_id = t.cancellation_id WHERE c.payment_id = ?", a.paymentId.value) shouldBe 1
        }
    }

    Given("K3/K4 — different keys, the same merchantCancelId (same content, then mixed amounts)") {
        Then("K3: one cancellation, the rest EXISTING_CANCELLATION with the same cancellationId") {
            val a = fixture.authorized()
            holdFirstControlLock(n - 1)
            val results = CreatePaymentFixture.concurrently(n) { start.start(a.command(merchantCancelId = "mc-k3-0001")) }.map { it.getOrThrow() as CancelStartOutcome.Accepted }
            results.map { it.outcome } shouldContainExactlyInAnyOrder listOf(CancelOutcome.ACCEPTED) + List(n - 1) { CancelOutcome.EXISTING_CANCELLATION }
            results.map { it.cancellationId }.toSet().size shouldBe 1
            fixture.cancellationRows(a) shouldBe 1
        }
        Then("K4: one winner; requests with the winner's amount are EXISTING, others CANCEL_REQUEST_CONFLICT") {
            val a = fixture.authorized()
            holdFirstControlLock(n - 1)
            val amounts = (0 until n).map { if (it % 2 == 0) 3_000L else 4_000L }
            val r = CreatePaymentFixture.concurrently(n) { i -> amounts[i] to start.start(a.command(merchantCancelId = "mc-k4-0001", money = Money(amounts[i], a.money.currency))) }
                .map { it.getOrThrow() }
            val winner = r.single { (_, o) -> o is CancelStartOutcome.Accepted && o.outcome == CancelOutcome.ACCEPTED }.first
            r.forEach { (amount, o) ->
                withClue("$amount → $o") {
                    when {
                        amount == winner -> (o as CancelStartOutcome.Accepted).cancellationId shouldBe (r.first { it.second is CancelStartOutcome.Accepted }.second as CancelStartOutcome.Accepted).cancellationId
                        else -> (o as CancelStartOutcome.Rejected).rejection shouldBe CancelRejection.CANCEL_REQUEST_CONFLICT
                    }
                }
            }
            fixture.cancellationRows(a) shouldBe 1
        }
    }

    Given("K5 — two full cancels with different ids at once") {
        Then("only one starts") {
            val a = fixture.authorized()
            holdFirstControlLock(1)
            val r = outcomes(CreatePaymentFixture.concurrently(2) { start.start(a.command()) })
            r shouldContainExactlyInAnyOrder listOf("ACCEPTED", CancelRejection.CANCEL_IN_PROGRESS.name)
        }
    }

    Given("K12 — an operator correction cancel and a merchant cancel on the same payment at once") {
        Then("with a hold: the merchant is ORDER_ON_HOLD and the operator starts") {
            val a = fixture.authorized()
            fixture.holdOn(a)
            holdFirstControlLock(1)
            val r = CreatePaymentFixture.concurrently(2) { i -> if (i == 0) start.start(a.command()) else operator.cancel(a.operatorCommand()) }.map { it.getOrThrow() }
            r.filterIsInstance<CancelStartOutcome.Rejected>().single().rejection shouldBe CancelRejection.ORDER_ON_HOLD
            r.filterIsInstance<OperatorCorrectionCancelResult.Accepted>().single().existing shouldBe false
        }
        Then("without a hold: one starts and the other is CANCEL_IN_PROGRESS") {
            val a = fixture.authorized()
            holdFirstControlLock(1)
            val r = CreatePaymentFixture.concurrently(2) { i -> if (i == 0) start.start(a.command()) else operator.cancel(a.operatorCommand()) }.map { it.getOrThrow() }
            val started = r.count { it is CancelStartOutcome.Accepted || it is OperatorCorrectionCancelResult.Accepted }
            started shouldBe 1
            val inProgress = r.count {
                (it is CancelStartOutcome.Rejected && it.rejection == CancelRejection.CANCEL_IN_PROGRESS) ||
                    (it is OperatorCorrectionCancelResult.Rejected && it.code == OperatorCancelRejectionCode.CANCEL_IN_PROGRESS)
            }
            inProgress shouldBe 1
            fixture.cancellationRows(a) shouldBe 1
        }
    }

    // rev 0.3 m-3: 같은 approvalId로 서로 다른 Payment 두 건 동시 → 한 건만 시작, 다른 쪽은 UNIQUE(approval_id) 1062 → 새 TX 재판정 → 충돌(APPROVAL_CONFLICT, 거절 감사 1행).
    Given("m-3 — the same approvalId on two different payments at once") {
        Then("exactly one starts and the other is APPROVAL_CONFLICT with one rejection audit row") {
            val a = fixture.authorized()
            val b = fixture.authorized()
            val approval = "apr-m3-" + System.nanoTime()
            // 첫 INSERT TX가 커밋 전에 붙잡힌 동안 다른 쪽이 같은 approval_id UNIQUE에서 실제로 잠금을 기다리게 한다(1062 → 새 TX 재판정 경로를 강제).
            val held = AtomicBoolean(false)
            faults.after(FaultPoint.CANCELLATION_INSERT) { if (held.compareAndSet(false, true)) db.awaitLockWaiters(1) }
            val r = CreatePaymentFixture.concurrently(2) { i -> operator.cancel((if (i == 0) a else b).operatorCommand(approvalId = approval)) }.map { it.getOrThrow() }
            held.get() shouldBe true
            r.count { it is OperatorCorrectionCancelResult.Accepted } shouldBe 1
            r.filterIsInstance<OperatorCorrectionCancelResult.Rejected>().single().code shouldBe OperatorCancelRejectionCode.APPROVAL_CONFLICT
            fixture.count("SELECT COUNT(*) FROM payment_cancellation WHERE approval_id = ?", approval) shouldBe 1
            fixture.count("SELECT COUNT(*) FROM operator_cancel_rejection WHERE approval_id = ? AND rejection_reason = 'APPROVAL_CONFLICT'", approval) shouldBe 1
        }
    }
})

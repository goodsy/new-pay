package com.paycore.payment.payment.application

import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.adapter.persistence.FaultInjector
import com.paycore.payment.payment.adapter.persistence.FaultPoint
import com.paycore.payment.payment.adapter.persistence.JpaPaymentRepository
import com.paycore.payment.payment.adapter.persistence.PaymentDbTest
import com.paycore.payment.payment.adapter.persistence.PersistenceTestData.newOrder
import com.paycore.payment.payment.adapter.persistence.PersistenceTestData.t0
import com.paycore.payment.payment.application.CreatePaymentFixture.command
import com.paycore.payment.payment.application.CreatePaymentFixture.concurrently
import com.paycore.payment.payment.application.provided.CreatePaymentResult
import com.paycore.payment.payment.application.provided.CreatePaymentUseCase
import com.paycore.payment.payment.application.provided.ReportSuccessAfterTerminationUseCase
import com.paycore.payment.payment.domain.TerminationEvidence
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CP-DOMAIN-001 M-3: 같은 늦은 성공이 응답·통보·조회 경로로 **동시에** 여러 번 보고돼도 실제 MySQL에 incident hold는 1건이다
 * (control 행 잠금 직렬화 + domain no-op + UNIQUE(OrderKey, paymentId, reason) 백스톱 — 백스톱 자체는 PaymentSchemaSpec).
 */
@PaymentDbTest
class IncidentHoldConvergenceSpec(
    private val createPayment: CreatePaymentUseCase,
    private val incidents: ReportSuccessAfterTerminationUseCase,
    private val payments: JpaPaymentRepository,
    private val faults: FaultInjector,
    private val transactionManager: PlatformTransactionManager,
    private val db: DbAccess,
) : BehaviorSpec({

    afterEach { faults.reset() }

    Given("a payment that ended EXPIRED and is then reported as authorized by 8 channels at once") {
        Then("exactly one incident hold is stored and every report succeeds") {
            val order = newOrder()
            val created = createPayment.create(command(order)).shouldBeInstanceOf<CreatePaymentResult.Accepted>()
            val paymentId = created.payment.paymentId
            TransactionTemplate(transactionManager).executeWithoutResult {
                val p = payments.lock(paymentId)!!
                p.value.expire(TerminationEvidence.NOT_TRANSMITTED_AND_SESSION_DISCARDED)
                payments.update(p.value, p.version)
            }
            val n = 8
            // 첫 보고 TX가 control을 잠근 채, 나머지 7개가 실제로 그 잠금을 기다릴 때까지 붙잡는다(동시성 관찰 증거).
            val held = AtomicBoolean(false)
            faults.after(FaultPoint.CONTROL_LOCK) { if (held.compareAndSet(false, true)) db.awaitLockWaiters(n - 1) }

            concurrently(n) { incidents.report(order, paymentId, t0) }.forEach { it.getOrThrow() }

            db.count("SELECT COUNT(*) FROM incident_hold WHERE merchant_account_id = ? AND payment_id = ?", order.merchantAccountId.value, paymentId.value) shouldBe
                1
            // 보류는 Payment 상태를 덮어쓰지 않는다(늦은 성공은 사건, 상태는 EXPIRED 유지).
            db.count("SELECT COUNT(*) FROM payment WHERE payment_id = ? AND status = 'EXPIRED'", paymentId.value) shouldBe 1
        }
    }
})

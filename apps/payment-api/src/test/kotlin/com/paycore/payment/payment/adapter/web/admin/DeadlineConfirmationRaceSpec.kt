package com.paycore.payment.payment.adapter.web.admin

import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.adapter.persistence.FaultInjector
import com.paycore.payment.payment.adapter.persistence.FaultPoint
import com.paycore.payment.payment.adapter.web.InternalConnector
import com.paycore.payment.payment.adapter.web.MutablePaymentClock
import com.paycore.payment.payment.adapter.web.PaymentApiTest
import com.paycore.payment.payment.adapter.web.admin.AdminOpsTestClient.Companion.EXECUTE
import com.paycore.payment.payment.adapter.web.admin.AdminOpsTestClient.Companion.body
import com.paycore.payment.payment.adapter.web.admin.AdminOpsTestClient.Companion.deadline
import com.paycore.payment.payment.application.CancelFixture
import com.paycore.payment.payment.application.ConfirmFixture
import com.paycore.payment.payment.application.ScriptedAdmissionPort
import com.paycore.payment.payment.application.provided.ConfirmPaymentUseCase
import com.paycore.payment.payment.application.required.AuthenticationSessionRepository
import com.paycore.payment.payment.application.required.CheckoutSessionStore
import com.paycore.payment.payment.application.required.CheckoutTokenStore
import com.paycore.payment.payment.application.required.OrderPaymentControlRepository
import com.paycore.payment.payment.application.required.PaymentRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.transaction.PlatformTransactionManager
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 늦은 실행 명령 vs 기한 확인(ADMIN-PAYMENT-OPS-001 §4.4.5 `DeadlineConfirmationRaceSpec` — 실제 HTTP·실제 MySQL 두 연결, 장벽): 실행 TX가 기한 전에 시작해 취소 행을 넣은 채 멈춘 동안 기한
 * 확인이 끼어든다. 둘 다 결정 행을 INSERT하므로 **UNIQUE(approval_id)가 정한다** — 결정 행은 정확히 1개, 확인이 이기면 실행 TX 전체 rollback(취소·예약 0, 실행 응답 409 EXPIRED 재생), 실행이
 * 이기면 확인의 INSERT가 1062 → 재시도에서 STARTED(confirmed=false). 결정 없는 취소는 남지 않는다.
 */
@PaymentApiTest
class DeadlineConfirmationRaceSpec(
    private val internal: InternalConnector,
    private val clock: MutablePaymentClock,
    private val faults: FaultInjector,
    private val db: DbAccess,
    admission: ScriptedAdmissionPort,
    confirm: ConfirmPaymentUseCase,
    payments: PaymentRepository,
    controls: OrderPaymentControlRepository,
    sessions: AuthenticationSessionRepository,
    tokens: CheckoutTokenStore,
    checkoutSessions: CheckoutSessionStore,
    transactionManager: PlatformTransactionManager,
) : BehaviorSpec({

    val confirmFixture = ConfirmFixture(payments, controls, sessions, tokens, checkoutSessions, transactionManager, db)
    val fixture = CancelFixture(confirmFixture, confirm, admission, controls, payments, transactionManager)
    val api = AdminOpsTestClient(internal.localPort)

    afterEach {
        faults.reset()
        clock.reset()
    }

    fun decisionOf(apr: String) =
        fixture.scalar("SELECT CONCAT(decision, '|', COALESCE(rejection_code, '-'), '|', origin) FROM operator_approval_decision WHERE approval_id = ?", apr)
    fun decisions(apr: String) = fixture.count("SELECT COUNT(*) FROM operator_approval_decision WHERE approval_id = ?", apr)

    /** 기한 = 시험 T0 + 10분(실행은 T0에 시작, 확인은 T0 + 1시간에). */
    fun raceBody(a: CancelFixture.Authorized) = body(a, approvedAt = ConfirmFixture.T0.minusSeconds(60), executeBefore = ConfirmFixture.T0.plus(Duration.ofMinutes(10)))

    Given("the confirmation commits first while the execution TX holds an uncommitted cancellation") {
        Then("one EXPIRED decision; the execution TX rolls back entirely and replays 409 APPROVAL_EXECUTION_EXPIRED; no cancellation or reservation") {
            val a = fixture.authorized(paymentIdValue = AdminOpsTestClient.newPaymentId())
            val b = raceBody(a)
            val apr = AdminOpsTestClient.approvalIdOf(b)
            val inserted = CountDownLatch(1)
            val release = CountDownLatch(1)
            val first = AtomicBoolean(true)
            faults.after(FaultPoint.CANCELLATION_INSERT) { if (first.compareAndSet(true, false)) { inserted.countDown(); release.await(30, TimeUnit.SECONDS) } }
            val pool = Executors.newSingleThreadExecutor()
            val execution = pool.submit<AdminOpsTestClient.Response> { api.post(EXECUTE, b) }
            inserted.await(30, TimeUnit.SECONDS) shouldBe true
            clock.set(ConfirmFixture.T0.plus(Duration.ofHours(1)))
            val confirmation = api.post(deadline(apr), b)
            confirmation.status shouldBe 200
            confirmation.json.get("code").asString() shouldBe "APPROVAL_EXECUTION_EXPIRED"
            confirmation.json.get("confirmed").asBoolean() shouldBe true
            release.countDown()
            val executed = execution.get(60, TimeUnit.SECONDS)
            pool.shutdown()
            executed.status shouldBe 409
            executed.json.get("error").asString() shouldBe "APPROVAL_EXECUTION_EXPIRED"
            decisions(apr) shouldBe 1
            decisionOf(apr) shouldBe "REJECTED|APPROVAL_EXECUTION_EXPIRED|DEADLINE_CONFIRMATION"
            fixture.cancellationRows(a) shouldBe 0
            fixture.count("SELECT COUNT(*) FROM return_reservation WHERE payment_id = ?", a.paymentId.value) shouldBe 0
        }
    }

    Given("the execution commits first while the confirmation TX is about to record EXPIRED") {
        Then("one STARTED decision; the confirmation's INSERT conflicts, retries and answers STARTED confirmed=false; its audit row is rolled back") {
            val a = fixture.authorized(paymentIdValue = AdminOpsTestClient.newPaymentId())
            val b = raceBody(a)
            val apr = AdminOpsTestClient.approvalIdOf(b)
            val execInserted = CountDownLatch(1)
            val execRelease = CountDownLatch(1)
            val confirmAtAudit = CountDownLatch(1)
            val confirmRelease = CountDownLatch(1)
            val firstExec = AtomicBoolean(true)
            val firstAudit = AtomicBoolean(true)
            faults.after(FaultPoint.CANCELLATION_INSERT) { if (firstExec.compareAndSet(true, false)) { execInserted.countDown(); execRelease.await(30, TimeUnit.SECONDS) } }
            faults.after(FaultPoint.OPERATOR_REJECTION_APPEND) { if (firstAudit.compareAndSet(true, false)) { confirmAtAudit.countDown(); confirmRelease.await(30, TimeUnit.SECONDS) } }
            val pool = Executors.newFixedThreadPool(2)
            val execution = pool.submit<AdminOpsTestClient.Response> { api.post(EXECUTE, b) }
            execInserted.await(30, TimeUnit.SECONDS) shouldBe true
            clock.set(ConfirmFixture.T0.plus(Duration.ofHours(1)))
            val confirmation = pool.submit<AdminOpsTestClient.Response> { api.post(deadline(apr), b) }
            confirmAtAudit.await(30, TimeUnit.SECONDS) shouldBe true
            execRelease.countDown()
            val executed = execution.get(60, TimeUnit.SECONDS)
            executed.status shouldBe 200
            executed.json.get("existing").asBoolean() shouldBe false
            confirmRelease.countDown()
            val confirmed = confirmation.get(60, TimeUnit.SECONDS)
            pool.shutdown()
            confirmed.status shouldBe 200
            confirmed.json.get("decision").asString() shouldBe "STARTED"
            confirmed.json.get("confirmed").asBoolean() shouldBe false
            decisions(apr) shouldBe 1
            decisionOf(apr) shouldBe "STARTED|-|EXECUTION_COMMAND"
            fixture.cancellationRows(a) shouldBe 1
            fixture.count("SELECT COUNT(*) FROM operator_cancel_rejection WHERE approval_id = ?", apr) shouldBe 0
        }
    }
})

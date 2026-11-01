package com.paycore.payment.payment.application

import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.adapter.persistence.FaultInjector
import com.paycore.payment.payment.adapter.persistence.FaultPoint
import com.paycore.payment.payment.adapter.web.ApiTestTimes
import com.paycore.payment.payment.adapter.web.MutablePaymentClock
import com.paycore.payment.payment.adapter.web.PaymentApiTest
import com.paycore.payment.payment.application.CheckoutExpiryFlow.Companion.WINDOW_END
import com.paycore.payment.payment.application.provided.CreatePaymentUseCase
import com.paycore.payment.payment.application.provided.ExpireElapsedRequestedPaymentsCommand
import com.paycore.payment.payment.application.provided.ExpireElapsedRequestedPaymentsUseCase
import com.paycore.payment.payment.application.provided.IssueCheckoutTokenUseCase
import com.paycore.payment.payment.application.provided.OpenCheckoutSessionUseCase
import com.paycore.payment.payment.application.provided.StartCardAuthenticationCommand
import com.paycore.payment.payment.application.provided.StartCardAuthenticationResult
import com.paycore.payment.payment.application.provided.StartCardAuthenticationUseCase
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Duration
import java.util.UUID

/**
 * 결제창 만료 주기 작업 Use Case(AUTHN-001 §7.5.4·§7.5.7, D-AUTHN-005·008) — 실 MySQL, 시험 시계(API 시험 구성). 후보(jOOQ, 비권위 — 결제창 시각 지난 REQUESTED, 키셋)마다
 * TX 하나: control(X) → payment(X) → `RequestedPaymentClosing`(세션·결제창 세션·토큰 닫기 + PaymentExpired). 시각 전·이미 종료는 변경 없음, 한 후보의 실패는 그 건만
 * rollback하고 다음 후보를 계속한다(다음 회차에 다시 후보). 인과 값: correlationId = 회차 ID, causationId = `checkout-expiry-job:<회차 ID>`.
 * 공유 시험 DB의 다른 시험 Payment도 후보가 되므로 단언은 이 시험의 Payment로 한정한다.
 */
@PaymentApiTest
class RequestedPaymentExpiryServiceSpec(
    private val job: ExpireElapsedRequestedPaymentsUseCase,
    private val start: StartCardAuthenticationUseCase,
    private val clock: MutablePaymentClock,
    private val faults: FaultInjector,
    create: CreatePaymentUseCase,
    issue: IssueCheckoutTokenUseCase,
    open: OpenCheckoutSessionUseCase,
    db: DbAccess,
) : BehaviorSpec({

    afterEach {
        clock.reset()
        faults.reset()
    }

    val flow = CheckoutExpiryFlow(create, issue, open, db)

    fun run(batchSize: Int = 50): Pair<String, com.paycore.payment.payment.application.provided.ExpiryRunResult> {
        val runId = UUID.randomUUID().toString()
        return runId to job.expireElapsed(ExpireElapsedRequestedPaymentsCommand(runId, batchSize))
    }

    Given("a REQUESTED payment with an open checkout and an IN_PROGRESS authentication, at its window end") {
        Then("one run expires it with its records closed and exactly one PaymentExpired carrying the run's causation") {
            val c = flow.opened()
            start.start(StartCardAuthenticationCommand(c.handle, c.secret, "SIMISSA", "LUMP_SUM", "req-start"))
                .shouldBeInstanceOf<StartCardAuthenticationResult.Redirect>()
            val issuedLater = flow.token(c.order, c.paymentId) // 교환 전 ISSUED 토큰 하나
            clock.set(WINDOW_END)

            val (runId, result) = run()
            result.expired shouldBeGreaterThanOrEqual 1
            flow.status(c.paymentId) shouldBe "EXPIRED"
            flow.evidence(c.paymentId) shouldBe listOf("NOT_TRANSMITTED_AND_SESSION_DISCARDED")
            flow.authSessions(c.paymentId) shouldBe listOf("EXPIRED" to "PAYMENT_CLOSED")
            flow.checkoutSessions(c.paymentId) shouldBe listOf("EXPIRED" to "PAYMENT_CLOSED")
            flow.tokenStatuses(c.paymentId).map { it.toString() }.sorted() shouldBe listOf("CLOSED", "CONSUMED")
            issuedLater.isNotEmpty() shouldBe true
            flow.expiredEvents(c.paymentId).single().let {
                it["correlation_id"] shouldBe runId
                it["causation_id"] shouldBe "checkout-expiry-job:$runId"
                it["event_category"] shouldBe "INTEGRATION_NON_FINANCIAL"
            }

            // 다음 회차: 이미 EXPIRED — 후보가 아니고 두 번째 PaymentExpired 없음.
            run()
            flow.expiredEvents(c.paymentId).size shouldBe 1
        }
    }

    Given("payments before their window end and many elapsed ones across small pages") {
        Then("not-due payments are untouched; keyset paging with batch size 1 still expires every elapsed one in one run") {
            val (_, notDue) = flow.payment(at = ApiTestTimes.T0.plus(Duration.ofMinutes(10)))
            val elapsed = (1..3).map { flow.payment().second }
            clock.set(WINDOW_END)
            val (_, result) = run(batchSize = 1)
            result.expired shouldBeGreaterThanOrEqual 3
            elapsed.forEach { flow.status(it) shouldBe "EXPIRED"; flow.expiredEvents(it).size shouldBe 1 }
            flow.status(notDue) shouldBe "REQUESTED"
            flow.expiredEvents(notDue) shouldBe emptyList()
        }
    }

    Given("a transient store failure for a candidate (review #4 n-3)") {
        Then("it is counted as failed (not a defect) and the candidate stays REQUESTED for the next run") {
            val (_, id) = flow.payment()
            clock.set(WINDOW_END)
            faults.after(FaultPoint.OUTBOX_APPEND) { throw com.paycore.payment.payment.application.required.PersistenceFailure.LockTimeout(RuntimeException("injected lock timeout")) }
            val (_, failedRun) = run()
            faults.reset()
            failedRun.failed shouldBeGreaterThanOrEqual 1
            flow.status(id) shouldBe "REQUESTED"
            run().second.expired shouldBeGreaterThanOrEqual 1
        }
    }

    Given("a failure after PaymentExpired was flushed (D-AUTHN-008: zero events on rollback)") {
        Then("that candidate rolls back completely (REQUESTED, no evidence, no event), the run reports a failure and continues; the next run expires it") {
            val (_, id) = flow.payment()
            clock.set(WINDOW_END)
            faults.after(FaultPoint.OUTBOX_APPEND) { throw IllegalStateException("injected after PaymentExpired append") }
            val (_, failedRun) = run()
            faults.reset()
            // 검토 #4 n-3: 저장소 일시 실패가 아닌 예외(결함 후보)는 failed가 아니라 defects로 센다(ERROR 로그 — 경보 대상). 이전 기대: failed ≥ 1.
            failedRun.defects shouldBeGreaterThanOrEqual 1
            flow.status(id) shouldBe "REQUESTED"
            flow.evidence(id) shouldBe emptyList()
            flow.expiredEvents(id) shouldBe emptyList()

            run().second.expired shouldBeGreaterThanOrEqual 1
            flow.status(id) shouldBe "EXPIRED"
            flow.expiredEvents(id).size shouldBe 1
        }
    }
})

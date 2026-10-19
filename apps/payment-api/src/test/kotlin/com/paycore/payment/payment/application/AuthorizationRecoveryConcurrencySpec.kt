package com.paycore.payment.payment.application

import com.paycore.payment.payment.adapter.integration.institution.StubVan.Action
import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.adapter.persistence.FaultInjector
import com.paycore.payment.payment.adapter.persistence.FaultPoint
import com.paycore.payment.payment.adapter.web.ApiTestTimes.T0
import com.paycore.payment.payment.adapter.web.MutablePaymentClock
import com.paycore.payment.payment.adapter.web.PaymentApiTest
import com.paycore.payment.payment.application.provided.ConfirmPaymentResult
import com.paycore.payment.payment.application.provided.ConfirmPaymentUseCase
import com.paycore.payment.payment.application.provided.RecoverAuthorizationsCommand
import com.paycore.payment.payment.application.provided.RecoverAuthorizationsUseCase
import com.paycore.payment.payment.application.required.AuthenticationSessionRepository
import com.paycore.payment.payment.application.required.AuthorizationInquiryCall
import com.paycore.payment.payment.application.required.AuthorizationInquiryPort
import com.paycore.payment.payment.application.required.CheckoutSessionStore
import com.paycore.payment.payment.application.required.CheckoutTokenStore
import com.paycore.payment.payment.application.required.InquiryCallResult
import com.paycore.payment.payment.application.required.OrderPaymentControlRepository
import com.paycore.payment.payment.application.required.PaymentRepository
import com.paycore.payment.payment.domain.AttemptId
import com.paycore.payment.payment.domain.AuthorizationId
import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.ProviderRequestRef
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.transaction.PlatformTransactionManager
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 회복 경합(AUTH-001 §8 C23·C24, §6.1 다중 Worker) — 실 MySQL 다중 연결. 직렬화 지점은 control X → … → Attempt 행(같은 행 version 조건부 갱신)과 회복 일정 `SKIP LOCKED`.
 * 결과는 "어느 쪽이 이기든" 불변식(전송 수·행 수·이벤트 수·상태 조합)으로 본다.
 */
@PaymentApiTest
class AuthorizationRecoveryConcurrencySpec(
    private val start: ConfirmStartService,
    private val dispatcher: AuthorizationDispatchService,
    private val results: AuthorizationResultService,
    private val confirm: ConfirmPaymentUseCase,
    private val recovery: RecoverAuthorizationsUseCase,
    private val inquiry: AuthorizationInquiryPort,
    private val van: ProgrammableVan,
    private val faults: FaultInjector,
    private val clock: MutablePaymentClock,
    payments: PaymentRepository,
    controls: OrderPaymentControlRepository,
    sessions: AuthenticationSessionRepository,
    tokens: CheckoutTokenStore,
    checkoutSessions: CheckoutSessionStore,
    transactionManager: PlatformTransactionManager,
    db: DbAccess,
) : BehaviorSpec({

    val fixture = ConfirmFixture(payments, controls, sessions, tokens, checkoutSessions, transactionManager, db)
    val pool = Executors.newFixedThreadPool(4)
    afterSpec { pool.shutdownNow() }
    afterEach {
        faults.reset()
        clock.reset()
    }

    fun <T> race(vararg tasks: () -> T): List<Result<T>> {
        val barrier = CyclicBarrier(tasks.size)
        return tasks.map { t -> pool.submit(Callable { barrier.await(); runCatching { t() } }) }.map { it.get(60, TimeUnit.SECONDS) }
    }

    fun authorizationId(p: ConfirmFixture.Prepared) = fixture.scalar("SELECT authorization_id FROM payment_authorization WHERE payment_id = ?", p.paymentId.value) as String
    fun attempt(p: ConfirmFixture.Prepared, column: String) = fixture.scalar("SELECT $column FROM authorization_attempt WHERE authorization_id = ?", authorizationId(p))
    fun events(type: String, p: ConfirmFixture.Prepared) = fixture.count("SELECT COUNT(*) FROM outbox_event WHERE event_type = ? AND aggregate_id = ?", type, authorizationId(p))
    fun transactions(p: ConfirmFixture.Prepared) = fixture.count("SELECT COUNT(*) FROM payment_transaction WHERE authorization_id = ?", authorizationId(p))
    fun mid(p: ConfirmFixture.Prepared) = p.order.merchantAccountId.value
    val causation = EventCausation("concurrency-test", "concurrency-test")

    Given("C23 — TXd (dispatch gate) vs the recovery fencing of the same PREPARED attempt") {
        Then("exactly one wins: DISPATCHING → one request and AUTHORIZED; ABANDONED → no request and FAILED(PRE_TRANSMISSION); never both") {
            repeat(5) { round ->
                val p = fixture.prepared()
                val accepted = start.start(p.command()).shouldBeInstanceOf<ConfirmStartOutcome.Accepted>()
                val id = accepted.authorizationId
                val attemptId = AttemptId(attempt(p, "attempt_id") as String)
                // 검토 #3 m-1: TXd는 preparedAt + preparedDeadline(1s) 이후 승인하지 않으므로 경합이 의미 있도록 기한 전 시각으로.
                clock.set(T0.plusMillis(500))
                val outcomes = race({ dispatcher.dispatch(id, causation) }, { results.fence(id, attemptId, causation) })
                withClue("round $round ${outcomes.map { it.exceptionOrNull()?.toString() }}") {
                    outcomes.forEach { it.isSuccess shouldBe true }
                    val requests = van.authRequestsFor(mid(p)).size
                    when (attempt(p, "state")) {
                        "RESOLVED" -> {
                            requests shouldBe 1
                            fixture.paymentStatus(p) shouldBe "AUTHORIZED"
                            events("PaymentAuthorized", p) shouldBe 1
                            events("PaymentAuthorizationFailed", p) shouldBe 0
                        }
                        "ABANDONED_BEFORE_DISPATCH" -> {
                            requests shouldBe 0
                            attempt(p, "abandon_cause") shouldBe "FENCED_BY_RECOVERY"
                            fixture.paymentStatus(p) shouldBe "FAILED"
                            events("PaymentAuthorizationFailed", p) shouldBe 1
                            events("PaymentAuthorized", p) shouldBe 0
                        }
                        else -> error("unexpected attempt state ${attempt(p, "state")}")
                    }
                }
                clock.reset()
            }
        }
    }

    Given("C24 — the result step (TX2) vs the recovery's dispatch-lease-expiry mark") {
        Then("either order: no exception, one final state AUTHORIZED, one transaction, PaymentAuthorized once (a mark after RESOLVED is NoChange)") {
            repeat(3) { round ->
                val p = fixture.prepared()
                val updates = AtomicInteger()
                faults.after(FaultPoint.AUTHORIZATION_UPDATE) { if (updates.incrementAndGet() == 2) throw IllegalStateException("injected TX2 failure") }
                confirm.confirm(p.command()).shouldBeInstanceOf<ConfirmPaymentResult.Accepted>()
                faults.reset()
                attempt(p, "state") shouldBe "DISPATCHING"
                val id = AuthorizationId(authorizationId(p))
                val attemptId = AttemptId(attempt(p, "attempt_id") as String)
                // 기관 원장의 원 결정(조회 관찰)을 얻어 TX2 반영 단계에 넣는다.
                val ref = ProviderRequestRef(
                    attempt(p, "institution") as String, Environment.valueOf(attempt(p, "environment") as String), attempt(p, "institution_account") as String,
                    attempt(p, "operation") as String, attempt(p, "request_id") as String,
                )
                val found = inquiry.inquire(AuthorizationInquiryCall(attemptId, ref, ScriptedAdmissionPort.ROUTE_REF, "aq_" + UUID.randomUUID().toString().replace("-", "").take(26).uppercase(), false))
                    .shouldBeInstanceOf<InquiryCallResult.Found>()
                clock.set(T0.plusSeconds(31))
                val outcomes = race({ results.applyObservation(id, found.observation, causation) }, { results.markLeaseExpired(id, attemptId, causation) })
                withClue("round $round ${outcomes.map { it.exceptionOrNull()?.toString() }}") {
                    outcomes.forEach { it.isSuccess shouldBe true }
                    attempt(p, "state") shouldBe "RESOLVED"
                    attempt(p, "unknown_cause") shouldBeIn listOf(null, "DISPATCH_LEASE_EXPIRED")
                    fixture.paymentStatus(p) shouldBe "AUTHORIZED"
                    transactions(p) shouldBe 1
                    events("PaymentAuthorized", p) shouldBe 1
                }
                clock.reset()
            }
        }
    }

    Given("two recovery workers running at the same time (§6.1 — SKIP LOCKED claim)") {
        Then("each UNKNOWN attempt is inquired once and authorized once; no attempt is handled by both workers") {
            val prepared = (1..4).map { fixture.prepared() }
            prepared.forEach { p ->
                van.onAuth(mid(p)) { r -> ProgrammableVan.Script(van.approved(r), Action.Close) }
                confirm.confirm(p.command()).shouldBeInstanceOf<ConfirmPaymentResult.Accepted>()
                attempt(p, "state") shouldBe "OUTCOME_UNKNOWN"
            }
            clock.set(T0.plusSeconds(11))
            val outcomes = race(
                { recovery.recover(RecoverAuthorizationsCommand(UUID.randomUUID().toString(), 1000)) },
                { recovery.recover(RecoverAuthorizationsCommand(UUID.randomUUID().toString(), 1000)) },
            )
            outcomes.forEach { it.isSuccess shouldBe true }
            prepared.forEach { p ->
                withClue(p.paymentId.value) {
                    van.inquiriesFor(attempt(p, "request_id") as String) shouldHaveSize 1
                    fixture.paymentStatus(p) shouldBe "AUTHORIZED"
                    transactions(p) shouldBe 1
                    events("PaymentAuthorized", p) shouldBe 1
                    van.authRequestsFor(mid(p)) shouldHaveSize 1
                }
            }
        }
    }
})

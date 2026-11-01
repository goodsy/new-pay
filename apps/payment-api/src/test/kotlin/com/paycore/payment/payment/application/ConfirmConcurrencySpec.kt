package com.paycore.payment.payment.application

import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.adapter.persistence.PersistenceTestData
import com.paycore.payment.payment.adapter.web.MutablePaymentClock
import com.paycore.payment.payment.adapter.web.PaymentApiTest
import com.paycore.payment.payment.application.ConfirmFixture.Companion.T0
import com.paycore.payment.payment.application.provided.ConfirmRejection
import com.paycore.payment.payment.application.provided.ExpireElapsedRequestedPaymentsCommand
import com.paycore.payment.payment.application.provided.ExpireElapsedRequestedPaymentsUseCase
import com.paycore.payment.payment.application.provided.StartCardAuthenticationCommand
import com.paycore.payment.payment.application.provided.StartCardAuthenticationResult
import com.paycore.payment.payment.application.provided.StartCardAuthenticationUseCase
import com.paycore.payment.payment.application.required.AuthenticationSessionRepository
import com.paycore.payment.payment.application.required.CheckoutSessionStore
import com.paycore.payment.payment.application.required.CheckoutTokenStore
import com.paycore.payment.payment.application.required.OrderPaymentControlRepository
import com.paycore.payment.payment.application.required.PaymentRepository
import com.paycore.payment.payment.domain.LimitPolicyResolution
import com.paycore.payment.payment.domain.OrderKey
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.transaction.PlatformTransactionManager
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * confirm TX1 경합(AUTH-001 §8 C8·C18·C19·C21·C22) — 실 MySQL 다중 연결. 직렬화 지점은 멱등 행·control·payment·사용량 행 잠금이며 기대값은 명세 §8 표에서 직접 적는다.
 * 결과는 "어느 쪽이 이기든" 불변식(행 수·이벤트 수·상태 조합)으로 본다.
 */
@PaymentApiTest
class ConfirmConcurrencySpec(
    private val start: ConfirmStartService,
    private val admission: ScriptedAdmissionPort,
    private val expiry: ExpireElapsedRequestedPaymentsUseCase,
    private val authStart: StartCardAuthenticationUseCase,
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
    val pool = Executors.newFixedThreadPool(8)
    afterSpec { pool.shutdownNow() }
    afterEach { clock.reset() }

    fun <T> race(vararg tasks: () -> T): List<Result<T>> {
        val barrier = CyclicBarrier(tasks.size)
        return tasks.map { t -> pool.submit(Callable { barrier.await(); runCatching { t() } }) }.map { it.get(60, TimeUnit.SECONDS) }
    }

    Given("C21 — eight concurrent confirms of one payment (same key, other keys, same content, other authenticationRef)") {
        Then("one authorization and one attempt; same content refers to the same authorization; other content is CONFIRM_CONTENT_CONFLICT (or AUTHENTICATION_NOT_VERIFIED when it wins first, §4.3-6)") {
            repeat(3) { round ->
                val p = fixture.prepared()
                val shared = ConfirmFixture.newKey()
                val results = race(
                    { start.start(p.command(key = shared)) }, { start.start(p.command(key = shared)) },
                    { start.start(p.command()) }, { start.start(p.command()) }, { start.start(p.command()) },
                    { start.start(p.command(authenticationRef = "aref_" + "W".repeat(22))) }, { start.start(p.command(authenticationRef = "aref_" + "W".repeat(22))) },
                    { start.start(p.command()) },
                ).map { it.getOrThrow() }
                withClue("round $round") {
                    fixture.authorizationRows(p) shouldBe 1
                    fixture.count("SELECT COUNT(*) FROM authorization_attempt t JOIN payment_authorization a ON a.authorization_id = t.authorization_id WHERE a.payment_id = ?", p.paymentId.value) shouldBe 1
                    val accepted = results.filterIsInstance<ConfirmStartOutcome.Accepted>()
                    accepted.map { it.authorizationId }.toSet().size shouldBe 1
                    accepted.size shouldBe 6
                    // 다른 authenticationRef 두 요청(index 5·6)만 거절된다. Authorization이 이미 있으면 5a CONFIRM_CONTENT_CONFLICT, 그보다 먼저 잠금을 잡으면 그 ref의 세션이 없어 6 AUTHENTICATION_NOT_VERIFIED.
                    results.filterIndexed { i, _ -> i == 5 || i == 6 }.forEach { r ->
                        r.shouldBeInstanceOf<ConfirmStartOutcome.Rejected>().rejection shouldBeIn setOf(ConfirmRejection.CONFIRM_CONTENT_CONFLICT, ConfirmRejection.AUTHENTICATION_NOT_VERIFIED)
                    }
                    fixture.paymentStatus(p) shouldBe "AUTHORIZING"
                }
            }
        }
    }

    Given("C22 — confirms of different payments of the same MID and business date at the daily limit boundary") {
        Then("the sum of reservations never exceeds the daily limit; the rest are LIMIT_EXCEEDED with Payment REQUESTED") {
            val order = PersistenceTestData.newOrder()
            admission.respondFor(order.merchantAccountId) {
                ScriptedAdmissionPort.admitted(it, limit = LimitPolicyResolution.Resolved(ScriptedAdmissionPort.policy(it, perTransaction = 10_000, daily = 30_000)))
            }
            val prepared = (1..6).map { fixture.prepared(order = OrderKey(order.environment, order.merchantAccountId, PersistenceTestData.newOrder().merchantOrderId)) }
            val results = race(*prepared.map { p -> { start.start(p.command()) } }.toTypedArray()).map { it.getOrThrow() }
            results.count { it is ConfirmStartOutcome.Accepted } shouldBe 3
            results.filterIsInstance<ConfirmStartOutcome.Rejected>().map { it.rejection }.toSet() shouldBe setOf(ConfirmRejection.LIMIT_EXCEEDED)
            fixture.count(
                "SELECT COALESCE(SUM(r.amount_minor), 0) FROM limit_reservation r WHERE r.environment = ? AND r.merchant_account_id = ? AND r.state = 'RESERVED'",
                order.environment.name, order.merchantAccountId.value,
            ) shouldBeLessThanOrEqual 30_000
            prepared.count { fixture.paymentStatus(it) == "REQUESTED" } shouldBe 3
        }
    }

    Given("C18 — confirm and the expiry job at an elapsed checkout window") {
        Then("whichever wins: EXPIRED once, one PaymentExpired, the confirm key result PAYMENT_TERMINATED") {
            repeat(3) {
                val p = fixture.prepared()
                clock.set(T0.plus(Duration.ofMinutes(31)))
                val (confirm, _) = race(
                    { start.start(p.command()) },
                    { expiry.expireElapsed(ExpireElapsedRequestedPaymentsCommand("c18-" + DbAccess.unique("r").takeLast(12), 100)); null },
                )
                confirm.getOrThrow().shouldBeInstanceOf<ConfirmStartOutcome.Rejected>().rejection shouldBe ConfirmRejection.PAYMENT_TERMINATED
                fixture.paymentStatus(p) shouldBe "EXPIRED"
                fixture.count("SELECT COUNT(*) FROM outbox_event WHERE effect_key = ?", "PaymentExpired:${p.paymentId.value}") shouldBe 1
                fixture.count("SELECT COUNT(*) FROM payment_termination_evidence WHERE payment_id = ?", p.paymentId.value) shouldBe 1
                clock.reset()
            }
        }
    }

    Given("C19 — confirm before the window and the expiry job just after it") {
        Then("confirm first → the job leaves the AUTHORIZING payment alone; the job first → confirm PAYMENT_TERMINATED") {
            val p = fixture.prepared()
            (start.start(p.command()) is ConfirmStartOutcome.Accepted) shouldBe true
            clock.set(T0.plus(Duration.ofMinutes(31)))
            expiry.expireElapsed(ExpireElapsedRequestedPaymentsCommand("c19-" + DbAccess.unique("r").takeLast(12), 100))
            fixture.paymentStatus(p) shouldBe "AUTHORIZING"
            val q = fixture.prepared()
            expiry.expireElapsed(ExpireElapsedRequestedPaymentsCommand("c19b-" + DbAccess.unique("r").takeLast(12), 100))
            start.start(q.command()).shouldBeInstanceOf<ConfirmStartOutcome.Rejected>().rejection shouldBe ConfirmRejection.PAYMENT_TERMINATED
        }
    }

    Given("C8 — card authentication start (TX-A) against confirm TX1 on the same payment") {
        Then("confirm wins → the start sees a payment that is not REQUESTED; start wins → confirm is AUTHENTICATION_NOT_VERIFIED and Payment stays REQUESTED") {
            repeat(4) { round ->
                val p = fixture.prepared()
                val (confirm, startResult) = race(
                    { start.start(p.command()) },
                    { authStart.start(StartCardAuthenticationCommand(p.handle, p.cookieSecret, "SIMISSA", "LUMP_SUM", "req-c8")) },
                )
                val c = confirm.getOrThrow()
                withClue("round $round: $c / ${startResult.getOrNull()}") {
                    when (c) {
                        is ConfirmStartOutcome.Accepted -> {
                            fixture.paymentStatus(p) shouldBe "AUTHORIZING"
                            startResult.getOrThrow()::class shouldBeIn listOf(StartCardAuthenticationResult.PaymentNotAvailable::class)
                        }
                        is ConfirmStartOutcome.Rejected -> {
                            c.rejection shouldBe ConfirmRejection.AUTHENTICATION_NOT_VERIFIED
                            fixture.paymentStatus(p) shouldBe "REQUESTED"
                            fixture.authorizationRows(p) shouldBe 0
                        }
                        else -> error("unexpected $c")
                    }
                }
            }
        }
    }
})

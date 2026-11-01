package com.paycore.payment.payment.application

import com.paycore.payment.payment.adapter.persistence.DbAccess
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
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.core.instrument.MeterRegistry
import java.time.Duration
import java.util.UUID

/**
 * 결제창·만료 관찰 지표(AUTHN-001 §15 Observability, §7.5.4): 비권위 jOOQ gauge — 시각 지난 REQUESTED 수, TTL 지난 비종료 인증 세션 수, 만료 지난 미교환 토큰 수(시험 시계 기준,
 * label 없음 — ID를 label에 넣지 않는다). 주기 작업이 시각 지난 REQUESTED를 모두 닫으면 그 gauge는 0이다. 결제창·인증·만료 지표의 label 이름은 `result`·`outcome`뿐이다.
 */
@PaymentApiTest
class CheckoutObservabilitySpec(
    private val registry: MeterRegistry,
    private val job: ExpireElapsedRequestedPaymentsUseCase,
    private val start: StartCardAuthenticationUseCase,
    private val clock: MutablePaymentClock,
    create: CreatePaymentUseCase,
    issue: IssueCheckoutTokenUseCase,
    open: OpenCheckoutSessionUseCase,
    db: DbAccess,
) : BehaviorSpec({

    afterEach { clock.reset() }

    val flow = CheckoutExpiryFlow(create, issue, open, db)

    fun gauge(name: String): Double = registry.find(name).gauge()!!.also { it.id.tags shouldBe emptyList() }.value()

    Given("an open checkout with an IN_PROGRESS authentication and an unexchanged token") {
        Then("the backlog gauges count them once their deadlines pass, and the elapsed-REQUESTED gauge is 0 after the expiry job") {
            val c = flow.opened()
            start.start(StartCardAuthenticationCommand(c.handle, c.secret, "SIMISSA", "LUMP_SUM", "req-obs")).shouldBeInstanceOf<StartCardAuthenticationResult.Redirect>()
            flow.token(c.order, c.paymentId)
            clock.set(ApiTestTimes.T0.plus(Duration.ofMinutes(11))) // 인증 세션(10분)·토큰(10분) 시각 경과, 결제창(30분) 안
            gauge(UNRESOLVED_SESSIONS) shouldBeGreaterThanOrEqual 1.0
            gauge(EXPIRED_UNEXCHANGED_TOKENS) shouldBeGreaterThanOrEqual 1.0

            clock.set(WINDOW_END)
            gauge(ELAPSED_REQUESTED) shouldBeGreaterThanOrEqual 1.0
            job.expireElapsed(ExpireElapsedRequestedPaymentsCommand(UUID.randomUUID().toString(), 100))
            gauge(ELAPSED_REQUESTED) shouldBe 0.0
        }
    }

    Given("every checkout and authentication meter (AUTHN-001)") {
        Then("label names are only result or outcome (no identifiers in labels)") {
            registry.meters.filter { m -> listOf("paycore.checkout", "paycore.authentication", "paycore.payment.checkout").any { m.id.name.startsWith(it) } }.flatMap { it.id.tags.map { t -> t.key } }.toSet().minus(setOf("result", "outcome")) shouldBe emptySet()
        }
    }
}) {
    companion object {
        const val ELAPSED_REQUESTED = "paycore.payment.checkout_window.elapsed_requested"
        const val UNRESOLVED_SESSIONS = "paycore.authentication.sessions.unresolved"
        const val EXPIRED_UNEXCHANGED_TOKENS = "paycore.checkout.tokens.unexchanged_expired"
    }
}

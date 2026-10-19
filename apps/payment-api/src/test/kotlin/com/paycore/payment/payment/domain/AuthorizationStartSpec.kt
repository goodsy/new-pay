package com.paycore.payment.payment.domain

import com.paycore.payment.payment.domain.AuthorizationFixture.authorizationId
import com.paycore.payment.payment.domain.AuthorizationFixture.command
import com.paycore.payment.payment.domain.AuthorizationFixture.limitPolicy
import com.paycore.payment.payment.domain.AuthorizationFixture.readyToStart
import com.paycore.payment.payment.domain.AuthorizationFixture.startedAt
import com.paycore.payment.payment.domain.MoneyFixture.won
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * 승인 시작 단일 domain 연산(L-2·L-3, AC 7, 명세 §4.2): 성공하면 Payment AUTHORIZING + Authorization OPEN(Attempt PREPARED) + 세션 귀속 +
 * 한도 점유가 함께 생기고, 어떤 거절·예외에서도 아무 Aggregate도 바뀌지 않는다(D-AUTH-005: REQUESTED 유지·세션 미귀속).
 * 실제 DB 경쟁(승인 시작 vs 만료, 동시 confirm)은 AUTH-001 application 시험의 몫이다.
 */
class AuthorizationStartSpec : BehaviorSpec({

    /** 아무것도 바뀌지 않았다: Payment REQUESTED, 세션 VERIFIED·미귀속, 한도 점유 없음, control 그대로. */
    fun StartReady.shouldBeUntouched() {
        payment.status shouldBe PaymentStatus.REQUESTED
        session.status shouldBe AuthenticationStatus.VERIFIED
        session.boundAuthorizationId shouldBe null
        usage.reservations shouldBe emptyList()
        control.currentPaymentId shouldBe payment.id
        control.authorizedPaymentId shouldBe null
    }

    Given("a REQUESTED payment with a VERIFIED session, the Demo plan and limit policies") {
        Then("start changes all four together: AUTHORIZING, OPEN authorization with one PREPARED attempt, bound session, reserved limit") {
            val ready = readyToStart()
            val result = AuthorizationStart.start(command(ready))
            val started = result.shouldBeInstanceOf<AuthorizationStartResult.Started>().authorization
            ready.payment.status shouldBe PaymentStatus.AUTHORIZING
            started.id shouldBe authorizationId
            started.status shouldBe AuthorizationStatus.OPEN
            started.attempts.map { it.state } shouldBe listOf(AttemptState.PREPARED)
            started.attempts.single().providerRequest shouldBe AuthorizationFixture.providerRequest
            started.money shouldBe 10_000L.won()
            started.plan shouldBe PaymentPlan.LumpSum
            started.authenticatedCardType shouldBe CardType.CREDIT
            started.admission shouldBe AuthorizationFixture.admission
            started.limitPolicyVersion shouldBe "demo-limit-v1"
            started.planPolicyVersion shouldBe "demo-plan-v1"
            started.limitKey.businessDate shouldBe java.time.LocalDate.of(2026, 10, 15)
            ready.session.boundAuthorizationId shouldBe authorizationId
            ready.usage.reservationOf(authorizationId) shouldBe LimitReservation(authorizationId, 10_000, LimitReservationState.RESERVED)
        }
    }

    Given("business rejections before anything is sent (D-AUTH-005)") {
        Then("an unverified session → AuthenticationNotVerified(NOT_VERIFIED), nothing changes") {
            val order = OrderPaymentControlFixture.openedWithRequestedPayment()
            val ready = StartReady(order.control, order.current, AuthorizationFixture.inProgressSession(order.current), DailyLimitUsage.open(AuthorizationFixture.usageKey()))
            AuthorizationStart.start(command(ready)) shouldBe
                AuthorizationStartResult.Rejected(AuthorizationStartRejection.AuthenticationNotVerified(BindingRejection.NOT_VERIFIED))
            ready.payment.status shouldBe PaymentStatus.REQUESTED
            ready.session.boundAuthorizationId shouldBe null
            ready.usage.reservations shouldBe emptyList()
        }
        Then("a session past its TTL → AuthenticationNotVerified(EXPIRED)") {
            val ready = readyToStart()
            AuthorizationStart.start(command(ready, at = startedAt.plus(AuthorizationFixture.sessionTtl))) shouldBe
                AuthorizationStartResult.Rejected(AuthorizationStartRejection.AuthenticationNotVerified(BindingRejection.EXPIRED))
            ready.shouldBeUntouched()
        }
        Then("a check card installment → PlanNotAllowed(CHECK_CARD_INSTALLMENT), the session stays reusable for a new plan") {
            val ready = readyToStart(plan = PaymentPlan.Installment(3), cardType = CardType.CHECK)
            AuthorizationStart.start(command(ready, plans = AuthorizationFixture.lowMinimumPlanPolicy)) shouldBe
                AuthorizationStartResult.Rejected(AuthorizationStartRejection.PlanNotAllowed(PlanRejection.CHECK_CARD_INSTALLMENT))
            ready.shouldBeUntouched()
        }
        Then("a missing or ambiguous limit policy → LimitPolicyUnavailable (fail-closed, D-AUTH-006)") {
            listOf(LimitPolicyResolution.Missing, LimitPolicyResolution.Ambiguous).forEach { resolution ->
                val ready = readyToStart()
                AuthorizationStart.start(command(ready, limit = resolution)) shouldBe
                    AuthorizationStartResult.Rejected(AuthorizationStartRejection.LimitPolicyUnavailable(resolution))
                ready.shouldBeUntouched()
            }
        }
        Then("over the per-transaction or daily limit → LimitExceeded, nothing reserved") {
            val perTx = readyToStart()
            AuthorizationStart.start(command(perTx, limit = LimitPolicyResolution.Resolved(limitPolicy(perTransaction = LimitAmount.Limited(9_999))))) shouldBe
                AuthorizationStartResult.Rejected(AuthorizationStartRejection.LimitExceeded(LimitRejection.PER_TRANSACTION_LIMIT_EXCEEDED))
            perTx.shouldBeUntouched()
            val daily = readyToStart()
            AuthorizationStart.start(command(daily, limit = LimitPolicyResolution.Resolved(limitPolicy(daily = LimitAmount.Limited(9_999))))) shouldBe
                AuthorizationStartResult.Rejected(AuthorizationStartRejection.LimitExceeded(LimitRejection.DAILY_LIMIT_EXCEEDED))
            daily.shouldBeUntouched()
        }
        Then("after a rejection the same payment can start once the condition is resolved (new key, same window)") {
            val ready = readyToStart()
            AuthorizationStart.start(command(ready, limit = LimitPolicyResolution.Missing)).shouldBeInstanceOf<AuthorizationStartResult.Rejected>()
            AuthorizationStart.start(command(ready)).shouldBeInstanceOf<AuthorizationStartResult.Started>()
            ready.payment.status shouldBe PaymentStatus.AUTHORIZING
        }
    }

    Given("slot violations (existing domain exceptions, checked first)") {
        Then("incident hold on the order → AuthorizationStartRejectedException(INCIDENT_HOLD), nothing changes") {
            // 1번 시도 확정 거절 → 2번 시도 REQUESTED → 1번에 늦은 성공 보고(hold).
            val order = OrderPaymentControlFixture.retriedAfterConfirmedDecline()
            order.control.reportSuccessAfterTermination(order.history.first())
            val ready = StartReady(order.control, order.current, AuthorizationFixture.verifiedSession(order.current), DailyLimitUsage.open(AuthorizationFixture.usageKey()))
            shouldThrow<AuthorizationStartRejectedException> { AuthorizationStart.start(command(ready)) }.reason shouldBe BlockReason.INCIDENT_HOLD
            ready.payment.status shouldBe PaymentStatus.REQUESTED
            ready.session.boundAuthorizationId shouldBe null
            ready.usage.reservations shouldBe emptyList()
        }
        Then("a payment that is not the current slot → StaleSlotException; an already started payment → InvalidPaymentTransitionException") {
            val order = OrderPaymentControlFixture.retriedAfterConfirmedDecline()
            val stale = StartReady(order.control, order.history.first(), AuthorizationFixture.verifiedSession(PaymentFixture.requested()), DailyLimitUsage.open(AuthorizationFixture.usageKey()))
            shouldThrow<StaleSlotException> { AuthorizationStart.start(command(stale)) }
            val ready = readyToStart()
            AuthorizationStart.start(command(ready)).shouldBeInstanceOf<AuthorizationStartResult.Started>()
            shouldThrow<InvalidPaymentTransitionException> {
                AuthorizationStart.start(command(ready).copy(authorizationId = AuthorizationId("demo-auth-0002")))
            }
            ready.usage.reservations.size shouldBe 1
        }
        Then("inconsistent inputs are caller defects and change nothing: another intent, another business date's usage") {
            val ready = readyToStart()
            shouldThrow<IllegalArgumentException> { AuthorizationStart.start(command(ready, intent = OrderIntent(OrderFixture.demoSite, 10_001L.won()))) }
            ready.shouldBeUntouched()
            shouldThrow<IllegalArgumentException> { AuthorizationStart.start(command(ready, at = startedAt.plusSeconds(86_400))) }
            ready.shouldBeUntouched()
        }
    }

    // AUTH-001 명세 §4.11-7(AU-05): Admission 호출 전 읽기 전용 사전 검사 — 슬롯 위반은 start와 같은 예외, 인증 근거 무효는 결과 값. 상태를 바꾸지 않는다.
    Given("the read-only preflight before the admission check") {
        Then("a bindable VERIFIED session passes and nothing changes") {
            val ready = readyToStart()
            AuthorizationStart.preflight(ready.control, ready.payment, ready.session, AuthorizationFixture.intent.money, startedAt) shouldBe null
            ready.shouldBeUntouched()
        }
        Then("no session found for the reference, or a session that cannot be bound, is AuthenticationNotVerified and nothing changes") {
            val ready = readyToStart()
            AuthorizationStart.preflight(ready.control, ready.payment, null, AuthorizationFixture.intent.money, startedAt) shouldBe
                AuthorizationStartRejection.AuthenticationNotVerified(BindingRejection.NOT_VERIFIED)
            AuthorizationStart.preflight(ready.control, ready.payment, ready.session, AuthorizationFixture.intent.money, startedAt.plusSeconds(86_400)) shouldBe
                AuthorizationStartRejection.AuthenticationNotVerified(BindingRejection.EXPIRED)
            ready.shouldBeUntouched()
        }
        Then("slot violations are reported first with the same exceptions as start") {
            val order = OrderPaymentControlFixture.retriedAfterConfirmedDecline()
            order.control.reportSuccessAfterTermination(order.history.first())
            shouldThrow<AuthorizationStartRejectedException> {
                AuthorizationStart.preflight(order.control, order.current, null, AuthorizationFixture.intent.money, startedAt)
            }.reason shouldBe BlockReason.INCIDENT_HOLD
            order.current.status shouldBe PaymentStatus.REQUESTED
        }
    }
})

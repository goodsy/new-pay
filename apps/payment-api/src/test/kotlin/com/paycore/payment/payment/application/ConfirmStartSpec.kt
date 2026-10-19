package com.paycore.payment.payment.application

import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.adapter.persistence.FaultInjector
import com.paycore.payment.payment.adapter.persistence.FaultPoint
import com.paycore.payment.payment.adapter.persistence.PersistenceTestData
import com.paycore.payment.payment.adapter.web.MutablePaymentClock
import com.paycore.payment.payment.adapter.web.PaymentApiTest
import com.paycore.payment.payment.application.ConfirmFixture.Companion.T0
import com.paycore.payment.payment.application.provided.ConfirmOutcome
import com.paycore.payment.payment.application.provided.ConfirmPaymentFailure
import com.paycore.payment.payment.application.provided.ConfirmRejection
import com.paycore.payment.payment.application.required.AdmissionRefusalReason
import com.paycore.payment.payment.application.required.AdmissionResult
import com.paycore.payment.payment.application.required.AdmissionUnavailableReason
import com.paycore.payment.payment.application.required.AuthenticationSessionRepository
import com.paycore.payment.payment.application.required.CheckoutSessionStore
import com.paycore.payment.payment.application.required.CheckoutTokenStore
import com.paycore.payment.payment.application.required.OrderPaymentControlRepository
import com.paycore.payment.payment.application.required.PaymentAuthorizationRepository
import com.paycore.payment.payment.application.required.PaymentRepository
import com.paycore.payment.payment.domain.AttemptState
import com.paycore.payment.payment.domain.AuthorizationStatus
import com.paycore.payment.payment.domain.CardType
import com.paycore.payment.payment.domain.CurrencyCode
import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.LimitPolicyResolution
import com.paycore.payment.payment.domain.LimitReservationState
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.OrderKey
import com.paycore.payment.payment.domain.PaymentPlan
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Duration
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * confirm TX1(AUTH-001 §4.3 판정 순서·§4.8 멱등 저장·§4.9 매핑 중 TX1이 정하는 것). 실 MySQL·운영 Port·운영 Route 등록부(시험 설정), 허용 판정은 시험 대역
 * ([ScriptedAdmissionPort]). 기대값은 명세 표에서 직접 적는다. 전송(TXd 이후)은 S6 — 여기서는 TX1이 커밋한 행과 저장 결과만 본다.
 */
@PaymentApiTest
class ConfirmStartSpec(
    private val start: ConfirmStartService,
    private val admission: ScriptedAdmissionPort,
    private val clock: MutablePaymentClock,
    private val authorizations: PaymentAuthorizationRepository,
    private val faults: FaultInjector,
    payments: PaymentRepository,
    controls: OrderPaymentControlRepository,
    sessions: AuthenticationSessionRepository,
    tokens: CheckoutTokenStore,
    checkoutSessions: CheckoutSessionStore,
    private val transactionManager: PlatformTransactionManager,
    private val db: DbAccess,
) : BehaviorSpec({

    val fixture = ConfirmFixture(payments, controls, sessions, tokens, checkoutSessions, transactionManager, db)
    val tx = TransactionTemplate(transactionManager)

    afterEach {
        clock.reset()
        faults.reset()
    }

    fun accepted(o: ConfirmStartOutcome) = o.shouldBeInstanceOf<ConfirmStartOutcome.Accepted>()

    fun rejected(o: ConfirmStartOutcome, rejection: ConfirmRejection): ConfirmStartOutcome.Rejected {
        val r = o.shouldBeInstanceOf<ConfirmStartOutcome.Rejected>()
        r.rejection shouldBe rejection
        return r
    }

    Given("a REQUESTED payment with a VERIFIED authentication session") {
        Then("TX1 commits Payment AUTHORIZING, one OPEN authorization with a PREPARED attempt, the session bound, the limit reserved and ACCEPTED (L-3, AN §22-8)") {
            val p = fixture.prepared()
            val a = accepted(start.start(p.command()))
            a.code shouldBe ConfirmOutcome.ACCEPTED
            a.replayed shouldBe false
            fixture.paymentStatus(p) shouldBe "AUTHORIZING"
            val stored = tx.execute { authorizations.lockByPaymentId(p.paymentId) }!!.value
            stored.id shouldBe a.authorizationId
            stored.status shouldBe AuthorizationStatus.OPEN
            stored.attempts.single().state shouldBe AttemptState.PREPARED
            stored.attempts.single().providerRequest.requestId shouldStartWith "ar_"
            stored.attempts.single().providerRequest.institutionAccount shouldBe ScriptedAdmissionPort.INSTITUTION_MERCHANT_REF
            stored.admission.routeProfile shouldBe "SIM-VAN-01"
            stored.admission.protocolVersion shouldBe "sim-van-1"
            fixture.scalar("SELECT bound_authorization_id FROM authentication_session WHERE authentication_ref = ?", p.authenticationRef.value) shouldBe a.authorizationId.value
            fixture.scalar("SELECT state FROM limit_reservation WHERE authorization_id = ?", a.authorizationId.value) shouldBe LimitReservationState.RESERVED.name
            fixture.idempotencyRows(p).single().let {
                it["result_kind"] shouldBe "SUCCESS"
                it["result_code"] shouldBe "ACCEPTED"
                it["result_authorization_id"] shouldBe a.authorizationId.value
            }
        }
        Then("the admission is evaluated inside TX1 with the same instant as the authorization start (MS2 §6.4, AC 15)") {
            val p = fixture.prepared()
            val a = accepted(start.start(p.command()))
            val call = admission.callsFor(p.order.merchantAccountId).single()
            call.inTransaction shouldBe true
            call.acquirerCode shouldBe "SIMACQ-A"
            val openedAt = tx.execute { authorizations.lockByPaymentId(p.paymentId) }!!.value.openedAt
            call.evaluatedAt shouldBe openedAt
            a.authorizationId shouldNotBe null
        }
    }

    Given("idempotency (§4.8, D-AUTH-004)") {
        Then("the same key and body replays the stored result without new rows; the same key with another body is IDEMPOTENCY_KEY_REUSED") {
            val p = fixture.prepared()
            val key = ConfirmFixture.newKey()
            val first = accepted(start.start(p.command(key = key)))
            val again = accepted(start.start(p.command(key = key)))
            again.authorizationId shouldBe first.authorizationId
            again.replayed shouldBe true
            fixture.authorizationRows(p) shouldBe 1
            start.start(p.command(key = key, merchantOrderId = "other-order")) shouldBe ConfirmStartOutcome.IdempotencyKeyReused
        }
        Then("another key with the same content reuses the active authorization (REUSED_ACTIVE_AUTHORIZATION, no new attempt)") {
            val p = fixture.prepared()
            val first = accepted(start.start(p.command()))
            val reused = accepted(start.start(p.command()))
            reused.code shouldBe ConfirmOutcome.REUSED_ACTIVE_AUTHORIZATION
            reused.authorizationId shouldBe first.authorizationId
            fixture.count("SELECT COUNT(*) FROM authorization_attempt WHERE authorization_id = ?", first.authorizationId.value) shouldBe 1
        }
        Then("another key with a different authenticationRef or restatement is CONFIRM_CONTENT_CONFLICT, stored and replayed") {
            val p = fixture.prepared()
            accepted(start.start(p.command()))
            val key = ConfirmFixture.newKey()
            rejected(start.start(p.command(key = key, authenticationRef = "aref_" + "Z".repeat(22))), ConfirmRejection.CONFIRM_CONTENT_CONFLICT)
            rejected(start.start(p.command(key = key, authenticationRef = "aref_" + "Z".repeat(22))), ConfirmRejection.CONFIRM_CONTENT_CONFLICT).replayed shouldBe true
            rejected(start.start(p.command(money = Money(9_999, p.money.currency))), ConfirmRejection.CONFIRM_CONTENT_CONFLICT)
        }
    }

    Given("the restatement and state checks before any session (§4.3-3·4·5)") {
        Then("a restatement different from the payment's fixed input is CONFIRM_CONTENT_CONFLICT and nothing starts (CA-08)") {
            listOf(
                { p: ConfirmFixture.Prepared -> p.command(merchantOrderId = "another-order") },
                { p: ConfirmFixture.Prepared -> p.command(money = Money(10_001, p.money.currency)) },
                { p: ConfirmFixture.Prepared -> p.command(money = Money(10_000, PersistenceTestData.USD)) },
            ).forEach { change ->
                val p = fixture.prepared()
                rejected(start.start(change(p)), ConfirmRejection.CONFIRM_CONTENT_CONFLICT)
                fixture.paymentStatus(p) shouldBe "REQUESTED"
                fixture.authorizationRows(p) shouldBe 0
            }
        }
        Then("a payment of another MID or an unknown payment is NotFound and nothing is stored") {
            val p = fixture.prepared()
            start.start(p.command(mid = PersistenceTestData.newOrder().merchantAccountId)) shouldBe ConfirmStartOutcome.NotFound
            start.start(p.command(paymentId = "pay_unknown_" + DbAccess.unique("x").takeLast(8))) shouldBe ConfirmStartOutcome.NotFound
            fixture.idempotencyRows(p) shouldBe emptyList()
        }
        Then("an elapsed checkout window expires the payment in the same TX: PAYMENT_TERMINATED stored, Payment EXPIRED, one PaymentExpired (AN §22-8)") {
            val p = fixture.prepared()
            clock.set(T0.plus(Duration.ofMinutes(31)))
            val r = rejected(start.start(p.command()), ConfirmRejection.PAYMENT_TERMINATED)
            r.replayed shouldBe false
            fixture.paymentStatus(p) shouldBe "EXPIRED"
            fixture.count("SELECT COUNT(*) FROM outbox_event WHERE effect_key = ?", "PaymentExpired:${p.paymentId.value}") shouldBe 1
            fixture.authorizationRows(p) shouldBe 0
            fixture.idempotencyRows(p).single()["result_code"] shouldBe "PAYMENT_TERMINATED"
        }
        Then("a session that is not VERIFIED, or an unknown authentication reference, is AUTHENTICATION_NOT_VERIFIED, stored, Payment REQUESTED") {
            val unverified = fixture.prepared(verified = false)
            rejected(start.start(unverified.command()), ConfirmRejection.AUTHENTICATION_NOT_VERIFIED)
            fixture.paymentStatus(unverified) shouldBe "REQUESTED"
            val p = fixture.prepared()
            rejected(start.start(p.command(authenticationRef = "aref_" + "Q".repeat(22))), ConfirmRejection.AUTHENTICATION_NOT_VERIFIED)
            fixture.idempotencyRows(p).single()["result_code"] shouldBe "AUTHENTICATION_NOT_VERIFIED"
        }
    }

    Given("admission results (§4.3-8, D-AUTH1-001)") {
        Then("Refused → PAYMENT_NOT_ELIGIBLE with the fixed reason, stored; NotFoundOrForbidden → NotFound, not stored") {
            val p = fixture.prepared()
            admission.respondFor(p.order.merchantAccountId) { AdmissionResult.Refused(AdmissionRefusalReason.CARD_COMPANY_NOT_ENABLED) }
            rejected(start.start(p.command()), ConfirmRejection.PAYMENT_NOT_ELIGIBLE).detail shouldBe "CARD_COMPANY_NOT_ENABLED"
            fixture.paymentStatus(p) shouldBe "REQUESTED"
            val q = fixture.prepared()
            admission.respondFor(q.order.merchantAccountId) { AdmissionResult.NotFoundOrForbidden }
            start.start(q.command()) shouldBe ConfirmStartOutcome.NotFound
            fixture.idempotencyRows(q) shouldBe emptyList()
        }
        Then("Unavailable and LimitPolicyUnavailable are 503 without storing; after the policy is registered the same key starts the authorization (AC 21)") {
            val p = fixture.prepared()
            admission.respondFor(p.order.merchantAccountId) { AdmissionResult.Unavailable(AdmissionUnavailableReason.SNAPSHOT_EXPIRED) }
            val key = ConfirmFixture.newKey()
            shouldThrow<ConfirmPaymentFailure.TemporarilyUnavailable> { start.start(p.command(key = key)) }
            admission.respondFor(p.order.merchantAccountId) { ScriptedAdmissionPort.admitted(it, limit = LimitPolicyResolution.Missing) }
            shouldThrow<ConfirmPaymentFailure.TemporarilyUnavailable> { start.start(p.command(key = key)) }
            fixture.idempotencyRows(p) shouldBe emptyList()
            fixture.paymentStatus(p) shouldBe "REQUESTED"
            fixture.scalar("SELECT bound_authorization_id FROM authentication_session WHERE authentication_ref = ?", p.authenticationRef.value) shouldBe null
            admission.respondFor(p.order.merchantAccountId) { ScriptedAdmissionPort.admitted(it) }
            accepted(start.start(p.command(key = key))).code shouldBe ConfirmOutcome.ACCEPTED
        }
        Then("a routeRef unknown to the route registry is 503 fail-closed without storing (MS2 §13.2)") {
            val p = fixture.prepared()
            admission.respondFor(p.order.merchantAccountId) { ScriptedAdmissionPort.admitted(it, routeRef = "SIM-VAN-99") }
            shouldThrow<ConfirmPaymentFailure.TemporarilyUnavailable> { start.start(p.command()) }
            fixture.idempotencyRows(p) shouldBe emptyList()
            fixture.authorizationRows(p) shouldBe 0
        }
        // 검토 #3 n-4(§4.3-9 "REAL·LIVE→SIMULATOR → 503"): Route 환경이 Payment 환경과 다르면 Capability 대조(저장되는 거절) 전에 503 fail-closed, 저장 없음.
        Then("a route whose environment differs from the payment's (LIVE payment, TEST simulator route) is 503 before any stored capability rejection") {
            val live = PersistenceTestData.newOrder().let { OrderKey(Environment.LIVE, it.merchantAccountId, it.merchantOrderId) }
            val p = fixture.prepared(currency = CurrencyCode("USD"), order = live)
            shouldThrow<ConfirmPaymentFailure.TemporarilyUnavailable> { start.start(p.command()) }
            fixture.idempotencyRows(p) shouldBe emptyList()
            fixture.authorizationRows(p) shouldBe 0
        }
    }

    Given("route capability and plan checks (§4.3-9·10, D-AUTH1-002·003)") {
        Then("a non-KRW payment is PAYMENT_NOT_ELIGIBLE, stored, nothing sent (AC 22)") {
            val p = fixture.prepared(amount = 1_000, currency = PersistenceTestData.USD)
            rejected(start.start(p.command()), ConfirmRejection.PAYMENT_NOT_ELIGIBLE).detail shouldBe "CURRENCY_NOT_SUPPORTED"
            fixture.authorizationRows(p) shouldBe 0
        }
        Then("card type × plan matrix: credit lump sum, credit installment (policy months), check lump sum start; check installment and months outside the policy are PAYMENT_PLAN_NOT_ALLOWED (AC 23)") {
            listOf(
                Triple(CardType.CREDIT, PaymentPlan.LumpSum, null),
                Triple(CardType.CREDIT, PaymentPlan.Installment(2), null),
                Triple(CardType.CREDIT, PaymentPlan.Installment(6), null),
                Triple(CardType.CHECK, PaymentPlan.LumpSum, null),
                Triple(CardType.CHECK, PaymentPlan.Installment(3), ConfirmRejection.PAYMENT_PLAN_NOT_ALLOWED),
                Triple(CardType.CREDIT, PaymentPlan.Installment(5), ConfirmRejection.PAYMENT_PLAN_NOT_ALLOWED),
                Triple(CardType.CREDIT, PaymentPlan.Installment(12), ConfirmRejection.PAYMENT_PLAN_NOT_ALLOWED),
            ).forEach { (cardType, plan, expected) ->
                withClue("$cardType $plan") {
                    val p = fixture.prepared(amount = 60_000, plan = plan, cardType = cardType)
                    val o = start.start(p.command())
                    if (expected == null) accepted(o) else rejected(o, expected)
                    fixture.paymentStatus(p) shouldBe if (expected == null) "AUTHORIZING" else "REQUESTED"
                }
            }
        }
        Then("an installment below the policy minimum amount is PAYMENT_PLAN_NOT_ALLOWED; at the minimum it starts") {
            rejected(start.start(fixture.prepared(amount = 49_999, plan = PaymentPlan.Installment(3)).command()), ConfirmRejection.PAYMENT_PLAN_NOT_ALLOWED)
            accepted(start.start(fixture.prepared(amount = 50_000, plan = PaymentPlan.Installment(3)).command()))
        }
        Then("limit boundaries (one policy per MID + currency, D-AUTH-006): per-transaction and daily exactly at the limit start, one minor unit over is LIMIT_EXCEEDED, stored") {
            val order = PersistenceTestData.newOrder()
            admission.respondFor(order.merchantAccountId) { ScriptedAdmissionPort.admitted(it, limit = LimitPolicyResolution.Resolved(ScriptedAdmissionPort.policy(it, perTransaction = 20_000, daily = 30_000))) }
            fun sameMid() = PersistenceTestData.newOrder().let { com.paycore.payment.payment.domain.OrderKey(it.environment, order.merchantAccountId, it.merchantOrderId) }
            rejected(start.start(fixture.prepared(amount = 20_001, order = sameMid()).command()), ConfirmRejection.LIMIT_EXCEEDED)
            accepted(start.start(fixture.prepared(amount = 20_000, order = sameMid()).command()))
            rejected(start.start(fixture.prepared(amount = 10_001, order = sameMid()).command()), ConfirmRejection.LIMIT_EXCEEDED)
            accepted(start.start(fixture.prepared(amount = 10_000, order = sameMid()).command()))
        }
    }

    Given("payment states after a start (§4.3-5)") {
        Then("a terminated payment is PAYMENT_TERMINATED; an expired payment by the job also") {
            val p = fixture.prepared()
            clock.set(T0.plus(Duration.ofMinutes(31)))
            rejected(start.start(p.command()), ConfirmRejection.PAYMENT_TERMINATED)
            clock.reset()
            rejected(start.start(p.command()), ConfirmRejection.PAYMENT_TERMINATED)
        }
    }

    // rev 0.3 m-1(AU-32): at은 사용량 잠금 전에 정하고, 잠금 대기 중 Asia/Seoul 자정이 지나면 TX를 처음부터 다시 한다(정책 선택 시각 = 일 한도 날짜 시각).
    Given("the Asia/Seoul business date changes while TX1 waits for the limit usage lock (m-1)") {
        Then("TX1 restarts: the authorization opens at the new instant, the usage row is the new business date, and the admission saw the same instant") {
            val base = java.time.Instant.parse("2026-10-14T14:59:00.000Z") // Asia/Seoul 23:59:00
            clock.set(base)
            val p = fixture.prepared(base = base)
            val moved = java.util.concurrent.atomic.AtomicBoolean(false)
            faults.after(FaultPoint.LIMIT_USAGE_LOCK) { if (moved.compareAndSet(false, true)) clock.set(base.plusSeconds(61)) } // 00:00:01 다음 날
            accepted(start.start(p.command()))
            val stored = tx.execute { authorizations.lockByPaymentId(p.paymentId) }!!.value
            stored.openedAt shouldBe base.plusSeconds(61)
            stored.limitKey.businessDate shouldBe java.time.LocalDate.of(2026, 10, 15)
            admission.callsFor(p.order.merchantAccountId).last().evaluatedAt shouldBe base.plusSeconds(61)
        }
    }

    Given("TX1 failure injection (§8 — all rows roll back)") {
        Then("a failure after the authorization insert or the limit usage update leaves no authorization, attempt, binding, reservation or idempotency row") {
            listOf(FaultPoint.AUTHORIZATION_INSERT, FaultPoint.LIMIT_USAGE_UPDATE).forEach { point ->
                withClue(point) {
                    val p = fixture.prepared()
                    faults.after(point) { throw IllegalStateException("injected failure") }
                    shouldThrow<RuntimeException> { start.start(p.command()) }
                    faults.reset()
                    fixture.paymentStatus(p) shouldBe "REQUESTED"
                    fixture.authorizationRows(p) shouldBe 0
                    fixture.scalar("SELECT bound_authorization_id FROM authentication_session WHERE authentication_ref = ?", p.authenticationRef.value) shouldBe null
                    fixture.idempotencyRows(p) shouldBe emptyList()
                    fixture.count("SELECT COUNT(*) FROM limit_reservation r JOIN payment_authorization a ON a.authorization_id = r.authorization_id WHERE a.payment_id = ?", p.paymentId.value) shouldBe 0
                    // 같은 키 재시도가 정상 시작으로 수렴한다.
                    accepted(start.start(p.command()))
                }
            }
        }
    }
})

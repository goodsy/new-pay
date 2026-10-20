package com.paycore.payment.payment.application

import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.adapter.persistence.FaultInjector
import com.paycore.payment.payment.adapter.persistence.FaultPoint
import com.paycore.payment.payment.adapter.web.MutablePaymentClock
import com.paycore.payment.payment.adapter.web.PaymentApiTest
import com.paycore.payment.payment.application.CancelFixture.Companion.ROUTE_CB
import com.paycore.payment.payment.application.provided.CancelOutcome
import com.paycore.payment.payment.application.provided.CancelPaymentFailure
import com.paycore.payment.payment.application.provided.CancelRejection
import com.paycore.payment.payment.application.provided.ConfirmPaymentUseCase
import com.paycore.payment.payment.application.required.AdmissionRefusalReason
import com.paycore.payment.payment.application.required.AdmissionResult
import com.paycore.payment.payment.application.required.AdmissionUnavailableReason
import com.paycore.payment.payment.application.required.AuthenticationSessionRepository
import com.paycore.payment.payment.application.required.CheckoutSessionStore
import com.paycore.payment.payment.application.required.CheckoutTokenStore
import com.paycore.payment.payment.application.required.OrderPaymentControlRepository
import com.paycore.payment.payment.application.required.PaymentRepository
import com.paycore.payment.payment.domain.CardType
import com.paycore.payment.payment.domain.CurrencyCode
import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.MerchantAccountId
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.PaymentPlan
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.transaction.PlatformTransactionManager
import java.time.Duration

/**
 * 취소 TX1(CANCEL-001 §4.3 판정 순서·§4.8 멱등 저장·§4.9 매핑 중 TX1이 정하는 것). 실 MySQL·운영 Port·운영 Route 등록부(시험 설정 — sim-van-2 `SIM-VAN-C`·`SIM-VAN-CB`),
 * 승인은 운영 confirm 경로로 만든다([CancelFixture]). 기대값은 명세 표에서 직접 적는다. 전송(TXd 이후)은 S6 — 여기서는 TX1이 커밋한 행과 저장 결과만 본다.
 */
@PaymentApiTest
class CancelStartServiceSpec(
    private val start: CancelStartService,
    private val admission: ScriptedAdmissionPort,
    private val routes: WithdrawableRouteResolver,
    private val faults: FaultInjector,
    private val clock: MutablePaymentClock,
    van: ProgrammableVan,
    confirm: ConfirmPaymentUseCase,
    payments: PaymentRepository,
    controls: OrderPaymentControlRepository,
    sessions: AuthenticationSessionRepository,
    tokens: CheckoutTokenStore,
    checkoutSessions: CheckoutSessionStore,
    transactionManager: PlatformTransactionManager,
    db: DbAccess,
) : BehaviorSpec({

    val confirmFixture = ConfirmFixture(payments, controls, sessions, tokens, checkoutSessions, transactionManager, db)
    val fixture = CancelFixture(confirmFixture, confirm, admission, controls, payments, transactionManager, van)

    afterEach {
        faults.reset()
        routes.reset()
        clock.reset()
    }

    fun accepted(o: CancelStartOutcome) = o.shouldBeInstanceOf<CancelStartOutcome.Accepted>()
    fun rejected(o: CancelStartOutcome) = o.shouldBeInstanceOf<CancelStartOutcome.Rejected>().rejection

    Given("an authorized payment on a sim-van-2 route with a cancel capability") {
        Then("a full cancel starts: cancellation OPEN, attempt PREPARED on the original route namespace (operation CANCEL, cr_ ULID), reservation RESERVED, schedule and ACCEPTED stored in one TX") {
            val a = fixture.authorized()
            val o = accepted(start.start(a.command(merchantCancelId = "mc-full-0001")))
            o.outcome shouldBe CancelOutcome.ACCEPTED
            o.replayed shouldBe false
            val id = o.cancellationId.value
            id shouldMatch Regex("^pcn_[0-9A-HJKMNP-TV-Z]{26}$")
            fixture.scalar("SELECT status FROM payment_cancellation WHERE cancellation_id = ?", id) shouldBe "OPEN"
            fixture.scalar("SELECT scope FROM payment_cancellation WHERE cancellation_id = ?", id) shouldBe "FULL"
            fixture.scalar("SELECT merchant_cancel_id FROM payment_cancellation WHERE cancellation_id = ?", id) shouldBe "mc-full-0001"
            fixture.scalar("SELECT cancel_capability_version FROM payment_cancellation WHERE cancellation_id = ?", id) shouldBe "cancel-c-demo-1"
            fixture.scalar("SELECT CONCAT(state, '|', institution, '|', environment, '|', institution_account, '|', operation, '|', IFNULL(route_profile_version, '-')) FROM cancel_attempt WHERE cancellation_id = ?", id) shouldBe
                "PREPARED|SIMVAN|TEST|SIMMERCH0001|CANCEL|-"
            (fixture.scalar("SELECT request_id FROM cancel_attempt WHERE cancellation_id = ?", id) as String) shouldMatch Regex("^cr_[0-9A-HJKMNP-TV-Z]{26}$")
            (fixture.scalar("SELECT attempt_id FROM cancel_attempt WHERE cancellation_id = ?", id) as String) shouldMatch Regex("^pca_[0-9A-HJKMNP-TV-Z]{26}$")
            fixture.scalar("SELECT CONCAT(state, '|', amount_minor) FROM return_reservation WHERE cancellation_id = ?", id) shouldBe "RESERVED|10000"
            fixture.scalar("SELECT CONCAT(authorized_minor, '|', reserved_minor, '|', completed_minor) FROM return_balance WHERE payment_id = ?", a.paymentId.value) shouldBe "10000|10000|0"
            fixture.scalar("SELECT recovery_config_version FROM cancellation_recovery_schedule WHERE cancellation_id = ?", id) shouldBe "cancel-recovery-demo-1"
            fixture.count(
                "SELECT COUNT(*) FROM cancellation_recovery_schedule s JOIN cancel_attempt t ON t.attempt_id = s.attempt_id " +
                    "WHERE s.cancellation_id = ? AND s.next_check_at = t.prepared_at + INTERVAL 1 SECOND AND s.dispatch_lease_until IS NULL AND s.closed_at IS NULL",
                id,
            ) shouldBe 1
            fixture.idempotencyRows(a) shouldBe listOf(mapOf("result_kind" to "SUCCESS", "result_code" to "ACCEPTED", "result_payment_id" to a.paymentId.value, "result_cancellation_id" to id))
            // K-12: Payment·주문 승인 이력은 그대로.
            fixture.scalar("SELECT status FROM payment WHERE payment_id = ?", a.paymentId.value) shouldBe "AUTHORIZED"
        }
        Then("a partial cancel is PARTIAL and leaves the rest cancellable") {
            val a = fixture.authorized()
            val id = accepted(start.start(a.command(money = Money(3_000, a.money.currency)))).cancellationId.value
            fixture.scalar("SELECT scope FROM payment_cancellation WHERE cancellation_id = ?", id) shouldBe "PARTIAL"
            fixture.scalar("SELECT CONCAT(reserved_minor, '|', authorized_minor) FROM return_balance WHERE payment_id = ?", a.paymentId.value) shouldBe "3000|10000"
        }
    }

    Given("idempotency (K-22) and the business key (D-CANCEL-002)") {
        Then("the same key and body replays the stored result without a new cancellation; another body under the key is IDEMPOTENCY_KEY_REUSED") {
            val a = fixture.authorized()
            val c = a.command()
            val first = accepted(start.start(c))
            val again = accepted(start.start(c))
            again.cancellationId shouldBe first.cancellationId
            again.replayed shouldBe true
            start.start(c.copy(money = Money(9_000, a.money.currency))) shouldBe CancelStartOutcome.IdempotencyKeyReused
            fixture.cancellationRows(a) shouldBe 1
        }
        Then("another key with the same merchantCancelId and content is EXISTING_CANCELLATION (stored); different content is CANCEL_REQUEST_CONFLICT (stored, replayed)") {
            val a = fixture.authorized()
            val first = accepted(start.start(a.command(merchantCancelId = "mc-same-01", money = Money(3_000, a.money.currency))))
            val existing = accepted(start.start(a.command(merchantCancelId = "mc-same-01", money = Money(3_000, a.money.currency))))
            existing.outcome shouldBe CancelOutcome.EXISTING_CANCELLATION
            existing.cancellationId shouldBe first.cancellationId
            val conflictCommand = a.command(merchantCancelId = "mc-same-01", money = Money(4_000, a.money.currency))
            rejected(start.start(conflictCommand)) shouldBe CancelRejection.CANCEL_REQUEST_CONFLICT
            start.start(conflictCommand).shouldBeInstanceOf<CancelStartOutcome.Rejected>().replayed shouldBe true
            fixture.cancellationRows(a) shouldBe 1
            fixture.idempotencyRows(a).map { it["result_code"] } shouldBe listOf("ACCEPTED", "EXISTING_CANCELLATION", "CANCEL_REQUEST_CONFLICT")
        }
        Then("(n-8) a merchantCancelId rejected without a cancellation row can be used again with a new key") {
            val a = fixture.authorized()
            rejected(start.start(a.command(merchantCancelId = "mc-retry-01", money = Money(10_001, a.money.currency)))) shouldBe CancelRejection.CANCEL_AMOUNT_EXCEEDS_BALANCE
            accepted(start.start(a.command(merchantCancelId = "mc-retry-01"))).outcome shouldBe CancelOutcome.ACCEPTED
        }
    }

    Given("scope and existence (K-20)") {
        Then("another MID, another environment, an unknown or malformed paymentId is NotFound and nothing is stored") {
            val a = fixture.authorized()
            start.start(a.command(mid = MerchantAccountId("mid-other-${System.nanoTime()}"))) shouldBe CancelStartOutcome.NotFound
            start.start(a.command().copy(environment = Environment.LIVE)) shouldBe CancelStartOutcome.NotFound
            start.start(a.command(paymentId = "pay_doesnotexist0000000000")) shouldBe CancelStartOutcome.NotFound
            start.start(a.command(paymentId = "pay with space")) shouldBe CancelStartOutcome.NotFound
            fixture.idempotencyRows(a) shouldBe emptyList()
            fixture.cancellationRows(a) shouldBe 0
        }
    }

    Given("business rejections (stored and replayed — §4.9)") {
        Then("a REQUESTED payment is PAYMENT_NOT_CANCELLABLE") {
            val p = confirmFixture.prepared()
            val a = CancelFixture.Authorized(p, "none")
            rejected(start.start(a.command())) shouldBe CancelRejection.PAYMENT_NOT_CANCELLABLE
            fixture.idempotencyRows(a).single()["result_code"] shouldBe "PAYMENT_NOT_CANCELLABLE"
        }
        Then("a second cancel while one is in progress is CANCEL_IN_PROGRESS (D-CANCEL-003)") {
            val a = fixture.authorized()
            accepted(start.start(a.command(money = Money(3_000, a.money.currency))))
            rejected(start.start(a.command(money = Money(2_000, a.money.currency)))) shouldBe CancelRejection.CANCEL_IN_PROGRESS
            fixture.cancellationRows(a) shouldBe 1
        }
        Then("an amount above the cancellable balance, another currency, or a hold on the payment is rejected") {
            val a = fixture.authorized()
            rejected(start.start(a.command(money = Money(10_001, a.money.currency)))) shouldBe CancelRejection.CANCEL_AMOUNT_EXCEEDS_BALANCE
            rejected(start.start(a.command(money = Money(1_000, CurrencyCode("USD"))))) shouldBe CancelRejection.CURRENCY_MISMATCH
            fixture.holdOn(a)
            rejected(start.start(a.command())) shouldBe CancelRejection.ORDER_ON_HOLD
            fixture.cancellationRows(a) shouldBe 0
        }
        Then("a partial cancel of a check card on a route that supports partial cancel of credit lump sum only is PARTIAL_CANCEL_NOT_SUPPORTED; its full cancel starts") {
            val a = fixture.authorized(cardType = CardType.CHECK, routeRef = ROUTE_CB)
            rejected(start.start(a.command(money = Money(3_000, a.money.currency)))) shouldBe CancelRejection.PARTIAL_CANCEL_NOT_SUPPORTED
            accepted(start.start(a.command())).outcome shouldBe CancelOutcome.ACCEPTED
        }
        Then("a credit installment partial cancel on SIM-VAN-C starts (installment partial is a route capability, not a PG rule)") {
            val a = fixture.authorized(amount = 60_000, plan = PaymentPlan.Installment(3))
            accepted(start.start(a.command(money = Money(10_000, a.money.currency)))).outcome shouldBe CancelOutcome.ACCEPTED
        }
    }

    Given("fail-closed route resolution (503, nothing stored)") {
        Then("an authorization on a sim-van-1 route has no cancel capability: TemporarilyUnavailable and no rows, no idempotency record") {
            val a = fixture.authorized(routeRef = ScriptedAdmissionPort.ROUTE_REF)
            shouldThrow<CancelPaymentFailure.TemporarilyUnavailable> { start.start(a.command()) }
            fixture.idempotencyRows(a) shouldBe emptyList()
            fixture.cancellationRows(a) shouldBe 0
        }
        Then("a withdrawn route is TemporarilyUnavailable") {
            val a = fixture.authorized()
            routes.withdraw(CancelFixture.ROUTE_C)
            shouldThrow<CancelPaymentFailure.TemporarilyUnavailable> { start.start(a.command()) }
            fixture.cancellationRows(a) shouldBe 0
        }
        Then("m-4: a route whose institution or environment no longer matches the original attempt is TemporarilyUnavailable and nothing is stored") {
            val a = fixture.authorized()
            routes.replace(CancelFixture.ROUTE_C) { it.copy(institution = "OTHERVAN") }
            shouldThrow<CancelPaymentFailure.TemporarilyUnavailable> { start.start(a.command()) }
            routes.replace(CancelFixture.ROUTE_C) { it.copy(environment = Environment.LIVE) }
            shouldThrow<CancelPaymentFailure.TemporarilyUnavailable> { start.start(a.command()) }
            fixture.idempotencyRows(a) shouldBe emptyList()
            fixture.cancellationRows(a) shouldBe 0
        }
    }

    // K-3: 취소는 신규 승인 권리를 요구하지 않는다 — 신규 결제 중지·계약 철회·Snapshot lease 만료 중에도(Admission이 거절·불가여도) 시작된다. Admission을 부르지 않는다.
    Given("K-3 — the merchant can no longer start new payments") {
        Then("a cancel still starts and the admission port is never consulted by the cancel") {
            mapOf<String, (AdmissionCall) -> AdmissionResult>(
                "new payments suspended" to { AdmissionResult.Refused(AdmissionRefusalReason.entries.first()) },
                "contract revoked / not found" to { AdmissionResult.NotFoundOrForbidden },
                "snapshot lease expired / unavailable" to { AdmissionResult.Unavailable(AdmissionUnavailableReason.entries.first()) },
            ).forEach { (name, answer) ->
                val a = fixture.authorized()
                admission.respondFor(a.mid, answer)
                val before = admission.callsFor(a.mid).size
                withClue(name) {
                    accepted(start.start(a.command(money = Money(1_000, a.money.currency)))).outcome shouldBe CancelOutcome.ACCEPTED
                    admission.callsFor(a.mid).size shouldBe before
                }
            }
        }
    }

    Given("TX1 faults after each port flush (§9.2)") {
        Then("every row rolls back (no orphan cancellation, attempt, balance, reservation, schedule or idempotency record)") {
            listOf(
                FaultPoint.CANCELLATION_INSERT, FaultPoint.RETURN_BALANCE_INSERT, FaultPoint.CANCEL_SCHEDULE_INSERT, FaultPoint.IDEMPOTENCY_COMPLETE,
            ).forEach { point ->
                val a = fixture.authorized()
                faults.after(point) { throw IllegalStateException("injected after $point") }
                withClue(point) {
                    shouldThrow<RuntimeException> { start.start(a.command()) }
                    fixture.cancellationRows(a) shouldBe 0
                    fixture.count("SELECT COUNT(*) FROM return_balance WHERE payment_id = ?", a.paymentId.value) shouldBe 0
                    fixture.count("SELECT COUNT(*) FROM cancellation_recovery_schedule WHERE payment_id = ?", a.paymentId.value) shouldBe 0
                    fixture.idempotencyRows(a) shouldBe emptyList()
                }
                faults.reset()
            }
        }
        Then("a commit whose outcome is unknown is OutcomeUnknown; the same key then returns the committed result") {
            val a = fixture.authorized()
            val c = a.command()
            CommitFaults.arm()
            try {
                shouldThrow<CancelPaymentFailure.OutcomeUnknown> { start.start(c) }
            } finally {
                CommitFaults.reset()
            }
            accepted(start.start(c)).replayed shouldBe true
        }
    }

    Given("a stored record past its retention") {
        Then("is renewed as a new request (30 days — D-CPC-004)") {
            val a = fixture.authorized()
            val c = a.command(money = Money(10_001, a.money.currency))
            rejected(start.start(c)) shouldBe CancelRejection.CANCEL_AMOUNT_EXCEEDS_BALANCE
            clock.set(ConfirmFixture.T0.plus(Duration.ofDays(30)).plusSeconds(1))
            start.start(c).shouldBeInstanceOf<CancelStartOutcome.Rejected>().replayed shouldBe false
            start.start(c).shouldBeInstanceOf<CancelStartOutcome.Rejected>().replayed shouldBe true
        }
    }
})

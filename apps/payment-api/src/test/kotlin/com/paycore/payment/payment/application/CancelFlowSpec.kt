package com.paycore.payment.payment.application

import com.paycore.payment.payment.adapter.integration.institution.StubVan.Action
import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.adapter.persistence.FaultInjector
import com.paycore.payment.payment.adapter.persistence.FaultPoint
import com.paycore.payment.payment.adapter.web.MutablePaymentClock
import com.paycore.payment.payment.adapter.web.PaymentApiTest
import com.paycore.payment.payment.application.provided.CancelFailureCategory
import com.paycore.payment.payment.application.provided.CancelPaymentResult
import com.paycore.payment.payment.application.provided.CancelPaymentUseCase
import com.paycore.payment.payment.application.provided.CancelRejection
import com.paycore.payment.payment.application.provided.CancellationViewStatus
import com.paycore.payment.payment.application.provided.ConfirmPaymentUseCase
import com.paycore.payment.payment.application.provided.OperatorCorrectionCancelResult
import com.paycore.payment.payment.application.provided.OperatorCorrectionCancelUseCase
import com.paycore.payment.payment.application.required.AuthenticationSessionRepository
import com.paycore.payment.payment.application.required.CheckoutSessionStore
import com.paycore.payment.payment.application.required.CheckoutTokenStore
import com.paycore.payment.payment.application.required.OrderPaymentControlRepository
import com.paycore.payment.payment.application.required.PaymentRepository
import com.paycore.payment.payment.domain.CardType
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.PaymentPlan
import com.paycore.payment.payment.domain.ReturnStatus
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.transaction.PlatformTransactionManager
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * 취소 전체 흐름(CANCEL-001 §4.2 — TX1 → TXd → TX 밖 기관 취소 → TX2)을 실 MySQL·운영 Adapter·실제 소켓의 시험 전용 stub VAN([ProgrammableVan] — sim-van-2 원장 의미)으로 시험한다.
 * 기대값은 명세 §4.4~§4.6·§4.10·§6.6 표·§7.2·§9.2·§9.3에서 직접 적는다. 회복 Worker(UNKNOWN·PREPARED 방치 이후)는 S7.
 */
@PaymentApiTest
class CancelFlowSpec(
    private val cancel: CancelPaymentUseCase,
    private val operator: OperatorCorrectionCancelUseCase,
    private val van: ProgrammableVan,
    private val port: ObservedCancellationPort,
    private val admission: ScriptedAdmissionPort,
    private val routes: WithdrawableRouteResolver,
    private val faults: FaultInjector,
    private val clock: MutablePaymentClock,
    private val results: CancellationResultService,
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
    val mapper = JsonMapper.builder().build()

    afterEach {
        faults.reset()
        routes.reset()
        clock.reset()
        CommitFaults.reset()
        port.throwOnCall = false
    }

    fun accepted(r: CancelPaymentResult) = r.shouldBeInstanceOf<CancelPaymentResult.Accepted>()
    fun q(sql: String, vararg args: Any?) = fixture.scalar(sql, *args)
    fun n(sql: String, vararg args: Any?) = fixture.count(sql, *args)
    fun events(type: String, cancellationId: String) = n("SELECT COUNT(*) FROM outbox_event WHERE event_type = ? AND aggregate_id = ?", type, cancellationId)
    fun payload(type: String, cancellationId: String) = mapper.readTree(q("SELECT CAST(payload AS CHAR) FROM outbox_event WHERE event_type = ? AND aggregate_id = ?", type, cancellationId) as String)
    fun attempt(id: String, column: String) = q("SELECT $column FROM cancel_attempt WHERE cancellation_id = ?", id)
    fun reservation(id: String) = q("SELECT state FROM return_reservation WHERE cancellation_id = ?", id)

    Given("the VAN approves the cancel") {
        Then("200 COMPLETED: one CANCEL transaction, PaymentCancelled once, reservation CONSUMED seq 1, attempt RESOLVED with the pinned route profile version, one CANCEL_REQUEST") {
            val a = fixture.authorized()
            val v = accepted(cancel.cancel(a.command())).view
            v.status shouldBe CancellationViewStatus.COMPLETED
            v.returnStatus shouldBe ReturnStatus.FULLY_CANCELLED
            v.cancellableAmountMinor shouldBe 0
            v.cancelApprovalNumber!!.startsWith("4") shouldBe true
            val id = v.cancellationId.value
            q("SELECT status FROM payment_cancellation WHERE cancellation_id = ?", id) shouldBe "COMPLETED"
            attempt(id, "state") shouldBe "RESOLVED"
            attempt(id, "route_profile_version") shouldBe "sim-van-c-demo-1"
            n("SELECT COUNT(*) FROM payment_transaction WHERE cancellation_id = ? AND type = 'CANCEL'", id) shouldBe 1
            val txId = q("SELECT transaction_id FROM payment_transaction WHERE cancellation_id = ?", id) as String
            v.cancelTransactionId shouldBe txId
            q("SELECT CONCAT(state, '|', completion_sequence) FROM return_reservation WHERE cancellation_id = ?", id) shouldBe "CONSUMED|1"
            events("PaymentCancelled", id) shouldBe 1
            q("SELECT CONCAT(event_category, '|', effect_key, '|', aggregate_type) FROM outbox_event WHERE event_type = 'PaymentCancelled' AND aggregate_id = ?", id) shouldBe
                "FINANCIAL_FACT|PaymentCancelled:$txId|PaymentCancellation"
            val p = payload("PaymentCancelled", id)
            p.get("paymentId").stringValue() shouldBe a.paymentId.value
            p.get("authorizationId").stringValue() shouldBe a.authorizationId
            p.get("cancellationId").stringValue() shouldBe id
            p.get("transactionId").stringValue() shouldBe txId
            p.get("originalTransactionId").stringValue() shouldBe q("SELECT transaction_id FROM payment_transaction WHERE authorization_id = ? AND type = 'AUTHORIZATION'", a.authorizationId)
            p.get("amount").longValue() shouldBe 10_000
            p.get("authorizedAmount").longValue() shouldBe 10_000
            p.get("returnSequence").intValue() shouldBe 1
            p.get("cumulativeReturnedAmount").longValue() shouldBe 10_000
            p.get("returnStatusAfter").stringValue() shouldBe "FULLY_CANCELLED"
            p.get("scope").stringValue() shouldBe "FULL"
            p.get("initiatorKind").stringValue() shouldBe "MERCHANT"
            p.get("policyAssignmentVersion").stringValue() shouldBe q("SELECT policy_assignment_version FROM payment_authorization WHERE authorization_id = ?", a.authorizationId)
            p.toString().contains("SIMMERCH0001") shouldBe false
            val requests = van.cancelRequestsFor(a.mid.value)
            requests.size shouldBe 1
            requests.single().get("requestId").stringValue() shouldBe attempt(id, "request_id")
            requests.single().get("scope").stringValue() shouldBe "FULL"
            n("SELECT COUNT(*) FROM cancel_observation WHERE cancellation_id = ? AND source = 'RESPONSE'", id) shouldBe 1
            q("SELECT IF(closed_at IS NULL, 'open', 'closed') FROM cancellation_recovery_schedule WHERE cancellation_id = ?", id) shouldBe "closed"
            // K-12·INV-C5: Payment 상태·주문 승인 이력·일 한도 사용량은 바뀌지 않는다.
            q("SELECT status FROM payment WHERE payment_id = ?", a.paymentId.value) shouldBe "AUTHORIZED"
            q("SELECT authorized_payment_id FROM order_payment_control WHERE merchant_account_id = ? AND merchant_order_id = ?", a.mid.value, a.prepared.order.merchantOrderId.value) shouldBe
                a.paymentId.value
            q("SELECT state FROM limit_reservation WHERE authorization_id = ?", a.authorizationId) shouldBe "CONSUMED"
            // 외부 호출은 DB TX 밖(K-13).
            port.transactionActiveAtCall.last() shouldBe false
        }
        Then("the same key replays the current result without a new institution call; the payment is then ALREADY_FULLY_CANCELLED") {
            val a = fixture.authorized()
            val c = a.command()
            accepted(cancel.cancel(c)).view.status shouldBe CancellationViewStatus.COMPLETED
            val again = accepted(cancel.cancel(c))
            again.replayed shouldBe true
            again.view.status shouldBe CancellationViewStatus.COMPLETED
            van.cancelRequestsFor(a.mid.value).size shouldBe 1
            cancel.cancel(a.command()).shouldBeInstanceOf<CancelPaymentResult.Rejected>().rejection shouldBe CancelRejection.ALREADY_FULLY_CANCELLED
        }
        Then("a partial cancel and then the remainder complete with sequences 1 and 2; the second is FULLY_CANCELLED and the scope stays PARTIAL") {
            val a = fixture.authorized()
            val first = accepted(cancel.cancel(a.command(money = Money(3_000, a.money.currency)))).view
            first.status shouldBe CancellationViewStatus.COMPLETED
            first.returnStatus shouldBe ReturnStatus.PARTIALLY_CANCELLED
            first.cancellableAmountMinor shouldBe 7_000
            val second = accepted(cancel.cancel(a.command(money = Money(7_000, a.money.currency)))).view
            second.returnStatus shouldBe ReturnStatus.FULLY_CANCELLED
            payload("PaymentCancelled", second.cancellationId.value).get("returnSequence").intValue() shouldBe 2
            van.cancelRequestsFor(a.mid.value).map { it.get("scope").stringValue() } shouldBe listOf("PARTIAL", "PARTIAL")
        }
    }

    Given("the VAN declines or answers PENDING") {
        Then("DECLINED → 200 FAILED DECLINED, reservation RELEASED, PaymentCancelFailed once; the balance is free for a new cancel") {
            val a = fixture.authorized()
            van.onCancel(a.mid.value) { van.declineCancel(it, "CANCEL_PERIOD_EXPIRED") }
            val v = accepted(cancel.cancel(a.command())).view
            v.status shouldBe CancellationViewStatus.FAILED
            v.failureCategory shouldBe CancelFailureCategory.DECLINED
            reservation(v.cancellationId.value) shouldBe "RELEASED"
            events("PaymentCancelFailed", v.cancellationId.value) shouldBe 1
            q("SELECT event_category FROM outbox_event WHERE event_type = 'PaymentCancelFailed' AND aggregate_id = ?", v.cancellationId.value) shouldBe "INTEGRATION_NON_FINANCIAL"
            v.cancellableAmountMinor shouldBe 10_000
        }
        Then("PENDING → 202 UNKNOWN, reservation RESERVED, schedule open; another cancel is CANCEL_IN_PROGRESS") {
            val a = fixture.authorized()
            van.onCancel(a.mid.value) { van.pendingCancel(it) }
            val v = accepted(cancel.cancel(a.command())).view
            v.status shouldBe CancellationViewStatus.UNKNOWN
            reservation(v.cancellationId.value) shouldBe "RESERVED"
            q("SELECT IF(closed_at IS NULL, 'open', 'closed') FROM cancellation_recovery_schedule WHERE cancellation_id = ?", v.cancellationId.value) shouldBe "open"
            cancel.cancel(a.command(money = Money(1_000, a.money.currency))).shouldBeInstanceOf<CancelPaymentResult.Rejected>().rejection shouldBe CancelRejection.CANCEL_IN_PROGRESS
        }
    }

    Given("transport failures after the request was written (CC §7 — never NO_EFFECT from a timeout)") {
        Then("the VAN records the cancel and closes without a response → 202 UNKNOWN, attempt OUTCOME_UNKNOWN(CONNECTION_LOST), reservation RESERVED, no transaction") {
            val a = fixture.authorized()
            van.onCancel(a.mid.value) { r -> ProgrammableVan.Script(van.cancelApproved(r), Action.Close) }
            val v = accepted(cancel.cancel(a.command())).view
            v.status shouldBe CancellationViewStatus.UNKNOWN
            attempt(v.cancellationId.value, "CONCAT(state, '|', unknown_cause)") shouldBe "OUTCOME_UNKNOWN|CONNECTION_LOST"
            reservation(v.cancellationId.value) shouldBe "RESERVED"
            n("SELECT COUNT(*) FROM payment_transaction WHERE cancellation_id = ?", v.cancellationId.value) shouldBe 0
            van.cancelDecided(attempt(v.cancellationId.value, "request_id") as String)!!.get("outcome").stringValue() shouldBe "APPROVED"
        }
        Then("n-4: an exception from the cancellation port is OutcomeUnknown — 202 UNKNOWN, no exception to the caller") {
            val a = fixture.authorized()
            port.throwOnCall = true
            val v = accepted(cancel.cancel(a.command())).view
            v.status shouldBe CancellationViewStatus.UNKNOWN
            attempt(v.cancellationId.value, "state") shouldBe "OUTCOME_UNKNOWN"
            reservation(v.cancellationId.value) shouldBe "RESERVED"
        }
    }

    Given("the dispatch gate (TXd — §4.4)") {
        Then("a route withdrawn after TX1 is GATE_REFUSED: 200 FAILED NOT_SENT, no CANCEL_REQUEST, reservation RELEASED, PaymentCancelFailed, schedule closed") {
            val a = fixture.authorized()
            faults.after(FaultPoint.IDEMPOTENCY_COMPLETE) { routes.withdraw(CancelFixture.ROUTE_C) }
            val v = accepted(cancel.cancel(a.command())).view
            v.status shouldBe CancellationViewStatus.FAILED
            v.failureCategory shouldBe CancelFailureCategory.NOT_SENT
            attempt(v.cancellationId.value, "CONCAT(state, '|', abandon_cause)") shouldBe "ABANDONED_BEFORE_DISPATCH|GATE_REFUSED"
            van.cancelRequestsFor(a.mid.value).size shouldBe 0
            reservation(v.cancellationId.value) shouldBe "RELEASED"
            events("PaymentCancelFailed", v.cancellationId.value) shouldBe 1
            q("SELECT IF(closed_at IS NULL, 'open', 'closed') FROM cancellation_recovery_schedule WHERE cancellation_id = ?", v.cancellationId.value) shouldBe "closed"
        }
        Then("m-4: a route whose institution no longer matches the original attempt after TX1 is GATE_REFUSED and nothing is sent") {
            val a = fixture.authorized()
            faults.after(FaultPoint.IDEMPOTENCY_COMPLETE) { routes.replace(CancelFixture.ROUTE_C) { it.copy(institution = "OTHERVAN") } }
            accepted(cancel.cancel(a.command())).view.failureCategory shouldBe CancelFailureCategory.NOT_SENT
            van.cancelRequestsFor(a.mid.value).size shouldBe 0
        }
        Then("CN-08: a TXd commit whose outcome is unknown does not send — 202, attempt DISPATCHING without a CANCEL_REQUEST") {
            val a = fixture.authorized()
            val updates = AtomicInteger()
            faults.after(FaultPoint.CANCELLATION_UPDATE) { if (updates.incrementAndGet() == 1) CommitFaults.arm() }
            val v = accepted(cancel.cancel(a.command())).view
            v.status shouldBe CancellationViewStatus.IN_PROGRESS
            attempt(v.cancellationId.value, "state") shouldBe "DISPATCHING"
            van.cancelRequestsFor(a.mid.value).size shouldBe 0
        }
        Then("past the prepared deadline the gate does not dispatch — 202 IN_PROGRESS, attempt PREPARED (left to recovery fencing)") {
            val a = fixture.authorized()
            faults.after(FaultPoint.IDEMPOTENCY_COMPLETE) { clock.set(ConfirmFixture.T0.plus(Duration.ofSeconds(5))) }
            val v = accepted(cancel.cancel(a.command())).view
            v.status shouldBe CancellationViewStatus.IN_PROGRESS
            attempt(v.cancellationId.value, "state") shouldBe "PREPARED"
            van.cancelRequestsFor(a.mid.value).size shouldBe 0
        }
    }

    Given("a TX2 failure after the VAN approved (§9.2)") {
        Then("202; the cancellation stays OPEN with the attempt DISPATCHING and exactly one CANCEL_REQUEST (no resend) — recovery converges it (S7)") {
            val a = fixture.authorized()
            val updates = AtomicInteger()
            faults.after(FaultPoint.CANCELLATION_UPDATE) { if (updates.incrementAndGet() == 2) throw IllegalStateException("injected TX2 failure") }
            val v = accepted(cancel.cancel(a.command())).view
            v.status shouldBe CancellationViewStatus.IN_PROGRESS
            attempt(v.cancellationId.value, "state") shouldBe "DISPATCHING"
            van.cancelRequestsFor(a.mid.value).size shouldBe 1
            n("SELECT COUNT(*) FROM cancel_observation WHERE cancellation_id = ?", v.cancellationId.value) shouldBe 0
        }
    }

    Given("a conflicting VAN result (VC-CXL-PARTIAL shape — D-CANCEL-006)") {
        Then("the VAN approves one minor unit less: 202 UNKNOWN, CANCEL_RESULT_CONFLICT hold, incident 1 with one CancellationIncidentDetected (effect key …:1), no transaction, RESERVED") {
            val a = fixture.authorized()
            van.onCancel(a.mid.value) { r -> ProgrammableVan.Script(van.cancelApproved(r) { it.put("cancelledAmountMinor", r.get("cancelAmountMinor").longValue() - 1) }) }
            val v = accepted(cancel.cancel(a.command())).view
            val id = v.cancellationId.value
            v.status shouldBe CancellationViewStatus.UNKNOWN
            q("SELECT incident_count FROM payment_cancellation WHERE cancellation_id = ?", id).toString() shouldBe "1"
            n("SELECT COUNT(*) FROM incident_hold WHERE payment_id = ? AND reason = 'CANCEL_RESULT_CONFLICT'", a.paymentId.value) shouldBe 1
            events("CancellationIncidentDetected", id) shouldBe 1
            q("SELECT CONCAT(event_category, '|', effect_key) FROM outbox_event WHERE event_type = 'CancellationIncidentDetected' AND aggregate_id = ?", id) shouldBe
                "RISK_INCIDENT|CancellationIncidentDetected:$id:1"
            n("SELECT COUNT(*) FROM payment_transaction WHERE cancellation_id = ?", id) shouldBe 0
            reservation(id) shouldBe "RESERVED"
        }
    }

    // 검토 #3 n-4: 위험 힌트 CRITICAL은 초과(ReturnExcess.EXCEEDED)일 때만, payload에 초과 금액. 거절된 취소의 늦은 성공이 잔액 안이면 HIGH.
    Given("a late success of a declined cancel (FORCED)") {
        Then("within the balance: riskHint HIGH, excess 0; after the balance was fully returned by another cancel: CRITICAL with the excess amount") {
            val a = fixture.authorized()
            van.onCancel(a.mid.value) { van.declineCancel(it) }
            val declined = accepted(cancel.cancel(a.command(money = Money(4_000, a.money.currency)))).view.cancellationId
            fun lateApproval(amount: Long, ptx: String) = com.paycore.payment.payment.domain.CancelObservation(
                com.paycore.payment.payment.domain.CancelAttemptId(attempt(declined.value, "attempt_id") as String), com.paycore.payment.payment.domain.ObservationSource.NOTIFICATION,
                "vd_LATE_$ptx", com.paycore.payment.payment.domain.InstitutionOutcome.APPROVED,
                com.paycore.payment.payment.domain.CancelConfirmedTerms(
                    Money(amount, a.money.currency), q("SELECT provider_transaction_id FROM payment_transaction WHERE authorization_id = ? AND type = 'AUTHORIZATION'", a.authorizationId) as String,
                    ptx, "40000002", java.time.LocalDate.parse("2026-10-07"), java.time.LocalDate.parse("2026-10-07"),
                ),
                null, ConfirmFixture.T0,
            )
            results.applyObservation(declined, lateApproval(4_000, "SIMPTX-LATE-1"))
            payload("CancellationIncidentDetected", declined.value).get("riskHint").stringValue() shouldBe "HIGH"
            payload("CancellationIncidentDetected", declined.value).get("excessAmount").longValue() shouldBe 0
            // 같은 Payment의 두 번째 경우: 다른 취소로 전액 반환이 끝난 뒤 거절된 취소의 늦은 성공 → 초과.
            val b = fixture.authorized()
            van.onCancel(b.mid.value) { van.declineCancel(it) }
            val declinedBCommand = b.command(money = Money(4_000, b.money.currency))
            val declinedB = accepted(cancel.cancel(declinedBCommand)).view.cancellationId
            van.onCancel(b.mid.value) { van.approveCancel(it) }
            accepted(cancel.cancel(b.command())).view.status shouldBe CancellationViewStatus.COMPLETED
            val late = com.paycore.payment.payment.domain.CancelObservation(
                com.paycore.payment.payment.domain.CancelAttemptId(attempt(declinedB.value, "attempt_id") as String), com.paycore.payment.payment.domain.ObservationSource.NOTIFICATION,
                "vd_LATE_B", com.paycore.payment.payment.domain.InstitutionOutcome.APPROVED,
                com.paycore.payment.payment.domain.CancelConfirmedTerms(
                    Money(4_000, b.money.currency), q("SELECT provider_transaction_id FROM payment_transaction WHERE authorization_id = ? AND type = 'AUTHORIZATION'", b.authorizationId) as String,
                    "SIMPTX-LATE-B", "40000003", java.time.LocalDate.parse("2026-10-07"), java.time.LocalDate.parse("2026-10-07"),
                ),
                null, ConfirmFixture.T0,
            )
            results.applyObservation(declinedB, late)
            q("SELECT CONCAT(state, '|', excess) FROM return_reservation WHERE cancellation_id = ?", declinedB.value) shouldBe "FORCED|EXCEEDED"
            payload("CancellationIncidentDetected", declinedB.value).get("riskHint").stringValue() shouldBe "CRITICAL"
            payload("CancellationIncidentDetected", declinedB.value).get("excessAmount").longValue() shouldBe 4_000
            // CP-QUERY-001 F-1(추가 기대 행): 초과(FORCED)가 있어도 같은 키 재생 응답의 반환 가능액은 domain 식 max(0, …)대로 0이다(음수 아님).
            val replayed = accepted(cancel.cancel(declinedBCommand))
            replayed.replayed shouldBe true
            replayed.view.cancellableAmountMinor shouldBe 0
            // 검토 #1 n-7: 반환 상태는 완료 반환(10,000 = 승인액) 기준 FULLY_CANCELLED — FORCED는 반환 상태를 바꾸지 않는다.
            replayed.view.returnStatus shouldBe ReturnStatus.FULLY_CANCELLED
        }
    }

    Given("an operator correction cancel (D-CANCEL1-001 — same dispatch and result path)") {
        Then("it is sent and completes like a merchant cancel; PaymentCancelled names the OPERATOR initiator") {
            val a = fixture.authorized()
            val r = operator.cancel(a.operatorCommand()).shouldBeInstanceOf<OperatorCorrectionCancelResult.Accepted>()
            r.view.status shouldBe CancellationViewStatus.COMPLETED
            payload("PaymentCancelled", r.view.cancellationId.value).get("initiatorKind").stringValue() shouldBe "OPERATOR"
        }
    }

    // §9.3(D-AUTH1-003 이월 — 1-h): 신용 일시불·신용 할부·체크 일시불 × 전액·부분 뒤 마지막 잔액. 원 Plan·cardType은 원 Authorization에서 온다.
    Given("the check card and installment cancel matrix (§9.3)") {
        Then("every row completes; PaymentCancelled carries the original plan and card type; Payment stays AUTHORIZED") {
            listOf(
                Triple(CardType.CREDIT, PaymentPlan.LumpSum, 10_000L),
                Triple(CardType.CREDIT, PaymentPlan.Installment(3), 60_000L),
                Triple(CardType.CHECK, PaymentPlan.LumpSum, 10_000L),
            ).forEach { (cardType, plan, amount) ->
                withClue("$cardType $plan") {
                    val full = fixture.authorized(amount = amount, cardType = cardType, plan = plan)
                    val v = accepted(cancel.cancel(full.command())).view
                    v.status shouldBe CancellationViewStatus.COMPLETED
                    val p = payload("PaymentCancelled", v.cancellationId.value)
                    p.get("originalCardType").stringValue() shouldBe cardType.name
                    p.get("originalPlan").get("kind").stringValue() shouldBe (if (plan is PaymentPlan.Installment) "INSTALLMENT" else "LUMP_SUM")
                    val partial = fixture.authorized(amount = amount, cardType = cardType, plan = plan)
                    accepted(cancel.cancel(partial.command(money = Money(1_000, partial.money.currency)))).view.status shouldBe CancellationViewStatus.COMPLETED
                    accepted(cancel.cancel(partial.command(money = Money(amount - 1_000, partial.money.currency)))).view.returnStatus shouldBe ReturnStatus.FULLY_CANCELLED
                    q("SELECT status FROM payment WHERE payment_id = ?", partial.paymentId.value) shouldBe "AUTHORIZED"
                }
            }
        }
    }
})

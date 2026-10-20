package com.paycore.payment.payment.application

import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.adapter.persistence.FaultInjector
import com.paycore.payment.payment.adapter.persistence.FaultPoint
import com.paycore.payment.payment.adapter.web.PaymentApiTest
import com.paycore.payment.payment.application.provided.CancelPaymentResult
import com.paycore.payment.payment.application.provided.CancelPaymentUseCase
import com.paycore.payment.payment.application.provided.CancelRejection
import com.paycore.payment.payment.application.provided.CancellationViewStatus
import com.paycore.payment.payment.application.provided.ConfirmPaymentUseCase
import com.paycore.payment.payment.application.required.AuthenticationSessionRepository
import com.paycore.payment.payment.application.required.CheckoutSessionStore
import com.paycore.payment.payment.application.required.CheckoutTokenStore
import com.paycore.payment.payment.application.required.OrderPaymentControlRepository
import com.paycore.payment.payment.application.required.PaymentRepository
import com.paycore.payment.payment.domain.ApprovedTerms
import com.paycore.payment.payment.domain.AttemptId
import com.paycore.payment.payment.domain.AuthorizationId
import com.paycore.payment.payment.domain.CancelAttemptId
import com.paycore.payment.payment.domain.CancelConfirmedTerms
import com.paycore.payment.payment.domain.CancelObservation
import com.paycore.payment.payment.domain.CancellationId
import com.paycore.payment.payment.domain.CardType
import com.paycore.payment.payment.domain.CurrencyCode
import com.paycore.payment.payment.domain.InstitutionObservation
import com.paycore.payment.payment.domain.InstitutionOutcome
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.ObservationSource
import com.paycore.payment.payment.domain.PaymentPlan
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.transaction.PlatformTransactionManager
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 취소 결과 반영 경합(CANCEL-001 §9.1 K13a·b·c 실 DB, K6, K18 — rev 0.3 m-10). 같은 상충 결정이 응답·조회·통보 출처로 동시에 여러 번 오면 사건은 하나(D-CANCEL1-005·007 — 같은 Attempt·같은 결과·
 * 같은 조건·같은 결정 ID), 서로 다른 결정마다 사건 +1이고 사건 이벤트 수 = `incident_count`(D-CANCEL1-002, n-4 불변식), hold는 하나, 1062 없음, 상태 불변. 기대값은 명세 §9.1·§19에서 적는다.
 */
@PaymentApiTest
class CancelResultConcurrencySpec(
    private val cancel: CancelPaymentUseCase,
    private val results: CancellationResultService,
    private val authorizationResults: AuthorizationResultService,
    private val van: ProgrammableVan,
    private val admission: ScriptedAdmissionPort,
    private val faults: FaultInjector,
    private val confirm: ConfirmPaymentUseCase,
    payments: PaymentRepository,
    controls: OrderPaymentControlRepository,
    sessions: AuthenticationSessionRepository,
    tokens: CheckoutTokenStore,
    checkoutSessions: CheckoutSessionStore,
    transactionManager: PlatformTransactionManager,
    private val db: DbAccess,
) : BehaviorSpec({

    val confirmFixture = ConfirmFixture(payments, controls, sessions, tokens, checkoutSessions, transactionManager, db)
    val fixture = CancelFixture(confirmFixture, confirm, admission, controls, payments, transactionManager, van)
    val n = 8

    afterEach { faults.reset() }

    fun q(sql: String, vararg args: Any?) = fixture.scalar(sql, *args)
    fun attemptOf(id: CancellationId) = CancelAttemptId(q("SELECT attempt_id FROM cancel_attempt WHERE cancellation_id = ?", id.value) as String)
    fun incidents(id: CancellationId) = (q("SELECT incident_count FROM payment_cancellation WHERE cancellation_id = ?", id.value) as Number).toInt()
    fun incidentEvents(id: CancellationId) = fixture.count("SELECT COUNT(*) FROM outbox_event WHERE event_type = 'CancellationIncidentDetected' AND aggregate_id = ?", id.value)
    fun holds(a: CancelFixture.Authorized) = fixture.count("SELECT COUNT(*) FROM incident_hold WHERE payment_id = ? AND reason = 'CANCEL_RESULT_CONFLICT'", a.paymentId.value)

    /** 기관 취소 승인 결정 하나(같은 결정 ID·같은 조건 = 같은 결정 — CN-31). */
    fun approval(attempt: CancelAttemptId, source: ObservationSource, decisionId: String, amountMinor: Long, ptx: String, originalPtx: String) = CancelObservation(
        attemptId = attempt, source = source, sourceResultId = decisionId, outcome = InstitutionOutcome.APPROVED,
        confirmed = CancelConfirmedTerms(Money(amountMinor, CurrencyCode("KRW")), originalPtx, ptx, "40009999", LocalDate.parse("2026-10-07"), LocalDate.parse("2026-10-07")),
        evidenceHash = null, observedAt = Instant.parse("2026-10-07T01:00:00Z"),
    )

    fun originalPtx(a: CancelFixture.Authorized) = q("SELECT provider_transaction_id FROM payment_transaction WHERE authorization_id = ? AND type = 'AUTHORIZATION'", a.authorizationId) as String

    /** 같은 결정을 출처를 섞어 [n]번 동시에 반영한 뒤 다른 결정 하나를 반영한다. 첫 반영 TX가 control을 잡은 채 나머지가 실제로 기다릴 때까지 붙잡는다. */
    fun sameDecisionThenAnother(id: CancellationId, decision: (ObservationSource) -> CancelObservation, another: CancelObservation) {
        val held = AtomicBoolean(false)
        faults.after(FaultPoint.CONTROL_LOCK) { if (held.compareAndSet(false, true)) db.awaitLockWaiters(n - 1) }
        val sources = listOf(ObservationSource.RESPONSE, ObservationSource.INQUIRY, ObservationSource.NOTIFICATION)
        CreatePaymentFixture.concurrently(n) { i -> results.applyObservation(id, decision(sources[i % sources.size])) }.forEach { it.getOrThrow() }
        faults.reset()
        results.applyObservation(id, another)
    }

    Given("K13a — a cancellation in progress (UNKNOWN after a PENDING answer)") {
        Then("8 concurrent copies of one conflicting decision from mixed sources are one incident, a different decision is a second; one hold, events = incident_count, still UNKNOWN·RESERVED") {
            val a = fixture.authorized()
            van.onCancel(a.mid.value) { van.pendingCancel(it) }
            val id = (cancel.cancel(a.command()) as CancelPaymentResult.Accepted).view.cancellationId
            val attempt = attemptOf(id)
            val orig = originalPtx(a)
            sameDecisionThenAnother(id, { s -> approval(attempt, s, "vd_K13A_D1", 9_999, "SIMPTX-K13A-1", orig) }, approval(attempt, ObservationSource.INQUIRY, "vd_K13A_D2", 9_998, "SIMPTX-K13A-2", orig))
            incidents(id) shouldBe 2
            incidentEvents(id) shouldBe 2
            holds(a) shouldBe 1
            q("SELECT status FROM payment_cancellation WHERE cancellation_id = ?", id.value) shouldBe "UNKNOWN"
            q("SELECT state FROM return_reservation WHERE cancellation_id = ?", id.value) shouldBe "RESERVED"
        }
    }

    Given("K13b — a completed cancellation") {
        Then("a conflicting approval with another PTX 8 times concurrently and then another decision: incident 2, one hold, still one CANCEL transaction") {
            val a = fixture.authorized()
            val v = (cancel.cancel(a.command()) as CancelPaymentResult.Accepted).view
            v.status shouldBe CancellationViewStatus.COMPLETED
            val id = v.cancellationId
            val attempt = attemptOf(id)
            val orig = originalPtx(a)
            sameDecisionThenAnother(id, { s -> approval(attempt, s, "vd_K13B_D1", 10_000, "SIMPTX-K13B-1", orig) }, approval(attempt, ObservationSource.NOTIFICATION, "vd_K13B_D2", 10_000, "SIMPTX-K13B-2", orig))
            incidents(id) shouldBe 2
            incidentEvents(id) shouldBe 2
            holds(a) shouldBe 1
            fixture.count("SELECT COUNT(*) FROM payment_transaction WHERE cancellation_id = ?", id.value) shouldBe 1
            q("SELECT status FROM payment_cancellation WHERE cancellation_id = ?", id.value) shouldBe "COMPLETED"
        }
    }

    Given("K13c — a declined (FAILED) cancellation") {
        Then("a late approval 8 times concurrently is one incident with the reservation FORCED; another decision is a second; no transaction") {
            val a = fixture.authorized()
            van.onCancel(a.mid.value) { van.declineCancel(it) }
            val v = (cancel.cancel(a.command()) as CancelPaymentResult.Accepted).view
            v.status shouldBe CancellationViewStatus.FAILED
            val id = v.cancellationId
            val attempt = attemptOf(id)
            val orig = originalPtx(a)
            sameDecisionThenAnother(id, { s -> approval(attempt, s, "vd_K13C_D1", 10_000, "SIMPTX-K13C-1", orig) }, approval(attempt, ObservationSource.INQUIRY, "vd_K13C_D2", 10_000, "SIMPTX-K13C-2", orig))
            incidents(id) shouldBe 2
            incidentEvents(id) shouldBe 2
            holds(a) shouldBe 1
            q("SELECT state FROM return_reservation WHERE cancellation_id = ?", id.value) shouldBe "FORCED"
            fixture.count("SELECT COUNT(*) FROM payment_transaction WHERE cancellation_id = ?", id.value) shouldBe 0
            q("SELECT status FROM payment_cancellation WHERE cancellation_id = ?", id.value) shouldBe "FAILED"
        }
    }

    // K6: 취소 TX1 vs 같은 Payment 승인 상충 결과(승인 TX2가 CONFLICTING hold 개설). hold 먼저 → 가맹점 ORDER_ON_HOLD, 취소 먼저 → 시작되고 그 뒤 hold는 진행 중 취소의 결과 반영을 막지 않는다(CC-16).
    Given("K6 — a merchant cancel and a conflicting authorization result on the same payment at once") {
        Then("either the cancel is ORDER_ON_HOLD, or it starts and still completes; the authorization hold exists in both orders") {
            repeat(3) { round ->
                val a = fixture.authorized()
                val authAttempt = AttemptId(q("SELECT attempt_id FROM authorization_attempt WHERE authorization_id = ?", a.authorizationId) as String)
                val conflicting = InstitutionObservation(
                    attemptId = authAttempt, source = ObservationSource.NOTIFICATION, sourceResultId = "vd_K6_$round", outcome = InstitutionOutcome.APPROVED,
                    approved = ApprovedTerms(Money(9_000, CurrencyCode("KRW")), PaymentPlan.LumpSum, CardType.CREDIT, "39999999", "SIMPTX-K6-$round", LocalDate.parse("2026-10-07"), null),
                    evidenceHash = null, observedAt = Instant.parse("2026-10-07T01:00:00Z"),
                )
                val r = CreatePaymentFixture.concurrently(2) { i ->
                    if (i == 0) cancel.cancel(a.command()) else authorizationResults.applyObservation(AuthorizationId(a.authorizationId), conflicting)
                }.map { it.getOrThrow() }
                withClue("round $round") {
                    when (val c = r[0]) {
                        is CancelPaymentResult.Rejected -> c.rejection shouldBe CancelRejection.ORDER_ON_HOLD
                        is CancelPaymentResult.Accepted -> c.view.status shouldBe CancellationViewStatus.COMPLETED
                        else -> error("unexpected $c")
                    }
                    fixture.count("SELECT COUNT(*) FROM incident_hold WHERE payment_id = ? AND reason = 'CONFLICTING_INSTITUTION_RESULT'", a.paymentId.value) shouldBe 1
                }
            }
        }
    }

    // K18(rev 0.3 m-10): 승인 TX2(같은 결과 재수신 — control 잠금)와 취소 TX1이 같은 주문에서 동시에 — 교착 없음.
    Given("K18 — authorization result re-deliveries and merchant cancels on the same order at once") {
        Then("all finish without a deadlock or error; one cancel starts") {
            val a = fixture.authorized()
            val authAttempt = AttemptId(q("SELECT attempt_id FROM authorization_attempt WHERE authorization_id = ?", a.authorizationId) as String)
            val decision = q("SELECT source_result_id FROM institution_observation WHERE authorization_id = ? AND source = 'RESPONSE'", a.authorizationId) as String
            val terms = ApprovedTerms(
                a.money, PaymentPlan.LumpSum, CardType.CREDIT,
                q("SELECT approval_number FROM payment_transaction WHERE authorization_id = ? AND type = 'AUTHORIZATION'", a.authorizationId) as String,
                originalPtx(a),
                LocalDate.parse(q("SELECT DATE_FORMAT(institution_transaction_date, '%Y-%m-%d') FROM payment_transaction WHERE authorization_id = ? AND type = 'AUTHORIZATION'", a.authorizationId) as String),
                (q("SELECT DATE_FORMAT(institution_business_date, '%Y-%m-%d') FROM payment_transaction WHERE authorization_id = ? AND type = 'AUTHORIZATION'", a.authorizationId) as String?)
                    ?.let(LocalDate::parse),
            )
            val duplicate = InstitutionObservation(authAttempt, ObservationSource.INQUIRY, decision, InstitutionOutcome.APPROVED, terms, null, Instant.parse("2026-10-07T01:00:00Z"))
            val r = CreatePaymentFixture.concurrently(n) { i ->
                if (i % 2 == 0) cancel.cancel(a.command()) else authorizationResults.applyObservation(AuthorizationId(a.authorizationId), duplicate)
            }
            r.forEach { it.getOrThrow() }
            fixture.cancellationRows(a) shouldBe 1
            fixture.count("SELECT COUNT(*) FROM incident_hold WHERE payment_id = ?", a.paymentId.value) shouldBe 0
        }
    }

    // K17(rev 0.3 m-10, 검토 #3 m-2): 같은 주문에서 P1(기관 거절 → FAILED)의 늦은 승인 성공(SUCCESS_AFTER_TERMINATION hold — P1 대상)과 P2(같은 주문 다음 Payment, AUTHORIZED)의
    // 가맹점 취소가 순서대로·동시에 — 어느 순서든 P2 취소는 ORDER_ON_HOLD가 아니고(가맹점 취소는 그 Payment의 hold만 본다 — D-CANCEL-001), P1 hold는 그대로, 교착·오류 없음.
    Given("K17 — a late success of P1 and a merchant cancel of P2 on the same order") {
        Then("sequentially in both orders and concurrently: P2's cancel starts, P1's hold stays, no deadlock or error") {
            listOf("hold-first", "cancel-first", "concurrent", "concurrent", "concurrent").forEachIndexed { round, mode ->
                val p1 = confirmFixture.prepared()
                val mid = p1.order.merchantAccountId
                admission.respondFor(mid) { ScriptedAdmissionPort.admitted(it, routeRef = CancelFixture.ROUTE_C) }
                van.onAuth(mid.value) { van.decline(it) }
                (confirm.confirm(p1.command()) as com.paycore.payment.payment.application.provided.ConfirmPaymentResult.Accepted).view.status shouldBe
                    com.paycore.payment.payment.application.provided.AuthorizationViewStatus.FAILED
                van.onAuth(mid.value) { van.approve(it) }
                val p2 = confirmFixture.prepared(order = p1.order, previous = p1)
                val v2 = (confirm.confirm(p2.command()) as com.paycore.payment.payment.application.provided.ConfirmPaymentResult.Accepted).view
                v2.status shouldBe com.paycore.payment.payment.application.provided.AuthorizationViewStatus.AUTHORIZED
                val b = CancelFixture.Authorized(p2, v2.authorizationId.value)
                val p1Authorization = q("SELECT authorization_id FROM payment_authorization WHERE payment_id = ?", p1.paymentId.value) as String
                val p1Attempt = AttemptId(q("SELECT attempt_id FROM authorization_attempt WHERE authorization_id = ?", p1Authorization) as String)
                val late = InstitutionObservation(
                    attemptId = p1Attempt, source = ObservationSource.NOTIFICATION, sourceResultId = "vd_K17_$round", outcome = InstitutionOutcome.APPROVED,
                    approved = ApprovedTerms(p1.money, PaymentPlan.LumpSum, CardType.CREDIT, "38888888", "SIMPTX-K17-$round", LocalDate.parse("2026-10-07"), null),
                    evidenceHash = null, observedAt = Instant.parse("2026-10-07T01:00:00Z"),
                )
                val hold = { authorizationResults.applyObservation(AuthorizationId(p1Authorization), late) }
                val cancelP2 = { cancel.cancel(b.command()) }
                val outcome: CancelPaymentResult = when (mode) {
                    "hold-first" -> { hold(); cancelP2() }
                    "cancel-first" -> cancelP2().also { hold() }
                    else -> {
                        val held = AtomicBoolean(false)
                        faults.after(FaultPoint.CONTROL_LOCK) { if (held.compareAndSet(false, true)) db.awaitLockWaiters(1) }
                        val r = CreatePaymentFixture.concurrently(2) { i -> if (i == 0) hold() else cancelP2() }.map { it.getOrThrow() }
                        faults.reset()
                        r[1] as CancelPaymentResult
                    }
                }
                withClue("round $round $mode") {
                    outcome.shouldBeInstanceOf<CancelPaymentResult.Accepted>().view.status shouldBe CancellationViewStatus.COMPLETED
                    fixture.count("SELECT COUNT(*) FROM incident_hold WHERE payment_id = ? AND reason = 'SUCCESS_AFTER_TERMINATION'", p1.paymentId.value) shouldBe 1
                    fixture.count("SELECT COUNT(*) FROM incident_hold WHERE payment_id = ?", p2.paymentId.value) shouldBe 0
                    q("SELECT status FROM payment WHERE payment_id = ?", p1.paymentId.value) shouldBe "FAILED"
                }
            }
        }
    }
})

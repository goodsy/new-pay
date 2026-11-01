package com.paycore.payment.payment.application

import com.paycore.payment.payment.adapter.batch.CancellationRecoveryScheduler
import com.paycore.payment.payment.adapter.integration.institution.StubVan.Action
import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.adapter.persistence.FaultInjector
import com.paycore.payment.payment.adapter.persistence.FaultPoint
import com.paycore.payment.payment.adapter.web.CapturedLogs
import com.paycore.payment.payment.adapter.web.MutablePaymentClock
import com.paycore.payment.payment.adapter.web.PaymentApiTest
import com.paycore.payment.payment.application.provided.CancelPaymentResult
import com.paycore.payment.payment.application.provided.CancelPaymentUseCase
import com.paycore.payment.payment.application.provided.CancelRejection
import com.paycore.payment.payment.application.provided.CancellationViewStatus
import com.paycore.payment.payment.application.provided.ConfirmPaymentUseCase
import com.paycore.payment.payment.application.provided.RecoverCancellationsCommand
import com.paycore.payment.payment.application.provided.RecoverCancellationsUseCase
import com.paycore.payment.payment.application.required.AuthenticationSessionRepository
import com.paycore.payment.payment.application.required.CheckoutSessionStore
import com.paycore.payment.payment.application.required.CheckoutTokenStore
import com.paycore.payment.payment.application.required.OrderPaymentControlRepository
import com.paycore.payment.payment.application.required.PaymentRepository
import com.paycore.payment.payment.domain.CancelAttemptId
import com.paycore.payment.payment.domain.CancelConfirmedTerms
import com.paycore.payment.payment.domain.CancelObservation
import com.paycore.payment.payment.domain.CancellationId
import com.paycore.payment.payment.domain.CurrencyCode
import com.paycore.payment.payment.domain.InstitutionOutcome
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.ObservationSource
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.transaction.PlatformTransactionManager
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 취소 UNKNOWN 회복 Worker(CANCEL-001 §7 — 일정 선점 → PREPARED fencing·dispatch lease 만료 표시·원 **취소** 요청 ID 조회 → TX2와 같은 반영 → 예산·연령 소진 시 대사 이관, 상태 불변 —
 * D-CANCEL1-003 임시 출구 없음). 실 MySQL·운영 Adapter·실제 소켓의 시험 전용 stub VAN(sim-van-2 원장 의미). 시험 Demo 시간(시험 yml `paycore.cancellation.timing`): lease 30s·margin 5s·
 * prepared 1s·조회 간격 10s·예산 3·최대 연령 1h·느린 조회 10m. Route `SIMVANC` 취소 closableAfter 2m·보존 7d(무효과 확정 있음), `SIMVANCB` 무효과 확정 없음.
 * 기대값은 명세 §7.1·§7.2 표("never auto-fail")·§9.1 K7·K8·K9·K14·§9.2에서 직접 적는다. 다른 시험 일정도 회차에 잡힐 수 있으므로 회차 합계가 아니라 이 시험의 취소로 판정한다.
 */
@PaymentApiTest
class CancellationRecoverySpec(
    private val cancel: CancelPaymentUseCase,
    private val recovery: RecoverCancellationsUseCase,
    private val scheduler: CancellationRecoveryScheduler,
    private val results: CancellationResultService,
    private val van: ProgrammableVan,
    private val admission: ScriptedAdmissionPort,
    private val routes: WithdrawableRouteResolver,
    private val faults: FaultInjector,
    private val clock: MutablePaymentClock,
    private val meters: MeterRegistry,
    private val inquiryPort: ObservedCancelInquiryPort,
    confirm: ConfirmPaymentUseCase,
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
    val t0 = ConfirmFixture.T0

    afterEach {
        faults.reset()
        routes.reset()
        clock.reset()
    }

    fun q(sql: String, vararg args: Any?) = fixture.scalar(sql, *args)
    fun runAt(at: Instant) {
        clock.set(at)
        recovery.recover(RecoverCancellationsCommand(UUID.randomUUID().toString(), 500))
    }
    fun status(id: CancellationId) = q("SELECT status FROM payment_cancellation WHERE cancellation_id = ?", id.value)
    fun requestId(id: CancellationId) = q("SELECT request_id FROM cancel_attempt WHERE cancellation_id = ?", id.value) as String
    fun reservation(id: CancellationId) = q("SELECT state FROM return_reservation WHERE cancellation_id = ?", id.value)
    fun events(type: String, id: CancellationId) = fixture.count("SELECT COUNT(*) FROM outbox_event WHERE event_type = ? AND aggregate_id = ?", type, id.value)
    fun schedule(id: CancellationId, column: String) = q("SELECT $column FROM cancellation_recovery_schedule WHERE cancellation_id = ?", id.value)

    /** 응답이 유실된 취소(VAN이 [decision]을 원장에 기록한 뒤 응답 없이 끊김 — null이면 기록도 없음). */
    fun lost(a: CancelFixture.Authorized, decision: ((tools.jackson.databind.JsonNode) -> tools.jackson.databind.node.ObjectNode)? = { van.cancelApproved(it) }): CancellationId {
        van.onCancel(a.mid.value) { r -> ProgrammableVan.Script(decision?.invoke(r), Action.Close) }
        val v = (cancel.cancel(a.command()) as CancelPaymentResult.Accepted).view
        v.status shouldBe CancellationViewStatus.UNKNOWN
        return v.cancellationId
    }

    Given("a cancel whose response was lost after the VAN approved (§9.2)") {
        Then("one recovery inquiry with the original cancel request ID finds the decision: COMPLETED, one transaction, PaymentCancelled once, no resend, schedule closed") {
            val a = fixture.authorized()
            val id = lost(a)
            runAt(t0.plusSeconds(11))
            status(id) shouldBe "COMPLETED"
            fixture.count("SELECT COUNT(*) FROM payment_transaction WHERE cancellation_id = ?", id.value) shouldBe 1
            events("PaymentCancelled", id) shouldBe 1
            van.cancelRequestsFor(a.mid.value).size shouldBe 1
            val inquiries = van.cancelInquiriesFor(requestId(id))
            inquiries.size shouldBe 1
            inquiries.single().get("closeIfAbsent").booleanValue() shouldBe false
            (inquiries.single().get("inquiryId").stringValue().startsWith("cq_")) shouldBe true
            schedule(id, "IF(closed_at IS NULL, 'open', 'closed')") shouldBe "closed"
            fixture.count("SELECT COUNT(*) FROM cancel_observation WHERE cancellation_id = ? AND source = 'INQUIRY'", id.value) shouldBe 1
            // 검토 #3 n-4: 회복의 기관 조회도 DB TX 밖(K-13).
            inquiryPort.transactionActiveAtCall.last() shouldBe false
        }
    }

    Given("a TX2 failure leaves the attempt DISPATCHING (§9.2)") {
        Then("after the lease the recovery marks it UNKNOWN(DISPATCH_LEASE_EXPIRED), then the inquiry completes it with one CANCEL_REQUEST in total") {
            val a = fixture.authorized()
            val updates = AtomicInteger()
            faults.after(FaultPoint.CANCELLATION_UPDATE) { if (updates.incrementAndGet() == 2) throw IllegalStateException("injected TX2 failure") }
            val id = (cancel.cancel(a.command()) as CancelPaymentResult.Accepted).view.cancellationId
            faults.reset()
            runAt(t0.plusSeconds(29))
            q("SELECT state FROM cancel_attempt WHERE cancellation_id = ?", id.value) shouldBe "DISPATCHING"
            // 변이 Mr6(생존 → 행 추가): lease 전의 표시 요청은 잠근 일정의 lease로 판정해 효과가 없다.
            val attempt = CancelAttemptId(q("SELECT attempt_id FROM cancel_attempt WHERE cancellation_id = ?", id.value) as String)
            results.markLeaseExpired(id, attempt, EventCausation("lease-early", "lease-early")) shouldBe com.paycore.payment.payment.domain.CancelResolutionOutcome.NoChange
            q("SELECT state FROM cancel_attempt WHERE cancellation_id = ?", id.value) shouldBe "DISPATCHING"
            runAt(t0.plusSeconds(31))
            q("SELECT CONCAT(state, '|', unknown_cause) FROM cancel_attempt WHERE cancellation_id = ?", id.value) shouldBe "OUTCOME_UNKNOWN|DISPATCH_LEASE_EXPIRED"
            runAt(t0.plusSeconds(42))
            status(id) shouldBe "COMPLETED"
            van.cancelRequestsFor(a.mid.value).size shouldBe 1
            events("PaymentCancelled", id) shouldBe 1
        }
    }

    Given("an attempt left PREPARED past its deadline") {
        Then("the recovery fences it: FAILED(PRE_TRANSMISSION), RELEASED, PaymentCancelFailed once, nothing sent") {
            val a = fixture.authorized()
            faults.after(FaultPoint.IDEMPOTENCY_COMPLETE) { clock.set(t0.plusSeconds(5)) }
            val id = (cancel.cancel(a.command()) as CancelPaymentResult.Accepted).view.cancellationId
            faults.reset()
            runAt(t0.plusSeconds(5))
            status(id) shouldBe "FAILED"
            q("SELECT failure_evidence FROM payment_cancellation WHERE cancellation_id = ?", id.value) shouldBe "PRE_TRANSMISSION_FAILURE_CONFIRMED"
            q("SELECT CONCAT(state, '|', abandon_cause) FROM cancel_attempt WHERE cancellation_id = ?", id.value) shouldBe "ABANDONED_BEFORE_DISPATCH|FENCED_BY_RECOVERY"
            reservation(id) shouldBe "RELEASED"
            events("PaymentCancelFailed", id) shouldBe 1
            van.cancelRequestsFor(a.mid.value).size shouldBe 0
        }
    }

    Given("the VAN never recorded the cancel (profile with cancel no-effect confirmation)") {
        Then("NOT_FOUND keeps it UNKNOWN before closableAfter; after it the pinned profile version matches, closeIfAbsent closes it: FAILED(NO_EFFECT), RELEASED") {
            val a = fixture.authorized()
            val id = lost(a, decision = null)
            runAt(t0.plusSeconds(11))
            status(id) shouldBe "UNKNOWN"
            reservation(id) shouldBe "RESERVED"
            runAt(t0.plus(Duration.ofMinutes(3)))
            status(id) shouldBe "FAILED"
            q("SELECT failure_evidence FROM payment_cancellation WHERE cancellation_id = ?", id.value) shouldBe "INSTITUTION_CONFIRMED_NO_EFFECT"
            reservation(id) shouldBe "RELEASED"
            van.cancelInquiriesFor(requestId(id)).map { it.get("closeIfAbsent").booleanValue() } shouldBe listOf(false, true)
            van.cancelClosureFor(requestId(id)) shouldBe q("SELECT source_result_id FROM cancel_observation WHERE cancellation_id = ? AND outcome = 'NO_EFFECT_CONFIRMED'", id.value)
        }
        Then("D-CANCEL1-004: a route profile version changed since dispatch withholds closeIfAbsent — still UNKNOWN") {
            val a = fixture.authorized()
            val id = lost(a, decision = null)
            routes.replace(CancelFixture.ROUTE_C) { it.copy(profileVersion = "sim-van-c-demo-2") }
            CapturedLogs().use { logs ->
                runAt(t0.plus(Duration.ofMinutes(3)))
                logs.lines().any { it.contains("closure=WITHHELD") && it.contains("ROUTE_PROFILE_VERSION_MISMATCH") } shouldBe true
                logs.lines().none { it.contains(requestId(id)) || it.contains("SIMMERCH0001") } shouldBe true
            }
            status(id) shouldBe "UNKNOWN"
            van.cancelInquiriesFor(requestId(id)).single().get("closeIfAbsent").booleanValue() shouldBe false
        }
    }

    Given("no cancel no-effect capability (route SIMVANCB) and the VAN never recorded the cancel") {
        Then("NOT_FOUND until the budget, then CancellationOutcomeUnresolved once; UNKNOWN·RESERVED stay, a new cancel is blocked; slow inquiries continue; after retention the schedule closes, still UNKNOWN (D-CANCEL1-003)") {
            val a = fixture.authorized(routeRef = CancelFixture.ROUTE_CB)
            val id = lost(a, decision = null)
            listOf(11L, 22L, 33L, 44L).forEach { runAt(t0.plusSeconds(it)) }
            events("CancellationOutcomeUnresolved", id) shouldBe 1
            q("SELECT CONCAT(event_category, '|', effect_key) FROM outbox_event WHERE event_type = 'CancellationOutcomeUnresolved' AND aggregate_id = ?", id.value) shouldBe
                "OPERATIONAL_RISK|CancellationOutcomeUnresolved:" + q("SELECT attempt_id FROM cancel_attempt WHERE cancellation_id = ?", id.value)
            (schedule(id, "escalation_config_version") as String) shouldBe "cancel-recovery-demo-1"
            status(id) shouldBe "UNKNOWN"
            reservation(id) shouldBe "RESERVED"
            cancel.cancel(a.command(money = Money(1_000, a.money.currency))).shouldBeInstanceOf<CancelPaymentResult.Rejected>().rejection shouldBe CancelRejection.CANCEL_IN_PROGRESS
            val before = van.cancelInquiriesFor(requestId(id)).size
            runAt(t0.plus(Duration.ofMinutes(20)))
            van.cancelInquiriesFor(requestId(id)).size shouldBe before + 1
            van.cancelInquiriesFor(requestId(id)).none { it.get("closeIfAbsent").booleanValue() } shouldBe true
            runAt(t0.plus(Duration.ofDays(8)))
            schedule(id, "IF(closed_at IS NULL, 'open', 'closed')") shouldBe "closed"
            status(id) shouldBe "UNKNOWN"
            events("CancellationOutcomeUnresolved", id) shouldBe 1
        }
    }

    // 변이 Mr8(생존 → 행 추가): 회복이 UNKNOWN을 읽은 뒤 이관 TX 전에 다른 경로가 결론을 내면 이관 이벤트를 쓰지 않는다(잠금 재판정 — 자동 출구가 아니라 경합 방어).
    Given("a cancel concluded between the recovery read and the escalation") {
        Then("no CancellationOutcomeUnresolved is written for a cancellation that is no longer UNKNOWN") {
            val a = fixture.authorized(routeRef = CancelFixture.ROUTE_CB)
            val id = lost(a, decision = null)
            listOf(11L, 22L, 33L).forEach { runAt(t0.plusSeconds(it)) }
            val attempt = CancelAttemptId(q("SELECT attempt_id FROM cancel_attempt WHERE cancellation_id = ?", id.value) as String)
            val originalPtx = q("SELECT provider_transaction_id FROM payment_transaction WHERE authorization_id = ? AND type = 'AUTHORIZATION'", a.authorizationId) as String
            val late = CancelObservation(
                attempt, ObservationSource.NOTIFICATION, "vd_RACE_${System.nanoTime()}", InstitutionOutcome.APPROVED,
                CancelConfirmedTerms(a.money, originalPtx, "SIMPTX-RACE-1", "40000000", LocalDate.parse("2026-10-07"), LocalDate.parse("2026-10-07")), null, t0,
            )
            val fired = AtomicBoolean(false)
            faults.after(FaultPoint.CANCEL_VIEW_FIND) { row ->
                val r = row as com.paycore.payment.payment.application.required.CancellationViewRow?
                if (r?.cancellationId == id && fired.compareAndSet(false, true)) results.applyObservation(id, late)
            }
            runAt(t0.plusSeconds(44))
            fired.get() shouldBe true
            status(id) shouldBe "COMPLETED"
            events("CancellationOutcomeUnresolved", id) shouldBe 0
        }
    }

    // 검토 #3 m-1: 보내지 않은(PREPARED) Attempt에 확정 결과가 와 사건 처리된 UNKNOWN 취소 — fence는 NoChange라 조회·이관 경로로 가야 한다(고정 버전 NULL → 종결 보류, D-CANCEL1-004).
    // 끝은 이관(D-CANCEL1-003 — 임시 출구 없음).
    Given("an UNKNOWN cancellation whose attempt was never sent (an incident on a PREPARED attempt)") {
        Then("the recovery inquires without closure and escalates by budget; status UNKNOWN, attempt PREPARED") {
            val a = fixture.authorized()
            faults.after(FaultPoint.IDEMPOTENCY_COMPLETE) { clock.set(t0.plusSeconds(5)) }
            val id = (cancel.cancel(a.command()) as CancelPaymentResult.Accepted).view.cancellationId
            faults.reset()
            val attempt = CancelAttemptId(q("SELECT attempt_id FROM cancel_attempt WHERE cancellation_id = ?", id.value) as String)
            val originalPtx = q("SELECT provider_transaction_id FROM payment_transaction WHERE authorization_id = ? AND type = 'AUTHORIZATION'", a.authorizationId) as String
            results.applyObservation(
                id,
                CancelObservation(
                    attempt, ObservationSource.NOTIFICATION, "vd_UNSENT_${System.nanoTime()}", InstitutionOutcome.APPROVED,
                    CancelConfirmedTerms(a.money, originalPtx, "SIMPTX-UNSENT-1", "40000001", LocalDate.parse("2026-10-07"), LocalDate.parse("2026-10-07")), null, t0,
                ),
            )
            status(id) shouldBe "UNKNOWN"
            q("SELECT state FROM cancel_attempt WHERE cancellation_id = ?", id.value) shouldBe "PREPARED"
            listOf(16L, 27L, 38L, 49L).forEach { runAt(t0.plusSeconds(it)) }
            events("CancellationOutcomeUnresolved", id) shouldBe 1
            status(id) shouldBe "UNKNOWN"
            val inquiries = van.cancelInquiriesFor(requestId(id))
            inquiries.size shouldBe 3
            inquiries.none { it.get("closeIfAbsent").booleanValue() } shouldBe true
        }
    }

    // 검토 #3 n-5(CN-08 변형): TXd commit이 실제로는 성공했지만 불명확으로 보고됨 → 보내지 않음 → lease 만료 → UNKNOWN → 조회 NOT_FOUND → (A) 종결 / (B) 이관.
    Given("a TXd commit that succeeded but was reported unknown (CN-08 variant)") {
        Then("profile A: never sent → lease expiry → UNKNOWN → NOT_FOUND → closure FAILED(NO_EFFECT); profile B: escalation") {
            listOf(CancelFixture.ROUTE_C, CancelFixture.ROUTE_CB).forEach { route ->
                withClue(route) {
                    clock.reset() // 각 프로필은 t0에서 시작한다(앞 프로필이 시계를 t0+3m로 옮겼다).
                    val a = fixture.authorized(routeRef = route)
                    val updates = AtomicInteger()
                    faults.after(FaultPoint.CANCELLATION_UPDATE) { if (updates.incrementAndGet() == 1) CommitFaults.arm() }
                    val id = (cancel.cancel(a.command()) as CancelPaymentResult.Accepted).view.cancellationId
                    faults.reset()
                    CommitFaults.reset()
                    q("SELECT state FROM cancel_attempt WHERE cancellation_id = ?", id.value) shouldBe "DISPATCHING"
                    van.cancelRequestsFor(a.mid.value).size shouldBe 0
                    runAt(t0.plusSeconds(31))
                    q("SELECT CONCAT(state, '|', unknown_cause) FROM cancel_attempt WHERE cancellation_id = ?", id.value) shouldBe "OUTCOME_UNKNOWN|DISPATCH_LEASE_EXPIRED"
                    if (route == CancelFixture.ROUTE_C) {
                        runAt(t0.plus(Duration.ofMinutes(3)))
                        status(id) shouldBe "FAILED"
                        q("SELECT failure_evidence FROM payment_cancellation WHERE cancellation_id = ?", id.value) shouldBe "INSTITUTION_CONFIRMED_NO_EFFECT"
                        reservation(id) shouldBe "RELEASED"
                    } else {
                        listOf(42L, 53L, 64L, 75L).forEach { runAt(t0.plusSeconds(it)) }
                        events("CancellationOutcomeUnresolved", id) shouldBe 1
                        status(id) shouldBe "UNKNOWN"
                        reservation(id) shouldBe "RESERVED"
                    }
                }
            }
        }
    }

    Given("n-7: the route disappears from the registry while the cancel is UNKNOWN") {
        Then("no closure is requested (closure WITHHELD — ROUTE_NOT_REGISTERED), the cancel stays UNKNOWN and escalates by budget") {
            val a = fixture.authorized()
            val id = lost(a, decision = null)
            // 시험 대역은 application의 Route 조회만 철회한다(Adapter 연결 등록부는 그대로 — 운영에서 Route가 빠지면 Adapter도 UNKNOWN_ROUTE). 판정 기준은 종결 요청 보류다.
            routes.withdraw(CancelFixture.ROUTE_C)
            CapturedLogs().use { logs ->
                listOf(11L, 22L, 33L, 44L).forEach { runAt(t0.plus(Duration.ofMinutes(3)).plusSeconds(it)) }
                logs.lines().any { it.contains("closure=WITHHELD") && it.contains("ROUTE_NOT_REGISTERED") } shouldBe true
            }
            status(id) shouldBe "UNKNOWN"
            events("CancellationOutcomeUnresolved", id) shouldBe 1
            van.cancelInquiriesFor(requestId(id)).none { it.get("closeIfAbsent").booleanValue() } shouldBe true
            // 검토 #3 n-6: Route가 없어 보존기간을 알 수 없으면 최대 연령에서 느린 조회를 멈춘다(일정 닫힘·로그) — 상태는 UNKNOWN 그대로(D-CANCEL1-003).
            CapturedLogs().use { logs ->
                runAt(t0.plus(Duration.ofHours(2)))
                logs.lines().any { it.contains("action=STOP_INQUIRY") && it.contains("ROUTE_NOT_REGISTERED") } shouldBe true
            }
            schedule(id, "IF(closed_at IS NULL, 'open', 'closed')") shouldBe "closed"
            status(id) shouldBe "UNKNOWN"
        }
    }

    Given("an in-flight cancellation with an incident (CC-16)") {
        Then("the recovery inquiry finds the same decision again — no change, no second incident; it escalates by budget") {
            val a = fixture.authorized()
            van.onCancel(a.mid.value) { r -> ProgrammableVan.Script(van.cancelApproved(r) { it.put("cancelledAmountMinor", r.get("cancelAmountMinor").longValue() - 1) }) }
            val id = (cancel.cancel(a.command()) as CancelPaymentResult.Accepted).view.cancellationId
            status(id) shouldBe "UNKNOWN"
            listOf(11L, 22L, 33L, 44L).forEach { runAt(t0.plusSeconds(it)) }
            (q("SELECT incident_count FROM payment_cancellation WHERE cancellation_id = ?", id.value) as Number).toInt() shouldBe 1
            events("CancellationIncidentDetected", id) shouldBe 1
            events("CancellationOutcomeUnresolved", id) shouldBe 1
            status(id) shouldBe "UNKNOWN"
        }
    }

    Given("K14 — two recovery workers at once") {
        Then("every cancel is inquired once and completed once") {
            val cancels = (1..4).map { lost(fixture.authorized()) }
            clock.set(t0.plusSeconds(11))
            CreatePaymentFixture.concurrently(2) { recovery.recover(RecoverCancellationsCommand(UUID.randomUUID().toString(), 500)) }.forEach { it.getOrThrow() }
            cancels.forEach { id ->
                withClue(id.value) {
                    status(id) shouldBe "COMPLETED"
                    van.cancelInquiriesFor(requestId(id)).size shouldBe 1
                    events("PaymentCancelled", id) shouldBe 1
                }
            }
        }
    }

    // K9: TX2 반영과 회복 조회 반영이 같은 기관 결정을 동시에 — 완료 1·거래 1·PaymentCancelled 1(D-CANCEL1-007 같은 결정).
    Given("K9 — the response and the inquiry report the same approval at once") {
        Then("one completion, one transaction, one PaymentCancelled") {
            val a = fixture.authorized()
            van.onCancel(a.mid.value) { r -> ProgrammableVan.Script(van.cancelApproved(r), Action.Close) }
            val id = (cancel.cancel(a.command()) as CancelPaymentResult.Accepted).view.cancellationId
            val decision = checkNotNull(van.cancelDecided(requestId(id)))
            val attempt = CancelAttemptId(q("SELECT attempt_id FROM cancel_attempt WHERE cancellation_id = ?", id.value) as String)
            fun obs(source: ObservationSource) = CancelObservation(
                attempt, source, decision.get("decisionId").stringValue(), InstitutionOutcome.APPROVED,
                CancelConfirmedTerms(
                    Money(decision.get("cancelledAmountMinor").longValue(), CurrencyCode("KRW")), decision.get("originalProviderTransactionId").stringValue(),
                    decision.get("providerTransactionId").stringValue(), decision.get("cancelApprovalNumber").stringValue(),
                    LocalDate.parse(decision.get("institutionTransactionDate").stringValue()), LocalDate.parse(decision.get("institutionBusinessDate").stringValue()),
                ),
                null, t0,
            )
            CreatePaymentFixture.concurrently(4) { i -> results.applyObservation(id, obs(if (i % 2 == 0) ObservationSource.RESPONSE else ObservationSource.INQUIRY)) }
                .forEach { it.getOrThrow() }
            status(id) shouldBe "COMPLETED"
            fixture.count("SELECT COUNT(*) FROM payment_transaction WHERE cancellation_id = ?", id.value) shouldBe 1
            events("PaymentCancelled", id) shouldBe 1
            events("CancellationIncidentDetected", id) shouldBe 0
        }
    }

    // K7: TXd와 회복 fencing이 같은 PREPARED Attempt를 — 먼저 잠근 쪽만 이긴다(payment_cancellation X + version).
    Given("K7 — the dispatch gate and the recovery fencing race for one PREPARED attempt") {
        Then("the gate holding the lock wins: DISPATCHING then sent once; the waiting fence sees DISPATCHING and does nothing") {
            val a = fixture.authorized()
            val started = AtomicBoolean(false)
            val fence = AtomicBoolean(false)
            faults.after(FaultPoint.CANCELLATION_LOCK) {
                // TXd의 첫 취소 잠금(TX1은 insertNew만): 회복 회차를 다른 스레드에서 시작하고 그 회차가 control 잠금을 기다릴 때까지 붙잡는다.
                if (started.compareAndSet(false, true)) {
                    clock.set(t0.plusSeconds(5))
                    Thread.ofVirtual().start { recovery.recover(RecoverCancellationsCommand(UUID.randomUUID().toString(), 500)); fence.set(true) }
                    db.awaitLockWaiters(1)
                }
            }
            val v = (cancel.cancel(a.command()) as CancelPaymentResult.Accepted).view
            faults.reset()
            val deadline = System.nanoTime() + 20_000_000_000
            while (!fence.get() && System.nanoTime() < deadline) Thread.sleep(20)
            fence.get() shouldBe true
            v.status shouldBe CancellationViewStatus.COMPLETED
            van.cancelRequestsFor(a.mid.value).size shouldBe 1
        }
        Then("the fence after the deadline wins when it runs first: FAILED(PRE_TRANSMISSION) and the gate does not dispatch") {
            val a = fixture.authorized()
            faults.after(FaultPoint.IDEMPOTENCY_COMPLETE) { clock.set(t0.plusSeconds(5)) }
            val v = (cancel.cancel(a.command()) as CancelPaymentResult.Accepted).view
            faults.reset()
            runAt(t0.plusSeconds(5))
            status(v.cancellationId) shouldBe "FAILED"
            van.cancelRequestsFor(a.mid.value).size shouldBe 0
        }
    }

    // K8: TX2 반영 vs 회복 lease 만료 표시 — 어느 순서든 예외 없음(F-11), 최종 상태 하나.
    Given("K8 — a late TX2 and the lease-expiry mark at once") {
        Then("no exception in either order and the cancel ends COMPLETED") {
            repeat(2) {
                val a = fixture.authorized()
                val updates = AtomicInteger()
                faults.after(FaultPoint.CANCELLATION_UPDATE) { if (updates.incrementAndGet() == 2) throw IllegalStateException("injected TX2 failure") }
                val id = (cancel.cancel(a.command()) as CancelPaymentResult.Accepted).view.cancellationId
                faults.reset()
                val decision = checkNotNull(van.cancelDecided(requestId(id)))
                val attempt = CancelAttemptId(q("SELECT attempt_id FROM cancel_attempt WHERE cancellation_id = ?", id.value) as String)
                val obs = CancelObservation(
                    attempt, ObservationSource.RESPONSE, decision.get("decisionId").stringValue(), InstitutionOutcome.APPROVED,
                    CancelConfirmedTerms(
                        Money(decision.get("cancelledAmountMinor").longValue(), CurrencyCode("KRW")), decision.get("originalProviderTransactionId").stringValue(),
                        decision.get("providerTransactionId").stringValue(), decision.get("cancelApprovalNumber").stringValue(),
                        LocalDate.parse(decision.get("institutionTransactionDate").stringValue()), LocalDate.parse(decision.get("institutionBusinessDate").stringValue()),
                    ),
                    null, t0,
                )
                clock.set(t0.plusSeconds(31))
                CreatePaymentFixture.concurrently(2) { i -> if (i == 0) results.applyObservation(id, obs) else results.markLeaseExpired(id, attempt, EventCausation("k8", "k8")) }
                    .forEach { it.getOrThrow() }
                status(id) shouldBe "COMPLETED"
                events("PaymentCancelled", id) shouldBe 1
            }
        }
    }

    Given("observability (closed label values, no identifiers)") {
        Then("the scheduler counts concluded and escalated runs; the backlog gauges see UNKNOWN cancels and their age") {
            val a = fixture.authorized()
            val id = lost(a)
            clock.set(t0.plusSeconds(5))
            (meters.get("paycore.cancellation.unknown").gauge().value() >= 1.0) shouldBe true
            (meters.get("paycore.cancellation.unknown.oldest_age_seconds").gauge().value() >= 0.0) shouldBe true
            (meters.get("paycore.cancellation.escalated").gauge().value() >= 0.0) shouldBe true
            val before = meters.counter("paycore.payment.cancellation_recovery", "result", "concluded").count()
            clock.set(t0.plusSeconds(11))
            scheduler.runOnce()
            status(id) shouldBe "COMPLETED"
            (meters.counter("paycore.payment.cancellation_recovery", "result", "concluded").count() >= before + 1) shouldBe true
        }
    }
})

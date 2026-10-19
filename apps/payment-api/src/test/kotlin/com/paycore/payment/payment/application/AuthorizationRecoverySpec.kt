package com.paycore.payment.payment.application

import com.paycore.payment.payment.adapter.integration.institution.StubVan.Action
import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.adapter.persistence.FaultInjector
import com.paycore.payment.payment.adapter.persistence.FaultPoint
import com.paycore.payment.payment.adapter.web.ApiTestTimes.T0
import com.paycore.payment.payment.adapter.web.CapturedLogs
import com.paycore.payment.payment.adapter.web.MutablePaymentClock
import com.paycore.payment.payment.adapter.web.PaymentApiTest
import com.paycore.payment.payment.application.provided.AuthorizationViewStatus
import com.paycore.payment.payment.application.provided.ConfirmPaymentResult
import com.paycore.payment.payment.application.provided.ConfirmPaymentUseCase
import com.paycore.payment.payment.application.provided.FailureCategory
import com.paycore.payment.payment.application.provided.RecoverAuthorizationsCommand
import com.paycore.payment.payment.application.provided.RecoverAuthorizationsUseCase
import com.paycore.payment.payment.application.required.AuthenticationSessionRepository
import com.paycore.payment.payment.application.required.CheckoutSessionStore
import com.paycore.payment.payment.application.required.CheckoutTokenStore
import com.paycore.payment.payment.application.required.OrderPaymentControlRepository
import com.paycore.payment.payment.application.required.PaymentRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.transaction.PlatformTransactionManager
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * UNKNOWN 회복 Worker(AUTH-001 §6 — 회복 일정 선점 → PREPARED fencing·dispatch lease 만료 표시·원 Attempt 조회 → TX2와 같은 반영 → 예산·연령 소진 시 대사 이관, 상태 불변).
 * 실 MySQL·운영 Adapter·실제 소켓의 시험 전용 stub VAN([ProgrammableVan] — 원장 의미 재현)으로 시험한다. 시험 Demo 시간(시험 yml): lease 30s·margin 5s·prepared 1s·조회 간격 10s·
 * 예산 3·최대 연령 1h·느린 조회 10m, Route A(`SIM-VAN-01`) closableAfter 2m·보존 7d, Route B(`SIM-VAN-B`) 무효과 확정 없음.
 * 기대값은 명세 §6.1~§6.3 표(자동 종료 근거 — "never auto-fail")와 §8 장애 주입 표에서 직접 적는다. 같은 DB의 다른 시험 일정도 회차에 잡힐 수 있으므로 회차 합계가 아니라 이 시험의 Payment로 판정한다.
 */
@PaymentApiTest
class AuthorizationRecoverySpec(
    private val confirm: ConfirmPaymentUseCase,
    private val recovery: RecoverAuthorizationsUseCase,
    private val start: ConfirmStartService,
    private val dispatcher: AuthorizationDispatchService,
    private val van: ProgrammableVan,
    private val admission: ScriptedAdmissionPort,
    private val faults: FaultInjector,
    private val clock: MutablePaymentClock,
    payments: PaymentRepository,
    controls: OrderPaymentControlRepository,
    sessions: AuthenticationSessionRepository,
    tokens: CheckoutTokenStore,
    checkoutSessions: CheckoutSessionStore,
    transactionManager: PlatformTransactionManager,
    private val db: DbAccess,
) : BehaviorSpec({

    val fixture = ConfirmFixture(payments, controls, sessions, tokens, checkoutSessions, transactionManager, db)

    afterEach {
        faults.reset()
        clock.reset()
    }

    fun recoverAt(at: Instant) {
        clock.set(at)
        recovery.recover(RecoverAuthorizationsCommand(UUID.randomUUID().toString(), RUN_BATCH))
    }

    fun authorizationId(p: ConfirmFixture.Prepared) = fixture.scalar("SELECT authorization_id FROM payment_authorization WHERE payment_id = ?", p.paymentId.value) as String
    fun requestId(p: ConfirmFixture.Prepared) = fixture.scalar("SELECT request_id FROM authorization_attempt WHERE authorization_id = ?", authorizationId(p)) as String
    fun attempt(p: ConfirmFixture.Prepared, column: String) = fixture.scalar("SELECT $column FROM authorization_attempt WHERE authorization_id = ?", authorizationId(p))
    fun schedule(p: ConfirmFixture.Prepared, column: String) =
        fixture.scalar("SELECT $column FROM authorization_recovery_schedule WHERE authorization_id = ?", authorizationId(p))
    fun events(type: String, aggregate: String) = fixture.count("SELECT COUNT(*) FROM outbox_event WHERE event_type = ? AND aggregate_id = ?", type, aggregate)
    fun transactions(p: ConfirmFixture.Prepared) = fixture.count("SELECT COUNT(*) FROM payment_transaction WHERE authorization_id = ?", authorizationId(p))
    fun view(p: ConfirmFixture.Prepared, key: String) = confirm.confirm(p.command(key = key)).shouldBeInstanceOf<ConfirmPaymentResult.Accepted>().view
    fun mid(p: ConfirmFixture.Prepared) = p.order.merchantAccountId.value
    fun unresolved(p: ConfirmFixture.Prepared, field: String) =
        fixture.scalar("SELECT payload->>'$.$field' FROM outbox_event WHERE event_type = 'AuthorizationOutcomeUnresolved' AND aggregate_id = ?", authorizationId(p))
    fun nextCheckAt(p: ConfirmFixture.Prepared): Instant = (schedule(p, "next_check_at") as java.time.LocalDateTime).toInstant(java.time.ZoneOffset.UTC)

    /** 선점이 Aggregate 판정보다 앞서는 창(회복이 PREPARED 일정을 잡은 직후 TXd가 커밋하는 경합 등)을 재현한다: 일정의 다음 점검 시각만 앞당긴다(업무 행은 그대로). */
    fun makeDueEarlier(p: ConfirmFixture.Prepared, seconds: Int) = db.app().use { c ->
        c.prepareStatement("UPDATE authorization_recovery_schedule SET next_check_at = DATE_SUB(next_check_at, INTERVAL ? SECOND) WHERE authorization_id = ?").use { ps ->
            ps.setInt(1, seconds)
            ps.setString(2, authorizationId(p))
            ps.executeUpdate() shouldBe 1
        }
    }

    /** 응답 유실(원장에는 결정이 남음) 또는 기록 전 끊김(원장 없음)으로 UNKNOWN이 된 confirm. */
    fun unknownAfter(p: ConfirmFixture.Prepared, recorded: Boolean, key: String = ConfirmFixture.newKey()): String {
        van.onAuth(mid(p)) { r -> ProgrammableVan.Script(if (recorded) van.approved(r) else null, Action.Close) }
        view(p, key).status shouldBe AuthorizationViewStatus.UNKNOWN
        attempt(p, "state") shouldBe "OUTCOME_UNKNOWN"
        return key
    }

    Given("a response lost after the VAN recorded the approval (AC1 §6-5, VC-VAN-DROP-AFTER-APPROVE)") {
        Then("the next inquiry finds the original decision: AUTHORIZED once, one transaction, PaymentAuthorized once, no second AUTH_REQUEST (no re-authorization)") {
            val p = fixture.prepared()
            val key = unknownAfter(p, recorded = true)
            recoverAt(T0.plusSeconds(11))
            fixture.paymentStatus(p) shouldBe "AUTHORIZED"
            attempt(p, "state") shouldBe "RESOLVED"
            transactions(p) shouldBe 1
            events("PaymentAuthorized", authorizationId(p)) shouldBe 1
            van.authRequestsFor(mid(p)) shouldHaveSize 1
            val inquiries = van.inquiriesFor(requestId(p))
            inquiries shouldHaveSize 1
            inquiries.single().get("originalRequestId").stringValue() shouldBe requestId(p)
            fixture.count("SELECT COUNT(*) FROM institution_observation WHERE authorization_id = ? AND source = 'INQUIRY' AND outcome = 'APPROVED'", authorizationId(p)) shouldBe 1
            schedule(p, "closed_at") shouldNotBe null
            // 같은 키 재요청은 현재 결과(AUTHORIZED)로 수렴한다.
            view(p, key).status shouldBe AuthorizationViewStatus.AUTHORIZED
            recoverAt(T0.plusSeconds(60))
            van.inquiriesFor(requestId(p)) shouldHaveSize 1
            events("PaymentAuthorized", authorizationId(p)) shouldBe 1
        }
        Then("an inquiry before the next check time is not sent (the schedule is not due)") {
            val p = fixture.prepared()
            unknownAfter(p, recorded = true)
            recoverAt(T0.plusSeconds(5))
            van.inquiriesFor(requestId(p)) shouldHaveSize 0
            fixture.paymentStatus(p) shouldBe "UNKNOWN"
        }
    }

    Given("TX2 failing after the institution approved (§8 fault table)") {
        Then("the dispatch lease expires → OUTCOME_UNKNOWN(DISPATCH_LEASE_EXPIRED) without inquiry or effect; the next inquiry → AUTHORIZED once, the VAN ledger approval once") {
            val p = fixture.prepared()
            val updates = AtomicInteger()
            faults.after(FaultPoint.AUTHORIZATION_UPDATE) { if (updates.incrementAndGet() == 2) throw IllegalStateException("injected TX2 failure") }
            view(p, ConfirmFixture.newKey()).status shouldBe AuthorizationViewStatus.IN_PROGRESS
            attempt(p, "state") shouldBe "DISPATCHING"
            recoverAt(T0.plusSeconds(20))
            withClue("lease not yet expired") { attempt(p, "state") shouldBe "DISPATCHING" }
            // 일정이 lease 전에 선점되더라도(선점과 TXd 경합) 살아 있는 전송을 UNKNOWN으로 표시하지 않는다 — 판정은 lease 시각으로.
            makeDueEarlier(p, 15)
            recoverAt(T0.plusSeconds(20))
            withClue("claimed before the lease expired") { attempt(p, "state") shouldBe "DISPATCHING" }
            recoverAt(T0.plusSeconds(31))
            attempt(p, "state") shouldBe "OUTCOME_UNKNOWN"
            attempt(p, "unknown_cause") shouldBe "DISPATCH_LEASE_EXPIRED"
            fixture.paymentStatus(p) shouldBe "UNKNOWN"
            van.inquiriesFor(requestId(p)) shouldHaveSize 0
            transactions(p) shouldBe 0
            recoverAt(T0.plusSeconds(42))
            fixture.paymentStatus(p) shouldBe "AUTHORIZED"
            transactions(p) shouldBe 1
            events("PaymentAuthorized", authorizationId(p)) shouldBe 1
            van.authRequestsFor(mid(p)) shouldHaveSize 1
        }
    }

    Given("TXd failing before commit (nothing sent)") {
        Then("after the prepared deadline the recovery fences the attempt: FAILED(NOT_SENT) PRE_TRANSMISSION, limit RELEASED, PaymentAuthorizationFailed once, no request to the VAN") {
            val p = fixture.prepared()
            val updates = AtomicInteger()
            faults.after(FaultPoint.AUTHORIZATION_UPDATE) { if (updates.incrementAndGet() == 1) throw IllegalStateException("injected TXd failure") }
            val key = ConfirmFixture.newKey()
            view(p, key).status shouldBe AuthorizationViewStatus.IN_PROGRESS
            faults.reset()
            attempt(p, "state") shouldBe "PREPARED"
            makeDueEarlier(p, 1)
            recoverAt(T0.plusMillis(500))
            withClue("claimed before the prepared deadline — not fenced (the sender may still pass the gate)") { attempt(p, "state") shouldBe "PREPARED" }
            // 앞선 선점이 다음 점검을 조회 간격(10s) 뒤로 밀었으므로 그때 fencing한다.
            recoverAt(T0.plusSeconds(11))
            attempt(p, "state") shouldBe "ABANDONED_BEFORE_DISPATCH"
            attempt(p, "abandon_cause") shouldBe "FENCED_BY_RECOVERY"
            fixture.paymentStatus(p) shouldBe "FAILED"
            fixture.scalar("SELECT failure_evidence FROM payment_authorization WHERE authorization_id = ?", authorizationId(p)) shouldBe "PRE_TRANSMISSION_FAILURE_CONFIRMED"
            fixture.scalar("SELECT state FROM limit_reservation WHERE authorization_id = ?", authorizationId(p)) shouldBe "RELEASED"
            events("PaymentAuthorizationFailed", authorizationId(p)) shouldBe 1
            van.authRequestsFor(mid(p)) shouldHaveSize 0
            view(p, key).failureCategory shouldBe FailureCategory.NOT_SENT
        }
    }

    Given("the connection dropped before the VAN recorded anything (VC-VAN-DROP-BEFORE-RECORD)") {
        Then("profile A: NOT_FOUND before closableAfter keeps UNKNOWN; after closableAfter the inquiry asks for closure → NO_EFFECT_CONFIRMED → FAILED(NO_EFFECT); the closure is recorded for the original request ID") {
            val p = fixture.prepared()
            val key = unknownAfter(p, recorded = false)
            recoverAt(T0.plusSeconds(11))
            fixture.paymentStatus(p) shouldBe "UNKNOWN"
            fixture.count("SELECT COUNT(*) FROM institution_observation WHERE authorization_id = ? AND outcome = 'NOT_FOUND'", authorizationId(p)) shouldBe 1
            schedule(p, "inquiry_count") shouldBe 1
            recoverAt(T0.plus(Duration.ofMinutes(3)))
            val inquiries = van.inquiriesFor(requestId(p))
            inquiries.map { it.get("closeIfAbsent").booleanValue() } shouldBe listOf(false, true)
            inquiries.map { it.get("inquiryId").stringValue() }.toSet() shouldHaveSize 2
            fixture.paymentStatus(p) shouldBe "FAILED"
            fixture.scalar("SELECT failure_evidence FROM payment_authorization WHERE authorization_id = ?", authorizationId(p)) shouldBe "INSTITUTION_CONFIRMED_NO_EFFECT"
            fixture.scalar("SELECT state FROM limit_reservation WHERE authorization_id = ?", authorizationId(p)) shouldBe "RELEASED"
            events("PaymentAuthorizationFailed", authorizationId(p)) shouldBe 1
            view(p, key).failureCategory shouldBe FailureCategory.NO_EFFECT
            van.authRequestsFor(mid(p)) shouldHaveSize 1
            withClue("the closure is recorded for the original request ID — a late resend of it is REJECTED_CLOSED (Simulator m-C race)") { van.closureFor(requestId(p)) shouldNotBe null }
        }
        Then("profile B (no confirmsNoEffect): never asks for closure; NOT_FOUND until the budget runs out → AuthorizationOutcomeUnresolved once, UNKNOWN unchanged, slow inquiries continue") {
            val p = fixture.prepared()
            admission.respondFor(p.order.merchantAccountId) { ScriptedAdmissionPort.admitted(it, routeRef = ROUTE_B) }
            unknownAfter(p, recorded = false)
            listOf(11L, 180L, 360L).forEach { s -> recoverAt(T0.plusSeconds(s)) }
            schedule(p, "inquiry_count") shouldBe 3
            schedule(p, "escalated_at") shouldBe null
            recoverAt(T0.plusSeconds(540))
            withClue("budget exhausted → escalation without another inquiry") { van.inquiriesFor(requestId(p)) shouldHaveSize 3 }
            schedule(p, "escalated_at") shouldNotBe null
            events("AuthorizationOutcomeUnresolved", authorizationId(p)) shouldBe 1
            fixture.scalar("SELECT effect_key FROM outbox_event WHERE event_type = 'AuthorizationOutcomeUnresolved' AND aggregate_id = ?", authorizationId(p)) shouldBe
                "AuthorizationOutcomeUnresolved:" + attempt(p, "attempt_id")
            // 검토 #3 m-4: 이관 판단의 설정 버전과 사유를 남긴다(Auditability).
            schedule(p, "escalation_config_version") shouldBe RECOVERY_VERSION
            unresolved(p, "escalationReason") shouldBe "BUDGET"
            unresolved(p, "configVersion") shouldBe RECOVERY_VERSION
            fixture.paymentStatus(p) shouldBe "UNKNOWN"
            attempt(p, "state") shouldBe "OUTCOME_UNKNOWN"
            recoverAt(T0.plusSeconds(560))
            withClue("slow interval after escalation") { van.inquiriesFor(requestId(p)) shouldHaveSize 3 }
            recoverAt(T0.plusSeconds(540 + 601))
            van.inquiriesFor(requestId(p)) shouldHaveSize 4
            van.inquiriesFor(requestId(p)).map { it.get("closeIfAbsent").booleanValue() }.toSet() shouldBe setOf(false)
            events("AuthorizationOutcomeUnresolved", authorizationId(p)) shouldBe 1
            fixture.paymentStatus(p) shouldBe "UNKNOWN"
            fixture.count("SELECT COUNT(*) FROM payment_termination_evidence WHERE payment_id = ?", p.paymentId.value) shouldBe 0
        }
    }

    Given("the age limit and the inquiry retention (§6.2, AU-22)") {
        Then("past max age the attempt is escalated without waiting for the budget; past the route retention inquiries stop and the state stays UNKNOWN") {
            val p = fixture.prepared()
            unknownAfter(p, recorded = false)
            recoverAt(T0.plus(Duration.ofHours(1)).plusSeconds(1))
            events("AuthorizationOutcomeUnresolved", authorizationId(p)) shouldBe 1
            unresolved(p, "escalationReason") shouldBe "MAX_AGE"
            schedule(p, "closed_at") shouldBe null
            val before = van.inquiriesFor(requestId(p)).size
            recoverAt(T0.plus(Duration.ofDays(7)).plusSeconds(1))
            van.inquiriesFor(requestId(p)) shouldHaveSize before
            schedule(p, "closed_at") shouldNotBe null
            fixture.paymentStatus(p) shouldBe "UNKNOWN"
            attempt(p, "state") shouldBe "OUTCOME_UNKNOWN"
        }
    }

    Given("inquiry results that are not final (§6.3 — UNKNOWN kept)") {
        Then("an UNAVAILABLE inquiry (connection closed) changes nothing but the count; the next inquiry finds the approval") {
            val p = fixture.prepared()
            unknownAfter(p, recorded = true)
            val calls = AtomicInteger()
            van.onInquiry(requestId(p)) { if (calls.incrementAndGet() == 1) Action.Close else null }
            recoverAt(T0.plusSeconds(11))
            fixture.paymentStatus(p) shouldBe "UNKNOWN"
            schedule(p, "inquiry_count") shouldBe 1
            fixture.count("SELECT COUNT(*) FROM institution_observation WHERE authorization_id = ? AND source = 'INQUIRY'", authorizationId(p)) shouldBe 0
            recoverAt(T0.plusSeconds(22))
            fixture.paymentStatus(p) shouldBe "AUTHORIZED"
            events("PaymentAuthorized", authorizationId(p)) shouldBe 1
        }
        Then("PENDING stays UNKNOWN (no closure, no event); once the VAN decides, the next inquiry → AUTHORIZED") {
            val p = fixture.prepared()
            van.onAuth(mid(p)) { van.pending(it) }
            view(p, ConfirmFixture.newKey()).status shouldBe AuthorizationViewStatus.UNKNOWN
            recoverAt(T0.plus(Duration.ofMinutes(3)))
            fixture.paymentStatus(p) shouldBe "UNKNOWN"
            van.inquiriesFor(requestId(p)).single().get("closeIfAbsent").booleanValue() shouldBe true
            fixture.count("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ?", authorizationId(p)) shouldBe 0
            van.resolvePending(requestId(p)) { r -> van.approved(r) }
            recoverAt(T0.plus(Duration.ofMinutes(4)))
            fixture.paymentStatus(p) shouldBe "AUTHORIZED"
            transactions(p) shouldBe 1
            events("PaymentAuthorized", authorizationId(p)) shouldBe 1
        }
    }

    Given("a failure while handling one claimed schedule (§6.1 실패 — 그 건만 rollback)") {
        Then("the result step failing rolls back that item only; the claim pushed its next check by the inquiry interval, so it is retried then (not in a run at the same time)") {
            val p = fixture.prepared()
            unknownAfter(p, recorded = true)
            faults.after(FaultPoint.AUTHORIZATION_LOCK) { throw IllegalStateException("injected result-step failure") }
            recoverAt(T0.plusSeconds(11))
            faults.reset()
            fixture.paymentStatus(p) shouldBe "UNKNOWN"
            fixture.count("SELECT COUNT(*) FROM institution_observation WHERE authorization_id = ? AND source = 'INQUIRY'", authorizationId(p)) shouldBe 0
            schedule(p, "inquiry_count") shouldBe 0
            van.inquiriesFor(requestId(p)) shouldHaveSize 1
            recoverAt(T0.plusSeconds(11))
            withClue("not due again within the interval") { van.inquiriesFor(requestId(p)) shouldHaveSize 1 }
            recoverAt(T0.plusSeconds(22))
            van.inquiriesFor(requestId(p)) shouldHaveSize 2
            fixture.paymentStatus(p) shouldBe "AUTHORIZED"
            events("PaymentAuthorized", authorizationId(p)) shouldBe 1
        }
    }

    Given("review #3 m-1 — the lease-expiry mark is decided on the locked schedule, not on the claim snapshot") {
        Then("a claim taken while the attempt was PREPARED, then TXd commits DISPATCHING before the handling: the live dispatch is not marked (still DISPATCHING)") {
            val p = fixture.prepared()
            val id = start.start(p.command()).shouldBeInstanceOf<ConfirmStartOutcome.Accepted>().authorizationId
            val attemptId = attempt(p, "attempt_id") as String
            makeDueEarlier(p, 1)
            val raced = AtomicInteger()
            faults.after(FaultPoint.RECOVERY_BEFORE_FIND_ATTEMPT) { arg ->
                if ((arg as com.paycore.payment.payment.domain.AttemptId).value == attemptId && raced.incrementAndGet() == 1) {
                    // TXd가 선점 뒤·판정 전에 커밋한다. 결과 반영(TX2)은 실패시켜 DISPATCHING으로 남긴다.
                    val updates = AtomicInteger()
                    faults.after(FaultPoint.AUTHORIZATION_UPDATE) { if (updates.incrementAndGet() == 2) throw IllegalStateException("injected TX2 failure") }
                    dispatcher.dispatch(id, EventCausation("race", "race"))
                    faults.after(FaultPoint.AUTHORIZATION_UPDATE) { }
                }
            }
            recoverAt(T0.plusMillis(500))
            raced.get() shouldBe 1
            attempt(p, "state") shouldBe "DISPATCHING"
            attempt(p, "unknown_cause") shouldBe null
            van.authRequestsFor(mid(p)) shouldHaveSize 1
        }
        Then("TXd does not admit at or after preparedAt + preparedDeadline: the attempt stays PREPARED (nothing sent) and the recovery fences it") {
            val p = fixture.prepared()
            val id = start.start(p.command()).shouldBeInstanceOf<ConfirmStartOutcome.Accepted>().authorizationId
            clock.set(T0.plusSeconds(1))
            dispatcher.dispatch(id, EventCausation("late", "late"))
            attempt(p, "state") shouldBe "PREPARED"
            van.authRequestsFor(mid(p)) shouldHaveSize 0
            recoverAt(T0.plusSeconds(2))
            attempt(p, "abandon_cause") shouldBe "FENCED_BY_RECOVERY"
            van.authRequestsFor(mid(p)) shouldHaveSize 0
        }
    }

    Given("review #3 n-6 — an escalated schedule claimed and then failing") {
        Then("the claim pushes the next check by the slow interval (not the normal inquiry interval)") {
            val p = fixture.prepared()
            unknownAfter(p, recorded = false)
            val escalatedAt = T0.plus(Duration.ofHours(1)).plusSeconds(1)
            recoverAt(escalatedAt)
            schedule(p, "escalated_at") shouldNotBe null
            val claimAt = escalatedAt.plus(Duration.ofMinutes(10))
            faults.after(FaultPoint.AUTHORIZATION_LOCK) { throw IllegalStateException("injected result-step failure") }
            recoverAt(claimAt)
            faults.reset()
            nextCheckAt(p) shouldBe claimAt.plus(Duration.ofMinutes(10))
        }
    }

    Given("review #3 m-4·n-10 — the schedule row records the config version and its CHECKs") {
        Then("TX1 writes the recovery config version; environment and closed_at ≥ created_at are enforced by the schema") {
            val p = fixture.prepared()
            val id = start.start(p.command()).shouldBeInstanceOf<ConfirmStartOutcome.Accepted>().authorizationId
            schedule(p, "recovery_config_version") shouldBe RECOVERY_VERSION
            schedule(p, "escalation_config_version") shouldBe null
            val attemptId = attempt(p, "attempt_id") as String
            db.root().use { c ->
                c.prepareStatement("DELETE FROM authorization_recovery_schedule WHERE attempt_id = ?").use { it.setString(1, attemptId); it.executeUpdate() shouldBe 1 }
                fun insert(environment: String, closedAt: String?): Int = c.prepareStatement(
                    "INSERT INTO authorization_recovery_schedule (attempt_id, authorization_id, payment_id, environment, merchant_account_id, merchant_order_id, " +
                        "next_check_at, inquiry_count, recovery_config_version, closed_at, created_at, updated_at, version) " +
                        "VALUES (?, ?, ?, ?, 'm', 'o', '2026-10-15 00:00:00.000', 0, 'v', ?, '2026-10-15 00:00:00.000', '2026-10-15 00:00:00.000', 0)",
                ).use { ps ->
                    ps.setString(1, attemptId); ps.setString(2, id.value); ps.setString(3, p.paymentId.value); ps.setString(4, environment); ps.setString(5, closedAt)
                    ps.executeUpdate()
                }
                shouldThrow<java.sql.SQLException> { insert("PROD", null) }.errorCode shouldBe 3819
                shouldThrow<java.sql.SQLException> { insert("TEST", "2026-10-14 23:59:59.999") }.errorCode shouldBe 3819
            }
        }
    }

    // D-AUTH1-007(Q-S8-1 = A): 회복은 Attempt에 고정된 Route 프로파일 버전이 현재 등록부 버전과 같을 때만 무효과 종결(closeIfAbsent)을 요청한다. 다르거나 없으면(V14 이전 행)
    // 조회·대사 이관만 한다(fail-closed). 등록부 변경은 시험 중 재기동할 수 없으므로 Attempt 행의 버전을 바꿔 "다른 프로파일로 전송된 Attempt"를 재현한다.
    Given("the route profile version fixed at dispatch (D-AUTH1-007)") {
        fun setVersion(p: ConfirmFixture.Prepared, version: String?) = db.withRoot { c ->
            c.prepareStatement("UPDATE ${db.databaseName}.authorization_attempt SET route_profile_version = ? WHERE authorization_id = ?").use { ps ->
                ps.setString(1, version)
                ps.setString(2, authorizationId(p))
                ps.executeUpdate() shouldBe 1
            }
        }

        Then("the dispatch stores the current route profile version; with the same version the closure path is unchanged (closeIfAbsent after closableAfter → FAILED(NO_EFFECT))") {
            val p = fixture.prepared()
            unknownAfter(p, recorded = false)
            attempt(p, "route_profile_version") shouldBe "sim-van-a-demo-1"
            recoverAt(T0.plusSeconds(11))
            recoverAt(T0.plus(Duration.ofMinutes(3)))
            van.inquiriesFor(requestId(p)).map { it.get("closeIfAbsent").booleanValue() } shouldBe listOf(false, true)
            fixture.paymentStatus(p) shouldBe "FAILED"
        }
        listOf("a different version" to "sim-van-a-demo-0", "no version (row before V14)" to null).forEach { (name, stored) ->
            Then("$name: never asks for closure; NOT_FOUND keeps UNKNOWN; the budget runs out → escalation once, UNKNOWN unchanged, no closure in the VAN ledger") {
                val p = fixture.prepared()
                unknownAfter(p, recorded = false)
                setVersion(p, stored)
                listOf(11L, 180L, 360L).forEach { s -> recoverAt(T0.plusSeconds(s)) }
                van.inquiriesFor(requestId(p)).map { it.get("closeIfAbsent").booleanValue() } shouldBe listOf(false, false, false)
                fixture.paymentStatus(p) shouldBe "UNKNOWN"
                recoverAt(T0.plusSeconds(540))
                schedule(p, "escalated_at") shouldNotBe null
                events("AuthorizationOutcomeUnresolved", authorizationId(p)) shouldBe 1
                unresolved(p, "escalationReason") shouldBe "BUDGET"
                fixture.paymentStatus(p) shouldBe "UNKNOWN"
                attempt(p, "state") shouldBe "OUTCOME_UNKNOWN"
                van.closureFor(requestId(p)) shouldBe null
                fixture.count("SELECT COUNT(*) FROM payment_termination_evidence WHERE payment_id = ?", p.paymentId.value) shouldBe 0
            }
        }
        Then("a different version still applies a found approval (inquiry is not blocked — only the closure request is)") {
            val p = fixture.prepared()
            unknownAfter(p, recorded = true)
            setVersion(p, "sim-van-a-demo-0")
            recoverAt(T0.plusSeconds(11))
            fixture.paymentStatus(p) shouldBe "AUTHORIZED"
            transactions(p) shouldBe 1
            van.inquiriesFor(requestId(p)).single().get("closeIfAbsent").booleanValue() shouldBe false
        }
    }

    Given("observability (§9 Observability, Secret 비노출)") {
        Then("recovery logs carry internal IDs only — no institution request ID, institution merchant account or HMAC key") {
            val p = fixture.prepared()
            unknownAfter(p, recorded = false)
            CapturedLogs().use { logs ->
                recoverAt(T0.plusSeconds(11))
                val lines = logs.lines().filter { it.contains("AuthorizationRecovery") || it.contains("authorizationId=${authorizationId(p)}") }
                withClue(lines) { lines.isNotEmpty() shouldBe true }
                logs.lines().forEach { line ->
                    withClue(line) {
                        line.contains(requestId(p)) shouldBe false
                        line.contains(ScriptedAdmissionPort.INSTITUTION_MERCHANT_REF) shouldBe false
                        line.contains("dGVzdC1vbmx5LXNpbS12YW4taG1hYy1rZXktMDAwMDE=") shouldBe false
                    }
                }
            }
        }
    }
}) {
    private companion object {
        const val RECOVERY_VERSION = "recovery-demo-1"
        const val RUN_BATCH = 1000
        const val ROUTE_B = "SIM-VAN-B"
    }
}

package com.paycore.payment.payment.application

import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.adapter.web.ApiTestTimes.T0
import com.paycore.payment.payment.adapter.web.CapturedLogs
import com.paycore.payment.payment.adapter.web.MutablePaymentClock
import com.paycore.payment.payment.adapter.web.PaymentApiTest
import com.paycore.payment.payment.application.provided.AuthorizationViewStatus
import com.paycore.payment.payment.application.provided.ConfirmPaymentResult
import com.paycore.payment.payment.application.provided.ConfirmPaymentUseCase
import com.paycore.payment.payment.application.provided.RecoverAuthorizationsCommand
import com.paycore.payment.payment.application.provided.RecoverAuthorizationsUseCase
import com.paycore.payment.payment.application.required.AdmissionRefusalReason
import com.paycore.payment.payment.application.required.AdmissionResult
import com.paycore.payment.payment.application.required.AuthenticationSessionRepository
import com.paycore.payment.payment.application.required.CheckoutSessionStore
import com.paycore.payment.payment.application.required.CheckoutTokenStore
import com.paycore.payment.payment.application.required.OrderPaymentControlRepository
import com.paycore.payment.payment.application.required.PaymentRepository
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.transaction.PlatformTransactionManager
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 전송 게이트 안전(AUTH-001 §4.4, 검토 #3 m-3·n-1·n-2) — 실 MySQL·운영 Adapter·실제 소켓 stub VAN.
 * - C26: 차단 Snapshot 적용(head X)이 진행 중이면 TXd(head S)는 기다리고, ACK(커밋) 뒤 **새 권리로** 다시 판정한다 — 방금 차단된 가맹점으로 전송하지 않는다.
 *   권리 판정 자체는 시험 Admission 대역이 하므로 "차단 Snapshot의 효과"는 그 대역의 답을 head X를 잡은 동안 바꾸는 것으로 재현한다(운영 Admission 배선은 `AuthorizationAdmissionSpec`).
 * - C29·AU-28: TXd commit이 불명확하면(실제로는 커밋됨) 보내지 않는다 → lease 만료 → UNKNOWN(결코 FAILED 아님).
 * - 최종 검토 m-1(D-AUTH1-007): TX1 뒤 등록부에서 Route가 사라졌으면 TXd는 GATE_REFUSED로 전송 전 종결한다(보내지 않음·한도 해제·실패 이벤트 1·일정 종결) — 진단 로그는 내부 ID만.
 * - n-1: 회복 일정 행이 없으면 TXd는 결함으로 rollback하고 보내지 않는다. n-2: 전송자 결함(연결 전 예외)은 202이고 회복이 lease 만료로 정리한다.
 */
@PaymentApiTest
class AuthorizationDispatchSafetySpec(
    private val start: ConfirmStartService,
    private val dispatcher: AuthorizationDispatchService,
    private val confirm: ConfirmPaymentUseCase,
    private val recovery: RecoverAuthorizationsUseCase,
    private val van: ProgrammableVan,
    private val port: ObservedAuthorizationPort,
    private val admission: ScriptedAdmissionPort,
    private val routes: WithdrawableRouteResolver,
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
    val pool = Executors.newFixedThreadPool(2)
    afterSpec { pool.shutdownNow() }
    afterEach {
        clock.reset()
        CommitFaults.reset()
        port.throwBeforeSend = false
        routes.reset()
    }
    val causation = EventCausation("dispatch-safety-test", "dispatch-safety-test")

    fun authorizationId(p: ConfirmFixture.Prepared) = fixture.scalar("SELECT authorization_id FROM payment_authorization WHERE payment_id = ?", p.paymentId.value) as String
    fun attempt(p: ConfirmFixture.Prepared, column: String) = fixture.scalar("SELECT $column FROM authorization_attempt WHERE authorization_id = ?", authorizationId(p))
    fun mid(p: ConfirmFixture.Prepared) = p.order.merchantAccountId.value
    fun recoverAt(seconds: Long) {
        clock.set(T0.plusSeconds(seconds))
        recovery.recover(RecoverAuthorizationsCommand(UUID.randomUUID().toString(), 1000))
    }

    Given("C26 — a block snapshot holding entitlement_head X while TXd needs head S") {
        Then("TXd waits for the snapshot, then re-evaluates with the new entitlement at the time after the lock: GATE_REFUSED, nothing sent (n-5: `at` after the head lock)") {
            val p = fixture.prepared()
            val merchantId = fixture.entitlementHead(p.order.merchantAccountId)
            val id = start.start(p.command()).shouldBeInstanceOf<ConfirmStartOutcome.Accepted>().authorizationId
            db.root().use { holder ->
                holder.autoCommit = false
                holder.prepareStatement("SELECT merchant_id FROM entitlement_head WHERE merchant_id = ? FOR UPDATE").use { it.setString(1, merchantId); it.executeQuery().close() }
                val dispatching = pool.submit(Callable { dispatcher.dispatch(id, causation) })
                Thread.sleep(700)
                withClue("TXd waits on head S behind the snapshot's X") { dispatching.isDone shouldBe false }
                van.authRequestsFor(mid(p)) shouldHaveSize 0
                // 차단 Snapshot의 효과: 이 MID는 이제 승인 불가. Snapshot 커밋(ACK) 시각을 앞으로.
                admission.respondFor(p.order.merchantAccountId) { AdmissionResult.Refused(AdmissionRefusalReason.CARD_COMPANY_NOT_ENABLED) }
                clock.set(T0.plusMillis(500))
                holder.commit()
                dispatching.get(30, TimeUnit.SECONDS)
            }
            attempt(p, "state") shouldBe "ABANDONED_BEFORE_DISPATCH"
            attempt(p, "abandon_cause") shouldBe "GATE_REFUSED"
            van.authRequestsFor(mid(p)) shouldHaveSize 0
            fixture.paymentStatus(p) shouldBe "FAILED"
            withClue("the gate evaluated at the instant after the head lock") { admission.callsFor(p.order.merchantAccountId).last().evaluatedAt shouldBe T0.plusMillis(500) }
        }
    }

    Given("C29·AU-28 — the TXd commit outcome is unknown (committed, but the connection failed after commit)") {
        Then("nothing is sent (202); after the lease the recovery marks OUTCOME_UNKNOWN(DISPATCH_LEASE_EXPIRED) — never FAILED") {
            val p = fixture.prepared()
            val id = start.start(p.command()).shouldBeInstanceOf<ConfirmStartOutcome.Accepted>().authorizationId
            CommitFaults.arm()
            dispatcher.dispatch(id, causation) shouldBe false
            van.authRequestsFor(mid(p)) shouldHaveSize 0
            withClue("the TXd actually committed") { attempt(p, "state") shouldBe "DISPATCHING" }
            recoverAt(31)
            attempt(p, "state") shouldBe "OUTCOME_UNKNOWN"
            attempt(p, "unknown_cause") shouldBe "DISPATCH_LEASE_EXPIRED"
            fixture.paymentStatus(p) shouldBe "UNKNOWN"
            fixture.count("SELECT COUNT(*) FROM payment_termination_evidence WHERE payment_id = ?", p.paymentId.value) shouldBe 0
            van.authRequestsFor(mid(p)) shouldHaveSize 0
        }
    }

    Given("final review m-1 — the route profile is no longer registered at TXd (removed after TX1 by a restart, D-AUTH1-007)") {
        Then("GATE_REFUSED before dispatch: nothing sent, Payment FAILED(PRE_TRANSMISSION), limit RELEASED, schedule closed, one PaymentAuthorizationFailed; a diagnostic log with internal IDs only") {
            val p = fixture.prepared()
            val id = start.start(p.command()).shouldBeInstanceOf<ConfirmStartOutcome.Accepted>().authorizationId
            routes.withdraw(ScriptedAdmissionPort.ROUTE_REF)
            val lines = CapturedLogs().use { logs ->
                dispatcher.dispatch(id, causation) shouldBe true
                logs.lines()
            }
            routes.reset()
            van.authRequestsFor(mid(p)) shouldHaveSize 0
            attempt(p, "state") shouldBe "ABANDONED_BEFORE_DISPATCH"
            attempt(p, "abandon_cause") shouldBe "GATE_REFUSED"
            attempt(p, "route_profile_version") shouldBe null
            fixture.paymentStatus(p) shouldBe "FAILED"
            fixture.scalar("SELECT failure_evidence FROM payment_authorization WHERE payment_id = ?", p.paymentId.value) shouldBe "PRE_TRANSMISSION_FAILURE_CONFIRMED"
            fixture.scalar("SELECT state FROM limit_reservation WHERE authorization_id = ?", id.value) shouldBe "RELEASED"
            withClue("the recovery schedule is closed") {
                fixture.count("SELECT COUNT(*) FROM authorization_recovery_schedule WHERE authorization_id = ? AND closed_at IS NOT NULL", id.value) shouldBe 1
            }
            fixture.count("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ?", id.value) shouldBe 1
            fixture.count("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'PaymentAuthorizationFailed'", id.value) shouldBe 1
            val diagnostic = lines.filter { it.contains("operation=AuthorizationDispatch result=GATE_REFUSED reason=ROUTE_NOT_REGISTERED authorizationId=${id.value}") }
            withClue(lines) { diagnostic shouldHaveSize 1 }
            withClue(diagnostic) {
                diagnostic.single().contains(fixture.scalar("SELECT request_id FROM authorization_attempt WHERE authorization_id = ?", id.value) as String) shouldBe false
                diagnostic.single().contains(ScriptedAdmissionPort.INSTITUTION_MERCHANT_REF) shouldBe false
            }
        }
    }

    Given("n-1 — the attempt's recovery schedule row is missing") {
        Then("TXd treats it as a defect: rolled back, attempt PREPARED, nothing sent") {
            val p = fixture.prepared()
            val id = start.start(p.command()).shouldBeInstanceOf<ConfirmStartOutcome.Accepted>().authorizationId
            db.root().use { c ->
                c.prepareStatement("DELETE FROM authorization_recovery_schedule WHERE authorization_id = ?").use { it.setString(1, id.value); it.executeUpdate() shouldBe 1 }
            }
            dispatcher.dispatch(id, causation) shouldBe false
            attempt(p, "state") shouldBe "PREPARED"
            van.authRequestsFor(mid(p)) shouldHaveSize 0
        }
    }

    Given("n-2 — the sender fails before any byte (an exception from the institution port)") {
        Then("confirm answers 202 IN_PROGRESS; the recovery later marks the lease expiry (UNKNOWN), no financial fact") {
            val p = fixture.prepared()
            port.throwBeforeSend = true
            confirm.confirm(p.command()).shouldBeInstanceOf<ConfirmPaymentResult.Accepted>().view.status shouldBe AuthorizationViewStatus.IN_PROGRESS
            port.throwBeforeSend = false
            attempt(p, "state") shouldBe "DISPATCHING"
            recoverAt(31)
            attempt(p, "state") shouldBe "OUTCOME_UNKNOWN"
            fixture.count("SELECT COUNT(*) FROM payment_transaction WHERE authorization_id = ?", authorizationId(p)) shouldBe 0
        }
    }
})

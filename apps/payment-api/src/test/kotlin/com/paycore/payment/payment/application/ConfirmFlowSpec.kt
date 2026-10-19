package com.paycore.payment.payment.application

import com.paycore.payment.payment.adapter.integration.institution.StubVan.Action
import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.adapter.persistence.FaultInjector
import com.paycore.payment.payment.adapter.persistence.FaultPoint
import com.paycore.payment.payment.adapter.web.MutablePaymentClock
import com.paycore.payment.payment.adapter.web.PaymentApiTest
import com.paycore.payment.payment.application.provided.AuthorizationViewStatus
import com.paycore.payment.payment.application.provided.ConfirmPaymentResult
import com.paycore.payment.payment.application.provided.ConfirmPaymentUseCase
import com.paycore.payment.payment.application.provided.FailureCategory
import com.paycore.payment.payment.application.required.AdmissionRefusalReason
import com.paycore.payment.payment.application.required.AdmissionResult
import com.paycore.payment.payment.application.required.AdmissionUnavailableReason
import com.paycore.payment.payment.application.required.AuthenticationSessionRepository
import com.paycore.payment.payment.application.required.CheckoutSessionStore
import com.paycore.payment.payment.application.required.CheckoutTokenStore
import com.paycore.payment.payment.application.required.OrderPaymentControlRepository
import com.paycore.payment.payment.application.required.PaymentRepository
import com.paycore.payment.payment.domain.CardType
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.transaction.PlatformTransactionManager
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * confirm 전체 흐름(AUTH-001 §4.2 — TX1 → TXd → TX 밖 기관 호출 → TX2)을 실 MySQL·운영 Adapter·실제 소켓의 시험 전용 stub VAN([ProgrammableVan])으로 시험한다.
 * 기대값은 명세 §4.4~§4.6·§4.10·§5.7.2 표에서 직접 적는다. 회복 Worker(UNKNOWN 이후)는 S7.
 */
@PaymentApiTest
class ConfirmFlowSpec(
    private val confirm: ConfirmPaymentUseCase,
    private val van: ProgrammableVan,
    private val port: ObservedAuthorizationPort,
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

    fun accepted(r: ConfirmPaymentResult) = r.shouldBeInstanceOf<ConfirmPaymentResult.Accepted>()
    fun authorizationId(p: ConfirmFixture.Prepared) = fixture.scalar("SELECT authorization_id FROM payment_authorization WHERE payment_id = ?", p.paymentId.value) as String
    fun events(type: String, key: String) = fixture.count("SELECT COUNT(*) FROM outbox_event WHERE event_type = ? AND effect_key LIKE ?", type, "$type:$key%")
    fun attemptState(p: ConfirmFixture.Prepared) = fixture.scalar(
        "SELECT t.state FROM authorization_attempt t JOIN payment_authorization a ON a.authorization_id = t.authorization_id WHERE a.payment_id = ?", p.paymentId.value,
    )

    Given("the VAN approves") {
        Then("200 AUTHORIZED: one transaction, PaymentAuthorized once, limit CONSUMED, attempt RESOLVED, one RESPONSE observation, exactly one AUTH_REQUEST with the attempt's request ID") {
            val p = fixture.prepared()
            val view = accepted(confirm.confirm(p.command())).view
            view.status shouldBe AuthorizationViewStatus.AUTHORIZED
            view.approved!!.money shouldBe p.money
            view.approved.cardType shouldBe CardType.CREDIT
            val auth = authorizationId(p)
            fixture.paymentStatus(p) shouldBe "AUTHORIZED"
            fixture.count("SELECT COUNT(*) FROM payment_transaction WHERE authorization_id = ?", auth) shouldBe 1
            val txId = fixture.scalar("SELECT transaction_id FROM payment_transaction WHERE authorization_id = ?", auth) as String
            events("PaymentAuthorized", txId) shouldBe 1
            fixture.scalar("SELECT event_category FROM outbox_event WHERE effect_key = ?", "PaymentAuthorized:$txId") shouldBe "FINANCIAL_FACT"
            fixture.scalar("SELECT state FROM limit_reservation WHERE authorization_id = ?", auth) shouldBe "CONSUMED"
            attemptState(p) shouldBe "RESOLVED"
            fixture.count("SELECT COUNT(*) FROM institution_observation WHERE authorization_id = ? AND source = 'RESPONSE'", auth) shouldBe 1
            val requests = van.authRequestsFor(p.order.merchantAccountId.value)
            requests.size shouldBe 1
            requests.single().get("requestId").stringValue() shouldBe fixture.scalar(
                "SELECT t.request_id FROM authorization_attempt t WHERE t.authorization_id = ?", auth,
            )
            requests.single().get("institutionAccount").stringValue() shouldBe ScriptedAdmissionPort.INSTITUTION_MERCHANT_REF
        }
        Then("a replay with the same key returns the current AUTHORIZED view without a new institution call (D-AUTH-004)") {
            val p = fixture.prepared()
            val key = ConfirmFixture.newKey()
            accepted(confirm.confirm(p.command(key = key)))
            val again = accepted(confirm.confirm(p.command(key = key)))
            again.replayed shouldBe true
            again.view.status shouldBe AuthorizationViewStatus.AUTHORIZED
            van.authRequestsFor(p.order.merchantAccountId.value).size shouldBe 1
        }
    }

    Given("other institution results (§5.7.2)") {
        Then("DECLINED → 200 FAILED(DECLINED), PaymentAuthorizationFailed once, limit RELEASED") {
            val p = fixture.prepared()
            van.onAuth(p.order.merchantAccountId.value) { van.decline(it) }
            val view = accepted(confirm.confirm(p.command())).view
            view.status shouldBe AuthorizationViewStatus.FAILED
            view.failureCategory shouldBe FailureCategory.DECLINED
            val auth = authorizationId(p)
            events("PaymentAuthorizationFailed", auth) shouldBe 1
            fixture.scalar("SELECT state FROM limit_reservation WHERE authorization_id = ?", auth) shouldBe "RELEASED"
            fixture.paymentStatus(p) shouldBe "FAILED"
        }
        Then("PENDING → 202 UNKNOWN with a PENDING observation (n-1), no outbox event") {
            val p = fixture.prepared()
            van.onAuth(p.order.merchantAccountId.value) { van.pending(it) }
            accepted(confirm.confirm(p.command())).view.status shouldBe AuthorizationViewStatus.UNKNOWN
            fixture.paymentStatus(p) shouldBe "UNKNOWN"
            fixture.count("SELECT COUNT(*) FROM institution_observation WHERE authorization_id = ? AND outcome = 'PENDING'", authorizationId(p)) shouldBe 1
        }
        Then("the response is lost (connection closed after the ledger records the approval) → 202 UNKNOWN, attempt OUTCOME_UNKNOWN(CONNECTION_LOST), no financial fact") {
            val p = fixture.prepared()
            van.onAuth(p.order.merchantAccountId.value) { ProgrammableVan.Script(van.approved(it), Action.Close) }
            accepted(confirm.confirm(p.command())).view.status shouldBe AuthorizationViewStatus.UNKNOWN
            attemptState(p) shouldBe "OUTCOME_UNKNOWN"
            fixture.scalar("SELECT unknown_cause FROM authorization_attempt WHERE authorization_id = ?", authorizationId(p)) shouldBe "CONNECTION_LOST"
            fixture.count("SELECT COUNT(*) FROM payment_transaction WHERE authorization_id = ?", authorizationId(p)) shouldBe 0
        }
        Then("no response before the deadline → 202 UNKNOWN(TIMEOUT)") {
            val p = fixture.prepared()
            van.onAuth(p.order.merchantAccountId.value) { ProgrammableVan.Script(van.approved(it), Action.Silent(3_000)) }
            accepted(confirm.confirm(p.command())).view.status shouldBe AuthorizationViewStatus.UNKNOWN
            fixture.scalar("SELECT unknown_cause FROM authorization_attempt WHERE authorization_id = ?", authorizationId(p)) shouldBe "TIMEOUT"
        }
        Then("a partial approval → 202 UNKNOWN with a CONFLICTING hold and one AuthorizationIncidentDetected (D-AUTH-008, F-1)") {
            val p = fixture.prepared()
            van.onAuth(p.order.merchantAccountId.value) { r -> ProgrammableVan.Script(van.approved(r) { it.put("approvedAmountMinor", r.get("amountMinor").longValue() - 1) }) }
            accepted(confirm.confirm(p.command())).view.status shouldBe AuthorizationViewStatus.UNKNOWN
            fixture.count("SELECT COUNT(*) FROM incident_hold WHERE payment_id = ? AND reason = 'CONFLICTING_INSTITUTION_RESULT'", p.paymentId.value) shouldBe 1
            events("AuthorizationIncidentDetected", p.paymentId.value) shouldBe 1
            fixture.scalar("SELECT effect_key FROM outbox_event WHERE event_type = 'AuthorizationIncidentDetected' AND aggregate_id = ?", authorizationId(p)) shouldBe
                "AuthorizationIncidentDetected:${p.paymentId.value}:CONFLICTING_INSTITUTION_RESULT:1"
        }
    }

    Given("connect and gate failures before any byte is sent (§4.4·§4.5)") {
        Then("a refused connection after the retry budget → 200 FAILED(NOT_SENT), PRE_TRANSMISSION, limit RELEASED") {
            val p = fixture.prepared()
            admission.respondFor(p.order.merchantAccountId) { ScriptedAdmissionPort.admitted(it, routeRef = "SIM-VAN-CLOSED") }
            val view = accepted(confirm.confirm(p.command())).view
            view.status shouldBe AuthorizationViewStatus.FAILED
            view.failureCategory shouldBe FailureCategory.NOT_SENT
            fixture.scalar("SELECT abandon_cause FROM authorization_attempt WHERE authorization_id = ?", authorizationId(p)) shouldBe "CONNECT_FAILED"
            fixture.scalar("SELECT failure_evidence FROM payment_authorization WHERE payment_id = ?", p.paymentId.value) shouldBe "PRE_TRANSMISSION_FAILURE_CONFIRMED"
        }
        Then("the gate re-evaluation refuses (A0~A2 at now) → FAILED(NOT_SENT) GATE_REFUSED with no request to the VAN") {
            val p = fixture.prepared()
            val calls = AtomicInteger()
            admission.respondFor(p.order.merchantAccountId) {
                if (calls.incrementAndGet() == 1) ScriptedAdmissionPort.admitted(it) else AdmissionResult.Refused(AdmissionRefusalReason.CARD_COMPANY_NOT_ENABLED)
            }
            accepted(confirm.confirm(p.command())).view.failureCategory shouldBe FailureCategory.NOT_SENT
            fixture.scalar("SELECT abandon_cause FROM authorization_attempt WHERE authorization_id = ?", authorizationId(p)) shouldBe "GATE_REFUSED"
            van.authRequestsFor(p.order.merchantAccountId.value).size shouldBe 0
            events("PaymentAuthorizationFailed", authorizationId(p)) shouldBe 1
        }
        Then("a transient gate Unavailable is retried within the prepared deadline and then dispatched (AU-33)") {
            val p = fixture.prepared()
            val calls = AtomicInteger()
            admission.respondFor(p.order.merchantAccountId) {
                when (calls.incrementAndGet()) {
                    1 -> ScriptedAdmissionPort.admitted(it)
                    2 -> AdmissionResult.Unavailable(AdmissionUnavailableReason.SNAPSHOT_EXPIRED)
                    else -> ScriptedAdmissionPort.admitted(it)
                }
            }
            accepted(confirm.confirm(p.command())).view.status shouldBe AuthorizationViewStatus.AUTHORIZED
            van.authRequestsFor(p.order.merchantAccountId.value).size shouldBe 1
        }
        Then("a gate Unavailable lasting past the prepared deadline → GATE_REFUSED (AU-33)") {
            val p = fixture.prepared()
            val calls = AtomicInteger()
            admission.respondFor(p.order.merchantAccountId) {
                if (calls.incrementAndGet() == 1) ScriptedAdmissionPort.admitted(it) else AdmissionResult.Unavailable(AdmissionUnavailableReason.SNAPSHOT_EXPIRED)
            }
            accepted(confirm.confirm(p.command())).view.failureCategory shouldBe FailureCategory.NOT_SENT
            fixture.scalar("SELECT abandon_cause FROM authorization_attempt WHERE authorization_id = ?", authorizationId(p)) shouldBe "GATE_REFUSED"
            van.authRequestsFor(p.order.merchantAccountId.value).size shouldBe 0
        }
    }

    Given("failures around the dispatch commit and the result commit (§4.2, AU-28, C29)") {
        Then("TXd failing before commit sends nothing: 202 IN_PROGRESS, attempt PREPARED (the recovery fences it — S7)") {
            val p = fixture.prepared()
            val updates = AtomicInteger()
            faults.after(FaultPoint.AUTHORIZATION_UPDATE) { if (updates.incrementAndGet() == 1) throw IllegalStateException("injected TXd failure") }
            accepted(confirm.confirm(p.command())).view.status shouldBe AuthorizationViewStatus.IN_PROGRESS
            attemptState(p) shouldBe "PREPARED"
            van.authRequestsFor(p.order.merchantAccountId.value).size shouldBe 0
        }
        Then("TX2 failing after the institution approved: 202 IN_PROGRESS, no financial fact yet, the VAN ledger has the approval once (recovery — S7)") {
            val p = fixture.prepared()
            val updates = AtomicInteger()
            faults.after(FaultPoint.AUTHORIZATION_UPDATE) { if (updates.incrementAndGet() == 2) throw IllegalStateException("injected TX2 failure") }
            accepted(confirm.confirm(p.command())).view.status shouldBe AuthorizationViewStatus.IN_PROGRESS
            attemptState(p) shouldBe "DISPATCHING"
            fixture.count("SELECT COUNT(*) FROM payment_transaction WHERE authorization_id = ?", authorizationId(p)) shouldBe 0
            van.authRequestsFor(p.order.merchantAccountId.value).size shouldBe 1
            (van.decided(van.authRequestsFor(p.order.merchantAccountId.value).single().get("requestId").stringValue()) != null) shouldBe true
        }
    }

    // AC 2: 외부 호출은 로컬 TX 밖 — 기관이 요청을 받는 순간 다른 연결이 그 Payment·승인·control 행을 잠금 대기 없이 잡을 수 있다.
    Given("the institution call (AC 2 — outside any local transaction)") {
        Then("while the VAN handles the request, no row the confirm touched is locked (payment, authorization, control, entitlement head, idempotency, recovery schedule) and no DB TX is active at the call") {
            val p = fixture.prepared()
            val merchantId = fixture.entitlementHead(p.order.merchantAccountId)
            val probes = ConcurrentLinkedQueue<String>()
            val mid = p.order.merchantAccountId.value
            van.onAuth(mid) { r ->
                db.app().use { c ->
                    c.autoCommit = false
                    listOf(
                        "SELECT payment_id FROM payment WHERE payment_id = ? FOR UPDATE NOWAIT" to p.paymentId.value,
                        "SELECT authorization_id FROM payment_authorization WHERE payment_id = ? FOR UPDATE NOWAIT" to p.paymentId.value,
                        "SELECT current_payment_id FROM order_payment_control WHERE current_payment_id = ? FOR UPDATE NOWAIT" to p.paymentId.value,
                        // 검토 #3 n-8: head(S)는 TX1·TXd에서만 — 호출 중에는 X도 잡힌다(행이 실제로 있음).
                        "SELECT merchant_id FROM entitlement_head WHERE merchant_id = ? FOR UPDATE NOWAIT" to merchantId,
                        "SELECT id FROM idempotency_request WHERE merchant_account_id = ? FOR UPDATE NOWAIT" to mid,
                        "SELECT attempt_id FROM authorization_recovery_schedule WHERE payment_id = ? FOR UPDATE NOWAIT" to p.paymentId.value,
                    ).forEach { (sql, arg) ->
                        val rows = runCatching { c.prepareStatement(sql).use { ps -> ps.setString(1, arg); ps.executeQuery().use { rs -> var n = 0; while (rs.next()) n++; n } } }
                        probes += when {
                            rows.isFailure -> "locked:$sql"
                            rows.getOrThrow() == 0 -> "no-row:$sql"
                            else -> "free"
                        }
                    }
                    c.rollback()
                }
                van.approve(r)
            }
            val callsBefore = port.transactionActiveAtCall.size
            accepted(confirm.confirm(p.command())).view.status shouldBe AuthorizationViewStatus.AUTHORIZED
            withClue(probes.toString()) { probes.toList() shouldBe List(6) { "free" } }
            withClue("no actual DB transaction on the calling thread at the institution call") {
                port.transactionActiveAtCall.drop(callsBefore) shouldBe listOf(false)
            }
        }
    }
})

package com.paycore.payment.payment.adapter.web.admin

import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.adapter.persistence.FaultInjector
import com.paycore.payment.payment.adapter.persistence.FaultPoint
import com.paycore.payment.payment.adapter.web.InternalConnector
import com.paycore.payment.payment.adapter.web.MutablePaymentClock
import com.paycore.payment.payment.adapter.web.PaymentApiTest
import com.paycore.payment.payment.adapter.web.admin.AdminOpsTestClient.Companion.APPROVER
import com.paycore.payment.payment.adapter.web.admin.AdminOpsTestClient.Companion.EXECUTE
import com.paycore.payment.payment.adapter.web.admin.AdminOpsTestClient.Companion.REQUESTER
import com.paycore.payment.payment.adapter.web.admin.AdminOpsTestClient.Companion.body
import com.paycore.payment.payment.adapter.web.admin.AdminOpsTestClient.Companion.deadline
import com.paycore.payment.payment.adapter.web.admin.AdminOpsTestClient.Companion.newApprovalId
import com.paycore.payment.payment.application.CancelFixture
import com.paycore.payment.payment.application.ConfirmFixture
import com.paycore.payment.payment.application.ScriptedAdmissionPort
import com.paycore.payment.payment.application.provided.ConfirmPaymentUseCase
import com.paycore.payment.payment.application.required.AuthenticationSessionRepository
import com.paycore.payment.payment.application.required.CheckoutSessionStore
import com.paycore.payment.payment.application.required.CheckoutTokenStore
import com.paycore.payment.payment.application.required.OrderPaymentControlRepository
import com.paycore.payment.payment.application.required.PaymentRepository
import com.paycore.payment.payment.application.required.PersistenceFailure
import com.paycore.payment.payment.application.required.StoreConstraint
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.transaction.PlatformTransactionManager
import java.net.Socket
import java.time.Duration
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * ADMIN 채널 운영자 보정 취소 실행·결정 기록(ADMIN-PAYMENT-OPS-001 §4.3~§4.6·§4.4.5, D-OPS-002·005·009 — 실제 내장 Tomcat 내부 포트·실제 서명 체인·실제 MySQL 8.4.11). 기대값은 명세 표에서 직접
 * 적는다: 시작·업무 거절은 approvalId별 결정 행 1개(같은 approvalId에는 항상 같은 결정), 409·404는 커밋된 결정 행이 있을 때만, 400은 형식 + 감사(결정 없음), 그 밖 503. 대상 Payment는 공식
 * confirm 경로(CancelFixture)로 만든다.
 */
@PaymentApiTest
class AdminOperatorCancellationApiSpec(
    private val internal: InternalConnector,
    private val clock: MutablePaymentClock,
    private val faults: FaultInjector,
    private val db: DbAccess,
    private val dataSource: DataSource,
    admission: ScriptedAdmissionPort,
    confirm: ConfirmPaymentUseCase,
    payments: PaymentRepository,
    controls: OrderPaymentControlRepository,
    sessions: AuthenticationSessionRepository,
    tokens: CheckoutTokenStore,
    checkoutSessions: CheckoutSessionStore,
    transactionManager: PlatformTransactionManager,
) : BehaviorSpec({

    val confirmFixture = ConfirmFixture(payments, controls, sessions, tokens, checkoutSessions, transactionManager, db)
    val fixture = CancelFixture(confirmFixture, confirm, admission, controls, payments, transactionManager)
    val api = AdminOpsTestClient(internal.localPort)

    // ADMIN 채널 계약 형식(`pay_` + ULID) paymentId로 공식 confirm 경로 승인.
    fun authorized() = fixture.authorized(paymentIdValue = AdminOpsTestClient.newPaymentId())

    afterEach {
        faults.reset()
        clock.reset()
    }

    fun decisions(approvalId: String) = fixture.count("SELECT COUNT(*) FROM operator_approval_decision WHERE approval_id = ?", approvalId)
    fun audits(approvalId: String) = fixture.count("SELECT COUNT(*) FROM operator_cancel_rejection WHERE approval_id = ?", approvalId)
    fun decisionOf(approvalId: String) =
        fixture.scalar("SELECT CONCAT(decision, '|', COALESCE(rejection_code, '-'), '|', origin) FROM operator_approval_decision WHERE approval_id = ?", approvalId)
    fun cancellations(a: CancelFixture.Authorized) = fixture.cancellationRows(a)
    fun error(r: AdminOpsTestClient.Response) = r.json.get("error").asString()

    Given("a valid two-person approval of an authorized payment") {
        Then("the first command starts (200, existing=false) with one STARTED decision; the same bytes again replay it (existing=true) and nothing new is written") {
            val a = authorized()
            val b = body(a)
            val apr = AdminOpsTestClient.approvalIdOf(b)
            val first = api.post(EXECUTE, b)
            first.status shouldBe 200
            first.json.get("existing").asBoolean() shouldBe false
            val cancellationId = first.json.get("cancellationId").asString()
            decisionOf(apr) shouldBe "STARTED|-|EXECUTION_COMMAND"
            fixture.scalar("SELECT cancellation_id FROM operator_approval_decision WHERE approval_id = ?", apr) shouldBe cancellationId
            fixture.scalar("SELECT content_hash FROM operator_approval_decision WHERE approval_id = ?", apr).toString() shouldMatch Regex("^opd1:[0-9a-f]{64}$")
            val again = api.post(EXECUTE, b)
            again.status shouldBe 200
            again.json.get("existing").asBoolean() shouldBe true
            again.json.get("cancellationId").asString() shouldBe cancellationId
            decisions(apr) shouldBe 1
            cancellations(a) shouldBe 1
            audits(apr) shouldBe 0
            // 기록 결정 조회(L-5·L-b 공통 필드).
            val d = api.get("$EXECUTE/$apr")
            d.status shouldBe 200
            d.json.get("decision").asString() shouldBe "STARTED"
            d.json.get("cancellationId").asString() shouldBe cancellationId
            d.json.get("paymentId").asString() shouldBe a.paymentId.value
            d.json.get("amountMinor").asLong() shouldBe a.money.amountMinor
            d.json.get("requestedBy").asString() shouldBe REQUESTER
            d.json.get("approvedBy").asString() shouldBe APPROVER
            d.json.get("legacy").asBoolean() shouldBe false
            d.json.has("reason") shouldBe false
        }
        Then("a different correlationId is the same command (not part of the content hash)") {
            val a = authorized()
            val apr = newApprovalId()
            api.post(EXECUTE, body(a, approvalId = apr, correlationId = "corr-a")).status shouldBe 200
            api.post(EXECUTE, body(a, approvalId = apr, correlationId = "corr-b")).json.get("existing").asBoolean() shouldBe true
        }
        Then("an approval never seen is NONE") {
            val r = api.get("$EXECUTE/${newApprovalId()}")
            r.status shouldBe 200
            r.body shouldBe """{"decision":"NONE"}"""
        }
    }

    Given("business rejections are decisions (D-OPS-005: the approval is consumed)") {
        Then("same requester and approver is 409 OPERATOR_SAME_PERSON with a REJECTED decision; each resend is the same 409 and one more audit row") {
            val a = authorized()
            val b = body(a, approvedBy = REQUESTER)
            val apr = AdminOpsTestClient.approvalIdOf(b)
            api.post(EXECUTE, b).let { it.status shouldBe 409; error(it) shouldBe "OPERATOR_SAME_PERSON" }
            api.post(EXECUTE, b).let { it.status shouldBe 409; error(it) shouldBe "OPERATOR_SAME_PERSON" }
            decisionOf(apr) shouldBe "REJECTED|OPERATOR_SAME_PERSON|EXECUTION_COMMAND"
            decisions(apr) shouldBe 1
            audits(apr) shouldBe 2
            cancellations(a) shouldBe 0
        }
        Then("a temporary rejection stays after its condition is gone: approvedAt in the future is APPROVAL_WINDOW_INVALID, and after the clock passes it the same bytes are still rejected") {
            val a = authorized()
            val approvedAt = ConfirmFixture.T0.plus(Duration.ofHours(1))
            val b = body(a, approvedAt = approvedAt)
            val apr = AdminOpsTestClient.approvalIdOf(b)
            api.post(EXECUTE, b).let { it.status shouldBe 409; error(it) shouldBe "APPROVAL_WINDOW_INVALID" }
            clock.set(approvedAt.plusSeconds(60))
            api.post(EXECUTE, b).let { it.status shouldBe 409; error(it) shouldBe "APPROVAL_WINDOW_INVALID" }
            cancellations(a) shouldBe 0
            decisions(apr) shouldBe 1
            audits(apr) shouldBe 2
        }
        Then("a stale approved target (revision differs) is 409 OPERATOR_TARGET_STALE as a decision") {
            val a = authorized()
            val b = body(a, revision = 7)
            api.post(EXECUTE, b).let { it.status shouldBe 409; error(it) shouldBe "OPERATOR_TARGET_STALE" }
            decisionOf(AdminOpsTestClient.approvalIdOf(b)) shouldBe "REJECTED|OPERATOR_TARGET_STALE|EXECUTION_COMMAND"
        }
        Then("an unknown payment is 404 PAYMENT_NOT_FOUND backed by a decision, replayed as 404") {
            val pay = AdminOpsTestClient.newPaymentId()
            val b = body(null, paymentId = pay, amountMinor = 1000, currency = "KRW")
            val apr = AdminOpsTestClient.approvalIdOf(b)
            api.post(EXECUTE, b).let { it.status shouldBe 404; error(it) shouldBe "PAYMENT_NOT_FOUND" }
            api.post(EXECUTE, b).status shouldBe 404
            decisionOf(apr) shouldBe "REJECTED|PAYMENT_NOT_FOUND|EXECUTION_COMMAND"
            decisions(apr) shouldBe 1
            audits(apr) shouldBe 2
        }
    }

    Given("the same approvalId with different content (derived results — no new decision)") {
        Then("another amount is APPROVAL_CONFLICT, other participants only is APPROVAL_IDENTITY_MISMATCH; the decision row stays STARTED and one audit row each") {
            val a = authorized()
            val apr = newApprovalId()
            api.post(EXECUTE, body(a, approvalId = apr)).status shouldBe 200
            api.post(EXECUTE, body(a, approvalId = apr, amountMinor = 1000)).let { it.status shouldBe 409; error(it) shouldBe "APPROVAL_CONFLICT" }
            api.post(EXECUTE, body(a, approvalId = apr, approvedBy = "adm_01JC00000000000000000000A2")).let {
                it.status shouldBe 409
                error(it) shouldBe "APPROVAL_IDENTITY_MISMATCH"
            }
            api.post(EXECUTE, body(a, approvalId = apr, reason = "another reason text")).let { it.status shouldBe 409; error(it) shouldBe "APPROVAL_CONFLICT" }
            decisionOf(apr) shouldBe "STARTED|-|EXECUTION_COMMAND"
            decisions(apr) shouldBe 1
            audits(apr) shouldBe 3
            cancellations(a) shouldBe 1
        }
    }

    Given("the execution deadline (D-OPS-002)") {
        Then("now >= executeBefore is 409 APPROVAL_EXECUTION_EXPIRED; a window over 24h or not positive is APPROVAL_WINDOW_INVALID") {
            val a = authorized()
            val at = ConfirmFixture.T0.minus(Duration.ofHours(2))
            api.post(EXECUTE, body(a, approvedAt = at, executeBefore = ConfirmFixture.T0)).let { it.status shouldBe 409; error(it) shouldBe "APPROVAL_EXECUTION_EXPIRED" }
            api.post(EXECUTE, body(a, approvedAt = at, executeBefore = at.plus(Duration.ofHours(24)).plusMillis(1))).let { error(it) shouldBe "APPROVAL_WINDOW_INVALID" }
            api.post(EXECUTE, body(a, approvedAt = at, executeBefore = at)).let { error(it) shouldBe "APPROVAL_WINDOW_INVALID" }
            cancellations(a) shouldBe 0
        }
        Then("an already started approval replays after its deadline (existing=true)") {
            val a = authorized()
            val b = body(a)
            api.post(EXECUTE, b).status shouldBe 200
            clock.set(ConfirmFixture.T0.plus(Duration.ofDays(2)))
            api.post(EXECUTE, b).let { it.status shouldBe 200; it.json.get("existing").asBoolean() shouldBe true }
        }
    }

    Given("malformed commands (§4.6 — 400, audit row with placeholders, no decision)") {
        Then("each format category maps to its code and its audit columns") {
            val a = authorized()
            val good = body(a)
            val apr = AdminOpsTestClient.approvalIdOf(good)
            data class Case(val body: String, val code: String, val auditApproval: String, val auditPayment: String, val auditRequester: String)
            val cases = listOf(
                Case(good.replace(apr, "apr_bad"), "APPROVAL_ID_MALFORMED", "MALFORMED", a.paymentId.value, REQUESTER),
                Case(good.replace(a.paymentId.value, "pay_bad"), "PAYMENT_ID_MALFORMED", apr, "MALFORMED", REQUESTER),
                Case(good.replace(REQUESTER, "adm_bad"), "OPERATOR_ID_MALFORMED", apr, a.paymentId.value, "MALFORMED"),
                Case(good.replace(REQUESTER, "adm_bad").replace(apr, "apr_bad"), "REQUEST_MALFORMED", "MALFORMED", a.paymentId.value, "MALFORMED"),
                Case(good.replace("\"currency\":\"KRW\"", "\"currency\":\"krw\""), "REQUEST_MALFORMED", apr, a.paymentId.value, REQUESTER),
            )
            cases.forEach { c ->
                withClue(c.code) {
                    fun rows() = fixture.count(
                        "SELECT COUNT(*) FROM operator_cancel_rejection WHERE approval_id = ? AND payment_id = ? AND requested_by = ? AND rejection_reason = ?",
                        c.auditApproval, c.auditPayment, c.auditRequester, c.code,
                    )
                    val before = rows()
                    val r = api.post(EXECUTE, c.body)
                    r.status shouldBe 400
                    error(r) shouldBe c.code
                    rows() shouldBe before + 1
                }
            }
            decisions(apr) shouldBe 0
            cancellations(a) shouldBe 0
        }
        Then("strict JSON (L-1): duplicate key, trailing token, string amount, decimal amount, null, unknown field, missing field, leading space in reference are 400 REQUEST_MALFORMED") {
            val a = authorized()
            val good = body(a)
            val amount = "\"amountMinor\":${a.money.amountMinor}"
            listOf(
                good.replace("{\"approvalId\"", "{\"amountMinor\":1,\"approvalId\""),
                "$good {}",
                good.replace(amount, "\"amountMinor\":\"${a.money.amountMinor}\""),
                good.replace(amount, "\"amountMinor\":${a.money.amountMinor}.0"),
                good.replace(amount, "\"amountMinor\":null"),
                good.replace("{\"approvalId\"", "{\"extra\":1,\"approvalId\""),
                good.replace(",$amount", ""),
                good.replace("\"reference\":\"RECON-2026-0001\"", "\"reference\":\" RECON-2026-0001\""),
                "not json",
            ).forEach { b ->
                withClue(b.take(60)) {
                    val r = api.post(EXECUTE, b)
                    r.status shouldBe 400
                    error(r) shouldBe "REQUEST_MALFORMED"
                }
            }
            decisions(AdminOpsTestClient.approvalIdOf(good)) shouldBe 0
        }
    }

    Given("the deadline confirmation call (D-OPS-009)") {
        Then("before the deadline: NONE and nothing written; after: REJECTED APPROVAL_EXECUTION_EXPIRED confirmed=true once, then confirmed=false; a late execution replays 409; no cancellation") {
            val a = authorized()
            val b = body(a)
            val apr = AdminOpsTestClient.approvalIdOf(b)
            api.post(deadline(apr), b).let {
                it.status shouldBe 200
                it.body shouldBe """{"decision":"NONE","confirmed":false}"""
            }
            decisions(apr) shouldBe 0
            audits(apr) shouldBe 0
            clock.set(ConfirmFixture.T0.plus(Duration.ofDays(1)))
            api.post(deadline(apr), b).let {
                it.status shouldBe 200
                it.json.get("decision").asString() shouldBe "REJECTED"
                it.json.get("code").asString() shouldBe "APPROVAL_EXECUTION_EXPIRED"
                it.json.get("confirmed").asBoolean() shouldBe true
            }
            decisionOf(apr) shouldBe "REJECTED|APPROVAL_EXECUTION_EXPIRED|DEADLINE_CONFIRMATION"
            audits(apr) shouldBe 1
            api.post(deadline(apr), b).json.get("confirmed").asBoolean() shouldBe false
            api.post(EXECUTE, b).let { it.status shouldBe 409; error(it) shouldBe "APPROVAL_EXECUTION_EXPIRED" }
            decisions(apr) shouldBe 1
            cancellations(a) shouldBe 0
            fixture.count("SELECT COUNT(*) FROM return_reservation WHERE payment_id = ?", a.paymentId.value) shouldBe 0
        }
        Then("a started approval answers STARTED confirmed=false; a payment that does not exist still records EXPIRED; path and body approvalId must match (400)") {
            val a = authorized()
            val b = body(a)
            val apr = AdminOpsTestClient.approvalIdOf(b)
            api.post(EXECUTE, b).status shouldBe 200
            clock.set(ConfirmFixture.T0.plus(Duration.ofDays(3)))
            api.post(deadline(apr), b).let {
                it.json.get("decision").asString() shouldBe "STARTED"
                it.json.get("confirmed").asBoolean() shouldBe false
            }
            val missing = body(null, paymentId = AdminOpsTestClient.newPaymentId(), amountMinor = 1000, currency = "KRW")
            api.post(deadline(AdminOpsTestClient.approvalIdOf(missing)), missing).json.get("code").asString() shouldBe "APPROVAL_EXECUTION_EXPIRED"
            api.post(deadline(newApprovalId()), b).let { it.status shouldBe 400; error(it) shouldBe "REQUEST_MALFORMED" }
        }
    }

    Given("the target lookup (F-1·F-2·F-3)") {
        Then("an authorized payment shows status, amounts, revision and flags; a payment without authorization is shown not cancellable; unknown and malformed ids are 404") {
            val a = authorized()
            val r = api.get("/internal/v1/admin/payments/${a.paymentId.value}/operator-cancel-target")
            r.status shouldBe 200
            r.json.get("paymentStatus").asString() shouldBe "AUTHORIZED"
            r.json.get("cancellable").asBoolean() shouldBe true
            r.json.get("authorizedMinor").asLong() shouldBe a.money.amountMinor
            r.json.get("cancellableMinor").asLong() shouldBe a.money.amountMinor
            r.json.get("balanceRevision").asLong() shouldBe 0
            r.json.get("onHold").asBoolean() shouldBe false
            r.json.get("inFlightCancel").asBoolean() shouldBe false
            val requested = confirmFixture.prepared(paymentIdValue = AdminOpsTestClient.newPaymentId())
            api.get("/internal/v1/admin/payments/${requested.paymentId.value}/operator-cancel-target").let {
                it.status shouldBe 200
                it.json.get("paymentStatus").asString() shouldBe "REQUESTED"
                it.json.get("cancellable").asBoolean() shouldBe false
                it.json.get("cancellableMinor").asLong() shouldBe 0
            }
            api.get("/internal/v1/admin/payments/${AdminOpsTestClient.newPaymentId()}/operator-cancel-target").status shouldBe 404
            api.get("/internal/v1/admin/payments/pay_x/operator-cancel-target").status shouldBe 404
        }
    }

    Given("concurrency on real connections (UNIQUE(approval_id) is the authority)") {
        Then("the same approvalId and bytes at once: one decision, one cancellation, one fresh and one replayed 200") {
            val a = authorized()
            val b = body(a)
            val apr = AdminOpsTestClient.approvalIdOf(b)
            val barrier = CyclicBarrier(2)
            val passes = AtomicInteger()
            // 두 TX가 비잠금 결정 조회(빈 결과)를 모두 지난 뒤 진행 — 잠금 뒤 재조회·UNIQUE·재시도 경로를 지나게 한다.
            faults.after(FaultPoint.OPERATOR_DECISION_FIND) { found -> if (found == null && passes.incrementAndGet() <= 2) barrier.await(10, TimeUnit.SECONDS) }
            val pool = Executors.newFixedThreadPool(2)
            val results = (1..2).map { pool.submit<AdminOpsTestClient.Response> { api.post(EXECUTE, b) } }.map { it.get(60, TimeUnit.SECONDS) }
            pool.shutdown()
            results.map { it.status } shouldBe listOf(200, 200)
            results.map { it.json.get("existing").asBoolean() }.sorted() shouldBe listOf(false, true)
            results.map { it.json.get("cancellationId").asString() }.toSet().size shouldBe 1
            decisions(apr) shouldBe 1
            cancellations(a) shouldBe 1
        }
        Then("the same approvalId on two payments at once: one decision; the other is 409 APPROVAL_CONFLICT") {
            val a = authorized()
            val c = authorized()
            val apr = newApprovalId()
            val barrier = CyclicBarrier(2)
            val passes = AtomicInteger()
            faults.after(FaultPoint.OPERATOR_DECISION_FIND) { found -> if (found == null && passes.incrementAndGet() <= 2) barrier.await(10, TimeUnit.SECONDS) }
            val pool = Executors.newFixedThreadPool(2)
            val results = listOf(a, c).map { p -> pool.submit<AdminOpsTestClient.Response> { api.post(EXECUTE, body(p, approvalId = apr)) } }.map { it.get(60, TimeUnit.SECONDS) }
            pool.shutdown()
            results.map { it.status }.sorted() shouldBe listOf(200, 409)
            error(results.single { it.status == 409 }) shouldBe "APPROVAL_CONFLICT"
            decisions(apr) shouldBe 1
            (cancellations(a) + cancellations(c)) shouldBe 1
        }
    }

    // review M-1: 같은 Payment·같은 approvalId·다른 내용. 뒤 TX는 앞 TX의 잠금을 기다렸다가 **잠금 뒤 결정 재조회**로 커밋된 결정을 보고 파생 거절해야 한다(재조회가 없으면 옛 행 호환
    // 경로 compareRequest가 Payment·금액만 비교해 200 existing:true로 잘못 재생한다). 순서는 장벽으로 강제: 앞 TX가 잠금을 쥔 채 멈춘 동안 뒤 TX가 비잠금 결정 조회(빈 결과)를 지나 잠금 대기.
    Given("the same approvalId and payment with different content, serialized by the payment lock") {
        fun race(first: String, second: String, holdPoint: FaultPoint): Pair<AdminOpsTestClient.Response, AdminOpsTestClient.Response> {
            val held = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val secondPassedFind = java.util.concurrent.CountDownLatch(1)
            val holdOnce = java.util.concurrent.atomic.AtomicBoolean(true)
            val firstStarted = java.util.concurrent.atomic.AtomicBoolean(false)
            faults.after(holdPoint) { if (holdOnce.compareAndSet(true, false)) { held.countDown(); release.await(30, TimeUnit.SECONDS) } }
            faults.after(FaultPoint.OPERATOR_DECISION_FIND) { found -> if (firstStarted.get() && found == null) secondPassedFind.countDown() }
            val pool = Executors.newFixedThreadPool(2)
            val f1 = pool.submit<AdminOpsTestClient.Response> { api.post(EXECUTE, first) }
            held.await(30, TimeUnit.SECONDS) shouldBe true
            firstStarted.set(true)
            val f2 = pool.submit<AdminOpsTestClient.Response> { api.post(EXECUTE, second) }
            secondPassedFind.await(30, TimeUnit.SECONDS) shouldBe true
            Thread.sleep(500) // 뒤 TX가 control 잠금 대기에 들어갈 시간
            release.countDown()
            val r1 = f1.get(60, TimeUnit.SECONDS)
            val r2 = f2.get(60, TimeUnit.SECONDS)
            pool.shutdown()
            return r1 to r2
        }
        Then("(a) the valid start commits first; the stale command waiting on the lock is 409 APPROVAL_CONFLICT; one decision, one cancellation") {
            val a = authorized()
            val apr = newApprovalId()
            val (valid, stale) = race(body(a, approvalId = apr), body(a, approvalId = apr, revision = 7), FaultPoint.CANCELLATION_INSERT)
            valid.status shouldBe 200
            valid.json.get("existing").asBoolean() shouldBe false
            stale.status shouldBe 409
            error(stale) shouldBe "APPROVAL_CONFLICT"
            decisionOf(apr) shouldBe "STARTED|-|EXECUTION_COMMAND"
            decisions(apr) shouldBe 1
            cancellations(a) shouldBe 1
        }
        Then("(b) the stale command is rejected first; the valid start waiting on the lock is 409 APPROVAL_CONFLICT; one REJECTED decision, no cancellation") {
            val a = authorized()
            val apr = newApprovalId()
            val (stale, valid) = race(body(a, approvalId = apr, revision = 7), body(a, approvalId = apr), FaultPoint.OPERATOR_REJECTION_APPEND)
            stale.status shouldBe 409
            error(stale) shouldBe "OPERATOR_TARGET_STALE"
            valid.status shouldBe 409
            error(valid) shouldBe "APPROVAL_CONFLICT"
            decisionOf(apr) shouldBe "REJECTED|OPERATOR_TARGET_STALE|EXECUTION_COMMAND"
            decisions(apr) shouldBe 1
            cancellations(a) shouldBe 0
        }
    }

    Given("failures inside TX1 (no decision means nothing committed)") {
        Then("a decision INSERT that fails (injected 1062 on every attempt) rolls back the whole TX: 503, no cancellation, reservation, schedule, decision or audit; a resend then starts") {
            val a = authorized()
            val b = body(a)
            val apr = AdminOpsTestClient.approvalIdOf(b)
            faults.after(FaultPoint.OPERATOR_DECISION_APPEND) { throw PersistenceFailure.UniqueConflict(StoreConstraint.OTHER) }
            api.post(EXECUTE, b).let { it.status shouldBe 503; error(it) shouldBe "TEMPORARILY_UNAVAILABLE" }
            cancellations(a) shouldBe 0
            fixture.count("SELECT COUNT(*) FROM return_reservation WHERE payment_id = ?", a.paymentId.value) shouldBe 0
            fixture.count("SELECT COUNT(*) FROM cancellation_recovery_schedule WHERE payment_id = ?", a.paymentId.value) shouldBe 0
            decisions(apr) shouldBe 0
            audits(apr) shouldBe 0
            faults.reset()
            api.post(EXECUTE, b).let { it.status shouldBe 200; it.json.get("existing").asBoolean() shouldBe false }
        }
        Then("a connection lost at commit is 503 OUTCOME_UNKNOWN; the resend converges on the real state") {
            val a = authorized()
            val b = body(a)
            val apr = AdminOpsTestClient.approvalIdOf(b)
            val armed = java.util.concurrent.atomic.AtomicBoolean(true)
            faults.after(FaultPoint.OPERATOR_DECISION_APPEND) {
                if (armed.compareAndSet(true, false)) {
                    val connection = DataSourceUtils.getConnection(dataSource)
                    val id = connection.createStatement().use { st -> st.executeQuery("SELECT CONNECTION_ID()").use { rs -> rs.next(); rs.getLong(1) } }
                    DataSourceUtils.releaseConnection(connection, dataSource)
                    db.withRoot { c -> c.createStatement().use { it.execute("KILL CONNECTION $id") } }
                }
            }
            val r = api.post(EXECUTE, b)
            r.status shouldBe 503
            // review L-4: 결정 INSERT 뒤 블록이 끝나고 남은 쓰기가 없어 끊긴 연결의 첫 실패는 commit이다 → CommitOutcomeUnknown → 항상 OUTCOME_UNKNOWN.
            error(r) shouldBe "OUTCOME_UNKNOWN"
            faults.reset()
            val again = api.post(EXECUTE, b)
            again.status shouldBe 200
            decisions(apr) shouldBe 1
            cancellations(a) shouldBe 1
        }
        Then("a response lost because the client closed the connection: the resend replays the committed decision (existing=true)") {
            val a = authorized()
            val b = body(a)
            val apr = AdminOpsTestClient.approvalIdOf(b)
            val bytes = b.toByteArray(Charsets.UTF_8)
            Socket("localhost", internal.localPort).use { s ->
                val head = "POST $EXECUTE HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n" +
                    "${AdminCallSignature.HEADER}: ${AdminChannelFixture.header("POST", EXECUTE, bytes)}\r\nConnection: close\r\n\r\n"
                s.getOutputStream().write(head.toByteArray(Charsets.US_ASCII) + bytes)
                s.getOutputStream().flush()
            }
            val deadlineAt = System.nanoTime() + Duration.ofSeconds(20).toNanos()
            while (decisions(apr) == 0L && System.nanoTime() < deadlineAt) Thread.sleep(100)
            decisions(apr) shouldBe 1
            api.post(EXECUTE, b).let { it.status shouldBe 200; it.json.get("existing").asBoolean() shouldBe true }
            cancellations(a) shouldBe 1
        }
    }
})

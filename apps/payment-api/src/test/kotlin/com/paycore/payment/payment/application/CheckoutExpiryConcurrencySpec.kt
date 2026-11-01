package com.paycore.payment.payment.application

import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.adapter.web.ApiTestTimes
import com.paycore.payment.payment.adapter.web.MutablePaymentClock
import com.paycore.payment.payment.adapter.web.PaymentApiTest
import com.paycore.payment.payment.application.CheckoutExpiryFlow.Companion.FAIL
import com.paycore.payment.payment.application.CheckoutExpiryFlow.Companion.OK
import com.paycore.payment.payment.application.CheckoutExpiryFlow.Companion.WINDOW_END
import com.paycore.payment.payment.application.CreatePaymentFixture.command
import com.paycore.payment.payment.application.CreatePaymentFixture.concurrently
import com.paycore.payment.payment.application.CreatePaymentFixture.demoActor
import com.paycore.payment.payment.application.provided.CheckoutFailCode
import com.paycore.payment.payment.application.provided.CheckoutTokenRejection
import com.paycore.payment.payment.application.provided.CompleteCardAuthenticationCommand
import com.paycore.payment.payment.application.provided.CompleteCardAuthenticationResult
import com.paycore.payment.payment.application.provided.CompleteCardAuthenticationUseCase
import com.paycore.payment.payment.application.provided.CreatePaymentOutcome
import com.paycore.payment.payment.application.provided.CreatePaymentResult
import com.paycore.payment.payment.application.provided.CreatePaymentUseCase
import com.paycore.payment.payment.application.provided.ExpireElapsedRequestedPaymentsCommand
import com.paycore.payment.payment.application.provided.ExpireElapsedRequestedPaymentsUseCase
import com.paycore.payment.payment.application.provided.IssueCheckoutTokenCommand
import com.paycore.payment.payment.application.provided.IssueCheckoutTokenResult
import com.paycore.payment.payment.application.provided.IssueCheckoutTokenUseCase
import com.paycore.payment.payment.application.provided.MerchantAuthentication
import com.paycore.payment.payment.application.provided.OpenCheckoutSessionCommand
import com.paycore.payment.payment.application.provided.OpenCheckoutSessionResult
import com.paycore.payment.payment.application.provided.OpenCheckoutSessionUseCase
import com.paycore.payment.payment.application.provided.StartCardAuthenticationCommand
import com.paycore.payment.payment.application.provided.StartCardAuthenticationResult
import com.paycore.payment.payment.application.provided.StartCardAuthenticationUseCase
import com.paycore.payment.payment.domain.OrderKey
import com.paycore.payment.payment.domain.PaymentId
import com.paycore.payment.payment.domain.ProviderResultLookup
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Duration
import java.util.UUID

/**
 * 결제창 만료 경합(AUTHN-001 §14 C7·C11~C16, D-AUTHN-008) — 실 MySQL 다중 연결, 시험 시계(API 시험 구성 — 모든 스레드가 같은 시계를 본다). control을 쥔 경로(주기 작업·
 * 토큰 발급·CreatePayment)만 Payment를 만료하고 직렬화 지점은 control(X) → payment(X)다: 어느 쪽이 먼저든 Payment EXPIRED 1회, 종료 근거 1행, PaymentExpired 정확히
 * 1건이고 진 쪽은 NoChange(1062·결함 없음). control 미보유 경로(교환·인증 시작·복귀)는 Payment를 바꾸지 않는다.
 */
@PaymentApiTest
class CheckoutExpiryConcurrencySpec(
    private val job: ExpireElapsedRequestedPaymentsUseCase,
    private val create: CreatePaymentUseCase,
    private val issue: IssueCheckoutTokenUseCase,
    private val open: OpenCheckoutSessionUseCase,
    private val start: StartCardAuthenticationUseCase,
    private val complete: CompleteCardAuthenticationUseCase,
    private val cancel: com.paycore.payment.payment.application.provided.CancelCheckoutUseCase,
    private val institution: ScriptedCardAuthenticationPort,
    private val clock: MutablePaymentClock,
    private val db: DbAccess,
) : BehaviorSpec({

    afterEach {
        clock.reset()
        institution.beforeLookup = {}
    }

    val flow = CheckoutExpiryFlow(create, issue, open, db)

    fun runJob() = job.expireElapsed(ExpireElapsedRequestedPaymentsCommand(UUID.randomUUID().toString(), 50))

    fun issueRaw(order: OrderKey, id: PaymentId) =
        issue.issue(IssueCheckoutTokenCommand(MerchantAuthentication(demoActor, order.merchantAccountId, order.environment), id.value, OK, FAIL, "req-race"))

    /** 한 번만 만료됐다(D-AUTHN-008·C16): EXPIRED, 종료 근거 1행, PaymentExpired 1건. */
    fun expiredOnce(id: PaymentId) {
        flow.status(id) shouldBe "EXPIRED"
        flow.evidence(id) shouldBe listOf("NOT_TRANSMITTED_AND_SESSION_DISCARDED")
        flow.expiredEvents(id).size shouldBe 1
    }

    /**
     * 경합 회차(검토 #4 n-4): 0회차는 동시, k(≥1)회차는 (k-1)번째 작업을 먼저 끝낸 뒤 나머지를 동시에 — 동시 실행이 한쪽 순서로만 끝나도 두 순서가 모두 나온다.
     */
    fun <T> race(round: Int, n: Int, task: (Int) -> T): List<T> {
        if (round == 0) return concurrently(n) { task(it) }.map { it.getOrThrow() }
        val lead = (round - 1) % n
        val first = task(lead)
        val rest = concurrently(n - 1) { j -> task(if (j < lead) j else j + 1) }.map { it.getOrThrow() }
        return listOf(first) + rest
    }

    /** PaymentExpired의 causationId로 만료를 실행한 경로를 가린다(주기 작업 `checkout-expiry-job:` / 토큰 발급 requestId / CreatePayment 멱등 기록 UUID). */
    fun winnerOf(id: PaymentId): String {
        val causation = flow.expiredEvents(id).single()["causation_id"].toString()
        return when {
            causation.startsWith("checkout-expiry-job:") -> "job"
            causation == "req-race" -> "issuance"
            else -> "create-payment"
        }
    }

    fun startOn(c: CheckoutExpiryFlow.Opened) = start.start(StartCardAuthenticationCommand(c.handle, c.secret, "SIMISSA", "LUMP_SUM", "req-race"))

    fun returnOf(c: CheckoutExpiryFlow.Opened): Pair<String, String> {
        val reg = institution.registrations.last { it.binding.paymentId == c.paymentId }
        val ref = db.withApp { cn -> with(db) { cn.scalar("SELECT provider_transaction_ref FROM authentication_session WHERE payment_id = ? AND status = 'IN_PROGRESS'", c.paymentId.value) } } as String
        val resultRef = ScriptedCardAuthenticationPort.newResultRef()
        val result = institution.authenticated(reg, ref)
        institution.lookupFor(resultRef) { ProviderResultLookup.Found(result) }
        return resultRef to reg.state
    }

    fun finish(c: CheckoutExpiryFlow.Opened, resultRef: String, state: String) =
        complete.complete(CompleteCardAuthenticationCommand(c.handle, c.secret, mapOf("resultRef" to listOf(resultRef), "state" to listOf(state)), "req-race"))

    Given("C7: a return in flight versus the expiry job") {
        Then("expiry wins: the job expires the payment, its session and checkout session while the lookup runs; TX-C re-judges under the lock and nothing is verified") {
            val c = flow.opened()
            startOn(c).shouldBeInstanceOf<StartCardAuthenticationResult.Redirect>()
            val (resultRef, state) = returnOf(c)
            institution.beforeLookup = { _ ->
                clock.set(WINDOW_END)
                runJob()
            }
            // §6.3·§14 C7(검토 #4 m-1): 만료가 이기면 410 + failUrl(code=CHECKOUT_EXPIRED) — 가맹점으로 돌아갈 링크가 있어야 한다. VERIFIED는 없다.
            finish(c, resultRef, state) shouldBe
                CompleteCardAuthenticationResult.CheckoutClosed(CheckoutFailCode.CHECKOUT_EXPIRED, "$FAIL?paymentId=${c.paymentId.value}&code=CHECKOUT_EXPIRED")
            expiredOnce(c.paymentId)
            flow.authSessions(c.paymentId) shouldBe listOf("EXPIRED" to "PAYMENT_CLOSED")
            db.withApp { cn -> with(db) { cn.scalar("SELECT COUNT(*) FROM authentication_session WHERE payment_id = ? AND authentication_ref IS NOT NULL", c.paymentId.value) } }
                .toString() shouldBe "0"
        }

        Then("the return wins: VERIFIED first, then the job expires the payment and the unbound VERIFIED session (evidence kept, H-4)") {
            val c = flow.opened()
            startOn(c)
            val (resultRef, state) = returnOf(c)
            finish(c, resultRef, state).shouldBeInstanceOf<CompleteCardAuthenticationResult.Verified>()
            clock.set(WINDOW_END)
            runJob()
            expiredOnce(c.paymentId)
            flow.authSessions(c.paymentId) shouldBe listOf("EXPIRED" to "PAYMENT_CLOSED")
            db.withApp { cn -> with(db) { cn.rows("SELECT evidence_ref, authentication_ref FROM authentication_session WHERE payment_id = ?", c.paymentId.value) } }
                .single().values.all { it != null } shouldBe true
            flow.checkoutSessions(c.paymentId) shouldBe listOf("COMPLETED" to "VERIFIED_REDIRECT")
        }
    }

    Given("C11: token exchange versus the expiry job") {
        Then("exchange first: its checkout session is closed EXPIRED(PAYMENT_CLOSED) by the job; job first: the unexchanged token is CLOSED and exchange is refused; the exchange never changes the payment") {
            val (o1, p1) = flow.payment()
            clock.set(ApiTestTimes.T0.plus(Duration.ofMinutes(25)))
            val t1 = flow.token(o1, p1)
            open.open(OpenCheckoutSessionCommand(t1, "req-race")).shouldBeInstanceOf<OpenCheckoutSessionResult.Opened>()
            clock.set(WINDOW_END)
            runJob()
            expiredOnce(p1)
            flow.checkoutSessions(p1) shouldBe listOf("EXPIRED" to "PAYMENT_CLOSED")
            flow.tokenStatuses(p1) shouldBe listOf("CONSUMED")

            clock.reset()
            val (o2, p2) = flow.payment()
            clock.set(ApiTestTimes.T0.plus(Duration.ofMinutes(25)))
            val t2 = flow.token(o2, p2)
            clock.set(WINDOW_END)
            runJob()
            open.open(OpenCheckoutSessionCommand(t2, "req-race")) shouldBe OpenCheckoutSessionResult.InvalidToken
            expiredOnce(p2)
            flow.tokenStatuses(p2) shouldBe listOf("CLOSED")
            flow.checkoutSessions(p2) shouldBe emptyList()
        }
    }

    Given("C12: token issuance versus the expiry job at the window end") {
        Then("whichever wins, the payment expires once, issuance answers PAYMENT_TERMINATED and no token stays ISSUED") {
            val winners = mutableSetOf<String>()
            repeat(3) { round ->
                withClue("round $round") {
                    clock.reset()
                    val (order, id) = flow.payment()
                    clock.set(WINDOW_END)
                    val results = race(round, 2) { i -> if (i == 0) issueRaw(order, id) else runJob() }
                    results.filterIsInstance<IssueCheckoutTokenResult>().single() shouldBe IssueCheckoutTokenResult.Rejected(CheckoutTokenRejection.PAYMENT_TERMINATED)
                    expiredOnce(id)
                    winners += winnerOf(id).also { println("C12 round $round winner=$it") } // 검토 #4 n-4: 어느 쪽이 이겼는지 증거에 남긴다
                    flow.tokenStatuses(id).none { it == "ISSUED" } shouldBe true
                }
            }
            winners shouldBe setOf("issuance", "job") // 검토 #4 n-4: 두 순서가 모두 나왔다
        }
    }

    Given("C13: two job runs concurrently (multiple instances)") {
        Then("each elapsed payment expires once with one PaymentExpired; the other run sees no change") {
            val ids = (1..4).map { flow.payment().second }
            clock.set(WINDOW_END)
            concurrently(2) { runJob() }.forEach { it.getOrThrow() }
            ids.forEach { expiredOnce(it) }
        }
    }

    Given("C14: CreatePayment (other key, same order) versus the expiry job") {
        Then("whichever wins, the old payment expires once and exactly one generation-2 payment exists; the API result stays CREATED") {
            val winners = mutableSetOf<String>()
            repeat(3) { round ->
                withClue("round $round") {
                    clock.reset()
                    val (order, id) = flow.payment()
                    val again = command(order, at = WINDOW_END)
                    clock.set(WINDOW_END)
                    val results = race(round, 2) { i -> if (i == 0) create.create(again) else runJob() }
                    val created = results.filterIsInstance<CreatePaymentResult>().single().shouldBeInstanceOf<CreatePaymentResult.Accepted>()
                    created.outcome shouldBe CreatePaymentOutcome.CREATED
                    expiredOnce(id)
                    winners += winnerOf(id).also { println("C14 round $round winner=$it") }
                    db.withApp { cn -> with(db) { cn.rows("SELECT slot_generation, status FROM payment WHERE merchant_account_id = ? ORDER BY slot_generation", order.merchantAccountId.value) } }
                        .map { it["slot_generation"].toString() to it["status"] } shouldBe listOf("1" to "EXPIRED", "2" to "REQUESTED")
                }
            }
            winners shouldBe setOf("create-payment", "job")
        }
    }

    Given("C15: authentication start (TX-A) versus the expiry job") {
        Then("concurrently at the window end: the payment expires once, no session stays open, and start is refused") {
            val winners = mutableSetOf<String>()
            repeat(3) { round ->
                withClue("round $round") {
                    clock.reset()
                    val c = flow.opened()
                    clock.set(WINDOW_END)
                    val results = race(round, 2) { i -> if (i == 0) startOn(c) else runJob() }
                    val started = results.filterIsInstance<StartCardAuthenticationResult>().single()
                    started::class shouldBeIn listOf(
                        StartCardAuthenticationResult.CheckoutTimeOver::class, StartCardAuthenticationResult.SessionExpired::class,
                        StartCardAuthenticationResult.PaymentNotAvailable::class,
                    )
                    expiredOnce(c.paymentId)
                    flow.authSessions(c.paymentId).none { it.first == "CREATED" || it.first == "IN_PROGRESS" } shouldBe true
                    // 순서 표지: 결제창 세션(T0 + 20분)은 창 끝(T0 + 30분)에 이미 TTL이 지났다. 시작이 먼저 잡으면 EXPIRED(TTL_ELAPSED)로 닫고, 작업이 먼저면 EXPIRED(PAYMENT_CLOSED).
                    val closedBy = flow.checkoutSessions(c.paymentId).single().second
                    winners += (if (closedBy == "TTL_ELAPSED") "start" else "job").also { println("C15 round $round winner=$it start=${started::class.simpleName} checkoutSession=$closedBy") }
                }
            }
            winners shouldBe setOf("start", "job")
        }

        Then("start wins TX-A, then the job expires everything before TX-B: TX-B changes nothing and answers that the payment is no longer available") {
            val c = flow.opened()
            clock.set(ApiTestTimes.T0.plus(Duration.ofMinutes(19))) // 결제창 세션(T0 + 20분) 안
            institution.registerFor(c.order.merchantAccountId.value) { _ ->
                clock.set(WINDOW_END)
                runJob()
                ScriptedCardAuthenticationPort.registered()
            }
            val r = startOn(c).shouldBeInstanceOf<StartCardAuthenticationResult.PaymentNotAvailable>()
            r.failRedirect shouldBe "$FAIL?paymentId=${c.paymentId.value}&code=PAYMENT_NOT_AVAILABLE"
            expiredOnce(c.paymentId)
            flow.authSessions(c.paymentId) shouldBe listOf("EXPIRED" to "PAYMENT_CLOSED")
            db.withApp { cn -> with(db) { cn.scalar("SELECT COUNT(*) FROM authentication_session WHERE payment_id = ? AND provider_transaction_ref IS NOT NULL", c.paymentId.value) } }
                .toString() shouldBe "0"
        }
    }

    Given("a start on a checkout the expiry job already closed (review #4 m-1 follow-on, Coordinator 2026-10-17)") {
        Then("410 CheckoutTimeOver with the fail link (CHECKOUT_EXPIRED), no registration call and no state change — not 401") {
            val c = flow.opened()
            clock.set(WINDOW_END)
            runJob()
            flow.checkoutSessions(c.paymentId) shouldBe listOf("EXPIRED" to "PAYMENT_CLOSED")
            val calls = institution.calls.size
            repeat(2) {
                startOn(c) shouldBe StartCardAuthenticationResult.CheckoutTimeOver("$FAIL?paymentId=${c.paymentId.value}&code=CHECKOUT_EXPIRED")
            }
            institution.calls.size shouldBe calls
            flow.checkoutSessions(c.paymentId) shouldBe listOf("EXPIRED" to "PAYMENT_CLOSED")
            flow.authSessions(c.paymentId) shouldBe emptyList()
            expiredOnce(c.paymentId)
        }
    }

    Given("a cancel on a checkout the expiry job already closed (review #4 m-1 follow-on, Coordinator 2026-10-17)") {
        Then("410 CheckoutTimeOver with the fail link (CHECKOUT_EXPIRED); the job's EXPIRED(PAYMENT_CLOSED) stays, no USER_CANCELLED and no cancel event") {
            val c = flow.opened()
            startOn(c).shouldBeInstanceOf<StartCardAuthenticationResult.Redirect>()
            clock.set(WINDOW_END)
            runJob()
            fun cancelEvents() = db.withApp { cn -> with(db) { cn.scalar("SELECT COUNT(*) FROM authentication_session_event e JOIN authentication_session s ON s.authentication_session_id = e.authentication_session_id WHERE s.payment_id = ? AND e.kind = 'FAILED'", c.paymentId.value) } }.toString()
            repeat(2) {
                cancel.cancel(com.paycore.payment.payment.application.provided.CancelCheckoutCommand(c.handle, c.secret, "req-cancel")) shouldBe
                    com.paycore.payment.payment.application.provided.CancelCheckoutResult.CheckoutTimeOver("$FAIL?paymentId=${c.paymentId.value}&code=CHECKOUT_EXPIRED")
            }
            flow.checkoutSessions(c.paymentId) shouldBe listOf("EXPIRED" to "PAYMENT_CLOSED")
            flow.authSessions(c.paymentId) shouldBe listOf("EXPIRED" to "PAYMENT_CLOSED")
            cancelEvents() shouldBe "0"
            expiredOnce(c.paymentId)
        }
    }

    Given("C16: every control-holding expiry path at once (two job runs, token issuance, CreatePayment)") {
        Then("PaymentExpired exactly once, one termination evidence row, the losers see no change (no 1062, no defect)") {
            val winners = mutableSetOf<String>()
            repeat(5) { round -> // 0회차 동시, 1·2회차 작업 먼저, 3회차 토큰 발급 먼저, 4회차 CreatePayment 먼저
                withClue("round $round") {
                    clock.reset()
                    val (order, id) = flow.payment()
                    val again = command(order, at = WINDOW_END)
                    clock.set(WINDOW_END)
                    race(round, 4) { i ->
                        when (i) {
                            0, 1 -> runJob()
                            2 -> issueRaw(order, id)
                            else -> create.create(again)
                        }
                    }
                    expiredOnce(id)
                    winners += winnerOf(id).also { println("C16 round $round winner=$it") }
                }
            }
            winners shouldBe setOf("job", "issuance", "create-payment")
        }
    }
})

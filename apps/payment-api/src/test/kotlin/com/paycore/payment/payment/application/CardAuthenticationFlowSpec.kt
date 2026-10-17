package com.paycore.payment.payment.application

import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.adapter.persistence.FaultInjector
import com.paycore.payment.payment.adapter.persistence.FaultPoint
import com.paycore.payment.payment.adapter.persistence.PaymentDbTest
import com.paycore.payment.payment.adapter.persistence.PersistenceTestData.newOrder
import com.paycore.payment.payment.application.CreatePaymentFixture.command
import com.paycore.payment.payment.application.CreatePaymentFixture.concurrently
import com.paycore.payment.payment.application.CreatePaymentFixture.demoActor
import com.paycore.payment.payment.application.provided.CancelCheckoutCommand
import com.paycore.payment.payment.application.provided.CancelCheckoutResult
import com.paycore.payment.payment.application.provided.CancelCheckoutUseCase
import com.paycore.payment.payment.application.provided.CheckoutFailCode
import com.paycore.payment.payment.application.provided.CompleteCardAuthenticationCommand
import com.paycore.payment.payment.application.provided.CompleteCardAuthenticationResult
import com.paycore.payment.payment.application.provided.CompleteCardAuthenticationUseCase
import com.paycore.payment.payment.application.provided.CreatePaymentResult
import com.paycore.payment.payment.application.provided.CreatePaymentUseCase
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
import com.paycore.payment.payment.application.required.CardAuthenticationRegistration
import com.paycore.payment.payment.application.required.PaymentRepository
import com.paycore.payment.payment.application.required.RegistrationFailure
import com.paycore.payment.payment.application.required.RegistrationResult
import com.paycore.payment.payment.domain.LookupFailure
import com.paycore.payment.payment.domain.OrderKey
import com.paycore.payment.payment.domain.PaymentId
import com.paycore.payment.payment.domain.PaymentPlan
import com.paycore.payment.payment.domain.ProviderOutcome
import com.paycore.payment.payment.domain.ProviderResultLookup
import com.paycore.payment.payment.domain.SecretDigest
import com.paycore.payment.payment.domain.SecretKind
import com.paycore.payment.payment.domain.TerminationEvidence
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 인증 시작(TX-A → 등록(TX 밖) → TX-B)·결과 반영(세션 선택 → 1단계 → 조회(TX 밖) → TX-C)·결제창 취소(AUTHN-001 §4.1·§6·§7.3·§7.4, D-AUTHN-003) — 실 MySQL, 기관은
 * 시험 대역([ScriptedCardAuthenticationPort] — 등록·조회 응답을 시험이 정하고 호출 시 TX 여부를 기록). 경합 C3~C6·C10, 장애 주입 rollback, 재조회 수렴(Recoverability).
 * 인증 성공 ≠ 승인 성공: 전 흐름 뒤 Payment는 REQUESTED이고 Outbox는 PaymentRequested 1건뿐이다.
 */
@PaymentDbTest
class CardAuthenticationFlowSpec(
    private val start: StartCardAuthenticationUseCase,
    private val complete: CompleteCardAuthenticationUseCase,
    private val cancel: CancelCheckoutUseCase,
    private val open: OpenCheckoutSessionUseCase,
    private val issue: IssueCheckoutTokenUseCase,
    private val create: CreatePaymentUseCase,
    private val payments: PaymentRepository,
    private val institution: ScriptedCardAuthenticationPort,
    private val faults: FaultInjector,
    private val transactionManager: PlatformTransactionManager,
    private val db: DbAccess,
) : BehaviorSpec({

    afterEach {
        faults.reset()
        institution.beforeLookup = {}
    }

    val tx = TransactionTemplate(transactionManager)
    val okUrl = "https://shop.example.test/pay/ok?o=1"
    val failUrl = "https://shop.example.test/pay/fail"

    data class Checkout(val order: OrderKey, val paymentId: PaymentId, val handle: String, val secret: String)

    fun now(): Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS)

    fun checkout(amount: Long = 10_000): Checkout {
        val order = newOrder()
        val intent = com.paycore.payment.payment.adapter.persistence.PersistenceTestData.intent(amount = amount)
        val id = create.create(command(order, intent = intent, at = now())).shouldBeInstanceOf<CreatePaymentResult.Accepted>().payment.paymentId
        val token = issue.issue(IssueCheckoutTokenCommand(MerchantAuthentication(demoActor, order.merchantAccountId, order.environment), id.value, okUrl, failUrl, "req-flow"))
            .shouldBeInstanceOf<IssueCheckoutTokenResult.Issued>().checkoutUrl.substringAfter("token=")
        val opened = open.open(OpenCheckoutSessionCommand(token, "req-flow")).shouldBeInstanceOf<OpenCheckoutSessionResult.Opened>()
        return Checkout(order, id, opened.handle, opened.cookieSecret)
    }

    fun begin(c: Checkout, cardCompany: String = "SIMISS-A", plan: String = "LUMP_SUM") =
        start.start(StartCardAuthenticationCommand(c.handle, c.secret, cardCompany, plan, "req-start"))

    fun registrationOf(c: Checkout): CardAuthenticationRegistration = institution.registrations.last { it.binding.paymentId == c.paymentId }

    fun finish(c: Checkout, resultRef: String, state: String, extra: Map<String, List<String>> = emptyMap()) =
        complete.complete(CompleteCardAuthenticationCommand(c.handle, c.secret, mapOf("resultRef" to listOf(resultRef), "state" to listOf(state)) + extra, "req-return"))

    fun sessionsOf(c: Checkout) =
        db.withApp { cn -> with(db) { cn.rows("SELECT * FROM authentication_session WHERE payment_id = ? ORDER BY created_at, authentication_session_id", c.paymentId.value) } }

    fun eventsOf(sessionId: Any?) =
        db.withApp { cn -> with(db) { cn.rows("SELECT kind, detail_code FROM authentication_session_event WHERE authentication_session_id = ? ORDER BY seq", sessionId) } }
            .map { it["kind"].toString() + (it["detail_code"]?.let { d -> ":$d" } ?: "") }

    fun checkoutSessionOf(c: Checkout) = db.withApp { cn -> with(db) { cn.rows("SELECT status, close_reason, expires_at FROM checkout_session WHERE handle = ?", c.handle) } }.single()

    /** 등록을 마친 IN_PROGRESS 세션의 기관 결과(조회 대역에 결과 참조로 등록). */
    fun answer(c: Checkout, outcome: ProviderOutcome = ProviderOutcome.AUTHENTICATED, mutate: (com.paycore.payment.payment.domain.ProviderAuthenticationResult) -> com.paycore.payment.payment.domain.ProviderAuthenticationResult = { it }): String {
        val reg = registrationOf(c)
        val ref = sessionsOf(c).last()["provider_transaction_ref"] as String
        val resultRef = ScriptedCardAuthenticationPort.newResultRef()
        val result = institution.authenticated(reg, ref, outcome = outcome, mutate = mutate)
        institution.lookupFor(resultRef) { ProviderResultLookup.Found(result) }
        return resultRef
    }

    Given("an open checkout session (handle cookie) of a REQUESTED TEST payment") {
        Then("start: TX-A creates the session, registration runs outside any TX, TX-B records the provider transaction; redirect to the configured customer origin") {
            val c = checkout()
            val calls = institution.calls.size
            val redirect = begin(c).shouldBeInstanceOf<StartCardAuthenticationResult.Redirect>()
            redirect.customerUrl shouldMatch Regex("^https://sim\\.example\\.test/sim/authenticate/sat_[A-Za-z0-9_-]{22}$")
            institution.calls.drop(calls).single().let { it.operation shouldBe "register"; it.inTransaction shouldBe false }
            val reg = registrationOf(c)
            reg.state shouldMatch Regex("^[A-Za-z0-9_-]{43}$")
            reg.returnUrl shouldBe "https://checkout.example.test/checkout/s/${c.handle}/authentication/return"
            reg.binding.plan shouldBe PaymentPlan.LumpSum
            reg.binding.cardCompany shouldBe "SIMISS-A"
            reg.binding.money.amountMinor shouldBe 10_000L
            val s = sessionsOf(c).single()
            s["status"] shouldBe "IN_PROGRESS"
            s["provider_transaction_ref"] shouldBe redirect.customerUrl.substringAfterLast('/')
            s["state_digest"] shouldBe SecretDigest.of(SecretKind.AUTHENTICATION_STATE, reg.state).value
            s["profile_id"] shouldBe "sim-auth-demo"
            s["plan_policy_version"] shouldBe "plan-demo-1"
            s["policy_version"] shouldBe "checkout-demo-1"
            // 인증 세션 만료 = min(now + 10분, 결제창 세션 만료) — 결제창 세션은 20분 남아 있다.
            ((s["expires_at"] as LocalDateTime).toInstant(ZoneOffset.UTC) <= (checkoutSessionOf(c)["expires_at"] as LocalDateTime).toInstant(ZoneOffset.UTC)) shouldBe true
            eventsOf(s["authentication_session_id"]) shouldBe listOf("CREATED", "STARTED")
        }

        Then("card companies outside the profile allowlist and plans failing the Demo pre-check are NotAllowed with no session") {
            val c = checkout(amount = 10_000)
            listOf("SIMISS-Z" to "LUMP_SUM", "SIMISS-A" to "INSTALLMENT_2", "SIMISS-A" to "INSTALLMENT_4", "SIMISS-A" to "LUMP", "SIMISS-A" to "INSTALLMENT_x", "" to "LUMP_SUM")
                .forEach { (company, plan) -> withClue("$company/$plan") { begin(c, company, plan) shouldBe StartCardAuthenticationResult.NotAllowed } }
            start.start(StartCardAuthenticationCommand(c.handle, c.secret, null, "LUMP_SUM", "req")) shouldBe StartCardAuthenticationResult.NotAllowed
            sessionsOf(c) shouldBe emptyList()
            val big = checkout(amount = 60_000)
            begin(big, plan = "INSTALLMENT_3").shouldBeInstanceOf<StartCardAuthenticationResult.Redirect>()
            registrationOf(big).binding.plan shouldBe PaymentPlan.Installment(3)
        }

        Then("a missing, wrong or other-handle cookie is SessionExpired for start, return and cancel") {
            val c = checkout()
            val other = checkout()
            listOf(null, "A".repeat(43), other.secret).forEach { secret ->
                withClue(secret?.take(4)) {
                    start.start(StartCardAuthenticationCommand(c.handle, secret, "SIMISS-A", "LUMP_SUM", "req")) shouldBe StartCardAuthenticationResult.SessionExpired
                    complete.complete(CompleteCardAuthenticationCommand(c.handle, secret, mapOf("resultRef" to listOf(ScriptedCardAuthenticationPort.newResultRef()), "state" to listOf("S".repeat(43))), "req")) shouldBe
                        CompleteCardAuthenticationResult.SessionExpired
                    cancel.cancel(CancelCheckoutCommand(c.handle, secret, "req")) shouldBe CancelCheckoutResult.SessionExpired
                }
            }
            sessionsOf(c) shouldBe emptyList()
            checkoutSessionOf(c)["status"] shouldBe "ACTIVE"
        }

        Then("a second start supersedes the first (one open session per payment); the superseded session's late return is Ignored (P1)") {
            val c = checkout()
            begin(c).shouldBeInstanceOf<StartCardAuthenticationResult.Redirect>()
            val firstReg = registrationOf(c)
            begin(c).shouldBeInstanceOf<StartCardAuthenticationResult.Redirect>()
            sessionsOf(c).map { it["status"] to it["expiry_cause"] } shouldBe listOf("EXPIRED" to "SUPERSEDED", "IN_PROGRESS" to null)
            finish(c, ScriptedCardAuthenticationPort.newResultRef(), firstReg.state) shouldBe CompleteCardAuthenticationResult.Ignored
            eventsOf(sessionsOf(c).first()["authentication_session_id"]).last() shouldBe "IGNORED_LATE_RESULT"
        }

        Then("a failed or timed-out registration leaves the session CREATED with one REGISTRATION_FAILED event; its state is rejected as not started (P4)") {
            val c = checkout()
            institution.registerFor(c.order.merchantAccountId.value) { RegistrationResult.Failed(RegistrationFailure.TIMEOUT) }
            begin(c) shouldBe StartCardAuthenticationResult.RegistrationFailed
            val s = sessionsOf(c).single()
            s["status"] shouldBe "CREATED"
            eventsOf(s["authentication_session_id"]) shouldBe listOf("CREATED", "REGISTRATION_FAILED:TIMEOUT")
            finish(c, ScriptedCardAuthenticationPort.newResultRef(), registrationOf(c).state) shouldBe CompleteCardAuthenticationResult.Rejected
            eventsOf(s["authentication_session_id"]).last() shouldBe "REJECTED_RESULT:SESSION_NOT_STARTED"
        }

        Then("C3: a start in another tab between registration and TX-B makes TX-B change nothing and answer Superseded") {
            val c = checkout()
            var raced = false
            institution.registerFor(c.order.merchantAccountId.value) { _ ->
                if (!raced) {
                    raced = true
                    val pool = Executors.newSingleThreadExecutor()
                    try {
                        pool.submit<StartCardAuthenticationResult> { begin(c) }.get(60, TimeUnit.SECONDS).shouldBeInstanceOf<StartCardAuthenticationResult.Redirect>()
                    } finally {
                        pool.shutdownNow()
                    }
                }
                ScriptedCardAuthenticationPort.registered()
            }
            begin(c) shouldBe StartCardAuthenticationResult.Superseded
            sessionsOf(c).map { it["status"] to it["expiry_cause"] } shouldBe listOf("EXPIRED" to "SUPERSEDED", "IN_PROGRESS" to null)
            (sessionsOf(c).first()["provider_transaction_ref"] == null) shouldBe true
        }
    }

    Given("an IN_PROGRESS session and the institution result") {
        Then("VERIFIED (R11): lookup outside any TX, session VERIFIED with evidence and receipt, checkout session COMPLETED(VERIFIED_REDIRECT), redirect to successUrl; payment unchanged") {
            val c = checkout()
            begin(c)
            val resultRef = answer(c)
            val calls = institution.calls.size
            val verified = finish(c, resultRef, registrationOf(c).state).shouldBeInstanceOf<CompleteCardAuthenticationResult.Verified>()
            institution.calls.drop(calls).single().let { it.operation shouldBe "lookup"; it.inTransaction shouldBe false }
            val s = sessionsOf(c).single()
            s["status"] shouldBe "VERIFIED"
            (s["authentication_ref"] as String) shouldMatch Regex("^aref_[A-Za-z0-9_-]{22}$")
            s["result_ref_digest"] shouldBe SecretDigest.of(SecretKind.RESULT_REFERENCE, resultRef).value
            s["card_type"] shouldBe "CREDIT"
            s["issuer_code"] shouldBe "SIMISS-A"
            verified.successRedirect shouldBe "$okUrl&paymentId=${c.paymentId.value}&authenticationRef=${s["authentication_ref"]}"
            checkoutSessionOf(c).let { it["status"] shouldBe "COMPLETED"; it["close_reason"] shouldBe "VERIFIED_REDIRECT" }
            db.withApp { cn -> with(db) { cn.rows("SELECT status FROM payment WHERE payment_id = ?", c.paymentId.value) } }.single()["status"] shouldBe "REQUESTED"
            db.withApp { cn -> with(db) { cn.rows("SELECT event_type FROM outbox_event WHERE aggregate_id = ?", c.paymentId.value) } }.map { it["event_type"] } shouldBe listOf("PaymentRequested")

            // P2 DUPLICATE: 같은 복귀 URL 재요청 → 같은 successUrl, 행은 한 번만(§7.6). P3(R0′): 다른 결과 참조 → 거절, 증거 덮어쓰기 없음.
            finish(c, resultRef, registrationOf(c).state) shouldBe verified
            finish(c, resultRef, registrationOf(c).state) shouldBe verified
            finish(c, answer(c), registrationOf(c).state) shouldBe CompleteCardAuthenticationResult.Rejected
            eventsOf(s["authentication_session_id"]) shouldBe listOf("CREATED", "STARTED", "VERIFIED", "DUPLICATE_RESULT", "REJECTED_RESULT:EVIDENCE_CONFLICT")
            sessionsOf(c).single()["result_ref_digest"] shouldBe SecretDigest.of(SecretKind.RESULT_REFERENCE, resultRef).value
        }

        Then("institution FAILED / CANCELLED / binding mismatch fail the session with the fail URL code; a new start in the same checkout then works") {
            mapOf(
                ProviderOutcome.FAILED to ("AUTHENTICATION_FAILED" to CheckoutFailCode.AUTHENTICATION_FAILED),
                ProviderOutcome.CANCELLED to ("USER_CANCELLED" to CheckoutFailCode.USER_CANCELLED),
            ).forEach { (outcome, expected) ->
                withClue(outcome) {
                    val c = checkout()
                    begin(c)
                    val failed = finish(c, answer(c, outcome), registrationOf(c).state).shouldBeInstanceOf<CompleteCardAuthenticationResult.Failed>()
                    failed.code shouldBe expected.second
                    failed.failRedirect shouldBe "$failUrl?paymentId=${c.paymentId.value}&code=${expected.second.name}"
                    sessionsOf(c).single().let { it["status"] shouldBe "FAILED"; it["failure_reason"] shouldBe expected.first }
                    checkoutSessionOf(c)["status"] shouldBe "ACTIVE"
                    begin(c).shouldBeInstanceOf<StartCardAuthenticationResult.Redirect>()
                }
            }
            val c = checkout()
            begin(c)
            val mismatch = answer(c) { it.copy(binding = it.binding.copy(money = com.paycore.payment.payment.domain.Money(1, it.binding.money.currency))) }
            finish(c, mismatch, registrationOf(c).state).shouldBeInstanceOf<CompleteCardAuthenticationResult.Failed>().code shouldBe CheckoutFailCode.AUTHENTICATION_FAILED
            sessionsOf(c).single()["failure_reason"] shouldBe "BINDING_MISMATCH"
        }

        Then("an unverifiable lookup keeps the session IN_PROGRESS (R3, one LOOKUP_UNVERIFIABLE row); the same return URL later converges to VERIFIED") {
            val c = checkout()
            begin(c)
            val resultRef = ScriptedCardAuthenticationPort.newResultRef()
            institution.lookupFor(resultRef) { ProviderResultLookup.Unverifiable(LookupFailure.TIMEOUT) }
            finish(c, resultRef, registrationOf(c).state) shouldBe CompleteCardAuthenticationResult.Unverifiable
            finish(c, resultRef, registrationOf(c).state) shouldBe CompleteCardAuthenticationResult.Unverifiable
            val s = sessionsOf(c).single()
            s["status"] shouldBe "IN_PROGRESS"
            eventsOf(s["authentication_session_id"]) shouldBe listOf("CREATED", "STARTED", "LOOKUP_UNVERIFIABLE:TIMEOUT")
            val result = institution.authenticated(registrationOf(c), s["provider_transaction_ref"] as String)
            institution.lookupFor(resultRef) { ProviderResultLookup.Found(result) }
            finish(c, resultRef, registrationOf(c).state).shouldBeInstanceOf<CompleteCardAuthenticationResult.Verified>()
        }

        Then("a state that selects no session, unknown query names, duplicates or malformed values are Rejected without calling the institution") {
            val c = checkout()
            begin(c)
            val calls = institution.calls.size
            val state = registrationOf(c).state
            val ref = ScriptedCardAuthenticationPort.newResultRef()
            listOf(
                mapOf("resultRef" to listOf(ref), "state" to listOf("Z".repeat(43))),
                mapOf("resultRef" to listOf(ref), "state" to listOf(state), "x" to listOf("1")),
                mapOf("resultRef" to listOf(ref, ref), "state" to listOf(state)),
                mapOf("resultRef" to listOf("srr_bad"), "state" to listOf(state)),
                mapOf("state" to listOf(state)),
            ).forEach { params ->
                withClue(params.keys) {
                    complete.complete(CompleteCardAuthenticationCommand(c.handle, c.secret, params, "req")) shouldBe CompleteCardAuthenticationResult.Rejected
                }
            }
            institution.calls.size shouldBe calls
            sessionsOf(c).single()["status"] shouldBe "IN_PROGRESS"
        }

        Then("cancel fails the open session (USER_CANCELLED), completes the checkout session (CANCELLED) and redirects to the fail URL") {
            val c = checkout()
            begin(c)
            val cancelled = cancel.cancel(CancelCheckoutCommand(c.handle, c.secret, "req-cancel")).shouldBeInstanceOf<CancelCheckoutResult.Cancelled>()
            cancelled.failRedirect shouldBe "$failUrl?paymentId=${c.paymentId.value}&code=USER_CANCELLED"
            sessionsOf(c).single().let { it["status"] shouldBe "FAILED"; it["failure_reason"] shouldBe "USER_CANCELLED" }
            checkoutSessionOf(c).let { it["status"] shouldBe "COMPLETED"; it["close_reason"] shouldBe "CANCELLED" }
            cancel.cancel(CancelCheckoutCommand(c.handle, c.secret, "req-cancel")) shouldBe CancelCheckoutResult.SessionExpired
        }

        Then("a payment that is no longer REQUESTED closes the checkout session (PAYMENT_NOT_AVAILABLE) on start without changing the payment") {
            val c = checkout()
            tx.executeWithoutResult { payments.lock(c.paymentId)!!.let { p -> p.value.fail(TerminationEvidence.PRE_TRANSMISSION_FAILURE_CONFIRMED); payments.update(p.value, p.version) } }
            val r = begin(c).shouldBeInstanceOf<StartCardAuthenticationResult.PaymentNotAvailable>()
            r.failRedirect shouldBe "$failUrl?paymentId=${c.paymentId.value}&code=PAYMENT_NOT_AVAILABLE"
            checkoutSessionOf(c).let { it["status"] shouldBe "COMPLETED"; it["close_reason"] shouldBe "PAYMENT_NOT_AVAILABLE" }
            sessionsOf(c) shouldBe emptyList()
            // 검토 #4 m-1 후속(Coordinator 2026-10-17): 이미 COMPLETED(PAYMENT_NOT_AVAILABLE)인 결제창의 다음 시작도 401이 아니라 409 + failUrl, 등록 호출·상태 변경 없음.
            val calls = institution.calls.size
            begin(c) shouldBe StartCardAuthenticationResult.PaymentNotAvailable("$failUrl?paymentId=${c.paymentId.value}&code=PAYMENT_NOT_AVAILABLE")
            institution.calls.size shouldBe calls
            checkoutSessionOf(c).let { it["status"] shouldBe "COMPLETED"; it["close_reason"] shouldBe "PAYMENT_NOT_AVAILABLE" }
            sessionsOf(c) shouldBe emptyList()
            // 같은 규칙의 취소(Coordinator 2026-10-17): 409 + failUrl, 결제창 세션 불변(CANCELLED로 바꾸지 않음).
            cancel.cancel(CancelCheckoutCommand(c.handle, c.secret, "req-cancel")) shouldBe
                CancelCheckoutResult.PaymentNotAvailable("$failUrl?paymentId=${c.paymentId.value}&code=PAYMENT_NOT_AVAILABLE")
            checkoutSessionOf(c).let { it["status"] shouldBe "COMPLETED"; it["close_reason"] shouldBe "PAYMENT_NOT_AVAILABLE" }
            sessionsOf(c) shouldBe emptyList()
        }
    }

    Given("concurrent returns and starts on real MySQL connections") {
        Then("C4: the same result reference twice concurrently → one VERIFIED, the other re-judged under the lock as DUPLICATE (same successUrl, one authenticationRef)") {
            val c = checkout()
            begin(c)
            val lookupsBefore = institution.calls.count { it.operation == "lookup" }
            val resultRef = answer(c)
            val state = registrationOf(c).state
            // 검토 #3 n-3: 두 요청이 모두 1단계를 지나 조회에 들어온 뒤에야 TX-C로 간다 → 진 쪽은 반드시 잠금 아래 재판정 경로(P2)로 간다.
            val bothLookedUp = java.util.concurrent.CountDownLatch(2)
            institution.beforeLookup = { _ -> bothLookedUp.countDown(); bothLookedUp.await(30, java.util.concurrent.TimeUnit.SECONDS) shouldBe true }
            val results = concurrently(2) { finish(c, resultRef, state) }.map { it.getOrThrow() }
            institution.calls.count { it.operation == "lookup" } shouldBe lookupsBefore + 2
            results.map { it.shouldBeInstanceOf<CompleteCardAuthenticationResult.Verified>().successRedirect }.toSet().size shouldBe 1
            val s = sessionsOf(c).single()
            eventsOf(s["authentication_session_id"]).count { it == "VERIFIED" } shouldBe 1
            eventsOf(s["authentication_session_id"]).count { it == "DUPLICATE_RESULT" } shouldBe 1
        }

        Then("C5: the same state with two different result references concurrently → exactly one VERIFIED, the other Rejected (no evidence overwrite)") {
            val c = checkout()
            begin(c)
            val state = registrationOf(c).state
            val refs = listOf(answer(c), answer(c))
            val bothLookedUp = java.util.concurrent.CountDownLatch(2)
            institution.beforeLookup = { _ -> bothLookedUp.countDown(); bothLookedUp.await(30, java.util.concurrent.TimeUnit.SECONDS) shouldBe true }
            val results = concurrently(2) { i -> finish(c, refs[i], state) }.map { it.getOrThrow() }
            results.count { it is CompleteCardAuthenticationResult.Verified } shouldBe 1
            results.count { it == CompleteCardAuthenticationResult.Rejected } shouldBe 1
            sessionsOf(c).single()["status"] shouldBe "VERIFIED"
        }

        Then("C6 + C10: a new start while the lookup is in flight wins; the return is re-judged under the lock as Ignored. During the lookup no TX or row lock is held") {
            val c = checkout()
            begin(c)
            val resultRef = answer(c)
            val state = registrationOf(c).state
            institution.beforeLookup = { _ ->
                // C10: 조회 중 이 요청 스레드에 열린 TX가 없고 다른 연결이 같은 payment 행을 바로 잠글 수 있다(대기 없음).
                db.withApp { cn ->
                    cn.autoCommit = false
                    with(db) { cn.update("SET SESSION innodb_lock_wait_timeout = 1") }
                    cn.prepareStatement("SELECT payment_id FROM payment WHERE payment_id = ? FOR UPDATE").use { ps -> ps.setString(1, c.paymentId.value); ps.executeQuery().use { it.next() shouldBe true } }
                    cn.rollback()
                }
                val pool = Executors.newSingleThreadExecutor()
                try {
                    pool.submit<StartCardAuthenticationResult> { begin(c) }.get(60, TimeUnit.SECONDS).shouldBeInstanceOf<StartCardAuthenticationResult.Redirect>()
                } finally {
                    pool.shutdownNow()
                }
            }
            finish(c, resultRef, state) shouldBe CompleteCardAuthenticationResult.Ignored
            institution.calls.last { it.operation == "lookup" }.inTransaction shouldBe false
            sessionsOf(c).map { it["status"] } shouldBe listOf("EXPIRED", "IN_PROGRESS")
        }
    }

    Given("a failure inside TX-A after the new session was flushed") {
        Then("TX-A rolls back: the superseded session is unchanged, no new session or event, the checkout session stays ACTIVE and the institution is not called") {
            val c = checkout()
            begin(c).shouldBeInstanceOf<StartCardAuthenticationResult.Redirect>()
            val before = sessionsOf(c).single()
            val calls = institution.calls.size
            faults.after(FaultPoint.AUTH_SESSION_INSERT) { throw IllegalStateException("injected in TX-A") }
            shouldThrow<IllegalStateException> { begin(c) }
            faults.reset()
            sessionsOf(c).map { it["authentication_session_id"] to it["status"] } shouldBe listOf(before["authentication_session_id"] to "IN_PROGRESS")
            eventsOf(before["authentication_session_id"]) shouldBe listOf("CREATED", "STARTED")
            checkoutSessionOf(c)["status"] shouldBe "ACTIVE"
            institution.calls.size shouldBe calls
        }
    }

    Given("a failure inside TX-C after the session update was flushed") {
        Then("everything rolls back (session IN_PROGRESS, checkout session ACTIVE, no new events); the same return URL then converges to VERIFIED") {
            val c = checkout()
            begin(c)
            val resultRef = answer(c)
            val state = registrationOf(c).state
            faults.after(FaultPoint.AUTH_SESSION_UPDATE) { throw IllegalStateException("injected in TX-C") }
            shouldThrow<IllegalStateException> { finish(c, resultRef, state) }
            faults.reset()
            val s = sessionsOf(c).single()
            s["status"] shouldBe "IN_PROGRESS"
            checkoutSessionOf(c)["status"] shouldBe "ACTIVE"
            eventsOf(s["authentication_session_id"]) shouldBe listOf("CREATED", "STARTED")
            finish(c, resultRef, state).shouldBeInstanceOf<CompleteCardAuthenticationResult.Verified>()
        }
    }
})

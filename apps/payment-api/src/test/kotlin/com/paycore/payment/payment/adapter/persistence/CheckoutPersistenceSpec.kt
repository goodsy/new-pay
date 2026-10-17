package com.paycore.payment.payment.adapter.persistence

import com.paycore.payment.payment.adapter.persistence.CheckoutPersistenceFixture.attributes
import com.paycore.payment.payment.adapter.persistence.CheckoutPersistenceFixture.created
import com.paycore.payment.payment.adapter.persistence.CheckoutPersistenceFixture.issuedAt
import com.paycore.payment.payment.adapter.persistence.CheckoutPersistenceFixture.session
import com.paycore.payment.payment.adapter.persistence.CheckoutPersistenceFixture.token
import com.paycore.payment.payment.adapter.persistence.PersistenceTestData.attributes as paymentAttributes
import com.paycore.payment.payment.adapter.persistence.PersistenceTestData.newOrder
import com.paycore.payment.payment.adapter.persistence.PersistenceTestData.requested
import com.paycore.payment.payment.application.required.AuthenticationSessionEventRecord
import com.paycore.payment.payment.application.required.AuthenticationSessionEventStore
import com.paycore.payment.payment.application.required.AuthenticationSessionRepository
import com.paycore.payment.payment.application.required.CheckoutSessionCloseReason
import com.paycore.payment.payment.application.required.CheckoutSessionStatus
import com.paycore.payment.payment.application.required.CheckoutSessionStore
import com.paycore.payment.payment.application.required.CheckoutTokenStatus
import com.paycore.payment.payment.application.required.CheckoutTokenStore
import com.paycore.payment.payment.application.required.EventAppendResult
import com.paycore.payment.payment.application.required.PaymentRepository
import com.paycore.payment.payment.application.required.PersistenceFailure
import com.paycore.payment.payment.domain.AuthenticatedDetails
import com.paycore.payment.payment.domain.AuthenticationEventDetail
import com.paycore.payment.payment.domain.AuthenticationEventKind
import com.paycore.payment.payment.domain.AuthenticationExpiryCause
import com.paycore.payment.payment.domain.AuthenticationRef
import com.paycore.payment.payment.domain.AuthenticationResultCheck
import com.paycore.payment.payment.domain.AuthenticationStatus
import com.paycore.payment.payment.domain.Payment
import com.paycore.payment.payment.domain.PresentedResult
import com.paycore.payment.payment.domain.ProviderAuthenticationResult
import com.paycore.payment.payment.domain.ProviderBindingEcho
import com.paycore.payment.payment.domain.ProviderOutcome
import com.paycore.payment.payment.domain.ProviderResultLookup
import com.paycore.payment.payment.domain.ResultVerdict
import com.paycore.payment.payment.domain.SecretKind
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration

/**
 * AUTHN-001 S4 Port 구현(JPA)을 실제 MySQL 8.4·runtime 계정으로 시험한다: 인증 세션 Aggregate 왕복(단방향 규칙 H-4 포함)·@Version 충돌·생성 컬럼 UNIQUE
 * 분류, 토큰·결제창 세션 Store 왕복과 상태 반영, 결정 로그 append(반복 종류 1행 — n-b), 채우기 기준 pin, Payment 결제창 시각 저장, TX 밖 호출 거부.
 */
@PaymentDbTest
class CheckoutPersistenceSpec(
    private val payments: PaymentRepository,
    private val sessions: AuthenticationSessionRepository,
    private val events: AuthenticationSessionEventStore,
    private val tokens: CheckoutTokenStore,
    private val checkoutSessions: CheckoutSessionStore,
    private val transactionManager: PlatformTransactionManager,
    private val db: DbAccess,
) : BehaviorSpec({

    val tx = TransactionTemplate(transactionManager)
    /** TransactionTemplate.execute는 콜백 결과를 그대로 돌려준다(null 결과도 그대로 — 조회 Port의 null 시험). */
    fun <T> inTx(block: () -> T): T = tx.execute { block() }

    fun storedPayment(): Payment = requested(newOrder()).also { p -> inTx { payments.insertNew(p, paymentAttributes()) } }

    Given("the Payment checkout window (D-AUTHN-005)") {
        Then("a new Payment stores its window and version and reads them back") {
            val p = storedPayment()
            inTx { payments.lock(p.id)!! }.value.checkoutWindow shouldBe PersistenceTestData.demoWindow
            db.withRoot { c -> with(db) { c.rows("SELECT checkout_validity_version AS v FROM payment WHERE payment_id = ?", p.id.value).single()["v"] } } shouldBe
                "checkout-validity-demo-1"
        }
    }

    Given("checkout tokens") {
        Then("a token round-trips, is found by digest, consumed with a version check, and one ISSUED token per payment is a unique conflict") {
            val p = storedPayment()
            val t = token(p)
            inTx { tokens.insert(t) } shouldBe 0L
            inTx { tokens.findByDigest(t.digest) } shouldBe t
            val locked = inTx { tokens.lockIssuedOfPayment(p.id) }!!
            locked.value shouldBe t
            shouldThrow<PersistenceFailure.UniqueConflict> { inTx { tokens.insert(token(p)) } }
            inTx { tokens.update(locked.value.consumed(issuedAt.plusSeconds(30)), locked.version) } shouldBe 1L
            inTx { tokens.lock(t.id) }!!.value.status shouldBe CheckoutTokenStatus.CONSUMED
            shouldThrow<PersistenceFailure.OptimisticConflict> { inTx { tokens.update(locked.value.closed(CheckoutTokenStatus.SUPERSEDED, issuedAt), locked.version) } }
            inTx { tokens.lockIssuedOfPayment(p.id) } shouldBe null
        }
    }

    Given("checkout sessions") {
        Then("a session round-trips by handle, one ACTIVE per payment, and closing records the reason and time") {
            val p = storedPayment()
            val t1 = token(p).also { inTx { tokens.insert(it) } }
            val s1 = session(t1)
            inTx { checkoutSessions.insert(s1) }
            inTx { checkoutSessions.findByHandle(s1.handle) } shouldBe s1
            val t2 = token(p, status = CheckoutTokenStatus.ISSUED).let { candidate ->
                inTx { tokens.update(tokens.lock(t1.id)!!.value.consumed(issuedAt.plusSeconds(5)), tokens.lock(t1.id)!!.version) }
                candidate.also { inTx { tokens.insert(it) } }
            }
            shouldThrow<PersistenceFailure.UniqueConflict> { inTx { checkoutSessions.insert(session(t2)) } }
            val active = inTx { checkoutSessions.lockActiveOfPayment(p.id) }!!
            inTx { checkoutSessions.update(active.value.closed(CheckoutSessionCloseReason.SUPERSEDED, issuedAt.plusSeconds(40)), active.version) }
            inTx { checkoutSessions.lock(s1.id) }!!.value.let { listOf(it.status, it.closeReason) } shouldBe listOf(CheckoutSessionStatus.REVOKED, CheckoutSessionCloseReason.SUPERSEDED)
            inTx { checkoutSessions.insert(session(t2)) }
        }
    }

    Given("authentication sessions (Aggregate)") {
        fun prepared(): Pair<Payment, com.paycore.payment.payment.application.required.CheckoutSessionRecord> {
            val p = storedPayment()
            val t = token(p).also { inTx { tokens.insert(it) } }
            return p to session(t).also { inTx { checkoutSessions.insert(it) } }
        }

        Then("CREATED → IN_PROGRESS → VERIFIED → superseded EXPIRED round-trips with evidence and receipt kept (H-4); stale versions are refused") {
            val (p, cs) = prepared()
            val s = created(p)
            inTx { sessions.insertNew(s, attributes(cs.id)) } shouldBe 0L
            inTx { sessions.findIdByState(cs.id, s.correlation.stateDigest) } shouldBe s.id
            val v0 = inTx { sessions.lock(s.id) }!!
            v0.value.status shouldBe AuthenticationStatus.CREATED
            v0.value.start("sat_demo0001")
            val v1 = inTx { sessions.update(v0.value, v0.version) }
            shouldThrow<PersistenceFailure.OptimisticConflict> { inTx { sessions.update(v0.value, v0.version) } }

            val loaded = inTx { sessions.lock(s.id) }!!
            loaded.version shouldBe v1
            val rrd = CheckoutPersistenceFixture.digest(SecretKind.RESULT_REFERENCE)
            val result = ProviderAuthenticationResult(
                "sat_demo0001", ProviderOutcome.AUTHENTICATED, loaded.value.correlation.stateDigest,
                ProviderBindingEcho(p.id, p.orderKey.environment, p.orderKey.merchantAccountId, loaded.value.binding.money, loaded.value.binding.plan, "SIMISS-A"),
                AuthenticatedDetails("CHECK", "SIMISS-A", "SIMACQ-A", "SIMAUTH-1", "ev-demo-1", issuedAt.plusSeconds(25)), issuedAt.plusSeconds(900),
            )
            val aref = AuthenticationRef("aref_" + "Q".repeat(22))
            AuthenticationResultCheck.afterLookup(
                loaded.value, p, PresentedResult(loaded.value.correlation.stateDigest, rrd), ProviderResultLookup.Found(result), aref, issuedAt.plusSeconds(30),
            ).verdict shouldBe ResultVerdict.VERIFIED
            inTx { sessions.update(loaded.value, loaded.version) }

            val verified = inTx { sessions.lock(s.id) }!!
            verified.value.verified shouldBe loaded.value.verified
            listOf(verified.value.authenticationRef, verified.value.resultRefDigest, verified.value.verifiedAt) shouldBe listOf(aref, rrd, issuedAt.plusSeconds(30))
            verified.value.expire(AuthenticationExpiryCause.SUPERSEDED, issuedAt.plusSeconds(40))
            inTx { sessions.update(verified.value, verified.version) }
            inTx { sessions.lock(s.id) }!!.value.let { listOf(it.status, it.expiryCause, it.verified != null, it.authenticationRef) } shouldBe
                listOf(AuthenticationStatus.EXPIRED, AuthenticationExpiryCause.SUPERSEDED, true, aref)
        }

        Then("a second open session of the same payment is a unique conflict; lockAllOfPayment returns the history in creation order") {
            val (p, cs) = prepared()
            val first = created(p, at = issuedAt.plusSeconds(20))
            inTx { sessions.insertNew(first, attributes(cs.id)) }
            shouldThrow<PersistenceFailure.UniqueConflict> { inTx { sessions.insertNew(created(p, at = issuedAt.plusSeconds(21)), attributes(cs.id)) } }
            val second = inTx {
                val all = sessions.lockAllOfPayment(p.id)
                val next = created(p, all.map { it.value }, at = issuedAt.plusSeconds(22))
                all.forEach { sessions.update(it.value, it.version) }
                sessions.insertNew(next, attributes(cs.id))
                next
            }
            inTx { sessions.lockAllOfPayment(p.id) }.map { it.value.id to it.value.status } shouldBe
                listOf(first.id to AuthenticationStatus.EXPIRED, second.id to AuthenticationStatus.CREATED)
        }

        Then("decision events append with increasing seq; a repeatable kind is recorded once per (kind, detail) (n-b)") {
            val (p, cs) = prepared()
            val s = created(p).also { inTx { sessions.insertNew(it, attributes(cs.id)) } }
            fun ev(kind: AuthenticationEventKind, detail: AuthenticationEventDetail?) = AuthenticationSessionEventRecord(s.id, kind, detail, issuedAt.plusSeconds(50), "corr-demo")
            inTx {
                listOf(
                    events.append(ev(AuthenticationEventKind.CREATED, null)),
                    events.append(ev(AuthenticationEventKind.REJECTED_RESULT, AuthenticationEventDetail.EVIDENCE_CONFLICT)),
                    events.append(ev(AuthenticationEventKind.REJECTED_RESULT, AuthenticationEventDetail.EVIDENCE_CONFLICT)),
                    events.append(ev(AuthenticationEventKind.REJECTED_RESULT, AuthenticationEventDetail.SESSION_NOT_STARTED)),
                )
            } shouldBe listOf(EventAppendResult.APPENDED, EventAppendResult.APPENDED, EventAppendResult.ALREADY_RECORDED, EventAppendResult.APPENDED)
            inTx { events.findBySession(s.id) }.map { it.kind to it.detail } shouldBe listOf(
                AuthenticationEventKind.CREATED to null,
                AuthenticationEventKind.REJECTED_RESULT to AuthenticationEventDetail.EVIDENCE_CONFLICT,
                AuthenticationEventKind.REJECTED_RESULT to AuthenticationEventDetail.SESSION_NOT_STARTED,
            )
            db.withRoot { c -> with(db) { c.rows("SELECT seq FROM authentication_session_event WHERE authentication_session_id = ? ORDER BY seq", s.id.value).map { it["seq"] } } } shouldBe
                listOf(0, 1, 2)
        }
    }

    Given("the backfill pin (D-AUTHN-007, m-B — written by the start-up migration strategy, review #2 m-2)") {
        Then("application start-up pinned the active setting in one row; the runtime account cannot rewrite it") {
            db.withApp { c -> with(db) { c.rows("SELECT pin_id, validity_version, validity_duration_ms FROM payment_checkout_window_pin") } }
                .map { listOf(it["pin_id"].toString(), it["validity_version"], it["validity_duration_ms"].toString()) } shouldBe
                listOf(listOf("1", "checkout-validity-demo-1", "1800000"))
            db.withApp { c -> with(db) { c.errorCodeOf("UPDATE payment_checkout_window_pin SET validity_version = 'other' WHERE pin_id = 1") } } shouldBe 1142
        }
    }

    Given("calls outside a transaction") {
        Then("every new Port refuses to run (MANDATORY)") {
            val p = requested(newOrder())
            listOf<() -> Any?>(
                { sessions.lockAllOfPayment(p.id) }, { tokens.lockIssuedOfPayment(p.id) }, { checkoutSessions.lockActiveOfPayment(p.id) },
            ).forEach { shouldThrow<IllegalTransactionStateException> { it() } }
        }
    }
})

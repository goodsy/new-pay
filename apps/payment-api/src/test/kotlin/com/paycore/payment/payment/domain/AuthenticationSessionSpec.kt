package com.paycore.payment.payment.domain

import com.paycore.payment.payment.domain.AuthorizationFixture.PROVIDER_TRANSACTION_REF
import com.paycore.payment.payment.domain.AuthorizationFixture.authenticationRef
import com.paycore.payment.payment.domain.AuthorizationFixture.authorizationId
import com.paycore.payment.payment.domain.AuthorizationFixture.binding
import com.paycore.payment.payment.domain.AuthorizationFixture.correlation
import com.paycore.payment.payment.domain.AuthorizationFixture.evidence
import com.paycore.payment.payment.domain.AuthorizationFixture.inProgressSession
import com.paycore.payment.payment.domain.AuthorizationFixture.resultRefDigest
import com.paycore.payment.payment.domain.AuthorizationFixture.sessionTtl
import com.paycore.payment.payment.domain.AuthorizationFixture.startedAt
import com.paycore.payment.payment.domain.AuthorizationFixture.verifiedSession
import com.paycore.payment.payment.domain.MoneyFixture.won
import com.paycore.testsupport.kotest.shouldBeEqualNullable
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.equals.shouldBeEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.time.Instant

/**
 * AuthenticationSession lifecycle(D-AUTH-001, 명세 §5.3): CREATED/IN_PROGRESS/VERIFIED/FAILED/EXPIRED, 인증 근거는 하나의 승인
 * operation에만 귀속, 인증 성공 ≠ 승인 성공, 귀속 뒤 TTL 만료가 승인을 바꾸지 않음.
 */
class AuthenticationSessionSpec : BehaviorSpec({

    val expiresAt = startedAt.plus(sessionTtl)

    /** 저장 행 복원(Mapper 경로). 상태별 일관된 기본값을 두고 시험마다 한 값만 깨뜨린다(Invalid 의도가 드러나게). */
    fun stored(
        b: AuthenticationBinding,
        status: AuthenticationStatus,
        verified: VerifiedAuthentication? = null,
        failure: AuthenticationFailure? = null,
        bound: AuthorizationId? = null,
        providerRef: String? = if (status == AuthenticationStatus.CREATED) null else PROVIDER_TRANSACTION_REF,
        expiryCause: AuthenticationExpiryCause? = null,
        resultRef: SecretDigest? = if (status == AuthenticationStatus.VERIFIED) resultRefDigest() else null,
        aref: AuthenticationRef? = if (status == AuthenticationStatus.VERIFIED) authenticationRef else null,
        verifiedAt: Instant? = if (status == AuthenticationStatus.VERIFIED) startedAt else null,
    ): AuthenticationSession = AuthenticationSession.reconstitute(
        id = AuthorizationFixture.sessionId,
        binding = b,
        correlation = correlation(),
        expiresAt = expiresAt,
        createdAt = startedAt.minusSeconds(60),
        status = status,
        providerTransactionRef = providerRef,
        verified = verified,
        verifiedAt = verifiedAt,
        resultRefDigest = resultRef,
        authenticationRef = aref,
        failure = failure,
        expiryCause = expiryCause,
        boundAuthorizationId = bound,
    )

    Given("a REQUESTED payment") {
        Then("a new session starts CREATED, bound to the payment conditions, and moves to IN_PROGRESS") {
            val payment = PaymentFixture.requested()
            val s = AuthenticationSession.create(AuthorizationFixture.sessionId, payment, binding(payment), correlation(), expiresAt, startedAt.minusSeconds(60), emptyList())
            s.status shouldBe AuthenticationStatus.CREATED
            s.start(PROVIDER_TRANSACTION_REF)
            s.status shouldBe AuthenticationStatus.IN_PROGRESS
            shouldThrow<InvalidAuthenticationTransitionException> { s.start(PROVIDER_TRANSACTION_REF) }
        }
        Then("only one open session per payment; a failed one does not block a new attempt in the same payment window") {
            val payment = PaymentFixture.requested()
            val first = inProgressSession(payment)
            shouldThrow<IllegalArgumentException> {
                AuthenticationSession.create(AuthenticationSessionId("demo-authn-0002"), payment, binding(payment), correlation(2), expiresAt, startedAt, listOf(first))
            }
            first.fail(AuthenticationFailure.USER_CANCELLED)
            val second = AuthenticationSession.create(AuthenticationSessionId("demo-authn-0002"), payment, binding(payment), correlation(2), expiresAt, startedAt, listOf(first))
            second.status shouldBe AuthenticationStatus.CREATED
        }
        Then("a session cannot start for a payment whose authorization started, nor bind to another payment's conditions") {
            val authorizing = PaymentFixture.authorizing()
            shouldThrow<IllegalArgumentException> {
                AuthenticationSession.create(AuthorizationFixture.sessionId, authorizing, binding(authorizing), correlation(), expiresAt, startedAt, emptyList())
            }
            val payment = PaymentFixture.requested()
            val other = PaymentFixture.requested(n = 2)
            shouldThrow<IllegalArgumentException> {
                AuthenticationSession.create(AuthorizationFixture.sessionId, payment, binding(other), correlation(), expiresAt, startedAt, emptyList())
            }
        }
    }

    Given("an IN_PROGRESS session receiving the server-verified result") {
        Then("matching evidence within the TTL verifies it; the same evidence again is idempotent") {
            val s = inProgressSession(PaymentFixture.requested())
            val ev = evidence(s.binding)
            s.verify(ev, resultRefDigest(), authenticationRef, startedAt) shouldBe VerificationOutcome.VERIFIED
            s.status shouldBe AuthenticationStatus.VERIFIED
            s.verified shouldBeEqualNullable ev
            s.verify(ev, resultRefDigest(), authenticationRef, startedAt.plusSeconds(1)) shouldBe VerificationOutcome.DUPLICATE
        }
        Then("different evidence after VERIFIED is rejected and never overwrites the first evidence") {
            val s = inProgressSession(PaymentFixture.requested())
            val ev = evidence(s.binding)
            s.verify(ev, resultRefDigest(), authenticationRef, startedAt)
            shouldThrow<AuthenticationEvidenceConflictException> { s.verify(evidence(s.binding, ref = "demo-authn-evidence-2"), resultRefDigest(), authenticationRef, startedAt) }
            s.verified shouldBeEqualNullable ev
        }
        Then("evidence for different conditions (amount) fails the session with BINDING_MISMATCH") {
            val payment = PaymentFixture.requested()
            val s = inProgressSession(payment)
            s.verify(evidence(binding(payment, money = 10_001L.won())), resultRefDigest(), authenticationRef, startedAt) shouldBe VerificationOutcome.REJECTED_BINDING_MISMATCH
            s.status shouldBe AuthenticationStatus.FAILED
            s.failure shouldBe AuthenticationFailure.BINDING_MISMATCH
        }
        Then("evidence arriving at or after expiresAt expires the session") {
            val s = inProgressSession(PaymentFixture.requested())
            s.verify(evidence(s.binding), resultRefDigest(), authenticationRef, expiresAt) shouldBe VerificationOutcome.EXPIRED
            s.status shouldBe AuthenticationStatus.EXPIRED
        }
        Then("a late success for a terminated session is ignored and grants nothing") {
            val s = inProgressSession(PaymentFixture.requested())
            s.fail(AuthenticationFailure.AUTHENTICATION_FAILED)
            s.verify(evidence(s.binding), resultRefDigest(), authenticationRef, startedAt) shouldBe VerificationOutcome.IGNORED_TERMINAL
            s.status shouldBe AuthenticationStatus.FAILED
            s.verified shouldBe null
        }
        Then("a CREATED session (not yet sent to the provider) cannot be verified") {
            val payment = PaymentFixture.requested()
            val s = AuthenticationSession.create(AuthorizationFixture.sessionId, payment, binding(payment), correlation(), expiresAt, startedAt, emptyList())
            shouldThrow<InvalidAuthenticationTransitionException> { s.verify(evidence(s.binding), resultRefDigest(), authenticationRef, startedAt) }
        }
    }

    Given("a VERIFIED, unbound session") {
        val payment = PaymentFixture.requested()
        Then("it binds once to one authorization operation; a second bind is rejected") {
            val s = verifiedSession(payment)
            s.bindingRejection(payment.id, AuthorizationFixture.intent.money, startedAt) shouldBe null
            s.bindTo(authorizationId, payment.id, AuthorizationFixture.intent.money, startedAt)
            s.boundAuthorizationId shouldBe authorizationId
            s.bindingRejection(payment.id, AuthorizationFixture.intent.money, startedAt) shouldBe BindingRejection.ALREADY_BOUND
            shouldThrow<InvalidAuthenticationTransitionException> {
                s.bindTo(AuthorizationId("demo-auth-0002"), payment.id, AuthorizationFixture.intent.money, startedAt)
            }
            s.boundAuthorizationId shouldBe authorizationId
        }
        Then("binding is refused for another payment, another amount, or after expiresAt") {
            val s = verifiedSession(payment)
            s.bindingRejection(PaymentFixture.requested(n = 2).id, AuthorizationFixture.intent.money, startedAt) shouldBe BindingRejection.OTHER_PAYMENT
            s.bindingRejection(payment.id, 10_001L.won(), startedAt) shouldBe BindingRejection.AMOUNT_MISMATCH
            s.bindingRejection(payment.id, AuthorizationFixture.intent.money, expiresAt) shouldBe BindingRejection.EXPIRED
            inProgressSession(payment).bindingRejection(payment.id, AuthorizationFixture.intent.money, startedAt) shouldBe BindingRejection.NOT_VERIFIED
        }
        Then("unbound, it can expire by TTL (not before expiresAt) or because the payment window closed") {
            val byTtl = verifiedSession(payment)
            shouldThrow<InvalidAuthenticationTransitionException> { byTtl.expire(AuthenticationExpiryCause.TTL_ELAPSED, expiresAt.minusMillis(1)) }
            byTtl.expire(AuthenticationExpiryCause.TTL_ELAPSED, expiresAt)
            byTtl.status shouldBe AuthenticationStatus.EXPIRED
            val byClose = verifiedSession(payment)
            byClose.expire(AuthenticationExpiryCause.PAYMENT_CLOSED, startedAt)
            byClose.isDiscarded() shouldBe true
        }
        Then("once bound, TTL expiry or failure never touches it (an authorization already started is not expired by the session)") {
            val s = verifiedSession(payment)
            s.bindTo(authorizationId, payment.id, AuthorizationFixture.intent.money, startedAt)
            shouldThrow<InvalidAuthenticationTransitionException> { s.expire(AuthenticationExpiryCause.TTL_ELAPSED, expiresAt.plusSeconds(3600)) }
            shouldThrow<InvalidAuthenticationTransitionException> { s.fail(AuthenticationFailure.AUTHENTICATION_FAILED) }
            s.status shouldBe AuthenticationStatus.VERIFIED
        }
    }

    Given("a stored session row") {
        Then("reconstitute rejects impossible combinations") {
            val payment = PaymentFixture.requested()
            val b = binding(payment)
            shouldThrow<IllegalArgumentException> { stored(b, AuthenticationStatus.VERIFIED, verified = null) }
            shouldThrow<IllegalArgumentException> {
                stored(b, AuthenticationStatus.FAILED, verified = null, failure = AuthenticationFailure.AUTHENTICATION_FAILED, bound = authorizationId)
            }
            stored(b, AuthenticationStatus.VERIFIED, verified = evidence(b), bound = authorizationId).isBound() shouldBe true
        }
    }

    // --- AUTHN-001 §7.1 domain 보강 ---

    Given("correlation values fixed for the server-to-server result check (D-AUTHN-003)") {
        val payment = PaymentFixture.requested()
        Then("the state digest is fixed at creation and the provider transaction reference once at start") {
            val s = AuthenticationSession.create(AuthorizationFixture.sessionId, payment, binding(payment), correlation(7), expiresAt, startedAt, emptyList())
            s.correlation shouldBe correlation(7)
            s.providerTransactionRef shouldBe null
            s.start(PROVIDER_TRANSACTION_REF)
            s.providerTransactionRef shouldBe PROVIDER_TRANSACTION_REF
            shouldThrow<InvalidAuthenticationTransitionException> { s.start("sat_other") }
            s.providerTransactionRef shouldBe PROVIDER_TRANSACTION_REF
        }
        Then("an empty provider reference is refused and the session stays CREATED") {
            val s = AuthenticationSession.create(AuthorizationFixture.sessionId, payment, binding(payment), correlation(), expiresAt, startedAt, emptyList())
            shouldThrow<IllegalArgumentException> { s.start("") }
            s.status shouldBe AuthenticationStatus.CREATED
        }
        Then("the correlation holds only an authentication-state digest (not a result-reference or token digest)") {
            shouldThrow<IllegalArgumentException> { AuthenticationCorrelation(resultRefDigest()) }
            shouldThrow<IllegalArgumentException> { AuthenticationCorrelation(SecretDigest.of(SecretKind.CHECKOUT_TOKEN, "ckt_demo")) }
        }
    }

    Given("expiry causes are stored (Auditability)") {
        val payment = PaymentFixture.requested()
        Then("TTL_ELAPSED, PAYMENT_CLOSED and SUPERSEDED are recorded on the session") {
            inProgressSession(payment).also { it.expire(AuthenticationExpiryCause.TTL_ELAPSED, expiresAt) }.expiryCause shouldBe AuthenticationExpiryCause.TTL_ELAPSED
            inProgressSession(payment).also { it.expire(AuthenticationExpiryCause.PAYMENT_CLOSED, startedAt) }.expiryCause shouldBe AuthenticationExpiryCause.PAYMENT_CLOSED
            inProgressSession(payment).also { it.expire(AuthenticationExpiryCause.SUPERSEDED, startedAt) }.expiryCause shouldBe AuthenticationExpiryCause.SUPERSEDED
            inProgressSession(payment).expiryCause shouldBe null
        }
        Then("SUPERSEDED may happen before expiresAt and also discards an unbound VERIFIED session, but never a bound one") {
            val verified = AuthorizationFixture.verifiedSession(payment)
            verified.expire(AuthenticationExpiryCause.SUPERSEDED, startedAt)
            verified.status shouldBe AuthenticationStatus.EXPIRED
            val bound = AuthorizationFixture.verifiedSession(payment).also { it.bindTo(authorizationId, payment.id, AuthorizationFixture.intent.money, startedAt) }
            shouldThrow<InvalidAuthenticationTransitionException> { bound.expire(AuthenticationExpiryCause.SUPERSEDED, startedAt) }
            bound.expiryCause shouldBe null
        }
    }

    Given("the verified result receipt (one-time result reference and the server-issued authentication reference)") {
        val payment = PaymentFixture.requested()
        Then("VERIFIED stores the result-reference digest, the authentication reference and the verification time") {
            val s = inProgressSession(payment)
            s.verify(evidence(s.binding), resultRefDigest(3), authenticationRef, startedAt) shouldBe VerificationOutcome.VERIFIED
            s.resultRefDigest shouldBe resultRefDigest(3)
            s.authenticationRef shouldBe authenticationRef
            s.verifiedAt shouldBe startedAt
        }
        Then("the same evidence with a different result reference is a conflict, not a duplicate, and keeps the first receipt") {
            val s = inProgressSession(payment)
            s.verify(evidence(s.binding), resultRefDigest(3), authenticationRef, startedAt)
            shouldThrow<AuthenticationEvidenceConflictException> {
                s.verify(evidence(s.binding), resultRefDigest(4), AuthenticationRef("aref_" + "B".repeat(22)), startedAt)
            }
            s.resultRefDigest shouldBe resultRefDigest(3)
            s.authenticationRef shouldBe authenticationRef
        }
        Then("a failure confirmed by the provider keeps the consumed result reference; PROVIDER_EXPIRED is a failure reason") {
            val s = inProgressSession(payment)
            s.fail(AuthenticationFailure.PROVIDER_EXPIRED, resultRefDigest(5))
            s.failure shouldBe AuthenticationFailure.PROVIDER_EXPIRED
            s.resultRefDigest shouldBe resultRefDigest(5)
            s.authenticationRef shouldBe null
        }
        Then("evidence whose provider validity already ended fails the session with PROVIDER_EXPIRED") {
            val s = inProgressSession(payment)
            s.verify(evidence(s.binding, validUntil = startedAt), resultRefDigest(), authenticationRef, startedAt) shouldBe
                VerificationOutcome.REJECTED_PROVIDER_EXPIRED
            s.status shouldBe AuthenticationStatus.FAILED
            s.failure shouldBe AuthenticationFailure.PROVIDER_EXPIRED
            s.authenticationRef shouldBe null
        }
        Then("the authentication reference format is aref_ + 22 base64url characters and toString shows only its first 9 characters") {
            shouldThrow<IllegalArgumentException> { AuthenticationRef("aref_short") }
            shouldThrow<IllegalArgumentException> { AuthenticationRef("xref_" + "A".repeat(22)) }
            shouldThrow<IllegalArgumentException> { AuthenticationRef("aref_" + "A".repeat(21) + "=") }
            authenticationRef.toString() shouldBe "AuthenticationRef(aref_AAAA…)"
        }
    }

    Given("the provider validity of the evidence (derived: D-AUTHN-003 validity check)") {
        val payment = PaymentFixture.requested()
        Then("binding is refused from min(expiresAt, providerValidUntil) — the earlier end wins") {
            val early = startedAt.plusSeconds(60)
            val s = inProgressSession(payment)
            s.verify(evidence(s.binding, validUntil = early), resultRefDigest(), authenticationRef, startedAt) shouldBe VerificationOutcome.VERIFIED
            s.bindingRejection(payment.id, AuthorizationFixture.intent.money, early.minusMillis(1)) shouldBe null
            s.bindingRejection(payment.id, AuthorizationFixture.intent.money, early) shouldBe BindingRejection.EXPIRED
            shouldThrow<InvalidAuthenticationTransitionException> { s.bindTo(authorizationId, payment.id, AuthorizationFixture.intent.money, early) }
        }
    }

    Given("stored rows for the AUTHN-001 columns") {
        val b = binding(PaymentFixture.requested())
        Then("reconstitute accepts consistent rows") {
            stored(b, AuthenticationStatus.CREATED).status shouldBe AuthenticationStatus.CREATED
            stored(b, AuthenticationStatus.IN_PROGRESS).providerTransactionRef shouldBe PROVIDER_TRANSACTION_REF
            stored(b, AuthenticationStatus.EXPIRED, providerRef = null, expiryCause = AuthenticationExpiryCause.SUPERSEDED).expiryCause shouldBe
                AuthenticationExpiryCause.SUPERSEDED
            stored(b, AuthenticationStatus.FAILED, failure = AuthenticationFailure.STATE_MISMATCH, resultRef = resultRefDigest(2)).resultRefDigest shouldBe
                resultRefDigest(2)
            stored(b, AuthenticationStatus.VERIFIED, verified = evidence(b)).authenticationRef shouldBe authenticationRef
            // H-4: 대체·만료된 VERIFIED 세션은 증거·영수증을 보존한 EXPIRED로 저장된다.
            stored(
                b, AuthenticationStatus.EXPIRED, verified = evidence(b), expiryCause = AuthenticationExpiryCause.SUPERSEDED,
                resultRef = resultRefDigest(), aref = authenticationRef, verifiedAt = startedAt,
            ).verified.shouldNotBeNull() shouldBeEqual evidence(b)
        }
        Then("reconstitute rejects evidence outside VERIFIED/EXPIRED and a result reference on EXPIRED without evidence (one-way rules, n-h)") {
            listOf<() -> Unit>(
                { stored(b, AuthenticationStatus.FAILED, failure = AuthenticationFailure.AUTHENTICATION_FAILED, verified = evidence(b), aref = authenticationRef, verifiedAt = startedAt) },
                { stored(b, AuthenticationStatus.IN_PROGRESS, verified = evidence(b), aref = authenticationRef, verifiedAt = startedAt) },
                { stored(b, AuthenticationStatus.EXPIRED, expiryCause = AuthenticationExpiryCause.TTL_ELAPSED, resultRef = resultRefDigest()) },
                { stored(b, AuthenticationStatus.EXPIRED, verified = evidence(b), expiryCause = AuthenticationExpiryCause.SUPERSEDED, aref = null, verifiedAt = startedAt) },
            ).forEachIndexed { i, row -> withClue("row $i") { shouldThrow<IllegalArgumentException> { row() } } }
        }
        Then("expiring a VERIFIED session keeps its evidence and receipt (audit), and the late verify EXPIRED branch records TTL_ELAPSED (n-c)") {
            val payment = PaymentFixture.requested()
            val v = AuthorizationFixture.verifiedSession(payment)
            v.expire(AuthenticationExpiryCause.SUPERSEDED, startedAt)
            v.verified.shouldNotBeNull() shouldBeEqual evidence(v.binding)
            v.authenticationRef shouldBe authenticationRef
            v.bindingRejection(payment.id, AuthorizationFixture.intent.money, startedAt) shouldBe BindingRejection.NOT_VERIFIED
            val late = inProgressSession(payment)
            late.verify(evidence(late.binding), resultRefDigest(), authenticationRef, expiresAt) shouldBe VerificationOutcome.EXPIRED
            late.expiryCause shouldBe AuthenticationExpiryCause.TTL_ELAPSED
        }
        Then("reconstitute rejects each impossible combination (the DB CHECKs, independently)") {
            listOf<() -> Unit>(
                { stored(b, AuthenticationStatus.EXPIRED) }, // EXPIRED without a cause
                { stored(b, AuthenticationStatus.IN_PROGRESS, expiryCause = AuthenticationExpiryCause.TTL_ELAPSED) }, // cause on a non-EXPIRED session
                { stored(b, AuthenticationStatus.FAILED) }, // FAILED without a reason
                { stored(b, AuthenticationStatus.IN_PROGRESS, providerRef = null) }, // started without a provider reference
                { stored(b, AuthenticationStatus.CREATED, providerRef = PROVIDER_TRANSACTION_REF) }, // CREATED with a provider reference
                { stored(b, AuthenticationStatus.VERIFIED, verified = evidence(b), aref = null) }, // VERIFIED without the authentication reference
                { stored(b, AuthenticationStatus.VERIFIED, verified = evidence(b), resultRef = null) }, // VERIFIED without the result reference
                { stored(b, AuthenticationStatus.VERIFIED, verified = evidence(b), verifiedAt = null) }, // VERIFIED without the verification time
                { stored(b, AuthenticationStatus.IN_PROGRESS, resultRef = resultRefDigest()) }, // result reference on an open session
                { stored(b, AuthenticationStatus.IN_PROGRESS, aref = authenticationRef) }, // authentication reference on a non-VERIFIED session
            ).forEachIndexed { i, row -> withClue("row $i") { shouldThrow<IllegalArgumentException> { row() } } }
        }
    }
})

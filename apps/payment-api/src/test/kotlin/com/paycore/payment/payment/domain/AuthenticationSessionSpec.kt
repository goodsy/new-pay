package com.paycore.payment.payment.domain

import com.paycore.payment.payment.domain.AuthorizationFixture.authorizationId
import com.paycore.payment.payment.domain.AuthorizationFixture.binding
import com.paycore.payment.payment.domain.AuthorizationFixture.evidence
import com.paycore.payment.payment.domain.AuthorizationFixture.inProgressSession
import com.paycore.payment.payment.domain.AuthorizationFixture.sessionTtl
import com.paycore.payment.payment.domain.AuthorizationFixture.startedAt
import com.paycore.payment.payment.domain.AuthorizationFixture.verifiedSession
import com.paycore.payment.payment.domain.MoneyFixture.won
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * AuthenticationSession lifecycle(D-AUTH-001, 명세 §5.3): CREATED/IN_PROGRESS/VERIFIED/FAILED/EXPIRED, 인증 근거는 하나의 승인
 * operation에만 귀속, 인증 성공 ≠ 승인 성공, 귀속 뒤 TTL 만료가 승인을 바꾸지 않음.
 */
class AuthenticationSessionSpec : BehaviorSpec({

    val expiresAt = startedAt.plus(sessionTtl)

    Given("a REQUESTED payment") {
        Then("a new session starts CREATED, bound to the payment conditions, and moves to IN_PROGRESS") {
            val payment = PaymentFixture.requested()
            val s = AuthenticationSession.create(AuthorizationFixture.sessionId, payment, binding(payment), expiresAt, startedAt.minusSeconds(60), emptyList())
            s.status shouldBe AuthenticationStatus.CREATED
            s.start()
            s.status shouldBe AuthenticationStatus.IN_PROGRESS
            shouldThrow<InvalidAuthenticationTransitionException> { s.start() }
        }
        Then("only one open session per payment; a failed one does not block a new attempt in the same payment window") {
            val payment = PaymentFixture.requested()
            val first = inProgressSession(payment)
            shouldThrow<IllegalArgumentException> {
                AuthenticationSession.create(AuthenticationSessionId("demo-authn-0002"), payment, binding(payment), expiresAt, startedAt, listOf(first))
            }
            first.fail(AuthenticationFailure.USER_CANCELLED)
            val second = AuthenticationSession.create(AuthenticationSessionId("demo-authn-0002"), payment, binding(payment), expiresAt, startedAt, listOf(first))
            second.status shouldBe AuthenticationStatus.CREATED
        }
        Then("a session cannot start for a payment whose authorization started, nor bind to another payment's conditions") {
            val authorizing = PaymentFixture.authorizing()
            shouldThrow<IllegalArgumentException> {
                AuthenticationSession.create(AuthorizationFixture.sessionId, authorizing, binding(authorizing), expiresAt, startedAt, emptyList())
            }
            val payment = PaymentFixture.requested()
            val other = PaymentFixture.requested(n = 2)
            shouldThrow<IllegalArgumentException> {
                AuthenticationSession.create(AuthorizationFixture.sessionId, payment, binding(other), expiresAt, startedAt, emptyList())
            }
        }
    }

    Given("an IN_PROGRESS session receiving the server-verified result") {
        Then("matching evidence within the TTL verifies it; the same evidence again is idempotent") {
            val s = inProgressSession(PaymentFixture.requested())
            val ev = evidence(s.binding)
            s.verify(ev, startedAt) shouldBe VerificationOutcome.VERIFIED
            s.status shouldBe AuthenticationStatus.VERIFIED
            s.verified shouldBe ev
            s.verify(ev, startedAt.plusSeconds(1)) shouldBe VerificationOutcome.DUPLICATE
        }
        Then("different evidence after VERIFIED is rejected and never overwrites the first evidence") {
            val s = inProgressSession(PaymentFixture.requested())
            val ev = evidence(s.binding)
            s.verify(ev, startedAt)
            shouldThrow<AuthenticationEvidenceConflictException> { s.verify(evidence(s.binding, ref = "demo-authn-evidence-2"), startedAt) }
            s.verified shouldBe ev
        }
        Then("evidence for different conditions (amount) fails the session with BINDING_MISMATCH") {
            val payment = PaymentFixture.requested()
            val s = inProgressSession(payment)
            s.verify(evidence(binding(payment, money = 10_001L.won())), startedAt) shouldBe VerificationOutcome.REJECTED_BINDING_MISMATCH
            s.status shouldBe AuthenticationStatus.FAILED
            s.failure shouldBe AuthenticationFailure.BINDING_MISMATCH
        }
        Then("evidence arriving at or after expiresAt expires the session") {
            val s = inProgressSession(PaymentFixture.requested())
            s.verify(evidence(s.binding), expiresAt) shouldBe VerificationOutcome.EXPIRED
            s.status shouldBe AuthenticationStatus.EXPIRED
        }
        Then("a late success for a terminated session is ignored and grants nothing") {
            val s = inProgressSession(PaymentFixture.requested())
            s.fail(AuthenticationFailure.AUTHENTICATION_FAILED)
            s.verify(evidence(s.binding), startedAt) shouldBe VerificationOutcome.IGNORED_TERMINAL
            s.status shouldBe AuthenticationStatus.FAILED
            s.verified shouldBe null
        }
        Then("a CREATED session (not yet sent to the provider) cannot be verified") {
            val payment = PaymentFixture.requested()
            val s = AuthenticationSession.create(AuthorizationFixture.sessionId, payment, binding(payment), expiresAt, startedAt, emptyList())
            shouldThrow<InvalidAuthenticationTransitionException> { s.verify(evidence(s.binding), startedAt) }
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
            shouldThrow<IllegalArgumentException> {
                AuthenticationSession.reconstitute(AuthorizationFixture.sessionId, b, expiresAt, startedAt, AuthenticationStatus.VERIFIED, null, null, null)
            }
            shouldThrow<IllegalArgumentException> {
                AuthenticationSession.reconstitute(AuthorizationFixture.sessionId, b, expiresAt, startedAt, AuthenticationStatus.FAILED, null, null, authorizationId)
            }
            AuthenticationSession.reconstitute(
                AuthorizationFixture.sessionId, b, expiresAt, startedAt, AuthenticationStatus.VERIFIED, evidence(b), null, authorizationId,
            ).isBound() shouldBe true
        }
    }
})

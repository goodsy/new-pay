package com.paycore.payment.payment.domain

import com.paycore.payment.payment.domain.AuthorizationFixture.inProgressSession
import com.paycore.payment.payment.domain.AuthorizationFixture.startedAt
import com.paycore.payment.payment.domain.AuthorizationFixture.verifiedSession
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * 결제창 만료(D-AUTH-005, 명세 §5.1·§5.5): REQUESTED Payment는 미전송이므로 세션을 모두 폐기한 뒤 EXPIRED(NOT_TRANSMITTED_AND_SESSION_DISCARDED).
 */
class RequestedPaymentExpirySpec : BehaviorSpec({

    Given("a REQUESTED payment whose window closes") {
        Then("open and unbound VERIFIED sessions are expired first, then the payment expires with a safe termination") {
            val payment = PaymentFixture.requested()
            val failed = inProgressSession(payment).also { it.fail(AuthenticationFailure.USER_CANCELLED) }
            val verified = verifiedSession(payment)
            RequestedPaymentExpiry.expire(payment, listOf(failed, verified), startedAt)
            failed.status shouldBe AuthenticationStatus.FAILED
            verified.status shouldBe AuthenticationStatus.EXPIRED
            payment.status shouldBe PaymentStatus.EXPIRED
            payment.terminationEvidence shouldBe listOf(TerminationEvidence.NOT_TRANSMITTED_AND_SESSION_DISCARDED)
            payment.hasSafeTermination() shouldBe true
        }
        Then("without any session it still expires (nothing to discard)") {
            val payment = PaymentFixture.requested()
            RequestedPaymentExpiry.expire(payment, emptyList(), startedAt)
            payment.hasSafeTermination() shouldBe true
        }
    }

    Given("a payment whose authorization started") {
        Then("the window expiry cannot end it (it may have been sent) and its bound session is untouched") {
            val s = AuthorizationFixture.started()
            shouldThrow<InvalidPaymentTransitionException> { RequestedPaymentExpiry.expire(s.payment, listOf(s.session), startedAt) }
            s.payment.status shouldBe PaymentStatus.AUTHORIZING
            s.session.status shouldBe AuthenticationStatus.VERIFIED
        }
    }

    Given("sessions of another payment") {
        Then("they are rejected and nothing changes") {
            val payment = PaymentFixture.requested()
            val other = inProgressSession(PaymentFixture.requested(n = 2))
            shouldThrow<IllegalArgumentException> { RequestedPaymentExpiry.expire(payment, listOf(other), startedAt) }
            payment.status shouldBe PaymentStatus.REQUESTED
            other.status shouldBe AuthenticationStatus.IN_PROGRESS
        }
    }
})

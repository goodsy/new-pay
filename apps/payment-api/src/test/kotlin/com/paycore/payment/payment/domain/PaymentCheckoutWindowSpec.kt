package com.paycore.payment.payment.domain

import com.paycore.payment.payment.domain.AuthorizationFixture.inProgressSession
import com.paycore.payment.payment.domain.AuthorizationFixture.startedAt
import com.paycore.payment.payment.domain.AuthorizationFixture.verifiedSession
import com.paycore.payment.payment.domain.PaymentFixture.CHECKOUT_VALIDITY_VERSION
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * Payment 결제창 시각(D-AUTHN-005·007, AUTHN-001 명세 §7.5.2): 생성 시 고정, V7 이전 REQUESTED만 한 번 채움(연장·변경 없음), 경과 판정은 `now ≥ expiresAt`.
 * `expireIfElapsed`는 REQUESTED·경과일 때만 기존 결제창 만료(NOT_TRANSMITTED_AND_SESSION_DISCARDED)를 실행한다.
 */
class PaymentCheckoutWindowSpec : BehaviorSpec({

    val at = startedAt

    Given("a Payment created with its checkout window") {
        Then("the window is fixed and elapses exactly at expiresAt") {
            val p = PaymentFixture.requestedWithCheckoutWindow(expiresAt = at)
            p.checkoutWindow shouldBe CheckoutWindow(at, CHECKOUT_VALIDITY_VERSION)
            p.isCheckoutWindowElapsed(at.minusMillis(1)) shouldBe false
            p.isCheckoutWindowElapsed(at) shouldBe true
        }
        Then("the validity version is required and at most 64 characters") {
            shouldThrow<IllegalArgumentException> { CheckoutWindow(at, "") }
            shouldThrow<IllegalArgumentException> { CheckoutWindow(at, "v".repeat(65)) }
        }
    }

    // V7 이전 행 채우기는 payment-api 기동 migration 전략(JDBC) 한 곳이 한다(검토 #2 m-2 — Coordinator 판정, §24.13). domain에는 채우기 연산이 없다: 결제창 시각은
    // `request`가 고정하고 `reconstitute`만 NULL(채우기 전 V7 이전 행)을 허용한다.
    Given("a stored pre-V7 Payment without a window (D-AUTHN-007)") {
        val legacy = { status: PaymentStatus -> PaymentFixture.storedWithoutWindow(status) }
        Then("it reconstitutes without a window in any status and is never elapsed while missing") {
            listOf(PaymentStatus.REQUESTED, PaymentStatus.AUTHORIZING).forEach { status ->
                val p = legacy(status)
                p.checkoutWindow shouldBe null
                p.isCheckoutWindowElapsed(at.plusSeconds(86_400)) shouldBe false
            }
        }
    }

    Given("expireIfElapsed") {
        Then("EXPIRED: an elapsed REQUESTED Payment discards its open and unbound VERIFIED sessions and expires with a safe termination") {
            val p = PaymentFixture.requestedWithCheckoutWindow(expiresAt = at)
            val open = inProgressSession(p)
            RequestedPaymentExpiry.expireIfElapsed(p, listOf(open), at) shouldBe RequestedExpiryOutcome.EXPIRED
            open.expiryCause shouldBe AuthenticationExpiryCause.PAYMENT_CLOSED
            p.status shouldBe PaymentStatus.EXPIRED
            p.terminationEvidence shouldBe listOf(TerminationEvidence.NOT_TRANSMITTED_AND_SESSION_DISCARDED)
            val q = PaymentFixture.requestedWithCheckoutWindow(expiresAt = at, n = 2)
            val verified = verifiedSession(q)
            RequestedPaymentExpiry.expireIfElapsed(q, listOf(verified), at) shouldBe RequestedExpiryOutcome.EXPIRED
            verified.status shouldBe AuthenticationStatus.EXPIRED
        }
        Then("NOT_DUE: before expiresAt nothing changes") {
            val p = PaymentFixture.requestedWithCheckoutWindow(expiresAt = at)
            val open = inProgressSession(p)
            RequestedPaymentExpiry.expireIfElapsed(p, listOf(open), at.minusMillis(1)) shouldBe RequestedExpiryOutcome.NOT_DUE
            p.status shouldBe PaymentStatus.REQUESTED
            open.status shouldBe AuthenticationStatus.IN_PROGRESS
        }
        Then("NOT_REQUESTED: an already expired Payment (second attempt) or one whose authorization started is not touched") {
            val p = PaymentFixture.requestedWithCheckoutWindow(expiresAt = at)
            RequestedPaymentExpiry.expireIfElapsed(p, emptyList(), at) shouldBe RequestedExpiryOutcome.EXPIRED
            RequestedPaymentExpiry.expireIfElapsed(p, emptyList(), at.plusSeconds(1)) shouldBe RequestedExpiryOutcome.NOT_REQUESTED
            p.terminationEvidence.size shouldBe 1
            val s = AuthorizationFixture.started()
            RequestedPaymentExpiry.expireIfElapsed(s.payment, listOf(s.session), at.plusSeconds(86_400)) shouldBe RequestedExpiryOutcome.NOT_REQUESTED
            s.payment.status shouldBe PaymentStatus.AUTHORIZING
        }
        Then("NO_WINDOW: a REQUESTED Payment without a window is reported (defect guard), not expired") {
            val p = PaymentFixture.storedWithoutWindow()
            RequestedPaymentExpiry.expireIfElapsed(p, emptyList(), at.plusSeconds(86_400)) shouldBe RequestedExpiryOutcome.NO_WINDOW
            p.status shouldBe PaymentStatus.REQUESTED
        }
    }
})

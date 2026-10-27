package com.paycore.payment.payment.domain

import com.paycore.payment.payment.domain.AuthorizationFixture.authenticationRef
import com.paycore.payment.payment.domain.AuthorizationFixture.inProgressSession
import com.paycore.payment.payment.domain.AuthorizationFixture.resultRefDigest
import com.paycore.payment.payment.domain.AuthorizationFixture.startedAt
import com.paycore.payment.payment.domain.AuthorizationFixture.verifiedSession
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.equals.shouldBeEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.time.Instant

/**
 * 인증 결과 복귀 판정(AUTHN-001 명세 rev 1.3 §7.3): 세션은 application이 URL state digest로 고른다(전제). 1단계(조회 전) P1~P6, 2단계(조회 뒤, TX-C)는
 * 1단계를 다시 판정한 뒤 R3 → R6 → R5 → R7 → R8 → R9 → R10 → R11. 기대값은 명세 표에서 독립적으로 적었다.
 */
class AuthenticationResultCheckSpec : BehaviorSpec({

    val now = startedAt
    val newRef = AuthenticationRef("aref_" + "N".repeat(22))

    fun presented(session: AuthenticationSession, rrd: SecretDigest = resultRefDigest(9)) = PresentedResult(session.correlation.stateDigest, rrd)

    fun decision(verdict: ResultVerdict, event: AuthenticationEventKind?, detail: AuthenticationEventDetail?) = ResultCheckDecision(verdict, event, detail)

    fun after(session: AuthenticationSession, payment: Payment, lookup: ProviderResultLookup, at: Instant = now, rrd: SecretDigest = resultRefDigest(9)) =
        AuthenticationResultCheck.afterLookup(session, payment, presented(session, rrd), lookup, newRef, at)

    fun found(result: ProviderAuthenticationResult) = ProviderResultLookup.Found(result)

    /** 정상 결과에서 echo 바인딩 한 값만 바꾼다(Invalid 의도가 이름에 드러나게 — R7 행). */
    fun echoChanged(s: AuthenticationSession, change: (ProviderBindingEcho) -> ProviderBindingEcho) =
        ProviderResultFixture.authenticatedMatching(s).let { it.copy(binding = change(it.binding)) }

    Given("the session-selection precondition") {
        Then("a presented state digest that is not the session's is a caller defect (the application selects the session by state digest)") {
            val payment = PaymentFixture.requested()
            val s = inProgressSession(payment)
            shouldThrow<IllegalArgumentException> {
                AuthenticationResultCheck.beforeLookup(s, payment, PresentedResult(SecretDigest.of(SecretKind.AUTHENTICATION_STATE, "other"), resultRefDigest()), now)
            }
            shouldThrow<IllegalArgumentException> { AuthenticationResultCheck.beforeLookup(s, PaymentFixture.requested(n = 2), presented(s), now) }
        }
    }

    Given("phase 1 (before the lookup — no external call)") {
        Then("P1: a FAILED or EXPIRED session ignores the late result and stays as it is") {
            val payment = PaymentFixture.requested()
            val failed = inProgressSession(payment).also { it.fail(AuthenticationFailure.USER_CANCELLED) }
            AuthenticationResultCheck.beforeLookup(failed, payment, presented(failed), now) shouldBe
                decision(ResultVerdict.IGNORED, AuthenticationEventKind.IGNORED_LATE_RESULT, null)
            failed.status shouldBe AuthenticationStatus.FAILED
            val superseded = verifiedSession(payment).also { it.expire(AuthenticationExpiryCause.SUPERSEDED, now) }
            AuthenticationResultCheck.beforeLookup(superseded, payment, presented(superseded, resultRefDigest()), now).verdict shouldBe ResultVerdict.IGNORED
            superseded.expiryCause shouldBe AuthenticationExpiryCause.SUPERSEDED
        }
        Then("P2: VERIFIED with the same result reference is a DUPLICATE (redirect again), nothing changes") {
            val payment = PaymentFixture.requested()
            val v = verifiedSession(payment)
            AuthenticationResultCheck.beforeLookup(v, payment, presented(v, resultRefDigest()), now) shouldBe
                decision(ResultVerdict.DUPLICATE, AuthenticationEventKind.DUPLICATE_RESULT, null)
            v.authenticationRef shouldBe authenticationRef
        }
        Then("P3: VERIFIED with another result reference is rejected as an evidence conflict and never overwritten") {
            val payment = PaymentFixture.requested()
            val v = verifiedSession(payment)
            AuthenticationResultCheck.beforeLookup(v, payment, presented(v, resultRefDigest(2)), now) shouldBe
                decision(ResultVerdict.REJECTED, AuthenticationEventKind.REJECTED_RESULT, AuthenticationEventDetail.EVIDENCE_CONFLICT)
            v.resultRefDigest shouldBe resultRefDigest()
        }
        Then("P4: a CREATED session (registration not completed) is rejected without a state change") {
            val payment = PaymentFixture.requested()
            val s = AuthenticationSession.create(AuthorizationFixture.sessionId, payment, AuthorizationFixture.binding(payment), AuthorizationFixture.correlation(),
                now.plus(AuthorizationFixture.sessionTtl), now, emptyList())
            AuthenticationResultCheck.beforeLookup(s, payment, presented(s), now) shouldBe
                decision(ResultVerdict.REJECTED, AuthenticationEventKind.REJECTED_RESULT, AuthenticationEventDetail.SESSION_NOT_STARTED)
            s.status shouldBe AuthenticationStatus.CREATED
        }
        Then("P5: an elapsed checkout window closes the session (PAYMENT_CLOSED) but never changes the Payment (no control lock on this path)") {
            val payment = PaymentFixture.requestedWithCheckoutWindow(expiresAt = now)
            val s = inProgressSession(payment)
            AuthenticationResultCheck.beforeLookup(s, payment, presented(s), now) shouldBe
                decision(ResultVerdict.PAYMENT_CLOSED, AuthenticationEventKind.EXPIRED, AuthenticationEventDetail.PAYMENT_CLOSED)
            s.expiryCause shouldBe AuthenticationExpiryCause.PAYMENT_CLOSED
            payment.status shouldBe PaymentStatus.REQUESTED
        }
        Then("P5: a Payment that is no longer REQUESTED also closes the session") {
            val payment = PaymentFixture.requested()
            val s = inProgressSession(payment)
            payment.startAuthorization()
            AuthenticationResultCheck.beforeLookup(s, payment, presented(s), now).verdict shouldBe ResultVerdict.PAYMENT_CLOSED
            s.status shouldBe AuthenticationStatus.EXPIRED
        }
        Then("P5 before P6: when both the checkout window and the session TTL have elapsed the session closes as PAYMENT_CLOSED (n-4)") {
            val payment = PaymentFixture.requestedWithCheckoutWindow(expiresAt = now)
            val s = inProgressSession(payment)
            AuthenticationResultCheck.beforeLookup(s, payment, presented(s), s.expiresAt.plusSeconds(1)).verdict shouldBe ResultVerdict.PAYMENT_CLOSED
            s.expiryCause shouldBe AuthenticationExpiryCause.PAYMENT_CLOSED
        }
        Then("P6: at or after the session expiry the session expires by TTL") {
            val payment = PaymentFixture.requested()
            val s = inProgressSession(payment)
            AuthenticationResultCheck.beforeLookup(s, payment, presented(s), s.expiresAt) shouldBe
                decision(ResultVerdict.SESSION_EXPIRED, AuthenticationEventKind.EXPIRED, AuthenticationEventDetail.TTL_ELAPSED)
            s.expiryCause shouldBe AuthenticationExpiryCause.TTL_ELAPSED
        }
        Then("an open, valid IN_PROGRESS session proceeds to the lookup unchanged") {
            val payment = PaymentFixture.requestedWithCheckoutWindow(expiresAt = now.plusSeconds(1))
            val s = inProgressSession(payment)
            AuthenticationResultCheck.beforeLookup(s, payment, presented(s), now) shouldBe decision(ResultVerdict.PROCEED, null, null)
            s.status shouldBe AuthenticationStatus.IN_PROGRESS
        }
    }

    Given("phase 2 (TX-C, after the lookup)") {
        Then("phase 1 is evaluated again first: a session verified meanwhile with the same reference is a DUPLICATE (C4)") {
            val payment = PaymentFixture.requested()
            val v = verifiedSession(payment)
            after(v, payment, found(ProviderResultFixture.authenticatedMatching(v)), rrd = resultRefDigest()).verdict shouldBe ResultVerdict.DUPLICATE
            after(v, payment, found(ProviderResultFixture.authenticatedMatching(v)), rrd = resultRefDigest(2)).verdict shouldBe ResultVerdict.REJECTED
        }
        Then("R3: an unverifiable lookup (each class, HTTP 404 included) keeps the session IN_PROGRESS") {
            LookupFailure.entries.forEach { failure ->
                withClue(failure) {
                    val payment = PaymentFixture.requested()
                    val s = inProgressSession(payment)
                    after(s, payment, ProviderResultLookup.Unverifiable(failure)) shouldBe
                        decision(ResultVerdict.UNVERIFIABLE, AuthenticationEventKind.LOOKUP_UNVERIFIABLE, AuthenticationEventDetail.valueOf(failure.name))
                    s.status shouldBe AuthenticationStatus.IN_PROGRESS
                    s.resultRefDigest shouldBe null
                }
            }
        }

        data class Row(val name: String, val result: (AuthenticationSession) -> ProviderAuthenticationResult, val failure: AuthenticationFailure, val detail: AuthenticationEventDetail)
        val failedRows = listOf(
            Row("R6 another provider transaction", ProviderResultFixture::authenticatedForAnotherTransaction, AuthenticationFailure.STATE_MISMATCH, AuthenticationEventDetail.STATE_MISMATCH),
            Row("R6 another echoed state", ProviderResultFixture::authenticatedWithDifferentEchoedState, AuthenticationFailure.STATE_MISMATCH, AuthenticationEventDetail.STATE_MISMATCH),
            Row("R6 before R5 (another transaction, also expired)", { s -> ProviderResultFixture.authenticatedForAnotherTransaction(s).copy(outcome = ProviderOutcome.EXPIRED, authenticated = null) },
                AuthenticationFailure.STATE_MISMATCH, AuthenticationEventDetail.STATE_MISMATCH),
            Row("R5 outcome EXPIRED", ProviderResultFixture::expiredAtProvider, AuthenticationFailure.PROVIDER_EXPIRED, AuthenticationEventDetail.PROVIDER_EXPIRED),
            Row("R5 validUntil reached", { s -> ProviderResultFixture.authenticatedMatching(s, validUntil = now) }, AuthenticationFailure.PROVIDER_EXPIRED, AuthenticationEventDetail.PROVIDER_EXPIRED),
            Row("R5 before R7 (expired and wrong amount)", { s -> ProviderResultFixture.authenticatedWithDifferentAmount(s).copy(validUntil = now) },
                AuthenticationFailure.PROVIDER_EXPIRED, AuthenticationEventDetail.PROVIDER_EXPIRED),
            Row("R7 amount", ProviderResultFixture::authenticatedWithDifferentAmount, AuthenticationFailure.BINDING_MISMATCH, AuthenticationEventDetail.BINDING_MISMATCH),
            Row("R7 paymentId", { s -> echoChanged(s) { it.copy(paymentId = PaymentId("demo-pay-other")) } }, AuthenticationFailure.BINDING_MISMATCH, AuthenticationEventDetail.BINDING_MISMATCH),
            Row("R7 currency", { s -> echoChanged(s) { it.copy(money = Money(it.money.amountMinor, CurrencyCode("USD"))) } }, AuthenticationFailure.BINDING_MISMATCH, AuthenticationEventDetail.BINDING_MISMATCH),
            Row("R7 environment", { s -> echoChanged(s) { it.copy(environment = Environment.LIVE) } }, AuthenticationFailure.BINDING_MISMATCH, AuthenticationEventDetail.BINDING_MISMATCH),
            Row("R7 plan", { s -> echoChanged(s) { it.copy(plan = PaymentPlan.Installment(3)) } }, AuthenticationFailure.BINDING_MISMATCH, AuthenticationEventDetail.BINDING_MISMATCH),
            Row("R7 card company", { s -> echoChanged(s) { it.copy(cardCompany = "SIMISS-OTHER") } }, AuthenticationFailure.BINDING_MISMATCH, AuthenticationEventDetail.BINDING_MISMATCH),
            Row("R7 MID", ProviderResultFixture::authenticatedWithDifferentMid, AuthenticationFailure.BINDING_MISMATCH, AuthenticationEventDetail.BINDING_MISMATCH),
            Row("R7 issuer differs from the chosen card company", ProviderResultFixture::authenticatedByAnotherIssuer, AuthenticationFailure.BINDING_MISMATCH, AuthenticationEventDetail.BINDING_MISMATCH),
            Row("R7 before R8 (failed with a wrong amount)", { s -> ProviderResultFixture.failed(s).let { it.copy(binding = ProviderResultFixture.authenticatedWithDifferentAmount(s).binding) } },
                AuthenticationFailure.BINDING_MISMATCH, AuthenticationEventDetail.BINDING_MISMATCH),
            Row("R8 authentication failed", ProviderResultFixture::failed, AuthenticationFailure.AUTHENTICATION_FAILED, AuthenticationEventDetail.AUTHENTICATION_FAILED),
            Row("R9 user cancelled", ProviderResultFixture::cancelled, AuthenticationFailure.USER_CANCELLED, AuthenticationEventDetail.USER_CANCELLED),
            Row("R10 unsupported card type", ProviderResultFixture::authenticatedWithUnsupportedCardType, AuthenticationFailure.AUTHENTICATION_FAILED, AuthenticationEventDetail.UNSUPPORTED_CARD_TYPE),
        )
        Then("R6·R5·R7·R8·R9·R10 fail the session with the reason and issue no authentication reference; R5·R7~R10 keep the consumed result reference, R6 does not (m-1)") {
            failedRows.forEach { row ->
                withClue(row.name) {
                    val payment = PaymentFixture.requested()
                    val s = inProgressSession(payment)
                    after(s, payment, found(row.result(s))) shouldBe decision(ResultVerdict.FAILED, AuthenticationEventKind.FAILED, row.detail)
                    s.status shouldBe AuthenticationStatus.FAILED
                    s.failure shouldBe row.failure
                    // R6은 다른 거래의 결과 참조다 — 이 세션의 소비 기록으로 남기면 그 참조의 정당한 세션이 1062로 막힌다(검토 m-1, Coordinator 판정).
                    s.resultRefDigest shouldBe if (row.failure == AuthenticationFailure.STATE_MISMATCH) null else resultRefDigest(9)
                    s.authenticationRef shouldBe null
                }
            }
        }
        Then("R11: a matching AUTHENTICATED result verifies the session with the provider evidence, the new authentication reference and the result reference") {
            listOf("CREDIT" to CardType.CREDIT, "CHECK" to CardType.CHECK).forEach { (token, type) ->
                val payment = PaymentFixture.requested()
                val s = inProgressSession(payment)
                after(s, payment, found(ProviderResultFixture.authenticatedMatching(s, cardType = token))) shouldBe
                    decision(ResultVerdict.VERIFIED, AuthenticationEventKind.VERIFIED, null)
                s.status shouldBe AuthenticationStatus.VERIFIED
                s.verified.shouldNotBeNull() shouldBeEqual VerifiedAuthentication(
                    "demo-authn-evidence-1", s.binding, type, s.binding.cardCompany, "DEMO-ACQUIRER", "DEMO-AUTHN-PROVIDER", AuthorizationFixture.providerValidUntil,
                )
                s.authenticationRef shouldBe newRef
                s.resultRefDigest shouldBe resultRefDigest(9)
                s.verifiedAt shouldBe now
            }
        }
    }

    Given("the provider result shape (m-E: required fields per outcome)") {
        Then("AUTHENTICATED needs the evidence details and the other outcomes must not carry them") {
            val payment = PaymentFixture.requested()
            val s = inProgressSession(payment)
            shouldThrow<IllegalArgumentException> { ProviderResultFixture.authenticatedMatching(s).copy(authenticated = null) }
            shouldThrow<IllegalArgumentException> { ProviderResultFixture.failed(s).copy(authenticated = ProviderResultFixture.details(s)) }
        }
    }
})

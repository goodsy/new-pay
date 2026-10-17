package com.paycore.payment.payment.domain

import com.paycore.payment.payment.domain.AuthorizationFixture.binding
import com.paycore.payment.payment.domain.AuthorizationFixture.correlation
import com.paycore.payment.payment.domain.AuthorizationFixture.inProgressSession
import com.paycore.payment.payment.domain.AuthorizationFixture.sessionTtl
import com.paycore.payment.payment.domain.AuthorizationFixture.startedAt
import com.paycore.payment.payment.domain.AuthorizationFixture.verifiedSession
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

/**
 * 인증 시작 domain 서비스(AUTHN-001 명세 §7.1·§7.2, AN-07, 관례 §12 "다중 Aggregate 연산은 검사 먼저"): 모든 검사 → 같은 Payment의 미폐기·미귀속
 * 세션(비종료와 VERIFIED 미귀속)을 EXPIRED(SUPERSEDED, 이미 만료 시각이 지났으면 TTL_ELAPSED) → 새 CREATED 세션. 검사가 실패하면 어떤 세션도 바뀌지 않는다.
 */
class AuthenticationSessionStartSpec : BehaviorSpec({

    val newId = AuthenticationSessionId("demo-authn-0101")
    val now = startedAt
    val newExpiresAt = now.plus(sessionTtl)

    fun start(payment: Payment, sessions: List<AuthenticationSession>, at: Instant = now, expiresAt: Instant = newExpiresAt, n: Int = 101) =
        AuthenticationSessionStart.start(payment, sessions, newId, binding(payment), correlation(n), expiresAt, at)

    Given("a REQUESTED payment without sessions") {
        Then("a new CREATED session is opened with the given correlation and nothing is superseded") {
            val payment = PaymentFixture.requested()
            val started = start(payment, emptyList())
            started.session.status shouldBe AuthenticationStatus.CREATED
            started.session.id shouldBe newId
            started.session.correlation shouldBe correlation(101)
            started.session.expiresAt shouldBe newExpiresAt
            started.superseded shouldBe emptyList()
        }
    }

    Given("a REQUESTED payment with earlier sessions (re-authentication in the same window)") {
        Then("an open session is superseded before the new one is created") {
            val payment = PaymentFixture.requested()
            val open = inProgressSession(payment)
            val started = start(payment, listOf(open))
            open.status shouldBe AuthenticationStatus.EXPIRED
            open.expiryCause shouldBe AuthenticationExpiryCause.SUPERSEDED
            started.superseded shouldBe listOf(open)
            started.session.status shouldBe AuthenticationStatus.CREATED
        }
        Then("an unbound VERIFIED session is superseded too, so at most one usable authentication remains (AN-07)") {
            val payment = PaymentFixture.requested()
            val verified = verifiedSession(payment)
            val started = start(payment, listOf(verified))
            verified.status shouldBe AuthenticationStatus.EXPIRED
            verified.expiryCause shouldBe AuthenticationExpiryCause.SUPERSEDED
            started.superseded shouldBe listOf(verified)
        }
        Then("an open session already past its expiry is recorded as TTL_ELAPSED, not SUPERSEDED") {
            val payment = PaymentFixture.requested()
            val stale = inProgressSession(payment)
            val later = stale.expiresAt.plusSeconds(1)
            start(payment, listOf(stale), at = later, expiresAt = later.plus(sessionTtl))
            stale.expiryCause shouldBe AuthenticationExpiryCause.TTL_ELAPSED
        }
        Then("FAILED and EXPIRED sessions are left as they are and are not reported as superseded") {
            val payment = PaymentFixture.requested()
            val failed = inProgressSession(payment, id = AuthenticationSessionId("demo-authn-0002")).also { it.fail(AuthenticationFailure.USER_CANCELLED) }
            val expired = inProgressSession(payment, id = AuthenticationSessionId("demo-authn-0003"))
                .also { it.expire(AuthenticationExpiryCause.PAYMENT_CLOSED, now) }
            val started = start(payment, listOf(failed, expired))
            failed.status shouldBe AuthenticationStatus.FAILED
            expired.expiryCause shouldBe AuthenticationExpiryCause.PAYMENT_CLOSED
            started.superseded shouldBe emptyList()
        }
    }

    Given("a start that must be refused (checks first — every earlier session is unchanged)") {
        Then("each refusal leaves the open and VERIFIED sessions untouched") {
            data class Case(val name: String, val run: (Payment, List<AuthenticationSession>) -> Unit)
            val cases = listOf(
                Case("binding of another payment") { p, s ->
                    AuthenticationSessionStart.start(p, s, newId, binding(PaymentFixture.requested(n = 2)), correlation(101), newExpiresAt, now)
                },
                Case("expiresAt not after now") { p, s -> start(p, s, expiresAt = now) },
                Case("a session of another payment in the list") { p, s -> start(p, s + inProgressSession(PaymentFixture.requested(n = 2))) },
                Case("the new id already used") { p, s ->
                    AuthenticationSessionStart.start(p, s, AuthorizationFixture.sessionId, binding(p), correlation(101), newExpiresAt, now)
                },
                Case("the new state digest already used") { p, s -> start(p, s, n = 1) },
            )
            cases.forEach { case ->
                withClue(case.name) {
                    val payment = PaymentFixture.requested()
                    val open = inProgressSession(payment)
                    shouldThrow<IllegalArgumentException> { case.run(payment, listOf(open)) }
                    open.status shouldBe AuthenticationStatus.IN_PROGRESS
                    open.expiryCause shouldBe null
                }
            }
        }
        Then("a payment whose authorization started is refused and its bound session stays VERIFIED and bound") {
            val s = AuthorizationFixture.started()
            shouldThrow<IllegalArgumentException> { start(s.payment, listOf(s.session)) }
            s.session.status shouldBe AuthenticationStatus.VERIFIED
            s.session.isBound() shouldBe true
        }
        Then("a REQUESTED payment with a bound session (impossible state) is refused without superseding anything") {
            // REQUESTED + 귀속 세션은 정상 경로로 만들 수 없다(귀속 = AUTHORIZING). 저장 행이 그런 조합을 줄 때를 대비한 방어를 복원 행으로 본다.
            val payment = PaymentFixture.requested()
            val open = inProgressSession(payment, id = AuthenticationSessionId("demo-authn-0002"))
            val bound = verifiedSession(payment).also { it.bindTo(AuthorizationFixture.authorizationId, payment.id, AuthorizationFixture.intent.money, now) }
            shouldThrow<IllegalArgumentException> { start(payment, listOf(open, bound)) }
            open.status shouldBe AuthenticationStatus.IN_PROGRESS
        }
    }
})

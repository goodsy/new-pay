package com.paycore.payment.payment.application

import com.paycore.payment.payment.adapter.integration.institution.StubVan.Action
import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.adapter.web.PaymentApiTest
import com.paycore.payment.payment.application.provided.AuthorizationViewStatus
import com.paycore.payment.payment.application.provided.ConfirmPaymentResult
import com.paycore.payment.payment.application.provided.ConfirmPaymentUseCase
import com.paycore.payment.payment.application.required.AuthenticationSessionRepository
import com.paycore.payment.payment.application.required.CheckoutSessionStore
import com.paycore.payment.payment.application.required.CheckoutTokenStore
import com.paycore.payment.payment.application.required.OrderPaymentControlRepository
import com.paycore.payment.payment.application.required.PaymentRepository
import com.paycore.payment.payment.domain.ApprovedTerms
import com.paycore.payment.payment.domain.AttemptId
import com.paycore.payment.payment.domain.AuthorizationId
import com.paycore.payment.payment.domain.CardType
import com.paycore.payment.payment.domain.InstitutionObservation
import com.paycore.payment.payment.domain.InstitutionOutcome
import com.paycore.payment.payment.domain.ObservationSource
import com.paycore.payment.payment.domain.PaymentPlan
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.transaction.PlatformTransactionManager
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 결과 반영(TX2 — 응답·조회·주입 공통 [AuthorizationResultService])의 경합(AUTH-001 §8 C25·C27). 같은 기관 결정이 여러 출처로 동시에 와도 금융 사실 1건·이벤트 1건, 같은 늦은 성공이
 * 동시에 여러 번 와도 hold 1건·사건 이벤트 1건(F-1, AU-17)이며 1062가 새지 않는다. 실 MySQL 다중 연결.
 */
@PaymentApiTest
class AuthorizationResultConcurrencySpec(
    private val confirm: ConfirmPaymentUseCase,
    private val results: AuthorizationResultService,
    private val van: ProgrammableVan,
    payments: PaymentRepository,
    controls: OrderPaymentControlRepository,
    sessions: AuthenticationSessionRepository,
    tokens: CheckoutTokenStore,
    checkoutSessions: CheckoutSessionStore,
    transactionManager: PlatformTransactionManager,
    db: DbAccess,
) : BehaviorSpec({

    val fixture = ConfirmFixture(payments, controls, sessions, tokens, checkoutSessions, transactionManager, db)
    val pool = Executors.newFixedThreadPool(8)
    afterSpec { pool.shutdownNow() }

    fun race(tasks: List<() -> Any?>): List<Result<Any?>> {
        val barrier = CyclicBarrier(tasks.size)
        return tasks.map { t -> pool.submit(Callable { barrier.await(); runCatching { t() } }) }.map { it.get(60, TimeUnit.SECONDS) }
    }

    data class Started(val p: ConfirmFixture.Prepared, val authorizationId: AuthorizationId, val attemptId: AttemptId)

    /** 응답 유실로 UNKNOWN이 된 승인(Attempt OUTCOME_UNKNOWN). */
    fun unknown(): Started {
        val p = fixture.prepared()
        van.onAuth(p.order.merchantAccountId.value) { ProgrammableVan.Script(null, Action.Close) }
        confirm.confirm(p.command()).shouldBeInstanceOf<ConfirmPaymentResult.Accepted>().view.status shouldBe AuthorizationViewStatus.UNKNOWN
        val auth = fixture.scalar("SELECT authorization_id FROM payment_authorization WHERE payment_id = ?", p.paymentId.value) as String
        val attempt = fixture.scalar("SELECT attempt_id FROM authorization_attempt WHERE authorization_id = ?", auth) as String
        return Started(p, AuthorizationId(auth), AttemptId(attempt))
    }

    fun terms(s: Started, ptx: String = "SIMPTX-C25-" + s.p.paymentId.value.takeLast(8)) =
        ApprovedTerms(s.p.money, PaymentPlan.LumpSum, CardType.CREDIT, "30000025", ptx, LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 14))

    fun observation(s: Started, source: ObservationSource, outcome: InstitutionOutcome, terms: ApprovedTerms?, resultId: String = "vd_C25DECISION0000000000000001") =
        InstitutionObservation(s.attemptId, source, resultId, outcome, terms, "sha256:demo", Instant.parse("2026-10-14T01:02:10Z"))

    Given("C25 — the same institution decision through the response and the inquiry at the same time") {
        Then("AUTHORIZED once, one transaction, one PaymentAuthorized, no exception") {
            repeat(3) {
                val s = unknown()
                val t = terms(s)
                val outcomes = race(
                    listOf(
                        { results.applyObservation(s.authorizationId, observation(s, ObservationSource.RESPONSE, InstitutionOutcome.APPROVED, t)) },
                        { results.applyObservation(s.authorizationId, observation(s, ObservationSource.INQUIRY, InstitutionOutcome.APPROVED, t)) },
                    ),
                )
                outcomes.forEach { it.getOrThrow() }
                fixture.paymentStatus(s.p) shouldBe "AUTHORIZED"
                fixture.count("SELECT COUNT(*) FROM payment_transaction WHERE authorization_id = ?", s.authorizationId.value) shouldBe 1
                fixture.count("SELECT COUNT(*) FROM outbox_event WHERE event_type = 'PaymentAuthorized' AND aggregate_id = ?", s.authorizationId.value) shouldBe 1
            }
        }
    }

    Given("C27 — the same late success after a confirmed decline reported eight times at once (response, inquiry, injection)") {
        Then("one SUCCESS_AFTER_TERMINATION hold, one AuthorizationIncidentDetected, no unique conflict leaking") {
            val s = unknown()
            results.applyObservation(s.authorizationId, observation(s, ObservationSource.INQUIRY, InstitutionOutcome.DECLINED, null, "vd_C27DECLINED00000000000001"))
            fixture.paymentStatus(s.p) shouldBe "FAILED"
            val late = terms(s, "SIMPTX-C27-LATE")
            val sources = listOf(ObservationSource.RESPONSE, ObservationSource.INQUIRY, ObservationSource.NOTIFICATION, ObservationSource.RECONCILIATION)
            val outcomes = race(
                (0 until 8).map { i -> { results.applyObservation(s.authorizationId, observation(s, sources[i % 4], InstitutionOutcome.APPROVED, late, "vd_C27LATE000000000000000001")) } },
            )
            outcomes.forEach { it.getOrThrow() }
            fixture.count("SELECT COUNT(*) FROM incident_hold WHERE payment_id = ?", s.p.paymentId.value) shouldBe 1
            fixture.count("SELECT COUNT(*) FROM outbox_event WHERE event_type = 'AuthorizationIncidentDetected' AND aggregate_id = ?", s.authorizationId.value) shouldBe 1
            fixture.count("SELECT COUNT(*) FROM payment_transaction WHERE authorization_id = ?", s.authorizationId.value) shouldBe 0
        }
    }
})

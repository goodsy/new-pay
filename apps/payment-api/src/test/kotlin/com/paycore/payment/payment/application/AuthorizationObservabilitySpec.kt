package com.paycore.payment.payment.application

import com.paycore.payment.payment.adapter.integration.institution.StubVan.Action
import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.adapter.web.ApiTestTimes.T0
import com.paycore.payment.payment.adapter.web.MutablePaymentClock
import com.paycore.payment.payment.adapter.web.PaymentApiTest
import com.paycore.payment.payment.application.provided.AuthorizationViewStatus
import com.paycore.payment.payment.application.provided.ConfirmPaymentResult
import com.paycore.payment.payment.application.provided.ConfirmPaymentUseCase
import com.paycore.payment.payment.application.provided.RecoverAuthorizationsCommand
import com.paycore.payment.payment.application.provided.RecoverAuthorizationsUseCase
import com.paycore.payment.payment.application.required.AuthenticationSessionRepository
import com.paycore.payment.payment.application.required.CheckoutSessionStore
import com.paycore.payment.payment.application.required.CheckoutTokenStore
import com.paycore.payment.payment.application.required.OrderPaymentControlRepository
import com.paycore.payment.payment.application.required.PaymentRepository
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.transaction.PlatformTransactionManager
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 승인 관찰 지표(AUTH-001 §9 Observability — S8 gap 4): 비권위 jOOQ gauge(UNKNOWN Attempt 수·가장 오래된 UNKNOWN 연령·대사 이관 뒤 미결 일정 수·해제 전 사건 보류 수, label 없음)와
 * 기관 호출 counter(`paycore.authorization.dispatch{outcome}`·`paycore.authorization.inquiry{result}`)를 실 MySQL·운영 Adapter·시험 stub VAN([ProgrammableVan])으로 본다.
 * 같은 컨텍스트의 다른 spec 행도 DB에 있으므로 gauge는 (1) 이 시험의 사건 뒤 늘어난다 (2) 같은 정의의 독립 SQL 수와 같다로 판정한다(정의: 명세 §16.18).
 */
@PaymentApiTest
class AuthorizationObservabilitySpec(
    private val confirm: ConfirmPaymentUseCase,
    private val recovery: RecoverAuthorizationsUseCase,
    private val van: ProgrammableVan,
    private val clock: MutablePaymentClock,
    private val registry: MeterRegistry,
    payments: PaymentRepository,
    controls: OrderPaymentControlRepository,
    sessions: AuthenticationSessionRepository,
    tokens: CheckoutTokenStore,
    checkoutSessions: CheckoutSessionStore,
    transactionManager: PlatformTransactionManager,
    db: DbAccess,
) : BehaviorSpec({

    val fixture = ConfirmFixture(payments, controls, sessions, tokens, checkoutSessions, transactionManager, db)

    afterEach { clock.reset() }

    fun gauge(name: String): Double = registry.find(name).gauge()!!.also { it.id.tags shouldBe emptyList() }.value()
    fun counter(name: String, key: String, value: String): Double = registry.find(name).tag(key, value).counter()?.count() ?: 0.0
    fun authorizationId(p: ConfirmFixture.Prepared) = fixture.scalar("SELECT authorization_id FROM payment_authorization WHERE payment_id = ?", p.paymentId.value) as String
    fun mid(p: ConfirmFixture.Prepared) = p.order.merchantAccountId.value
    fun view(p: ConfirmFixture.Prepared) = confirm.confirm(p.command()).shouldBeInstanceOf<ConfirmPaymentResult.Accepted>().view

    fun unknownCount() = fixture.count("SELECT COUNT(*) FROM authorization_attempt WHERE state = 'OUTCOME_UNKNOWN'").toDouble()
    fun escalatedCount() = fixture.count("SELECT COUNT(*) FROM authorization_recovery_schedule WHERE escalated_at IS NOT NULL AND closed_at IS NULL").toDouble()
    fun activeHoldCount() = fixture.count(
        "SELECT COUNT(*) FROM incident_hold h WHERE NOT EXISTS (SELECT 1 FROM incident_hold_release r WHERE r.incident_hold_id = h.id)",
    ).toDouble()

    fun recoverAt(at: Instant) {
        clock.set(at)
        recovery.recover(RecoverAuthorizationsCommand(UUID.randomUUID().toString(), 1000))
    }

    Given("a confirm whose response is lost (UNKNOWN) and then escalated past the max age") {
        Then("the UNKNOWN gauge counts it, the oldest-age gauge covers its age, and after escalation the escalated gauge counts it — each equal to the same SQL definition") {
            val unknownBefore = gauge(UNKNOWN)
            val dispatchUnknownBefore = counter(DISPATCH, "outcome", "UNKNOWN")
            val p = fixture.prepared()
            van.onAuth(mid(p)) { ProgrammableVan.Script(null, Action.Close) }
            view(p).status shouldBe AuthorizationViewStatus.UNKNOWN
            counter(DISPATCH, "outcome", "UNKNOWN") shouldBe dispatchUnknownBefore + 1.0

            gauge(UNKNOWN) shouldBe unknownBefore + 1.0
            gauge(UNKNOWN) shouldBe unknownCount()

            clock.set(T0.plus(Duration.ofMinutes(30)))
            gauge(UNKNOWN_OLDEST_AGE) shouldBeGreaterThanOrEqual Duration.ofMinutes(30).seconds.toDouble() - 1.0

            val escalatedBefore = gauge(ESCALATED)
            val inquiryNotFoundBefore = counter(INQUIRY, "result", "NOT_FOUND")
            recoverAt(T0.plusSeconds(11))
            withClue("one inquiry by the original request ID, NOT_FOUND (no ledger record)") { counter(INQUIRY, "result", "NOT_FOUND") shouldBeGreaterThan inquiryNotFoundBefore }
            recoverAt(T0.plus(Duration.ofHours(1)).plusSeconds(1))
            fixture.scalar("SELECT escalated_at FROM authorization_recovery_schedule WHERE authorization_id = ?", authorizationId(p)) shouldNotBe null
            gauge(ESCALATED) shouldBeGreaterThan escalatedBefore
            gauge(ESCALATED) shouldBe escalatedCount()
            withClue("escalation does not resolve the UNKNOWN") { gauge(UNKNOWN) shouldBe unknownCount() }
        }
    }

    Given("no UNKNOWN attempt older than now") {
        Then("the oldest-age gauge is never negative") {
            clock.set(Instant.parse("2000-01-01T00:00:00Z"))
            gauge(UNKNOWN_OLDEST_AGE) shouldBeGreaterThanOrEqual 0.0
        }
    }

    Given("a partial approval (CONFLICTING_INSTITUTION_RESULT hold)") {
        Then("the active-hold gauge counts holds without a release record") {
            val before = gauge(ACTIVE_HOLDS)
            val p = fixture.prepared()
            van.onAuth(mid(p)) { r -> ProgrammableVan.Script(van.approved(r) { it.put("approvedAmountMinor", r.get("amountMinor").longValue() - 1) }) }
            view(p).status shouldBe AuthorizationViewStatus.UNKNOWN
            gauge(ACTIVE_HOLDS) shouldBe before + 1.0
            gauge(ACTIVE_HOLDS) shouldBe activeHoldCount()
        }
    }

    Given("every authorization meter (AUTH-001 §9)") {
        Then("label names are only outcome, result or operation (no identifiers in labels)") {
            registry.meters.filter { it.id.name.startsWith("paycore.authorization") }.flatMap { it.id.tags.map { t -> t.key } }.toSet()
                .minus(setOf("outcome", "result", "operation")) shouldBe emptySet()
        }
    }
}) {
    companion object {
        const val UNKNOWN = "paycore.authorization.unknown"
        const val UNKNOWN_OLDEST_AGE = "paycore.authorization.unknown.oldest_age_seconds"
        const val ESCALATED = "paycore.authorization.escalated"
        const val ACTIVE_HOLDS = "paycore.payment.incident_holds.active"
        const val DISPATCH = "paycore.authorization.dispatch"
        const val INQUIRY = "paycore.authorization.inquiry"
    }
}

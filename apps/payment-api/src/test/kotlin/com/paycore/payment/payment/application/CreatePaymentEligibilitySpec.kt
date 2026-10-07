package com.paycore.payment.payment.application

import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.adapter.persistence.PaymentDbTest
import com.paycore.payment.payment.adapter.persistence.PersistenceTestData.USD
import com.paycore.payment.payment.adapter.persistence.PersistenceTestData.demoSite
import com.paycore.payment.payment.adapter.persistence.PersistenceTestData.intent
import com.paycore.payment.payment.adapter.persistence.PersistenceTestData.newOrder
import com.paycore.payment.payment.adapter.persistence.PersistenceTestData.t0
import com.paycore.payment.payment.application.CreatePaymentFixture.command
import com.paycore.payment.payment.application.CreatePaymentFixture.newIdentifiers
import com.paycore.payment.payment.application.CreatePaymentFixture.rows
import com.paycore.payment.payment.application.CreatePaymentFixture.rowsOf
import com.paycore.payment.payment.application.provided.CreatePaymentOutcome
import com.paycore.payment.payment.application.provided.CreatePaymentResult
import com.paycore.payment.payment.application.provided.CreatePaymentUseCase
import com.paycore.payment.payment.application.provided.EligibilityRefusal
import com.paycore.payment.payment.application.provided.EligibilitySnapshotVersion
import com.paycore.payment.payment.application.provided.EligibilityUnavailableReason
import com.paycore.payment.payment.application.provided.NotEligibleReason
import com.paycore.payment.payment.application.required.EligibilityResult
import com.paycore.payment.payment.domain.CurrencyCode
import com.paycore.payment.payment.domain.CurrencyUnit
import com.paycore.payment.payment.domain.MerchantSiteId
import com.paycore.payment.payment.domain.PaymentMethod
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Duration

/**
 * CP-DB-001 M-1: eligibility는 Use Case가 required Port([com.paycore.payment.payment.application.required.PaymentEligibilityPort])로
 * **command 값**으로 평가한다(CP-CONTRACT-001 §3.2 순서·§9 eligibility 행·§13). 실제 MySQL 8.4, Port는 시험 대역
 * [RecordingEligibilityPort](운영 구현 없음 — MASTER-SNAPSHOT-001/CP-API-001).
 */
@PaymentDbTest
class CreatePaymentEligibilitySpec(
    private val service: CreatePaymentUseCase,
    private val eligibility: RecordingEligibilityPort,
    private val db: DbAccess,
) : BehaviorSpec({

    val otherMerchantSite = MerchantSiteId("demo-site-of-another-merchant")

    /** 이 MID의 Site는 [demoSite]뿐인 Demo 기준정보(다른 Merchant의 Site는 Site 없음과 같은 NotFoundOrForbidden). */
    fun ownSiteOnly(call: EligibilityCall): EligibilityResult =
        if (call.merchantSiteId == demoSite) RecordingEligibilityPort.eligible(call) else EligibilityResult.NotFoundOrForbidden

    Given("a new order") {
        Then("the port is evaluated once, with the command's own environment, MID, site, currency, method and server time") {
            val order = newOrder()
            val site = MerchantSiteId("demo-site-002")
            val cmd = command(order, intent(amount = 1050, currency = USD, site = site), at = t0.plusNanos(789_000))
            val version = EligibilitySnapshotVersion("demo-snapshot-77")
            eligibility.respondFor(order) { RecordingEligibilityPort.eligible(it, version) }

            service.create(cmd).shouldBeInstanceOf<CreatePaymentResult.Accepted>().outcome shouldBe CreatePaymentOutcome.CREATED

            eligibility.callsFor(order) shouldBe listOf(EligibilityCall(order.environment, order.merchantAccountId, site, USD, PaymentMethod.CARD, t0))
            // Payment에 남는 Snapshot 버전은 이 평가 결과의 값이다(Auditability).
            db.count(
                "SELECT COUNT(*) FROM payment WHERE payment_id = ? AND eligibility_snapshot_version = 'demo-snapshot-77'",
                cmd.newIdentifiers.paymentId.value,
            ) shouldBe 1
        }
    }

    Given("a site that belongs to another merchant (or does not exist)") {
        Then("the request is refused as NotFoundOrForbidden, nothing is stored, and the same key then succeeds with the merchant's own site") {
            val order = newOrder()
            eligibility.respondFor(order, ::ownSiteOnly)
            val foreign = command(order, intent(site = otherMerchantSite))

            service.create(foreign) shouldBe CreatePaymentResult.EligibilityRefused(EligibilityRefusal.NotFoundOrForbidden)
            eligibility.callsFor(order).single().merchantSiteId shouldBe otherMerchantSite
            db.rowsOf(order) shouldBe rows(payment = 0, control = 0, idempotency = 0, outbox = 0)

            // 같은 키·다른 내용이지만 앞 요청은 rollback되어 기록이 없으므로 충돌이 아니라 새 요청이다.
            val own = command(order, intent(site = demoSite), key = foreign.idempotencyKey)
            service.create(own).shouldBeInstanceOf<CreatePaymentResult.Accepted>().outcome shouldBe CreatePaymentOutcome.CREATED
            db.rowsOf(order) shouldBe rows(payment = 1, control = 1, idempotency = 1, outbox = 1)
        }
    }

    Given("each refusal kind the port can return") {
        val cases: List<Pair<EligibilityResult, EligibilityRefusal>> =
            listOf(EligibilityResult.NotFoundOrForbidden to EligibilityRefusal.NotFoundOrForbidden) +
                NotEligibleReason.entries.map { EligibilityResult.NotEligible(it) to EligibilityRefusal.NotEligible(it) } +
                EligibilityUnavailableReason.entries.map { EligibilityResult.Unavailable(it) to EligibilityRefusal.Unavailable(it) }

        Then("each is classified as its refusal, the whole transaction is rolled back, and a later eligible retry with the same key creates") {
            cases.forEach { (answer, refusal) ->
                val order = newOrder()
                eligibility.respondFor(order) { answer }
                val cmd = command(order)

                service.create(cmd) shouldBe CreatePaymentResult.EligibilityRefused(refusal)
                db.rowsOf(order) shouldBe rows(payment = 0, control = 0, idempotency = 0, outbox = 0)

                eligibility.respondFor(order) { RecordingEligibilityPort.eligible(it) }
                service.create(cmd.copy(newIdentifiers = newIdentifiers()))
                    .shouldBeInstanceOf<CreatePaymentResult.Accepted>().replayed shouldBe false
            }
        }
    }

    Given("an expired idempotency record (30 days) whose renewal is refused") {
        Then("the renewal is rolled back: the old record and the payment stay exactly as they were") {
            val order = newOrder()
            val cmd = command(order)
            service.create(cmd).shouldBeInstanceOf<CreatePaymentResult.Accepted>()
            val before = db.withApp { c -> with(db) { c.rows("SELECT * FROM idempotency_request WHERE idempotency_key = ?", cmd.idempotencyKey.value) } }

            eligibility.respondFor(order) { EligibilityResult.Unavailable(EligibilityUnavailableReason.SNAPSHOT_EXPIRED) }
            val later = cmd.copy(requestedAt = t0.plus(Duration.ofDays(30)), newIdentifiers = newIdentifiers())
            service.create(later) shouldBe
                CreatePaymentResult.EligibilityRefused(EligibilityRefusal.Unavailable(EligibilityUnavailableReason.SNAPSHOT_EXPIRED))

            db.withApp { c -> with(db) { c.rows("SELECT * FROM idempotency_request WHERE idempotency_key = ?", cmd.idempotencyKey.value) } } shouldBe before
            db.rowsOf(order) shouldBe rows(payment = 1, control = 1, idempotency = 1, outbox = 1)
        }
    }

    Given("a stored result for the same key and content (§3.2: replay does not re-evaluate)") {
        Then("it is replayed without calling the port, even if eligibility would now refuse") {
            val order = newOrder()
            val cmd = command(order)
            val first = service.create(cmd).shouldBeInstanceOf<CreatePaymentResult.Accepted>()
            eligibility.respondFor(order) { EligibilityResult.NotEligible(NotEligibleReason.MERCHANT_SUSPENDED) }

            val replay = service.create(cmd.copy(newIdentifiers = newIdentifiers())).shouldBeInstanceOf<CreatePaymentResult.Accepted>()
            replay.replayed shouldBe true
            replay.payment shouldBe first.payment
            eligibility.callsFor(order).size shouldBe 1
        }
    }

    Given("the port answers inconsistently with the request (fail-closed defence in the use case)") {
        When("Eligible but its validUntil is not after the evaluation time") {
            Then("it is treated as an expired snapshot (Unavailable SNAPSHOT_EXPIRED) and nothing is stored") {
                listOf(Duration.ZERO, Duration.ofMillis(-1)).forEach { offset ->
                    val order = newOrder()
                    eligibility.respondFor(order) {
                        EligibilityResult.Eligible(RecordingEligibilityPort.DEMO_SNAPSHOT, it.evaluatedAt.plus(offset), CurrencyUnit(it.currency, 0))
                    }
                    service.create(command(order)) shouldBe
                        CreatePaymentResult.EligibilityRefused(EligibilityRefusal.Unavailable(EligibilityUnavailableReason.SNAPSHOT_EXPIRED))
                    db.rowsOf(order) shouldBe rows(payment = 0, control = 0, idempotency = 0, outbox = 0)
                }
            }
        }

        When("Eligible but the currency unit is for another currency") {
            Then("it fails (port defect, not an eligibility decision) and nothing is stored") {
                val order = newOrder()
                eligibility.respondFor(order) {
                    EligibilityResult.Eligible(RecordingEligibilityPort.DEMO_SNAPSHOT, it.evaluatedAt.plusSeconds(60), CurrencyUnit(CurrencyCode("USD"), 2))
                }
                shouldThrow<IllegalStateException> { service.create(command(order)) }
                db.rowsOf(order) shouldBe rows(payment = 0, control = 0, idempotency = 0, outbox = 0)
            }
        }

        When("the port throws") {
            Then("the exception reaches the caller and nothing is stored") {
                val order = newOrder()
                class SnapshotReadFailure : RuntimeException("snapshot read failure")
                eligibility.respondFor(order) { throw SnapshotReadFailure() }
                shouldThrow<SnapshotReadFailure> { service.create(command(order)) }
                db.rowsOf(order) shouldBe rows(payment = 0, control = 0, idempotency = 0, outbox = 0)
            }
        }
    }
})

package com.paycore.financial.paymentfact.application

import com.paycore.financial.FinancialMySqlContainerConfig
import com.paycore.financial.paymentfact.adapter.web.PaymentFactEnvelopeFixture
import com.paycore.financial.paymentfact.application.provided.HoldReleaseSweepResult
import com.paycore.financial.paymentfact.application.provided.ReceivePaymentFactFailure
import com.paycore.financial.paymentfact.application.provided.ReceivePaymentFactResult
import com.paycore.financial.paymentfact.application.required.OpenHold
import com.paycore.financial.paymentfact.application.required.PaymentFactClock
import com.paycore.financial.paymentfact.application.required.PaymentFactInboxStore
import com.paycore.financial.paymentfact.application.required.PaymentFactPersistenceFailure
import com.paycore.financial.paymentfact.application.required.PaymentFactProjectionStore
import com.paycore.financial.paymentfact.domain.HoldReason
import com.paycore.financial.paymentfact.domain.PaymentFact
import com.paycore.financial.paymentfact.domain.PaymentFactFixture
import com.paycore.financial.paymentfact.domain.PaymentFactInboxOutcome
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * D-FFR-003 ORIGINAL_UNKNOWN 자동 해제 실제 MySQL 시험(ACQUIRING-001 §12 — HR-1..HR-7, runtime 계정). 해제 기록은 원승인 Inbox TX와 원자적이고(같이 커밋·같이 rollback),
 * 보류 원본·취소 투영은 바뀌지 않으며, 재전송·SWEEP 재실행이 두 번째 판정을 만들지 않는다. 단언은 이 시험의 Payment로 한정한다.
 */
@SpringBootTest
@Import(FinancialMySqlContainerConfig::class)
class HoldReleaseDbSpec(
    private val service: PaymentFactInboxService,
    private val sweep: PaymentFactHoldReleaseSweepService,
    private val inboxStore: PaymentFactInboxStore,
    private val projectionStore: PaymentFactProjectionStore,
    private val transactionManager: PlatformTransactionManager,
    private val clock: PaymentFactClock,
    private val jdbc: JdbcTemplate,
) : BehaviorSpec({

    fun receive(fact: PaymentFact, eventId: String = UUID.randomUUID().toString()) = service.receive(PaymentFactEnvelopeFixture.inbound(fact, eventId))

    /** 취소 거래 → (판정, 불일치, 판정 경로). */
    fun releases(paymentId: String): Map<String, Triple<String, String?, String>> =
        jdbc.queryForList(
            "SELECT cancellation_transaction_id, decision, mismatch_reasons, decided_by FROM payment_fact_hold_release WHERE payment_id = ?", paymentId,
        ).associate { it["cancellation_transaction_id"] as String to Triple(it["decision"] as String, it["mismatch_reasons"] as String?, it["decided_by"] as String) }

    fun holdRows(paymentId: String): List<Map<String, Any?>> =
        jdbc.queryForList("SELECT id, transaction_id, reason, opened_at FROM payment_fact_hold WHERE payment_id = ? ORDER BY id", paymentId)

    Given("HR-1 an early partial cancel (HELD) and then its matching original arrives through the Inbox") {
        val (auth, cancel) = PaymentFactFixture.cancelWithoutOriginal(amountMinor = 3_000)
        val pid = auth.identity.paymentId
        receive(cancel)
        val holdsBefore = holdRows(pid)
        val applied = receive(auth)
        Then("the original's Inbox outcome is unchanged (APPLIED) and one RELEASED record is written by the INBOX path") {
            applied shouldBe ReceivePaymentFactResult.Stored(PaymentFactInboxOutcome.APPLIED, duplicate = false)
            releases(pid) shouldBe mapOf(cancel.transactionId to Triple("RELEASED", null, "INBOX"))
        }
        Then("the hold row and the cancel projection are not modified (append-only — original_known_at_receipt stays false)") {
            holdRows(pid) shouldBe holdsBefore
            holdsBefore.map { it["reason"] } shouldBe listOf(HoldReason.ORIGINAL_UNKNOWN.name)
            jdbc.queryForObject("SELECT original_known_at_receipt FROM payment_fact_cancellation WHERE transaction_id = ?", Boolean::class.java, cancel.transactionId) shouldBe
                false
        }
        And("a re-delivery of the original (DUPLICATE) and a SWEEP write no second decision") {
            receive(auth, eventId = jdbc.queryForObject("SELECT event_id FROM payment_fact_inbox WHERE transaction_id = ?", String::class.java, auth.transactionId)!!) shouldBe
                ReceivePaymentFactResult.Stored(PaymentFactInboxOutcome.APPLIED, duplicate = true)
            sweep.sweep(100)
            jdbc.queryForObject("SELECT COUNT(*) FROM payment_fact_hold_release WHERE payment_id = ?", Int::class.java, pid) shouldBe 1
        }
    }

    Given("HR-2 an early cancel that does not match the arriving original (different original provider transaction)") {
        val (auth, cancel) = PaymentFactFixture.cancelWithoutOriginal(amountMinor = 3_000)
        val mismatched = cancel.copy(originalProviderTransactionId = "VAN-T-not-the-original")
        receive(mismatched)
        receive(auth)
        Then("it is recorded as REVIEW_REQUIRED with ORIGINAL_MISMATCH — no automatic release") {
            releases(auth.identity.paymentId) shouldBe mapOf(mismatched.transactionId to Triple("REVIEW_REQUIRED", "ORIGINAL_MISMATCH", "INBOX"))
        }
    }

    Given("HR-3 the original's Inbox TX fails after the release record was written (before commit)") {
        val (auth, cancel) = PaymentFactFixture.cancelWithoutOriginal(amountMinor = 3_000)
        val pid = auth.identity.paymentId
        receive(cancel)
        val failing = object : PaymentFactProjectionStore by projectionStore {
            override fun recordFact(paymentId: String, at: Instant) {
                throw PaymentFactPersistenceFailure.Unavailable(IllegalStateException("injected: connection lost before commit"))
            }
        }
        val faulty = PaymentFactInboxService(transactionManager, inboxStore, failing, clock)
        val envelope = PaymentFactEnvelopeFixture.inbound(auth)
        Then("the whole TX rolls back: no original projection and no release record (atomic with the Inbox)") {
            shouldThrow<ReceivePaymentFactFailure> { faulty.receive(envelope) }
            jdbc.queryForObject("SELECT COUNT(*) FROM payment_fact_authorization WHERE payment_id = ?", Int::class.java, pid) shouldBe 0
            releases(pid) shouldBe emptyMap()
        }
        And("the relay's re-delivery then releases exactly once") {
            service.receive(envelope) shouldBe ReceivePaymentFactResult.Stored(PaymentFactInboxOutcome.APPLIED, duplicate = false)
            releases(pid) shouldBe mapOf(cancel.transactionId to Triple("RELEASED", null, "INBOX"))
        }
    }

    Given("HR-4 two early cancels whose sum exceeds the original") {
        val (auth, first) = PaymentFactFixture.cancelWithoutOriginal(amountMinor = 6_000)
        val second = PaymentFactFixture.cancelOf(auth, 6_000, returnSequence = 2, cumulativeMinor = 12_000)
        receive(first)
        receive(second)
        receive(auth)
        Then("both are REVIEW_REQUIRED with RETURN_EXCEEDS_AUTHORIZED") {
            releases(auth.identity.paymentId) shouldBe mapOf(
                first.transactionId to Triple("REVIEW_REQUIRED", "RETURN_EXCEEDS_AUTHORIZED", "INBOX"),
                second.transactionId to Triple("REVIEW_REQUIRED", "RETURN_EXCEEDS_AUTHORIZED", "INBOX"),
            )
        }
    }

    Given("HR-5 the original arrives with a different merchant order than the first fact (the original itself is held)") {
        val (auth, cancel) = PaymentFactFixture.cancelWithoutOriginal(amountMinor = 3_000)
        val otherOrder = auth.copy(identity = auth.identity.copy(merchantOrderId = "order-other"))
        receive(cancel)
        receive(otherOrder)
        Then("the cancel is REVIEW_REQUIRED with ORIGINAL_HELD (and the original's identity mismatch), the original keeps its own hold") {
            releases(auth.identity.paymentId) shouldBe mapOf(cancel.transactionId to Triple("REVIEW_REQUIRED", "ORIGINAL_HELD", "INBOX"))
            jdbc.queryForList("SELECT reason FROM payment_fact_hold WHERE transaction_id = ?", String::class.java, auth.transactionId) shouldBe
                listOf(HoldReason.IDENTITY_MISMATCH.name)
        }
    }

    Given("HR-6 a hold left from before the feature (the original was projected without a release decision) is handled by SWEEP") {
        val (auth, cancel) = PaymentFactFixture.cancelWithoutOriginal(amountMinor = 3_000)
        val pid = auth.identity.paymentId
        receive(cancel)
        // 기능 도입 전 상태 재현: 원승인 수신 TX가 해제 대상을 보지 못한 것처럼(빈 목록) 받는다.
        val legacy = PaymentFactInboxService(
            transactionManager, inboxStore,
            object : PaymentFactProjectionStore by projectionStore {
                override fun unreleasedOriginalUnknownHolds(paymentId: String, originalTransactionId: String): List<OpenHold> = emptyList()
            },
            clock,
        )
        legacy.receive(PaymentFactEnvelopeFixture.inbound(auth))
        val before = releases(pid)
        val first = sweep.sweep(1000)
        val second = sweep.sweep(1000)
        Then("before the sweep there is no decision; the sweep releases it once (SWEEP) and a re-run is a no-op") {
            before shouldBe emptyMap()
            releases(pid) shouldBe mapOf(cancel.transactionId to Triple("RELEASED", null, "SWEEP"))
            (first.released >= 1) shouldBe true
            jdbc.queryForObject("SELECT COUNT(*) FROM payment_fact_hold_release WHERE payment_id = ?", Int::class.java, pid) shouldBe 1
            second shouldBe HoldReleaseSweepResult(0, 0, 0)
        }
    }

    Given("HR-8 an early cancel of payment P1 names the transaction of another payment's authorization as its original (review m-1)") {
        val otherPaymentAuth = PaymentFactFixture.authorized()
        val (unseen, cancel) = PaymentFactFixture.cancelWithoutOriginal(amountMinor = 3_000)
        val cross = cancel.copy(originalTransactionId = otherPaymentAuth.transactionId)
        receive(otherPaymentAuth)
        receive(cross) shouldBe ReceivePaymentFactResult.Stored(PaymentFactInboxOutcome.HELD, duplicate = false)
        val candidates = TransactionTemplate(transactionManager).execute { projectionStore.paymentsWithReleasableHolds(1000) }
        sweep.sweep(1000)
        Then("SWEEP does not pick P1 (its original is not in P1 — it cannot be released automatically) and records nothing for it") {
            (unseen.identity.paymentId in candidates) shouldBe false
            releases(unseen.identity.paymentId) shouldBe emptyMap()
        }
    }

    Given("HR-7 the original and two early cancels race: the cancels and the original arrive concurrently on separate connections") {
        val (auth, first) = PaymentFactFixture.cancelWithoutOriginal(amountMinor = 3_000)
        val second = PaymentFactFixture.cancelOf(auth, 2_000, returnSequence = 2, cumulativeMinor = 5_000)
        val facts = listOf<PaymentFact>(first, second, auth)
        val pool = Executors.newFixedThreadPool(3)
        val results = try {
            facts.map { f -> pool.submit(Callable { receive(f) }) }.map { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        Then("they serialize on the payment row: every cancel held at receipt gets exactly one RELEASED decision, the others none") {
            results.all { it is ReceivePaymentFactResult.Stored && !it.duplicate } shouldBe true
            val heldAtReceipt = jdbc.queryForList(
                "SELECT transaction_id FROM payment_fact_hold WHERE payment_id = ? AND reason = 'ORIGINAL_UNKNOWN'", String::class.java, auth.identity.paymentId,
            ).toSet()
            releases(auth.identity.paymentId) shouldBe heldAtReceipt.associateWith { Triple("RELEASED", null, "INBOX") }
        }
    }
})

package com.paycore.financial.paymentfact.application

import com.paycore.financial.FinancialMySqlContainerConfig
import com.paycore.financial.paymentfact.adapter.web.PaymentFactEnvelopeFixture
import com.paycore.financial.paymentfact.adapter.web.PaymentFactEnvelopeParser
import com.paycore.financial.paymentfact.application.provided.ReceivePaymentFactFailure
import com.paycore.financial.paymentfact.application.provided.ReceivePaymentFactResult
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
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.TransactionSystemException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 결제 사실 Inbox 실제 MySQL 시험(FIN-FACT-RELAY-001 §7 FR-I1..I10). 사실은 Named Fixture(domain)에서 만들고 **실제 봉투 파서**를 거쳐 Use Case에 넣는다.
 * 저장소·Use Case는 운영 Bean이고, 장애 주입 시험만 운영 Store를 감싼 대역·커밋 뒤 실패 TX 관리자로 서비스를 직접 만든다. 단언은 이 시험의 Payment로 한정한다.
 */
@SpringBootTest
@Import(FinancialMySqlContainerConfig::class)
class PaymentFactInboxDbSpec(
    private val service: PaymentFactInboxService,
    private val inboxStore: PaymentFactInboxStore,
    private val projectionStore: PaymentFactProjectionStore,
    private val transactionManager: PlatformTransactionManager,
    private val clock: PaymentFactClock,
    private val jdbc: JdbcTemplate,
) : BehaviorSpec({

    fun count(sql: String, vararg args: Any): Int = jdbc.queryForObject(sql, Int::class.java, *args)!!
    fun inboxRows(paymentId: String) = count("SELECT COUNT(*) FROM payment_fact_inbox WHERE payment_id = ?", paymentId)
    fun inboxByEvent(eventId: String) = count("SELECT COUNT(*) FROM payment_fact_inbox WHERE event_id = ?", eventId)
    fun authorizationRows(paymentId: String) = count("SELECT COUNT(*) FROM payment_fact_authorization WHERE payment_id = ?", paymentId)
    fun cancellationRows(paymentId: String) = count("SELECT COUNT(*) FROM payment_fact_cancellation WHERE payment_id = ?", paymentId)
    fun streamRows(paymentId: String) = count("SELECT COUNT(*) FROM payment_fact_stream WHERE payment_id = ?", paymentId)
    fun factCount(paymentId: String) = count("SELECT fact_count FROM payment_fact_stream WHERE payment_id = ?", paymentId)
    fun holds(transactionId: String): Set<String> =
        jdbc.queryForList("SELECT reason FROM payment_fact_hold WHERE transaction_id = ?", String::class.java, transactionId).filterNotNull().toSet()
    fun originalKnown(transactionId: String): Boolean =
        jdbc.queryForObject("SELECT original_known_at_receipt FROM payment_fact_cancellation WHERE transaction_id = ?", Boolean::class.java, transactionId)!!

    fun receive(fact: PaymentFact, eventId: String = UUID.randomUUID().toString()) = service.receive(PaymentFactEnvelopeFixture.inbound(fact, eventId))

    Given("FR-I1 an authorization fact delivered once and then re-delivered (same eventId, same bytes — response lost / relay retry)") {
        val auth = PaymentFactFixture.authorized()
        val pid = auth.identity.paymentId
        val eventId = UUID.randomUUID().toString()
        val envelope = PaymentFactEnvelopeFixture.inbound(auth, eventId)
        val first = service.receive(envelope)
        val second = service.receive(envelope)
        Then("the first is APPLIED; the re-delivery answers DUPLICATE with the stored outcome and writes nothing") {
            first shouldBe ReceivePaymentFactResult.Stored(PaymentFactInboxOutcome.APPLIED, duplicate = false)
            second shouldBe ReceivePaymentFactResult.Stored(PaymentFactInboxOutcome.APPLIED, duplicate = true)
            inboxRows(pid) shouldBe 1
            authorizationRows(pid) shouldBe 1
            factCount(pid) shouldBe 1
            holds(auth.transactionId) shouldBe emptySet()
        }
        Then("the Inbox keeps the raw-body SHA-256, the signing kid and the effect key") {
            jdbc.queryForMap("SELECT effect_key, payload_sha256, signing_key_id, outcome FROM payment_fact_inbox WHERE event_id = ?", eventId) shouldBe mapOf(
                "effect_key" to auth.effectKey, "payload_sha256" to envelope.rawSha256, "signing_key_id" to PaymentFactEnvelopeFixture.KID, "outcome" to "APPLIED",
            )
        }
    }

    Given("FR-I2 the same eventId arrives with a different body") {
        val auth = PaymentFactFixture.authorized()
        val eventId = UUID.randomUUID().toString()
        receive(auth, eventId)
        val other = PaymentFactFixture.authorized()
        val result = receive(other, eventId)
        Then("it is an EventIdConflict and nothing of the other payment is stored (not even its serialization row)") {
            result shouldBe ReceivePaymentFactResult.EventIdConflict
            inboxByEvent(eventId) shouldBe 1
            streamRows(other.identity.paymentId) shouldBe 0
            authorizationRows(other.identity.paymentId) shouldBe 0
        }
    }

    Given("FR-I3 a different eventId claims an already recorded effect key") {
        val auth = PaymentFactFixture.authorized()
        receive(auth)
        val result = receive(auth)
        Then("it is an EffectKeyConflict — the business effect is recorded exactly once") {
            result shouldBe ReceivePaymentFactResult.EffectKeyConflict
            inboxRows(auth.identity.paymentId) shouldBe 1
            authorizationRows(auth.identity.paymentId) shouldBe 1
            factCount(auth.identity.paymentId) shouldBe 1
        }
    }

    Given("FR-I4 a cancel arrives before its original authorization (out of order — D-ACQ-002)") {
        val (auth, cancel) = PaymentFactFixture.cancelWithoutOriginal(amountMinor = 3_000)
        val pid = auth.identity.paymentId
        val held = receive(cancel)
        val applied = receive(auth)
        val later = PaymentFactFixture.cancelOf(auth, 2_000, returnSequence = 2, cumulativeMinor = 5_000)
        val laterResult = receive(later)
        Then("the early cancel is projected and HELD (ORIGINAL_UNKNOWN, original unknown at receipt) — not dropped") {
            held shouldBe ReceivePaymentFactResult.Stored(PaymentFactInboxOutcome.HELD, duplicate = false)
            holds(cancel.transactionId) shouldBe setOf(HoldReason.ORIGINAL_UNKNOWN.name)
            originalKnown(cancel.transactionId) shouldBe false
        }
        Then("the original is then APPLIED and a later cancel is APPLIED with the original known; the early hold stays (release is ACQUIRING-001)") {
            applied shouldBe ReceivePaymentFactResult.Stored(PaymentFactInboxOutcome.APPLIED, duplicate = false)
            laterResult shouldBe ReceivePaymentFactResult.Stored(PaymentFactInboxOutcome.APPLIED, duplicate = false)
            originalKnown(later.transactionId) shouldBe true
            holds(later.transactionId) shouldBe emptySet()
            holds(cancel.transactionId) shouldBe setOf(HoldReason.ORIGINAL_UNKNOWN.name)
            cancellationRows(pid) shouldBe 2
            authorizationRows(pid) shouldBe 1
            factCount(pid) shouldBe 3
        }
    }

    Given("FR-I5 cancels whose total exceeds the original") {
        val auth = PaymentFactFixture.authorized(amountMinor = 10_000)
        receive(auth)
        receive(PaymentFactFixture.cancelOf(auth, 10_000, returnSequence = 1, cumulativeMinor = 10_000))
        val excess = PaymentFactFixture.cancelOf(auth, 1, returnSequence = 2, cumulativeMinor = 10_001)
        val result = receive(excess)
        Then("the excess cancel is still recorded but HELD with RETURN_EXCEEDS_AUTHORIZED") {
            result shouldBe ReceivePaymentFactResult.Stored(PaymentFactInboxOutcome.HELD, duplicate = false)
            holds(excess.transactionId) shouldBe setOf(HoldReason.RETURN_EXCEEDS_AUTHORIZED.name)
            cancellationRows(auth.identity.paymentId) shouldBe 2
        }
    }

    Given("FR-I6 an unsupported event type/schema version") {
        val eventId = UUID.randomUUID().toString()
        val body = PaymentFactEnvelopeFixture.unsupportedBody(eventId)
        val envelope = PaymentFactEnvelopeParser.parse(body, PaymentFactEnvelopeFixture.KID)
        val first = service.receive(envelope)
        val again = service.receive(envelope)
        Then("it is kept as UNSUPPORTED (raw only, no projection) and a re-delivery is a DUPLICATE of it") {
            first shouldBe ReceivePaymentFactResult.Stored(PaymentFactInboxOutcome.UNSUPPORTED, duplicate = false)
            again shouldBe ReceivePaymentFactResult.Stored(PaymentFactInboxOutcome.UNSUPPORTED, duplicate = true)
            count("SELECT COUNT(*) FROM payment_fact_inbox WHERE event_id = ? AND outcome = 'UNSUPPORTED' AND payment_id IS NULL", eventId) shouldBe 1
        }
    }

    Given("FR-I7 eight concurrent deliveries of the same envelope (relay retries racing on separate connections)") {
        val auth = PaymentFactFixture.authorized()
        val envelope = PaymentFactEnvelopeFixture.inbound(auth)
        val results = concurrently(8) { service.receive(envelope) }
        Then("exactly one is a first application and the rest are DUPLICATEs; one Inbox row, one fact row") {
            results.count { it == ReceivePaymentFactResult.Stored(PaymentFactInboxOutcome.APPLIED, duplicate = false) } shouldBe 1
            results.count { it == ReceivePaymentFactResult.Stored(PaymentFactInboxOutcome.APPLIED, duplicate = true) } shouldBe 7
            inboxRows(auth.identity.paymentId) shouldBe 1
            authorizationRows(auth.identity.paymentId) shouldBe 1
            factCount(auth.identity.paymentId) shouldBe 1
        }
    }

    Given("FR-I8 an authorization and two cancels of one payment arrive concurrently") {
        val auth = PaymentFactFixture.authorized(amountMinor = 10_000)
        val c1 = PaymentFactFixture.cancelOf(auth, 3_000, returnSequence = 1, cumulativeMinor = 3_000)
        val c2 = PaymentFactFixture.cancelOf(auth, 7_000, returnSequence = 2, cumulativeMinor = 10_000)
        val facts = listOf<PaymentFact>(auth, c1, c2)
        val results = concurrently(3) { i -> receive(facts[i]) }
        Then("they serialize on the payment row: each fact exactly once, and every cancel's hold agrees with what was known at receipt") {
            results.all { it is ReceivePaymentFactResult.Stored && !it.duplicate } shouldBe true
            factCount(auth.identity.paymentId) shouldBe 3
            authorizationRows(auth.identity.paymentId) shouldBe 1
            cancellationRows(auth.identity.paymentId) shouldBe 2
            listOf(c1, c2).forEach { c ->
                val known = originalKnown(c.transactionId)
                holds(c.transactionId) shouldBe if (known) emptySet() else setOf(HoldReason.ORIGINAL_UNKNOWN.name)
            }
        }
    }

    Given("FR-I12 two concurrent cancels of one original whose sum exceeds it (the first holds its TX open after reading the history)") {
        val auth = PaymentFactFixture.authorized(amountMinor = 10_000)
        receive(auth)
        val first = PaymentFactFixture.cancelOf(auth, 6_000, returnSequence = 1, cumulativeMinor = 6_000)
        val second = PaymentFactFixture.cancelOf(auth, 6_000, returnSequence = 2, cumulativeMinor = 12_000)
        // 검토 n-7: 고정 sleep 대신 latch — 첫 TX는 이력을 읽은 뒤 release 신호까지 커밋하지 않는다.
        val historyRead = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pausing = PaymentFactInboxService(
            transactionManager, inboxStore,
            object : PaymentFactProjectionStore by projectionStore {
                override fun history(paymentId: String, owner: com.paycore.financial.paymentfact.domain.PaymentFactIdentity?) =
                    projectionStore.history(paymentId, owner).also {
                        historyRead.countDown()
                        check(release.await(30, TimeUnit.SECONDS)) { "release signal missing" }
                    }
            },
            clock,
        )
        val firstEnvelope = PaymentFactEnvelopeFixture.inbound(first)
        val secondEnvelope = PaymentFactEnvelopeFixture.inbound(second)
        val pool = Executors.newFixedThreadPool(2)
        val (results, secondWaited) = try {
            val a = pool.submit(Callable { pausing.receive(firstEnvelope) })
            check(historyRead.await(30, TimeUnit.SECONDS)) { "first TX did not read the history" }
            val b = pool.submit(Callable { service.receive(secondEnvelope) })
            // 잠금이 있으면 둘째 TX는 첫 TX가 커밋할 때까지 결제별 행에서 기다린다(이 짧은 대기 안에 끝나지 않는다). 잠금이 없으면 바로 끝난다.
            val waited = runCatching { b.get(1, TimeUnit.SECONDS) }.exceptionOrNull() is java.util.concurrent.TimeoutException
            release.countDown()
            listOf(a.get(60, TimeUnit.SECONDS), b.get(60, TimeUnit.SECONDS)) to waited
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
        Then("the payment row lock makes the second wait and see the first: the second is HELD as RETURN_EXCEEDS_AUTHORIZED (not both applied)") {
            secondWaited shouldBe true
            results[0] shouldBe ReceivePaymentFactResult.Stored(PaymentFactInboxOutcome.APPLIED, duplicate = false)
            results[1] shouldBe ReceivePaymentFactResult.Stored(PaymentFactInboxOutcome.HELD, duplicate = false)
            holds(second.transactionId) shouldBe setOf(HoldReason.RETURN_EXCEEDS_AUTHORIZED.name)
        }
    }

    Given("FR-I9 a storage failure after the fact row was written, before commit (mid-TX failure)") {
        val auth = PaymentFactFixture.authorized()
        val pid = auth.identity.paymentId
        val failing = object : PaymentFactProjectionStore by projectionStore {
            override fun recordFact(paymentId: String, at: Instant) {
                throw PaymentFactPersistenceFailure.Unavailable(IllegalStateException("injected: connection lost before commit"))
            }
        }
        val faulty = PaymentFactInboxService(transactionManager, inboxStore, failing, clock)
        val envelope = PaymentFactEnvelopeFixture.inbound(auth)
        Then("the receive fails as temporarily unavailable (503) and the whole TX rolls back — no Inbox, fact or serialization row") {
            shouldThrow<ReceivePaymentFactFailure> { faulty.receive(envelope) }
            inboxRows(pid) shouldBe 0
            authorizationRows(pid) shouldBe 0
            streamRows(pid) shouldBe 0
        }
        And("the relay's re-delivery of the same envelope is then applied once") {
            service.receive(envelope) shouldBe ReceivePaymentFactResult.Stored(PaymentFactInboxOutcome.APPLIED, duplicate = false)
            authorizationRows(pid) shouldBe 1
        }
    }

    Given("FR-I10 FINANCIAL commits but the commit acknowledgement is lost (commit outcome unknown → no ACK)") {
        val auth = PaymentFactFixture.authorized()
        val pid = auth.identity.paymentId
        val lossy = CommitThenFailTransactionManager(transactionManager)
        val unsure = PaymentFactInboxService(lossy, inboxStore, projectionStore, clock)
        val envelope = PaymentFactEnvelopeFixture.inbound(auth)
        Then("no ACK is returned (503) although the fact is durable") {
            shouldThrow<ReceivePaymentFactFailure> { unsure.receive(envelope) }
            lossy.failedCommits shouldBe 1
            inboxRows(pid) shouldBe 1
            authorizationRows(pid) shouldBe 1
        }
        And("the re-delivery converges: DUPLICATE with the original outcome, still exactly one fact") {
            service.receive(envelope) shouldBe ReceivePaymentFactResult.Stored(PaymentFactInboxOutcome.APPLIED, duplicate = true)
            authorizationRows(pid) shouldBe 1
            factCount(pid) shouldBe 1
        }
    }

    Given("FR-I11 append-only projection: the persistence model never updates a recorded fact") {
        val auth = PaymentFactFixture.authorized()
        receive(auth)
        Then("the fact, Inbox and hold Entities are @Immutable (JPA cannot issue an UPDATE for them)") {
            listOf(
                com.paycore.financial.paymentfact.adapter.persistence.JpaPaymentFactInboxEntity::class.java,
                com.paycore.financial.paymentfact.adapter.persistence.JpaAuthorizedPaymentFactEntity::class.java,
                com.paycore.financial.paymentfact.adapter.persistence.JpaCancelledPaymentFactEntity::class.java,
                com.paycore.financial.paymentfact.adapter.persistence.JpaPaymentFactHoldEntity::class.java,
            ).all { it.isAnnotationPresent(org.hibernate.annotations.Immutable::class.java) } shouldBe true
        }
    }
}) {
    /** 커밋은 실제로 하고 그 뒤 예외를 던진다(커밋 응답 유실 재현 — 시험 전용 대역). */
    class CommitThenFailTransactionManager(private val delegate: PlatformTransactionManager) : PlatformTransactionManager {
        @Volatile
        var failedCommits = 0

        override fun getTransaction(definition: TransactionDefinition?): TransactionStatus = delegate.getTransaction(definition)

        override fun commit(status: TransactionStatus) {
            delegate.commit(status)
            failedCommits++
            throw TransactionSystemException("injected: commit acknowledgement lost")
        }

        override fun rollback(status: TransactionStatus) = delegate.rollback(status)
    }

    companion object {
        /** [n]개 작업을 시작 신호에 맞춰 동시에 실행한다(각자 다른 DB 연결·로컬 TX). */
        fun <T> concurrently(n: Int, task: (Int) -> T): List<T> {
            val pool = Executors.newFixedThreadPool(n)
            try {
                val start = CountDownLatch(1)
                val futures = (0 until n).map { i -> pool.submit(Callable { start.await(); task(i) }) }
                start.countDown()
                return futures.map { it.get(60, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }
        }
    }
}

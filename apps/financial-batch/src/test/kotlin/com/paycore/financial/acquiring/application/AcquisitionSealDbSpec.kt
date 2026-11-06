package com.paycore.financial.acquiring.application

import com.paycore.financial.FinancialDbAccounts
import com.paycore.financial.FinancialMySqlContainerConfig
import com.paycore.financial.acquiring.application.provided.AcquisitionSealRefused
import com.paycore.financial.acquiring.application.provided.SealAcquisitionFileResult
import com.paycore.financial.acquiring.application.required.AcquiringClock
import com.paycore.financial.acquiring.application.required.AcquiringPersistenceFailure
import com.paycore.financial.acquiring.application.required.AcquisitionFileRepository
import com.paycore.financial.acquiring.application.required.AcquisitionLaneStore
import com.paycore.financial.acquiring.application.required.AcquisitionMembershipStore
import com.paycore.financial.acquiring.application.required.AcquisitionPolicySource
import com.paycore.financial.acquiring.application.required.AcquisitionRecordResultStore
import com.paycore.financial.acquiring.application.required.ExclusionRecord
import com.paycore.financial.acquiring.domain.AcquisitionFileFormat
import com.paycore.financial.acquiring.domain.AcquisitionFileId
import com.paycore.financial.acquiring.domain.CutoffPolicy
import com.paycore.financial.acquiring.domain.CutoffPolicyVersion
import com.paycore.financial.codeOf
import com.paycore.financial.paymentfact.adapter.web.PaymentFactEnvelopeFixture
import com.paycore.financial.paymentfact.application.PaymentFactInboxService
import com.paycore.financial.paymentfact.application.provided.AcquisitionSourcePayment
import com.paycore.financial.paymentfact.application.provided.PaymentFactAcquisitionSource
import com.paycore.financial.paymentfact.application.provided.ReceivePaymentFactResult
import com.paycore.financial.paymentfact.application.required.PaymentFactClock
import com.paycore.financial.paymentfact.application.required.PaymentFactInboxStore
import com.paycore.financial.paymentfact.application.required.PaymentFactProjectionStore
import com.paycore.financial.paymentfact.domain.PaymentFact
import com.paycore.financial.paymentfact.domain.PaymentFactFixture
import com.paycore.financial.paymentfact.domain.PaymentFactIdentity
import com.paycore.financial.paymentfact.domain.PaymentFactInboxOutcome
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.testcontainers.mysql.MySQLContainer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 매입 봉인 실제 MySQL 시험(ACQUIRING-001 배치1 — AQ-DB1..DB5, AQ-C1..C3, runtime 계정). 사실은 운영 Inbox 경로로 넣고, 봉인은 운영 Bean Port로 만든 서비스에
 * **고정 시계**(업무일 2026-11-05, Demo 마감 23:00 KST = 14:00Z 뒤)만 바꿔 실행한다. 시험마다 매입사 코드(lane)를 따로 써서 같은 컨테이너의 다른 시험 사실이 섞이지 않게 한다.
 */
@SpringBootTest
@Import(FinancialMySqlContainerConfig::class)
class AcquisitionSealDbSpec(
    private val inbox: PaymentFactInboxService,
    private val inboxStore: PaymentFactInboxStore,
    private val projectionStore: PaymentFactProjectionStore,
    private val factClock: PaymentFactClock,
    private val transactionManager: PlatformTransactionManager,
    private val lanes: AcquisitionLaneStore,
    private val files: AcquisitionFileRepository,
    private val membership: AcquisitionMembershipStore,
    private val source: PaymentFactAcquisitionSource,
    private val policies: AcquisitionPolicySource,
    private val results: AcquisitionRecordResultStore,
    private val board: AcquisitionWaitingBoard,
    private val jdbc: JdbcTemplate,
    private val container: MySQLContainer,
) : BehaviorSpec({

    val day = LocalDate.parse("2026-11-05")
    val afterCutoff = Instant.parse("2026-11-05T14:05:00Z")

    class FixedClock(var now: Instant) : AcquiringClock {
        override fun now(): Instant = now
    }

    fun service(
        clock: AcquiringClock = FixedClock(afterCutoff),
        membershipStore: AcquisitionMembershipStore = membership,
        factSource: PaymentFactAcquisitionSource = source,
        fileRepository: AcquisitionFileRepository = files,
        policySource: AcquisitionPolicySource = policies,
    ) = AcquisitionSealService(transactionManager, lanes, fileRepository, membershipStore, factSource, policySource, clock, results, board)

    fun policiesOf(version: String, effectiveFrom: String, cutoff: String) = object : AcquisitionPolicySource {
        override val senderId: String = policies.senderId
        override val cutoffPolicy = CutoffPolicy(listOf(CutoffPolicyVersion(version, LocalDate.parse(effectiveFrom), LocalTime.parse(cutoff))))
    }

    fun receive(fact: PaymentFact) = inbox.receive(PaymentFactEnvelopeFixture.inbound(fact, UUID.randomUUID().toString()))
    fun count(sql: String, vararg args: Any): Int = jdbc.queryForObject(sql, Int::class.java, *args)!!
    fun fileRows(acquirer: String) = count("SELECT COUNT(*) FROM acquisition_file WHERE acquirer_code = ?", acquirer)
    fun storedContent(fileId: String): ByteArray = jdbc.queryForObject("SELECT content FROM acquisition_file_content WHERE file_id = ?", ByteArray::class.java, fileId)!!
    fun recordAmounts(fileId: String): Map<String, Long> = jdbc.queryForList(
        "SELECT transaction_id, record_amount_minor FROM acquisition_file_record WHERE file_id = ? ORDER BY seq", fileId,
    ).associate { it["transaction_id"] as String to (it["record_amount_minor"] as Number).toLong() }

    Given("AQ-DB1 a lane with a plain sale, a partial pre-membership cancel, a full cancel, a mismatched early cancel, a released early cancel, a late and a USD approval") {
        val acq = PaymentFactFixture.nextAcquirerCode()
        val plain = PaymentFactFixture.authorized(amountMinor = 10_000, acquirerCode = acq)
        val partial = PaymentFactFixture.authorized(amountMinor = 20_000, acquirerCode = acq)
        val partialCancel = PaymentFactFixture.cancelOf(partial, 5_000)
        val full = PaymentFactFixture.authorized(amountMinor = 8_000, acquirerCode = acq)
        val fullCancel = PaymentFactFixture.cancelOf(full, 8_000)
        val (reviewAuth, reviewCancel) = PaymentFactFixture.cancelWithoutOriginal(amountMinor = 1_000, acquirerCode = acq)
        val mismatchedEarly = reviewCancel.copy(originalProviderTransactionId = "VAN-T-not-it")
        val (releasedAuth, releasedCancel) = PaymentFactFixture.cancelWithoutOriginal(amountMinor = 3_000, acquirerCode = acq)
        val late = PaymentFactFixture.authorized(amountMinor = 4_000, acquirerCode = acq, approvedAt = Instant.parse("2026-11-05T15:00:00.000Z"))
        val usd = PaymentFactFixture.authorized(amountMinor = 500, currency = "USD", acquirerCode = acq)
        listOf(plain, partial, partialCancel, full, fullCancel, mismatchedEarly, reviewAuth, releasedCancel, releasedAuth, late, usd).forEach { receive(it) }
        val sealed = service().seal(acq, "TEST", day)
        val fileId = "ACQ.$acq.TEST.20261105.001"
        Then("the file has SALE records for the plain (10,000), netted partial (15,000) and released early-cancel (7,000) approvals; full cancel excluded; others wait") {
            sealed.shouldBeInstanceOf<SealAcquisitionFileResult.Sealed>()
            sealed shouldBe SealAcquisitionFileResult.Sealed(
                fileId = fileId, contentSha256 = sealed.contentSha256, recordCount = 3, saleAmountMinor = 32_000, nettedCancellations = 3, excluded = 1,
                waiting = mapOf("CANCELLATION_HELD" to 1, "UNSUPPORTED_CURRENCY" to 1),
            )
            recordAmounts(fileId) shouldBe mapOf(plain.transactionId to 10_000L, partial.transactionId to 15_000L, releasedAuth.transactionId to 7_000L)
            jdbc.queryForList("SELECT cancellation_transaction_id, file_id IS NULL AS excluded FROM acquisition_netting WHERE original_transaction_id IN (?, ?, ?)",
                partial.transactionId, full.transactionId, releasedAuth.transactionId)
                .associate { it["cancellation_transaction_id"] to (it["excluded"] as Number).toInt() } shouldBe
                mapOf(partialCancel.transactionId to 0, fullCancel.transactionId to 1, releasedCancel.transactionId to 0)
            jdbc.queryForMap("SELECT reason, approved_amount_minor, netted_amount_minor FROM acquisition_exclusion WHERE transaction_id = ?", full.transactionId) shouldBe
                mapOf("reason" to "FULLY_CANCELLED_BEFORE_SUBMISSION", "approved_amount_minor" to 8_000L, "netted_amount_minor" to 8_000L)
        }
        Then("the stored bytes are the canonical v1 file of the stored records, their SHA-256 is the file row's seal hash and the lane remembers the day") {
            val content = storedContent(fileId)
            val parsed = AcquisitionFileFormat.parse(content)
            parsed.records.map { it.transactionId to it.recordAmountMinor } shouldBe recordAmounts(fileId).toList()
            jdbc.queryForMap("SELECT content_sha256, content_bytes, record_count, sale_amount_minor, transmission_state FROM acquisition_file WHERE file_id = ?", fileId) shouldBe
                mapOf(
                    "content_sha256" to AcquisitionFileFormat.sha256(content), "content_bytes" to content.size, "record_count" to 3, "sale_amount_minor" to 32_000L,
                    "transmission_state" to "SEALED",
                )
            jdbc.queryForObject("SELECT last_sealed_business_date FROM acquisition_lane WHERE acquirer_code = ?", LocalDate::class.java, acq) shouldBe day
        }
        And("AQ-DB2 sealing the same day again is AlreadySealed with the same hash and writes nothing (restart idempotency)") {
            val before = count("SELECT COUNT(*) FROM acquisition_file_record WHERE file_id = ?", fileId)
            service().seal(acq, "TEST", day) shouldBe SealAcquisitionFileResult.AlreadySealed(fileId, sealed.contentSha256)
            listOf(fileRows(acq), count("SELECT COUNT(*) FROM acquisition_file_record WHERE file_id = ?", fileId)) shouldBe listOf(1, before)
        }
        And("the next business day picks up the late approval (approved after the 5th's cutoff) and a gap or the LIVE lane is refused") {
            val next = service(FixedClock(Instant.parse("2026-11-06T14:05:00Z"))).seal(acq, "TEST", day.plusDays(1))
            next.shouldBeInstanceOf<SealAcquisitionFileResult.Sealed>()
            recordAmounts(next.fileId) shouldBe mapOf(late.transactionId to 4_000L)
            shouldThrow<AcquisitionSealRefused> { service(FixedClock(Instant.parse("2026-11-09T14:05:00Z"))).seal(acq, "TEST", day.plusDays(3)) }.reason shouldBe
                AcquisitionSealRefused.Reason.OUT_OF_ORDER
            shouldThrow<AcquisitionSealRefused> { service().seal(acq, "LIVE", day) }.reason shouldBe AcquisitionSealRefused.Reason.LIVE_BLOCKED
        }
    }

    Given("AQ-DB3 a seal before the business day's cutoff has passed") {
        val acq = PaymentFactFixture.nextAcquirerCode()
        receive(PaymentFactFixture.authorized(acquirerCode = acq))
        Then("it is refused (BEFORE_CUTOFF) and nothing is written") {
            shouldThrow<AcquisitionSealRefused> { service(FixedClock(Instant.parse("2026-11-05T13:59:59Z"))).seal(acq, "TEST", day) }.reason shouldBe
                AcquisitionSealRefused.Reason.BEFORE_CUTOFF
            listOf(fileRows(acq), count("SELECT COUNT(*) FROM acquisition_lane WHERE acquirer_code = ?", acq)) shouldBe listOf(0, 0)
        }
    }

    Given("AQ-DB4 a storage failure after the file and records were written, before commit") {
        val acq = PaymentFactFixture.nextAcquirerCode()
        val auth = PaymentFactFixture.authorized(amountMinor = 8_000, acquirerCode = acq)
        receive(auth)
        receive(PaymentFactFixture.cancelOf(auth, 8_000))
        receive(PaymentFactFixture.authorized(acquirerCode = acq))
        val failing = object : AcquisitionMembershipStore by membership {
            override fun insertExclusions(exclusions: List<ExclusionRecord>) {
                membership.insertExclusions(exclusions)
                throw AcquiringPersistenceFailure.Unavailable(IllegalStateException("injected: connection lost before commit"))
            }
        }
        Then("the whole seal rolls back: no file, bytes, records, netting, exclusion or lane day") {
            shouldThrow<AcquiringPersistenceFailure.Unavailable> { service(membershipStore = failing).seal(acq, "TEST", day) }
            listOf(
                fileRows(acq),
                count("SELECT COUNT(*) FROM acquisition_file_content WHERE file_id LIKE ?", "ACQ.$acq.%"),
                count("SELECT COUNT(*) FROM acquisition_file_record WHERE file_id LIKE ?", "ACQ.$acq.%"),
                count("SELECT COUNT(*) FROM acquisition_netting WHERE original_transaction_id = ?", auth.transactionId),
                count("SELECT COUNT(*) FROM acquisition_exclusion WHERE acquirer_code = ?", acq),
                count("SELECT COUNT(*) FROM acquisition_lane WHERE acquirer_code = ?", acq),
            ) shouldBe listOf(0, 0, 0, 0, 0, 0)
        }
        And("the re-run seals once") {
            (service().seal(acq, "TEST", day) as SealAcquisitionFileResult.Sealed).let { listOf(it.recordCount, it.excluded) } shouldBe listOf(1, 1)
        }
    }

    Given("AQ-DB5 the stored sealed bytes no longer match the stored records (tampered outside the application)") {
        val acq = PaymentFactFixture.nextAcquirerCode()
        receive(PaymentFactFixture.authorized(acquirerCode = acq))
        val sealed = service().seal(acq, "TEST", day)
        // root(관찰·설치 전용)만 바꿀 수 있다 — runtime 계정은 이 표 UPDATE가 1142다(FinancialDatabaseAccountSpec).
        FinancialDbAccounts.root(container).use { c ->
            c.codeOf("UPDATE acquisition_file_content SET content = CONCAT(content, 'X') WHERE file_id = '${sealed.fileId}'") shouldBe null
        }
        Then("a re-seal (restart) refuses with INTEGRITY_VIOLATION instead of returning the file for transmission") {
            shouldThrow<AcquisitionSealRefused> { service().seal(acq, "TEST", day) }.reason shouldBe AcquisitionSealRefused.Reason.INTEGRITY_VIOLATION
        }
    }

    Given("AQ-DB6 one approval with a value file v1 cannot carry (approval number with a space) and one normal approval (review MJ-1)") {
        val acq = PaymentFactFixture.nextAcquirerCode()
        val odd = PaymentFactFixture.authorized(acquirerCode = acq).copy(approvalNumber = "A 01")
        val normal = PaymentFactFixture.authorized(acquirerCode = acq)
        receive(odd) shouldBe ReceivePaymentFactResult.Stored(PaymentFactInboxOutcome.APPLIED, duplicate = false)
        receive(normal)
        val sealed = service().seal(acq, "TEST", day)
        val next = service(FixedClock(Instant.parse("2026-11-06T14:05:00Z"))).seal(acq, "TEST", day.plusDays(1))
        Then("the normal approval is sealed, the odd one waits (UNREPRESENTABLE - not excluded, not failing the lane) and the next day still seals") {
            sealed.shouldBeInstanceOf<SealAcquisitionFileResult.Sealed>()
            listOf(sealed.recordCount, sealed.waiting) shouldBe listOf(1, mapOf("UNREPRESENTABLE" to 1))
            recordAmounts(sealed.fileId).keys shouldBe setOf(normal.transactionId)
            next.shouldBeInstanceOf<SealAcquisitionFileResult.Sealed>()
            listOf(next.recordCount, next.waiting) shouldBe listOf(0, mapOf("UNREPRESENTABLE" to 1))
            count("SELECT COUNT(*) FROM acquisition_exclusion WHERE transaction_id = ?", odd.transactionId) shouldBe 0
        }
    }

    Given("AQ-DB7a an early cancel pointing to another original is held (ORIGINAL_UNKNOWN), then approval A1 of the same payment arrives (review MJ-2)") {
        val acq = PaymentFactFixture.nextAcquirerCode()
        val (otherOriginal, early) = PaymentFactFixture.cancelWithoutOriginal(amountMinor = 3_000, acquirerCode = acq)
        val pid = otherOriginal.identity.paymentId
        val a1 = PaymentFactFixture.authorized(paymentId = pid, transactionId = "ptx_a1_$pid", acquirerCode = acq)
        receive(early)
        receive(a1) shouldBe ReceivePaymentFactResult.Stored(PaymentFactInboxOutcome.APPLIED, duplicate = false)
        val sealed = service().seal(acq, "TEST", day)
        Then("A1 has no hold and no cancel of its own, but its payment has an unreleased hold: it waits (PAYMENT_HELD), not sealed at full amount") {
            count("SELECT COUNT(*) FROM payment_fact_hold_release WHERE payment_id = ?", pid) shouldBe 0
            sealed.shouldBeInstanceOf<SealAcquisitionFileResult.Sealed>()
            listOf(sealed.recordCount, sealed.waiting) shouldBe listOf(0, mapOf("PAYMENT_HELD" to 1))
        }
    }

    Given("AQ-DB7b approval A1 is applied, then a second approval A2 of the same payment is held as AUTHORIZATION_CONFLICT (review MJ-2)") {
        val acq = PaymentFactFixture.nextAcquirerCode()
        val a1 = PaymentFactFixture.authorized(acquirerCode = acq)
        val pid = a1.identity.paymentId
        val a2 = PaymentFactFixture.authorized(paymentId = pid, transactionId = "ptx_a2_$pid", acquirerCode = acq)
        receive(a1)
        receive(a2) shouldBe ReceivePaymentFactResult.Stored(PaymentFactInboxOutcome.HELD, duplicate = false)
        val sealed = service().seal(acq, "TEST", day)
        Then("both wait: A2 on its own hold, A1 because its payment is held - no SALE for the payment") {
            sealed.shouldBeInstanceOf<SealAcquisitionFileResult.Sealed>()
            listOf(sealed.recordCount, sealed.waiting) shouldBe listOf(0, mapOf("AUTHORIZATION_HELD" to 1, "PAYMENT_HELD" to 1))
        }
    }

    Given("AQ-DB8 the cutoff policy of an already sealed business day is changed in the configuration (review m-3, D-ACQ-003 versioning)") {
        val acq = PaymentFactFixture.nextAcquirerCode()
        receive(PaymentFactFixture.authorized(acquirerCode = acq))
        val sealed = service().seal(acq, "TEST", day)
        Then("a re-seal with the same policy is AlreadySealed; another version name, another cutoff time or no version in effect are refused (CUTOFF_POLICY_CHANGED)") {
            service(policySource = policiesOf("acq-cutoff-demo-1", "2026-01-01", "23:00")).seal(acq, "TEST", day) shouldBe
                SealAcquisitionFileResult.AlreadySealed(sealed.fileId, sealed.contentSha256)
            listOf(
                policiesOf("acq-cutoff-demo-2", "2026-01-01", "23:00"),
                policiesOf("acq-cutoff-demo-1", "2026-01-01", "22:00"),
                policiesOf("acq-cutoff-demo-1", "2027-01-01", "23:00"),
            ).map { p -> shouldThrow<AcquisitionSealRefused> { service(policySource = p).seal(acq, "TEST", day) }.reason }.toSet() shouldBe
                setOf(AcquisitionSealRefused.Reason.CUTOFF_POLICY_CHANGED)
        }
    }

    Given("AQ-C1 two seals of the same lane and day race on separate connections (convergence only - file PK + retry would also converge without the lane lock)") {
        val acq = PaymentFactFixture.nextAcquirerCode()
        repeat(3) { receive(PaymentFactFixture.authorized(acquirerCode = acq)) }
        val pool = Executors.newFixedThreadPool(2)
        val results = try {
            (1..2).map { pool.submit(Callable { service().seal(acq, "TEST", day) }) }.map { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        Then("they converge: one Sealed and one AlreadySealed with the same hash; each approval is a member once") {
            results.count { it is SealAcquisitionFileResult.Sealed } shouldBe 1
            results.count { it is SealAcquisitionFileResult.AlreadySealed } shouldBe 1
            results.map { it.contentSha256 }.toSet().size shouldBe 1
            count("SELECT COUNT(*) FROM acquisition_file_record WHERE file_id = ?", "ACQ.$acq.TEST.20261105.001") shouldBe 3
        }
        And("a second membership of the same approval is refused by the database (UNIQUE — 1062), even for the runtime account") {
            FinancialDbAccounts.connect(container, FinancialDbAccounts.RUNTIME_USER).use { c ->
                c.codeOf(
                    "INSERT INTO acquisition_file_record SELECT file_id, seq + 100, record_type, transaction_id, original_transaction_id, merchant_ref, approval_number, " +
                        "provider_transaction_id, original_provider_transaction_id, institution_transaction_date, event_at, currency, approved_amount_minor, " +
                        "netted_cancel_amount_minor, record_amount_minor, plan_kind, installment_months, card_type, issuer_code, generation, reacquisition_of " +
                        "FROM acquisition_file_record WHERE file_id = 'ACQ.$acq.TEST.20261105.001' AND seq = 1",
                )
            } shouldBe 1062
        }
    }

    Given("AQ-C4 a seal holds the lane (paused right after the lane lock, before reading candidates) while a second seal of the same lane and day starts (review m-2)") {
        val acq = PaymentFactFixture.nextAcquirerCode()
        receive(PaymentFactFixture.authorized(acquirerCode = acq))
        service().seal(acq, "TEST", day).shouldBeInstanceOf<SealAcquisitionFileResult.Sealed>()
        receive(PaymentFactFixture.authorized(acquirerCode = acq, approvedAt = Instant.parse("2026-11-06T01:00:00.000Z")))
        val clock = FixedClock(Instant.parse("2026-11-06T14:05:00Z"))
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pausingFiles = object : AcquisitionFileRepository by files {
            override fun find(id: AcquisitionFileId) = files.find(id).also {
                inside.countDown()
                check(release.await(30, TimeUnit.SECONDS)) { "release signal missing" }
            }
        }
        val pool = Executors.newFixedThreadPool(2)
        val (results, secondWaited) = try {
            val first = pool.submit(Callable { service(clock = clock, fileRepository = pausingFiles).seal(acq, "TEST", day.plusDays(1)) })
            check(inside.await(30, TimeUnit.SECONDS)) { "first seal did not take the lane" }
            val second = pool.submit(Callable { service(clock = clock).seal(acq, "TEST", day.plusDays(1)) })
            val waited = runCatching { second.get(1, TimeUnit.SECONDS) }.exceptionOrNull() is TimeoutException
            release.countDown()
            listOf(first.get(60, TimeUnit.SECONDS), second.get(60, TimeUnit.SECONDS)) to waited
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
        Then("the second seal waits on the lane row until the first commits, then sees the file (AlreadySealed): the lane lock serializes ordering and decisions") {
            secondWaited shouldBe true
            results[0].shouldBeInstanceOf<SealAcquisitionFileResult.Sealed>()
            results[1] shouldBe SealAcquisitionFileResult.AlreadySealed(results[0].fileId, results[0].contentSha256)
        }
    }

    Given("AQ-C2 a cancel commits while the seal waits for the payment row (cancel first — D-ACQ-012: received before membership → netted)") {
        val acq = PaymentFactFixture.nextAcquirerCode()
        val auth = PaymentFactFixture.authorized(amountMinor = 10_000, acquirerCode = acq)
        receive(auth)
        val cancel = PaymentFactFixture.cancelOf(auth, 4_000)
        val historyRead = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pausingInbox = PaymentFactInboxService(
            transactionManager, inboxStore,
            object : PaymentFactProjectionStore by projectionStore {
                override fun history(paymentId: String, owner: PaymentFactIdentity?) = projectionStore.history(paymentId, owner).also {
                    historyRead.countDown()
                    check(release.await(30, TimeUnit.SECONDS)) { "release signal missing" }
                }
            },
            factClock,
        )
        val pool = Executors.newFixedThreadPool(2)
        val (sealed, sealWaited) = try {
            val c = pool.submit(Callable { pausingInbox.receive(PaymentFactEnvelopeFixture.inbound(cancel)) })
            check(historyRead.await(30, TimeUnit.SECONDS)) { "cancel TX did not lock the payment row" }
            val s = pool.submit(Callable { service().seal(acq, "TEST", day) })
            val waited = runCatching { s.get(1, TimeUnit.SECONDS) }.exceptionOrNull() is TimeoutException
            release.countDown()
            c.get(60, TimeUnit.SECONDS) shouldBe ReceivePaymentFactResult.Stored(PaymentFactInboxOutcome.APPLIED, duplicate = false)
            s.get(60, TimeUnit.SECONDS) to waited
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
        Then("the seal waited on the payment row and then netted the cancel (record 6,000, netting row)") {
            sealWaited shouldBe true
            recordAmounts(sealed.fileId) shouldBe mapOf(auth.transactionId to 6_000L)
            count("SELECT COUNT(*) FROM acquisition_netting WHERE cancellation_transaction_id = ?", cancel.transactionId) shouldBe 1
        }
    }

    Given("AQ-C3 the seal holds the payment row when a cancel arrives (seal first — the cancel is after membership → not netted, separate CANCEL later)") {
        val acq = PaymentFactFixture.nextAcquirerCode()
        val auth = PaymentFactFixture.authorized(amountMinor = 10_000, acquirerCode = acq)
        receive(auth)
        val cancel = PaymentFactFixture.cancelOf(auth, 4_000)
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pausingSource = object : PaymentFactAcquisitionSource by source {
            override fun lockAndRead(paymentIds: Collection<String>): List<AcquisitionSourcePayment> = source.lockAndRead(paymentIds).also {
                locked.countDown()
                check(release.await(30, TimeUnit.SECONDS)) { "release signal missing" }
            }
        }
        val pool = Executors.newFixedThreadPool(2)
        val (sealed, cancelWaited) = try {
            val s = pool.submit(Callable { service(factSource = pausingSource).seal(acq, "TEST", day) })
            check(locked.await(30, TimeUnit.SECONDS)) { "seal TX did not lock the payment row" }
            val c = pool.submit(Callable { receive(cancel) })
            val waited = runCatching { c.get(1, TimeUnit.SECONDS) }.exceptionOrNull() is TimeoutException
            release.countDown()
            val result = s.get(60, TimeUnit.SECONDS)
            c.get(60, TimeUnit.SECONDS) shouldBe ReceivePaymentFactResult.Stored(PaymentFactInboxOutcome.APPLIED, duplicate = false)
            result to waited
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
        Then("the cancel waited for the membership commit; the SALE keeps the full amount and the cancel is not netted (final net is the same after the CANCEL record)") {
            cancelWaited shouldBe true
            recordAmounts(sealed.fileId) shouldBe mapOf(auth.transactionId to 10_000L)
            count("SELECT COUNT(*) FROM acquisition_netting WHERE cancellation_transaction_id = ?", cancel.transactionId) shouldBe 0
        }
    }
})

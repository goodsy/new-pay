package com.paycore.financial.acquiring.application

import com.paycore.financial.FinancialMySqlContainerConfig
import com.paycore.financial.acquiring.adapter.integration.TestAcquirerSftpConfig
import com.paycore.financial.acquiring.adapter.integration.TestAcquirerSftpServer
import com.paycore.financial.acquiring.application.provided.ReacquisitionExecution
import com.paycore.financial.acquiring.application.provided.ReadAcquisitionBacklogUseCase
import com.paycore.financial.acquiring.application.provided.SealAcquisitionFileResult
import com.paycore.financial.acquiring.application.provided.TransmissionResult
import com.paycore.financial.acquiring.application.required.AcquirerFileChannel
import com.paycore.financial.acquiring.application.required.AcquiringClock
import com.paycore.financial.acquiring.application.required.AcquisitionFileRepository
import com.paycore.financial.acquiring.application.required.AcquisitionLaneStore
import com.paycore.financial.acquiring.application.required.AcquisitionMembershipStore
import com.paycore.financial.acquiring.application.required.AcquisitionOpsDecisionStore
import com.paycore.financial.acquiring.application.required.AcquisitionPolicySource
import com.paycore.financial.acquiring.application.required.AcquisitionRecordResultStore
import com.paycore.financial.acquiring.application.required.AcquisitionResponseStore
import com.paycore.financial.acquiring.application.required.AcquisitionTransmissionStore
import com.paycore.financial.acquiring.application.required.AcquisitionTransportPolicy
import com.paycore.financial.acquiring.application.required.ReacquisitionApprovalStore
import com.paycore.financial.acquiring.application.required.ReacquisitionExecutionPolicy
import com.paycore.financial.acquiring.application.required.RecordRef
import com.paycore.financial.acquiring.domain.AcquirerResponseFormat
import com.paycore.financial.acquiring.domain.AcquirerResultFile
import com.paycore.financial.acquiring.domain.AcquirerResultLine
import com.paycore.financial.acquiring.domain.AcquisitionEnvironment
import com.paycore.financial.acquiring.domain.AcquisitionFileId
import com.paycore.financial.acquiring.domain.AcquisitionOpsOutcome
import com.paycore.financial.acquiring.domain.AcquisitionRecordType
import com.paycore.financial.acquiring.domain.LineResult
import com.paycore.financial.acquiring.domain.ReacquisitionCommand
import com.paycore.financial.acquiring.domain.ReacquisitionItem
import com.paycore.financial.acquiring.domain.ReacquisitionTargetStatus
import com.paycore.financial.acquiring.domain.RecordResult
import com.paycore.financial.paymentfact.adapter.web.PaymentFactEnvelopeFixture
import com.paycore.financial.paymentfact.application.PaymentFactInboxService
import com.paycore.financial.paymentfact.application.provided.PaymentFactAcquisitionSource
import com.paycore.financial.paymentfact.domain.AuthorizedPaymentFact
import com.paycore.financial.paymentfact.domain.PaymentFact
import com.paycore.financial.paymentfact.domain.PaymentFactFixture
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 재매입 파일 전송·결과 반영과 그 뒤 판정(ACQ-ADMIN-001 B2 — 명세 §14 B2, §15 L-6·L-8·L-9, §11 I-17) 실제 시험: 실제 MySQL runtime 계정(V20 권한) + 실제 SFTP(시험 SSHD).
 * 재매입 파일(세대 1·2)은 **운영 실행 서비스**(ReacquisitionExecutionService)로 만들고, 기존 전송·응답 서비스로 보낸다(전용 경로 없음).
 * 사용자 결정: D-ACQ-009(원 SALE ACCEPTED일 때만 CANCEL), D-ACQ-013(순액), D-ACQ-014(세대를 넘는 ACCEPTED 하나 — 보류). lane = REACQUISITION_RESULT_ACQUIRERS(이 시험 전용).
 *
 * RR-3의 "다른 세대 매입 확정" 상태는 지금 규칙으로 자연 도달하지 않는다(세대 0이 종결 거절이어야 세대 1이 생기고, 그 뒤 세대 0의 ACCEPTED는 CONFLICT — §15 L-1과 같음).
 * 그래서 **명시 상태 Fixture**([acceptedInOtherGeneration] — 의도: 전송 보류 방어만 검사, 앱 규칙 우회 사실을 이름·KDoc에 기록 — Test Fixture Policy)로 표지를 둔다.
 */
@SpringBootTest
@Import(FinancialMySqlContainerConfig::class, TestAcquirerSftpConfig::class)
class ReacquisitionResultDbSpec(
    private val inbox: PaymentFactInboxService,
    private val transactionManager: PlatformTransactionManager,
    private val lanes: AcquisitionLaneStore,
    private val files: AcquisitionFileRepository,
    private val membership: AcquisitionMembershipStore,
    private val source: PaymentFactAcquisitionSource,
    private val policies: AcquisitionPolicySource,
    private val attempts: AcquisitionTransmissionStore,
    private val responses: AcquisitionResponseStore,
    private val results: AcquisitionRecordResultStore,
    private val approvals: ReacquisitionApprovalStore,
    private val decisions: AcquisitionOpsDecisionStore,
    private val board: AcquisitionWaitingBoard,
    private val channel: AcquirerFileChannel,
    private val transport: AcquisitionTransportPolicy,
    private val server: TestAcquirerSftpServer,
    private val jdbc: JdbcTemplate,
    private val backlog: ReadAcquisitionBacklogUseCase,
    private val integrityEvents: AcquiringIntegrityEvents,
) : BehaviorSpec({

    val day1 = LocalDate.parse("2026-11-05")
    val executeAt = Instant.parse("2026-11-06T15:00:00Z")
    val executeAt2 = Instant.parse("2026-11-07T15:00:00Z")

    class FixedClock(@Volatile var now: Instant) : AcquiringClock {
        override fun now(): Instant = now
    }

    val window = object : ReacquisitionExecutionPolicy {
        override val maxExecutionWindow: Duration = Duration.ofHours(24)
        override val clockWindow: Duration = Duration.ofMinutes(5)
    }
    val acquirers = TestAcquirerSftpConfig.REACQUISITION_RESULT_ACQUIRERS.iterator()

    fun receive(fact: PaymentFact) = inbox.receive(PaymentFactEnvelopeFixture.inbound(fact, UUID.randomUUID().toString()))

    fun authorize(acq: String, amount: Long, minute: Int): AuthorizedPaymentFact =
        PaymentFactFixture.authorized(amountMinor = amount, acquirerCode = acq, approvedAt = Instant.parse("2026-11-05T01:%02d:03.456Z".format(minute))).also(::receive)

    fun seal(acq: String, date: LocalDate) =
        AcquisitionSealService(transactionManager, lanes, files, membership, source, policies, FixedClock(date.atTime(14, 5).toInstant(ZoneOffset.UTC)), results, board)
            .seal(acq, "TEST", date)

    fun transmit(fileId: String, at: Instant) = AcquisitionTransmissionService(transactionManager, files, attempts, channel, transport, FixedClock(at)).transmit(fileId)

    fun records(fileId: String): Map<Int, Triple<String, AcquisitionRecordType, Long>> = jdbc.query(
        "SELECT seq, transaction_id, record_type, record_amount_minor FROM acquisition_file_record WHERE file_id = ? ORDER BY seq", { rs, _ ->
            rs.getInt(1) to Triple(rs.getString(2), AcquisitionRecordType.valueOf(rs.getString(3)), rs.getLong(4))
        }, fileId,
    ).toMap()

    fun sha(fileId: String): String = jdbc.queryForObject("SELECT content_sha256 FROM acquisition_file WHERE file_id = ?", String::class.java, fileId)!!

    /** 매입사 결과 파일(회차 [n])을 outbox에 쓰고 기존 응답 서비스로 받는다(기관 수신 시각 = [at]). */
    fun result(acq: String, fileId: String, n: Int, lines: List<Pair<Int, LineResult>>, at: Instant) {
        val id = AcquisitionFileId.parse(fileId)
        val recs = records(fileId)
        val r = AcquirerResultFile(
            id, sha(fileId), n, at.truncatedTo(ChronoUnit.MILLIS),
            lines.map { (seq, res) ->
                val (txn, type, amount) = recs.getValue(seq)
                AcquirerResultLine(seq, txn, type, res, if (res == LineResult.REJECTED) "AMOUNT_MISMATCH" else null, amount)
            },
        )
        Files.write(server.outbox.resolve(AcquirerResponseFormat.resultFileName(id, n)), AcquirerResponseFormat.renderResult(r))
        AcquirerResponseService(transactionManager, files, responses, results, channel, FixedClock(at), membership).receive(acq, "TEST")
    }

    fun service(at: Instant) = ReacquisitionExecutionService(
        transactionManager, files, lanes, results, membership, approvals, decisions, source, policies, window, FixedClock(at), integrityEvents,
    )

    fun reacquire(fileId: String, generation: Int, at: Instant, vararg seqs: Int): String {
        val approvedAt = at.minusSeconds(10)
        val cmd = ReacquisitionCommand(
            approvalId = "apr_" + UUID.randomUUID().toString().replace("-", "").take(26), originalFileId = AcquisitionFileId.parse(fileId),
            items = seqs.map { ReacquisitionItem(it, generation, ReacquisitionTargetStatus.REJECTED) }, requestedBy = "adm_operator_1", approvedBy = "adm_approver_1",
            reasonCode = "INSTITUTION_REQUESTED", evidenceRef = "INQ-2026-0001", approvedAt = approvedAt, executeBefore = approvedAt.plus(Duration.ofHours(1)),
        )
        val d = service(at).execute(cmd).shouldBeInstanceOf<ReacquisitionExecution.Decided>().decision
        d.outcome shouldBe AcquisitionOpsOutcome.EXECUTED
        return requireNotNull(d.resultFileId).value
    }

    fun count(sql: String, vararg args: Any): Int = jdbc.queryForObject(sql, Int::class.java, *args)!!

    fun integrity(): Pair<Long, Long> = backlog.backlog().let { it.nettingMismatches to it.reacquisitionNettingMismatches }

    fun reacquisitionMismatches(fileId: String): Int =
        requireNotNull(TransactionTemplate(transactionManager).execute { membership.reacquisitionNettingMismatches(AcquisitionFileId.parse(fileId)) })

    /** 원 파일(업무일 1): 승인 [amount] 하나, 기관 거절(REJECTED). */
    fun rejectedOriginal(acq: String, amount: Long): Pair<String, AuthorizedPaymentFact> {
        val auth = authorize(acq, amount, 1)
        val fileId = seal(acq, day1).shouldBeInstanceOf<SealAcquisitionFileResult.Sealed>().fileId
        transmit(fileId, Instant.parse("2026-11-05T14:06:00Z")).outcome shouldBe TransmissionResult.Kind.TRANSMITTED
        result(acq, fileId, 1, listOf(1 to LineResult.REJECTED), Instant.parse("2026-11-05T15:00:00Z"))
        return fileId to auth
    }

    /**
     * **명시 상태 Fixture(의도된 비자연 상태 — 앱 규칙 우회)**: 원 파일 [fileId] 레코드 [seq]의 거래를 매입 확정 표지로 표시한다. 지금 규칙으로는 세대 0 종결 거절 뒤 세대 0
     * ACCEPTED가 CONFLICT로 격리돼 표지가 생기지 않는다(L-1). 표지 PK·FK만 만족시키고(가리키는 결과 행 = 그 레코드의 첫 결과 행) 전송 보류 방어만 검사한다.
     */
    fun acceptedInOtherGeneration(fileId: String, seq: Int, transactionId: String) {
        val ref = RecordRef(AcquisitionFileId.parse(fileId), seq)
        val first = results.entries(ref.fileId).getValue(seq).first()
        results.markAccepted(ref, transactionId, AcquisitionRecordType.SALE, 0, first.copy(result = RecordResult.ACCEPTED, reasonCode = null))
    }

    Given("RR-1 the generation 1 file is transmitted on the existing path, ACCEPTED by the acquirer, then a cancellation arrives (L-6: CANCEL record)") {
        val acq = acquirers.next()
        val (f0, auth) = rejectedOriginal(acq, 10_000)
        receive(PaymentFactFixture.cancelOf(auth, 3_000))
        seal(acq, day1.plusDays(1)) // 원 SALE 거절 → "취소 매입 불요"(판정 끝남).
        val g1 = reacquire(f0, 0, executeAt, 1)
        val sent = transmit(g1, executeAt.plusSeconds(60))
        result(acq, g1, 1, listOf(1 to LineResult.ACCEPTED), executeAt.plusSeconds(3_600))
        receive(PaymentFactFixture.cancelOf(auth, 2_000, returnSequence = 2, cumulativeMinor = 5_000))
        val before = integrity()
        val sealed = seal(acq, day1.plusDays(2)).shouldBeInstanceOf<SealAcquisitionFileResult.Sealed>()
        val after = integrity()

        Then("the re-acquisition file went through the existing SFTP path and its ACCEPTED marks the acquisition at generation 1 (D-ACQ-014)") {
            (sent.outcome to sent.state) shouldBe (TransmissionResult.Kind.TRANSMITTED to "TRANSMITTED")
            Files.exists(server.inboxFile("$g1.dat")) shouldBe true
            jdbc.queryForMap("SELECT file_id, seq, generation FROM acquisition_accepted_record WHERE transaction_id = ?", auth.transactionId).values.toList() shouldBe
                listOf(g1, 1, 1)
            records(g1).getValue(1).third shouldBe 7_000L
        }
        Then("the later cancellation becomes a CANCEL record of the next regular file (CancelCandidateRule opened for generation ≥ 1 — rev 0.6 waited INCONSISTENT)") {
            listOf(sealed.cancelRecords, sealed.cancelNotRequired) shouldBe listOf(1, 0)
            sealed.cancelWaiting shouldBe emptyMap()
            jdbc.queryForMap(
                "SELECT record_type, original_transaction_id, approved_amount_minor, record_amount_minor, generation FROM acquisition_file_record WHERE file_id = ? AND record_type = 'CANCEL'",
                sealed.fileId,
            ).values.toList() shouldBe listOf("CANCEL", auth.transactionId, 10_000L, 2_000L, 0)
        }
        Then("I-17 (rev 0.7 — L-8): that CANCEL record is not part of the generation 1 netted amount — both integrity checks stay unchanged") {
            // RED(rev 0.6 정의 ③ "세대 0 CANCEL 레코드 합"): 재매입 뒤 CANCEL 2,000이 세대 1 상계액 3,000에 섞여 3,000 ≠ 3,000 + 2,000 → 재매입 불일치 +1.
            reacquisitionMismatches(g1) shouldBe 0
            (after.first - before.first) shouldBe 0L
            (after.second - before.second) shouldBe 0L
        }
    }

    Given("RR-2 the generation 1 file is REJECTED, then a cancellation arrives (L-6: 'not required' against generation 1), then generation 2 is executed") {
        val acq = acquirers.next()
        val (f0, auth) = rejectedOriginal(acq, 10_000)
        receive(PaymentFactFixture.cancelOf(auth, 3_000))
        seal(acq, day1.plusDays(1))
        val g1 = reacquire(f0, 0, executeAt, 1)
        transmit(g1, executeAt.plusSeconds(60)).outcome shouldBe TransmissionResult.Kind.TRANSMITTED
        result(acq, g1, 1, listOf(1 to LineResult.REJECTED), executeAt.plusSeconds(3_600))
        receive(PaymentFactFixture.cancelOf(auth, 2_000, returnSequence = 2, cumulativeMinor = 5_000))
        val sealed = seal(acq, day1.plusDays(2)).shouldBeInstanceOf<SealAcquisitionFileResult.Sealed>()
        val before = integrity()
        val g2 = reacquire(g1, 1, executeAt2, 1)
        val after = integrity()

        Then("the cancellation is closed 'not required' against the rejected generation 1 record (not the old generation 0 — I-14)") {
            listOf(sealed.cancelRecords, sealed.cancelNotRequired) shouldBe listOf(0, 1)
            jdbc.queryForMap(
                "SELECT original_file_id, original_seq, amount_minor FROM acquisition_cancel_not_required WHERE cancellation_transaction_id = ?",
                "ptx_cancel_${auth.identity.paymentId}_2",
            ).values.toList() shouldBe listOf(g1, 1, 2_000L)
        }
        Then("generation 2 nets every cancellation once (10,000 − 3,000 − 2,000 = 5,000) and I-17 ② counts the generation 1 'not required' for it") {
            g2 shouldBe "ACQ.$acq.TEST.20261105.003"
            jdbc.queryForMap("SELECT generation, reacquisition_of, netted_cancel_amount_minor, record_amount_minor FROM acquisition_file_record WHERE file_id = ?", g2)
                .values.toList() shouldBe listOf(2, "$g1#1", 5_000L, 5_000L)
            reacquisitionMismatches(g2) shouldBe 0
            reacquisitionMismatches(g1) shouldBe 0
            (after.second - before.second) shouldBe 0L
        }
    }

    Given("RR-3 (L-9) a record of a sealed re-acquisition file is meanwhile accepted in another generation (explicit state fixture — D-ACQ-014)") {
        val acq = acquirers.next()
        val (f0, auth) = rejectedOriginal(acq, 7_000)
        val g1 = reacquire(f0, 0, executeAt, 1)
        TransactionTemplate(transactionManager).executeWithoutResult { acceptedInOtherGeneration(f0, 1, auth.transactionId) }
        val withheldBefore = backlog.backlog().transmissionWithheld
        val first = transmit(g1, executeAt.plusSeconds(60))
        val again = transmit(g1, executeAt.plusSeconds(120))

        Then("the claim re-checks the mark under the lock and withholds the file: nothing is uploaded, no attempt row, one evidence row per record") {
            listOf(first.outcome, first.state, first.detail) shouldBe listOf(TransmissionResult.Kind.WITHHELD, "TRANSMISSION_WITHHELD", "ACCEPTED_IN_OTHER_GENERATION")
            Files.exists(server.inboxFile("$g1.dat")) shouldBe false
            Files.list(server.inbox).use { s -> s.filter { it.fileName.toString().startsWith(".$g1.dat") }.count() } shouldBe 0L
            TransactionTemplate(transactionManager).execute { attempts.history(AcquisitionFileId.parse(g1)) } shouldBe emptyList()
            jdbc.queryForList(
                "SELECT seq, transaction_id, record_type, accepted_file_id, accepted_seq, accepted_generation, prior_state FROM acquisition_transmission_withhold WHERE file_id = ?",
                g1,
            ).map { it.values.toList() } shouldBe listOf(listOf(1, auth.transactionId, "SALE", f0, 1, 0, "SEALED"))
        }
        Then("a withheld file is terminal: never claimed again, not listed as transmittable, counted by the withheld gauge (reconciliation input)") {
            (again.outcome to again.state) shouldBe (TransmissionResult.Kind.WITHHELD to "TRANSMISSION_WITHHELD")
            count("SELECT COUNT(*) FROM acquisition_transmission_withhold WHERE file_id = ?", g1) shouldBe 1
            TransactionTemplate(transactionManager).execute {
                files.transmittable(acq, AcquisitionEnvironment.TEST, executeAt.plusSeconds(180), 10).map { it.value }
            } shouldBe emptyList()
            (backlog.backlog().transmissionWithheld - withheldBefore) shouldBe 1L
        }
    }

    Given("RR-4 (L-9) the late acceptance commits while the claim waits: the claim serializes on the other generation's file row lock") {
        val acq = acquirers.next()
        val (f0, auth) = rejectedOriginal(acq, 6_000)
        val g1 = reacquire(f0, 0, executeAt, 1)
        val pool = Executors.newFixedThreadPool(2)
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        // 응답 TX 흉내: 원 파일 행을 잠그고(응답 TX와 같은 잠금) 표지를 쓴 채 커밋을 미룬다.
        val holder = pool.submit {
            TransactionTemplate(transactionManager).executeWithoutResult {
                files.lockFile(AcquisitionFileId.parse(f0))
                acceptedInOtherGeneration(f0, 1, auth.transactionId)
                locked.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
        }
        locked.await(30, TimeUnit.SECONDS) shouldBe true
        val claim = pool.submit<TransmissionResult> { transmit(g1, executeAt.plusSeconds(60)) }
        val waited = try {
            claim.get(2, TimeUnit.SECONDS)
            false
        } catch (e: TimeoutException) {
            true
        }
        release.countDown()
        holder.get(30, TimeUnit.SECONDS)
        val outcome = claim.get(60, TimeUnit.SECONDS)
        pool.shutdown()

        Then("the claim waited for the uncommitted acceptance and then withheld the file — it did not read past it and upload (M12 removes the lock → RED)") {
            waited shouldBe true
            (outcome.outcome to outcome.state) shouldBe (TransmissionResult.Kind.WITHHELD to "TRANSMISSION_WITHHELD")
            Files.exists(server.inboxFile("$g1.dat")) shouldBe false
        }
    }
})

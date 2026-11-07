package com.paycore.financial.acquiring.application

import com.paycore.financial.FinancialMySqlContainerConfig
import com.paycore.financial.acquiring.adapter.integration.TestAcquirerSftpConfig
import com.paycore.financial.acquiring.adapter.integration.TestAcquirerSftpServer
import com.paycore.financial.acquiring.application.provided.ReacquisitionExecution
import com.paycore.financial.acquiring.application.provided.ReadAcquisitionBacklogUseCase
import com.paycore.financial.acquiring.application.provided.SealAcquisitionFileResult
import com.paycore.financial.acquiring.application.required.AcquirerFileChannel
import com.paycore.financial.acquiring.application.required.AcquiringClock
import com.paycore.financial.acquiring.application.required.AcquisitionBacklogReader
import com.paycore.financial.acquiring.application.required.AcquisitionResultPolicy
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
import com.paycore.financial.acquiring.domain.AcquirerResponseFormat
import com.paycore.financial.acquiring.domain.AcquirerResultFile
import com.paycore.financial.acquiring.domain.AcquirerResultLine
import com.paycore.financial.acquiring.domain.AcquisitionEnvironment
import com.paycore.financial.acquiring.domain.AcquisitionFileFormat
import com.paycore.financial.acquiring.domain.AcquisitionOpsDecision
import com.paycore.financial.acquiring.domain.AcquisitionFileId
import com.paycore.financial.acquiring.domain.AcquisitionOpsOrigin
import com.paycore.financial.acquiring.domain.AcquisitionOpsOutcome
import com.paycore.financial.acquiring.domain.AcquisitionRecordType
import com.paycore.financial.acquiring.domain.LineResult
import com.paycore.financial.acquiring.domain.ReacquisitionApproval
import com.paycore.financial.acquiring.domain.ReacquisitionCommand
import com.paycore.financial.acquiring.domain.ReacquisitionItem
import com.paycore.financial.acquiring.domain.ReacquisitionTargetStatus
import com.paycore.financial.paymentfact.adapter.web.PaymentFactEnvelopeFixture
import com.paycore.financial.paymentfact.application.PaymentFactInboxService
import com.paycore.financial.paymentfact.application.provided.PaymentFactAcquisitionSource
import com.paycore.financial.paymentfact.domain.AuthorizedPaymentFact
import com.paycore.financial.paymentfact.domain.PaymentFact
import com.paycore.financial.paymentfact.domain.PaymentFactFixture
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
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
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 재매입 실행 실제 DB 시험(RX-1..RX-9 — ACQ-ADMIN-001 B1, 명세 §3.2·§7). 실제 MySQL runtime 계정(V18 권한) + 실제 SFTP(시험 SSHD — 원 파일 결과 거절을 만든다).
 * 사용자 결정: D-ACQ-011(2인·FINANCIAL 재검사), D-ACQ-013(순액), D-ACQ-014(ACCEPTED·UNKNOWN), D-ACQA-003(묶음 전체 무효), D-ACQA-004(불요 종결).
 * Fixture: 결제 사실은 PaymentFactFixture(명시 금액), lane은 시험마다 다른 매입사(REACQUISITION_ACQUIRERS — 다른 시험 lane과 겹치지 않음).
 */
@SpringBootTest
@Import(FinancialMySqlContainerConfig::class, TestAcquirerSftpConfig::class)
class ReacquisitionExecutionDbSpec(
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
    private val backlogReader: AcquisitionBacklogReader,
    private val integrityEvents: AcquiringIntegrityEvents,
    private val registry: io.micrometer.core.instrument.MeterRegistry,
) : BehaviorSpec({

    val day1 = LocalDate.parse("2026-11-05")
    val receivedAt = Instant.parse("2026-11-05T15:00:00Z")
    val executeAt = Instant.parse("2026-11-06T15:00:00Z")

    class FixedClock(@Volatile var now: Instant) : AcquiringClock {
        override fun now(): Instant = now
    }

    val window = object : ReacquisitionExecutionPolicy {
        override val maxExecutionWindow: Duration = Duration.ofHours(24)
        override val clockWindow: Duration = Duration.ofMinutes(5)
    }
    val acquirers = TestAcquirerSftpConfig.REACQUISITION_ACQUIRERS.iterator()

    fun receive(fact: PaymentFact) = inbox.receive(PaymentFactEnvelopeFixture.inbound(fact, UUID.randomUUID().toString()))

    fun authorize(acq: String, amount: Long, minute: Int): AuthorizedPaymentFact =
        PaymentFactFixture.authorized(amountMinor = amount, acquirerCode = acq, approvedAt = Instant.parse("2026-11-05T01:%02d:03.456Z".format(minute))).also(::receive)

    fun seal(acq: String, date: LocalDate, laneStore: AcquisitionLaneStore = lanes) =
        AcquisitionSealService(transactionManager, laneStore, files, membership, source, policies, FixedClock(date.atTime(14, 5).toInstant(java.time.ZoneOffset.UTC)), results, board)
            .seal(acq, "TEST", date)

    /** 지표 원천(`integrity.netting_mismatch`·`integrity.reacquisition_netting_mismatch` gauge 값 — AcquiringMetrics가 그대로 싣는다). */
    fun integrity(): Pair<Long, Long> = backlog.backlog().let { it.nettingMismatches to it.reacquisitionNettingMismatches }

    /** 결과 마감(D-ACQ-004): 전송 뒤 1시간 결과가 없으면 UNKNOWN. */
    fun close(acq: String) = AcquisitionResultCloseService(
        transactionManager, files, results, object : AcquisitionResultPolicy {
            override val resultDeadline: Duration? = Duration.ofHours(1)
        }, FixedClock(receivedAt.plus(Duration.ofHours(3))),
    ).closeExpired(acq, "TEST", 10)

    /** 잠금을 얻은 직후 [hook]을 부르는 lane Port 래퍼(시험 전용 — 잠금 대기·보유를 흉내 낸다). */
    fun lanesWith(hook: () -> Unit) = object : AcquisitionLaneStore by lanes {
        override fun lock(acquirerCode: String, environment: AcquisitionEnvironment, at: Instant): LocalDate? = lanes.lock(acquirerCode, environment, at).also { hook() }
    }

    fun transmit(fileId: String) =
        AcquisitionTransmissionService(transactionManager, files, attempts, channel, transport, FixedClock(Instant.parse("2026-11-05T14:06:00Z"))).transmit(fileId)

    fun recordsOf(fileId: String): Map<Int, Triple<String, AcquisitionRecordType, Long>> = jdbc.query(
        "SELECT seq, transaction_id, record_type, record_amount_minor FROM acquisition_file_record WHERE file_id = ? ORDER BY seq", { rs, _ ->
            rs.getInt(1) to Triple(rs.getString(2), AcquisitionRecordType.valueOf(rs.getString(3)), rs.getLong(4))
        }, fileId,
    ).toMap()

    fun sha(fileId: String): String = jdbc.queryForObject("SELECT content_sha256 FROM acquisition_file WHERE file_id = ?", String::class.java, fileId)!!

    /** 원 파일 결과를 매입사 outbox에 쓰고 받는다(결과 회차 [n]). */
    fun result(acq: String, fileId: String, n: Int, lines: List<Pair<Int, LineResult>>) {
        val id = AcquisitionFileId.parse(fileId)
        val records = recordsOf(fileId)
        val r = AcquirerResultFile(
            id, sha(fileId), n, receivedAt.truncatedTo(ChronoUnit.MILLIS),
            lines.map { (seq, res) ->
                val (txn, type, amount) = records.getValue(seq)
                AcquirerResultLine(seq, txn, type, res, if (res == LineResult.REJECTED) "AMOUNT_MISMATCH" else null, amount)
            },
        )
        Files.write(server.outbox.resolve(AcquirerResponseFormat.resultFileName(id, n)), AcquirerResponseFormat.renderResult(r))
        AcquirerResponseService(transactionManager, files, responses, results, channel, FixedClock(receivedAt), membership).receive(acq, "TEST")
    }

    fun service(
        at: Instant = executeAt,
        store: ReacquisitionApprovalStore = approvals,
        clock: AcquiringClock = FixedClock(at),
        laneStore: AcquisitionLaneStore = lanes,
        decisionStore: AcquisitionOpsDecisionStore = decisions,
    ) = ReacquisitionExecutionService(
        transactionManager, files, laneStore, results, membership, store, decisionStore, source, policies, window, clock, integrityEvents,
    )

    fun command(
        fileId: String,
        vararg seqs: Int,
        approvalId: String = "apr_" + UUID.randomUUID().toString().replace("-", "").take(26),
        status: ReacquisitionTargetStatus = ReacquisitionTargetStatus.REJECTED,
        requestedBy: String = "adm_operator_1",
        approvedBy: String = "adm_approver_1",
        approvedAt: Instant = executeAt.minusSeconds(10),
        evidence: String = "INQ-2026-0001",
    ) = ReacquisitionCommand(
        approvalId = approvalId, originalFileId = AcquisitionFileId.parse(fileId), items = seqs.map { ReacquisitionItem(it, 0, status) }, requestedBy = requestedBy,
        approvedBy = approvedBy, reasonCode = "INSTITUTION_REQUESTED", evidenceRef = evidence, approvedAt = approvedAt, executeBefore = approvedAt.plus(Duration.ofHours(1)),
    )

    fun count(sql: String, vararg args: Any): Int = jdbc.queryForObject(sql, Int::class.java, *args)!!

    /** 원 파일(업무일 1): 승인 [amounts], 모두 기관 거절(REJECTED). 반환 = (매입사, 파일 ID, 승인 사실). */
    fun rejectedFile(vararg amounts: Long): Triple<String, String, List<AuthorizedPaymentFact>> {
        val acq = acquirers.next()
        val auths = amounts.mapIndexed { i, a -> authorize(acq, a, i + 1) }
        val fileId = (seal(acq, day1) as SealAcquisitionFileResult.Sealed).fileId
        transmit(fileId)
        result(acq, fileId, 1, recordsOf(fileId).keys.map { it to LineResult.REJECTED })
        return Triple(acq, fileId, auths)
    }

    Given("RX-1 two rejected sales, one with a cancellation received after membership and closed as 'cancel not required' (D-ACQ-009), re-acquired under one approval") {
        val (acq, fileId, auths) = rejectedFile(10_000, 8_000)
        receive(PaymentFactFixture.cancelOf(auths[1], 3_000))
        seal(acq, day1.plusDays(1)) // 원 SALE이 거절이라 그 취소는 "취소 매입 불요"로 판정이 끝난다.
        val cmd = command(fileId, 1, 2)
        val integrityBefore = integrity()
        val decided = service().execute(cmd).shouldBeInstanceOf<ReacquisitionExecution.Decided>()
        val integrityAfter = integrity()
        val newFile = "ACQ.$acq.TEST.20261105.002"

        Then("MJ-1 (rev 0.3): the re-acquisition does not move either netting integrity gauge — generation 0 netting and the generation ≥ 1 check (I-17) both stay unchanged") {
            // RED(rev 0.2 코드): 세대 구분 없는 상계 검사가 재매입 레코드(상계액 3,000 — 그를 가리키는 상계 행 없음)를 +1로 셌다.
            (integrityAfter.first - integrityBefore.first) shouldBe 0L
            (integrityAfter.second - integrityBefore.second) shouldBe 0L
            TransactionTemplate(transactionManager).execute { membership.reacquisitionNettingMismatches(AcquisitionFileId.parse(newFile)) } shouldBe 0
        }
        And("MJ-1 (rev 0.3): a generation ≥ 1 netted amount that disagrees with the decided cancellations is caught by the new check, not by the generation 0 one (rolled back)") {
            // 손상 주입(의도된 Invalid 상태 — 시험 TX 안에서만, rollback): 원 레코드(세대 0, #2)를 가리키는 "취소 매입 불요" 행을 하나 더 두면 판정 끝난 취소 합(3,000 + 1,000)이
            // 재매입 레코드 상계액(3,000)과 달라진다. 세대 0 상계 검사(상계 행 합 ↔ 세대 0 상계액)는 영향이 없다.
            val seen = TransactionTemplate(transactionManager).execute { status ->
                jdbc.update(
                    "INSERT INTO acquisition_cancel_not_required (cancellation_transaction_id, original_transaction_id, original_file_id, original_seq, amount_minor, " +
                        "currency, business_date, decided_at) VALUES (?, ?, ?, 2, 1000, 'KRW', ?, ?)",
                    "ptx_injected_${UUID.randomUUID()}", auths[1].transactionId, fileId, day1.plusDays(1), java.sql.Timestamp.from(executeAt),
                )
                val counts = backlogReader.read()
                val perFile = membership.reacquisitionNettingMismatches(AcquisitionFileId.parse(newFile))
                status.setRollbackOnly()
                Triple(counts.nettingMismatches - integrityAfter.first, counts.reacquisitionNettingMismatches - integrityAfter.second, perFile)
            }
            seen shouldBe Triple(0L, 1L, 1)
            integrity() shouldBe integrityAfter
        }

        Then("one local TX sealed the next version with generation 1 records at the net amount (D-ACQ-013) and recorded the decision and both approvals") {
            decided.decision.outcome shouldBe AcquisitionOpsOutcome.EXECUTED
            decided.decision.resultFileId?.value shouldBe newFile
            jdbc.queryForMap("SELECT original_file_id, file_version, transmission_state, record_count FROM acquisition_file WHERE file_id = ?", newFile).values.toList() shouldBe
                listOf(fileId, 2, "SEALED", 2)
            jdbc.query(
                "SELECT seq, transaction_id, generation, reacquisition_of, netted_cancel_amount_minor, record_amount_minor FROM acquisition_file_record WHERE file_id = ? ORDER BY seq",
                { rs, _ -> listOf(rs.getInt(1), rs.getString(2), rs.getInt(3), rs.getString(4), rs.getLong(5), rs.getLong(6)) }, newFile,
            ) shouldBe listOf(
                listOf(1, auths[0].transactionId, 1, "$fileId#1", 0L, 10_000L),
                listOf(2, auths[1].transactionId, 1, "$fileId#2", 3_000L, 5_000L),
            )
            count("SELECT COUNT(*) FROM acquisition_reacquisition_approval WHERE approval_id = ?", cmd.approvalId) shouldBe 2
            count("SELECT COUNT(*) FROM acquisition_ops_decision WHERE approval_id = ? AND decision = 'EXECUTED' AND result_file_id = ?", cmd.approvalId, newFile) shouldBe 1
            // 봉인 바이트는 저장 헤더·레코드로 다시 렌더링한 값과 같다(재시작 무결성 I-11과 같은 대조).
            val stored = requireNotNull(TransactionTemplate(transactionManager).execute { files.find(AcquisitionFileId.parse(newFile)) })
            stored.intact() shouldBe true
            AcquisitionFileFormat.parse(stored.content).header.originalFileId?.value shouldBe fileId
        }
        And("the same approval id with the same content replays the decision; with other content it is a conflict; nothing new is written") {
            service().execute(cmd).shouldBeInstanceOf<ReacquisitionExecution.Decided>().let { (it.replayed to it.decision.resultFileId?.value) shouldBe (true to newFile) }
            service().execute(cmd.copy(evidenceRef = "INQ-OTHER")) shouldBe ReacquisitionExecution.Conflict
            count("SELECT COUNT(*) FROM acquisition_file WHERE acquirer_code = ?", acq) shouldBe 3
            count("SELECT COUNT(*) FROM acquisition_ops_decision WHERE approval_id = ?", cmd.approvalId) shouldBe 1
        }
        And("RX-7 a cancellation received after the re-acquisition is NOT closed as 'not required' against the rejected old generation; it waits for the new generation's result") {
            // 원 SALE membership은 이제 가장 높은 세대(재매입)다(I-14). rev 0.7(B2 — §15 L-6): 세대 ≥ 1 원 SALE도 CancelCandidateRule이 정합으로 보고 그 세대 결과로 판정한다 —
            // 새 세대가 아직 결과 대기라 ORIGINAL_AWAITING_RESULT 대기(기대값 변경: rev 0.6 "INCONSISTENT" → 근거 L-6). 잘못된 종결 기록이 없다는 단언(불요 0·CANCEL 0)은 그대로다.
            // 새 세대 결과 뒤 판정(ACCEPTED → CANCEL, REJECTED → 불요)은 ReacquisitionResultDbSpec RR-1·RR-2.
            receive(PaymentFactFixture.cancelOf(auths[0], 2_000))
            val sealed = seal(acq, day1.plusDays(2)).shouldBeInstanceOf<SealAcquisitionFileResult.Sealed>()
            listOf(sealed.cancelNotRequired, sealed.cancelRecords) shouldBe listOf(0, 0)
            sealed.cancelWaiting shouldBe mapOf("ORIGINAL_AWAITING_RESULT" to 1)
            count("SELECT COUNT(*) FROM acquisition_cancel_not_required WHERE original_transaction_id = ?", auths[0].transactionId) shouldBe 0
        }
        And("a second approval for the same records is refused NOT_LATEST_GENERATION (decision recorded, nothing sealed)") {
            service().execute(command(fileId, 1)).shouldBeInstanceOf<ReacquisitionExecution.Decided>().decision.rejectionCode shouldBe "NOT_LATEST_GENERATION"
            count("SELECT COUNT(*) FROM acquisition_file WHERE acquirer_code = ? AND business_date = ?", acq, day1) shouldBe 2
        }
    }

    Given("RX-2 a bundle where one record has since been acquired in some generation (D-ACQA-003 — whole approval refused, no partial execution)") {
        val acq = acquirers.next()
        listOf(authorize(acq, 10_000, 1), authorize(acq, 7_000, 2))
        val fileId = (seal(acq, day1) as SealAcquisitionFileResult.Sealed).fileId
        transmit(fileId)
        result(acq, fileId, 1, listOf(1 to LineResult.REJECTED, 2 to LineResult.ACCEPTED))
        val cmd = command(fileId, 1, 2)
        val d = service().execute(cmd).shouldBeInstanceOf<ReacquisitionExecution.Decided>().decision
        Then("REJECTED(ALREADY_ACCEPTED) is recorded and neither record is re-acquired") {
            (d.outcome to d.rejectionCode) shouldBe (AcquisitionOpsOutcome.REJECTED to "ALREADY_ACCEPTED")
            count("SELECT COUNT(*) FROM acquisition_file WHERE acquirer_code = ?", acq) shouldBe 1
            count("SELECT COUNT(*) FROM acquisition_reacquisition_approval WHERE approval_id = ?", cmd.approvalId) shouldBe 0
        }
    }

    Given("RX-3 the rejected sale is fully cancelled before execution (D-ACQA-004 — 're-acquisition not required')") {
        val (acq, fileId, auths) = rejectedFile(6_000)
        receive(PaymentFactFixture.cancelOf(auths[0], 6_000))
        seal(acq, day1.plusDays(1))
        val cmd = command(fileId, 1)
        val d = service().execute(cmd).shouldBeInstanceOf<ReacquisitionExecution.Decided>().decision
        Then("NOT_REQUIRED is recorded with the approval and the closing row; no file is sealed; a later request is refused ALREADY_NOT_REQUIRED") {
            d.outcome shouldBe AcquisitionOpsOutcome.NOT_REQUIRED
            count("SELECT COUNT(*) FROM acquisition_file WHERE acquirer_code = ? AND business_date = ?", acq, day1) shouldBe 1
            jdbc.queryForMap(
                "SELECT approval_id, approved_amount_minor, cancelled_amount_minor FROM acquisition_reacquisition_not_required WHERE transaction_id = ?", auths[0].transactionId,
            ).values.toList() shouldBe listOf(cmd.approvalId, 6_000L, 6_000L)
            service().execute(command(fileId, 1)).shouldBeInstanceOf<ReacquisitionExecution.Decided>().decision.rejectionCode shouldBe "ALREADY_NOT_REQUIRED"
        }
    }

    Given("RX-4 fail-closed inputs: an undecided cancellation, the same person, an approval pinned to UNKNOWN for a REJECTED record (target version mismatch), an expired approval") {
        val (acq, fileId, auths) = rejectedFile(9_000)
        receive(PaymentFactFixture.cancelOf(auths[0], 1_000)) // 다음 봉인 전 — 아직 판정 안 된 취소
        Then("each is a recorded REJECTED decision with its code and nothing is sealed") {
            fun code(c: ReacquisitionCommand, at: Instant = executeAt) = service(at).execute(c).shouldBeInstanceOf<ReacquisitionExecution.Decided>().decision.rejectionCode
            code(command(fileId, 1)) shouldBe "CANCELLATION_UNDECIDED"
            code(command(fileId, 1, requestedBy = "adm_same", approvedBy = "adm_same")) shouldBe "SAME_PERSON"
            code(command(fileId, 1, status = ReacquisitionTargetStatus.UNKNOWN)) shouldBe "TARGET_CHANGED"
            code(command(fileId, 1), at = executeAt.plus(Duration.ofHours(2))) shouldBe "APPROVAL_EXECUTION_EXPIRED"
            code(command("ACQ.$acq.TEST.20261104.001", 1)) shouldBe "TARGET_NOT_FOUND"
            count("SELECT COUNT(*) FROM acquisition_file WHERE acquirer_code = ? AND business_date = ?", acq, day1) shouldBe 1
            count("SELECT COUNT(*) FROM acquisition_reacquisition_approval WHERE file_id = ?", fileId) shouldBe 0
        }
    }

    Given("RX-4U (rev 0.3 — m-4) a record that is really UNKNOWN: transmitted, no result by the result deadline, closed as UNKNOWN (D-ACQ-004)") {
        val acq = acquirers.next()
        authorize(acq, 6_500, 1)
        val fileId = (seal(acq, day1) as SealAcquisitionFileResult.Sealed).fileId
        transmit(fileId)
        close(acq).unknownRecords shouldBe 1
        Then("re-acquisition is refused UNKNOWN_NOT_RECONCILED (D-ACQ-014 — reconciliation first) whatever the pinned status; nothing is sealed or recorded as an approval") {
            jdbc.queryForList("SELECT result FROM acquisition_record_result WHERE file_id = ? AND seq = 1", String::class.java, fileId) shouldBe listOf("UNKNOWN")
            listOf(ReacquisitionTargetStatus.UNKNOWN, ReacquisitionTargetStatus.REJECTED).forEach { pinned ->
                val d = service().execute(command(fileId, 1, status = pinned)).shouldBeInstanceOf<ReacquisitionExecution.Decided>().decision
                (d.outcome to d.rejectionCode) shouldBe (AcquisitionOpsOutcome.REJECTED to "UNKNOWN_NOT_RECONCILED")
            }
            requireNotNull(service().targets(fileId, listOf(1))).items.single().let { (it.eligible to it.refusal) shouldBe (false to "UNKNOWN_NOT_RECONCILED") }
            count("SELECT COUNT(*) FROM acquisition_file WHERE acquirer_code = ?", acq) shouldBe 1
            count("SELECT COUNT(*) FROM acquisition_reacquisition_approval WHERE file_id = ?", fileId) shouldBe 0
        }
    }

    Given("RX-5a failure after the new file and decision rows were written (approval store fails) — one local TX") {
        val (acq, fileId, _) = rejectedFile(5_000)
        val failing = object : ReacquisitionApprovalStore by approvals {
            override fun append(approvals: List<ReacquisitionApproval>) = throw IllegalStateException("injected approval store failure")
        }
        val cmd = command(fileId, 1)
        shouldThrow<IllegalStateException> { service(store = failing).execute(cmd) }
        Then("everything rolls back: no new version, no decision, no approval (a retry then decides from scratch)") {
            count("SELECT COUNT(*) FROM acquisition_file WHERE acquirer_code = ?", acq) shouldBe 1
            count("SELECT COUNT(*) FROM acquisition_ops_decision WHERE approval_id = ?", cmd.approvalId) shouldBe 0
            service().execute(cmd).shouldBeInstanceOf<ReacquisitionExecution.Decided>().decision.outcome shouldBe AcquisitionOpsOutcome.EXECUTED
        }
    }

    Given("RX-6 concurrency on real connections: two approvals for the same record, and the same approval sent twice, released together (CyclicBarrier)") {
        val (acq, fileId, _) = rejectedFile(4_000)
        val first = command(fileId, 1)
        val second = command(fileId, 1)
        val pool = Executors.newFixedThreadPool(4)
        fun race(vararg commands: ReacquisitionCommand): List<ReacquisitionExecution> {
            val barrier = CyclicBarrier(commands.size)
            val futures = commands.map { c -> pool.submit<ReacquisitionExecution> { barrier.await(10, TimeUnit.SECONDS); service().execute(c) } }
            return futures.map { it.get(60, TimeUnit.SECONDS) }
        }
        val different = race(first, second)
        val (_, otherFile, _) = rejectedFile(4_500)
        val twice = command(otherFile, 1)
        val same = race(twice, twice)
        pool.shutdown()
        Then("different approvals serialize on the file/lane locks: exactly one EXECUTED, the other REJECTED(NOT_LATEST_GENERATION); one generation 1 record") {
            different.map { (it as ReacquisitionExecution.Decided).decision.let { d -> d.rejectionCode ?: d.outcome.name } } shouldContainExactlyInAnyOrder
                listOf("EXECUTED", "NOT_LATEST_GENERATION")
            count("SELECT COUNT(*) FROM acquisition_file_record WHERE reacquisition_of = ?", "$fileId#1") shouldBe 1
            count("SELECT COUNT(*) FROM acquisition_file WHERE acquirer_code = ?", acq) shouldBe 2
        }
        And("the same approval sent twice at once: one execution, the other replays it (UNIQUE approval id + re-check after the locks) — one decision row, one new file") {
            same.map { it.shouldBeInstanceOf<ReacquisitionExecution.Decided>().let { d -> d.replayed to d.decision.outcome } } shouldContainExactlyInAnyOrder
                listOf(false to AcquisitionOpsOutcome.EXECUTED, true to AcquisitionOpsOutcome.EXECUTED)
            count("SELECT COUNT(*) FROM acquisition_ops_decision WHERE approval_id = ?", twice.approvalId) shouldBe 1
            count("SELECT COUNT(*) FROM acquisition_file_record WHERE reacquisition_of = ?", "$otherFile#1") shouldBe 1
        }
    }

    Given("RX-8 deadline confirmation (D-OPS-009 precedent — no execution after the deadline)") {
        val (_, fileId, _) = rejectedFile(3_000)
        val cmd = command(fileId, 1)
        Then("before the deadline nothing is written; after it EXPIRED is recorded once and a late execution command replays it without sealing") {
            service().confirmDeadline(cmd) shouldBe ReacquisitionExecution.NotDecided
            count("SELECT COUNT(*) FROM acquisition_ops_decision WHERE approval_id = ?", cmd.approvalId) shouldBe 0
            val late = executeAt.plus(Duration.ofHours(2))
            service(late).confirmDeadline(cmd).shouldBeInstanceOf<ReacquisitionExecution.Decided>().let {
                listOf(it.confirmed, it.decision.rejectionCode, it.decision.origin) shouldBe listOf(true, "APPROVAL_EXECUTION_EXPIRED", AcquisitionOpsOrigin.DEADLINE_CONFIRMATION)
            }
            service().execute(cmd).shouldBeInstanceOf<ReacquisitionExecution.Decided>().let { (it.replayed to it.decision.rejectionCode) shouldBe (true to "APPROVAL_EXECUTION_EXPIRED") }
            count("SELECT COUNT(*) FROM acquisition_file_record WHERE reacquisition_of = ?", "$fileId#1") shouldBe 0
        }
    }

    Given("RX-9 the preview (non-authoritative, no locks) uses the same rule as the execution") {
        val (acq, fileId, auths) = rejectedFile(10_000, 2_000)
        receive(PaymentFactFixture.cancelOf(auths[0], 4_000))
        seal(acq, day1.plusDays(1))
        val preview = requireNotNull(service().targets(fileId, listOf(1, 2, 9)))
        Then("eligible items carry the expected net, and an unknown seq is TARGET_NOT_FOUND; the composite UNIQUE (file, seq, new generation) exists (n-4)") {
            preview.items.map { listOf(it.seq, it.eligible, it.refusal, it.cancelledAmountMinor, it.expectedNetMinor) } shouldBe listOf(
                listOf(1, true, null, 4_000L, 6_000L),
                listOf(2, true, null, 0L, 2_000L),
                listOf(9, false, "TARGET_NOT_FOUND", null, null),
            )
            service().targets("ACQ.$acq.TEST.20261101.001", listOf(1)) shouldBe null
            count(
                "SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_schema = DATABASE() AND table_name = 'acquisition_reacquisition_approval' " +
                    "AND constraint_name IN ('uk_acquisition_reacquisition_approval_record', 'fk_acquisition_reacquisition_approval_decision')",
            ) shouldBe 2
        }
    }

    Given("RX-10 (rev 0.3 — m-1) the execution deadline passes while the execution waits for its locks") {
        val (acq, fileId, _) = rejectedFile(3_500)
        val cmd = command(fileId, 1)
        val clock = FixedClock(executeAt) // 잠금 전: 기한 안
        val late = cmd.executeBefore.plusMillis(1)
        // 시험용 Port 래퍼: lane 잠금을 얻은 순간 시계가 기한을 넘는다(잠금 대기 동안 시간이 흐른 것과 같다).
        val d = service(clock = clock, laneStore = lanesWith { clock.now = late }).execute(cmd).shouldBeInstanceOf<ReacquisitionExecution.Decided>().decision
        Then("the deadline is judged with the time read after the locks: REJECTED(APPROVAL_EXECUTION_EXPIRED) decided at that time, nothing sealed") {
            // RED(rev 0.2 코드): 잠금 전에 읽은 시각으로 판정해 EXECUTED였다.
            (d.outcome to d.rejectionCode) shouldBe (AcquisitionOpsOutcome.REJECTED to "APPROVAL_EXECUTION_EXPIRED")
            d.decidedAt shouldBe late
            jdbc.queryForObject("SELECT decided_at FROM acquisition_ops_decision WHERE approval_id = ?", java.sql.Timestamp::class.java, cmd.approvalId)!!.toInstant() shouldBe late
            count("SELECT COUNT(*) FROM acquisition_file WHERE acquirer_code = ?", acq) shouldBe 1
        }
    }

    Given("RX-11 (rev 0.3 — m-2) two transactions reach the decision INSERT together (barrier inside the decision Port — real UNIQUE(approval_id) 1062 → retry)") {
        /** 시험용 Port 래퍼: 처음 [parties]번의 결정 INSERT를 모두 모아 동시에 보낸다(INSERT 직전 barrier). 재시도 경로는 INSERT 전에 커밋된 결정을 읽는다. */
        class OverlappingDecisions(private val delegate: AcquisitionOpsDecisionStore, private val parties: Int) : AcquisitionOpsDecisionStore by delegate {
            val barrier = CyclicBarrier(parties)
            val appends = AtomicInteger()
            override fun append(decision: AcquisitionOpsDecision) {
                if (appends.incrementAndGet() <= parties) barrier.await(20, TimeUnit.SECONDS)
                delegate.append(decision)
            }
        }
        val pool = Executors.newFixedThreadPool(2)
        fun race(store: OverlappingDecisions, vararg commands: ReacquisitionCommand): List<ReacquisitionExecution> =
            commands.map { c -> pool.submit<ReacquisitionExecution> { service(decisionStore = store).execute(c) } }.map { it.get(90, TimeUnit.SECONDS) }

        // (a) 같은 승인·같은 내용, 원 파일 없음 → 파일 행 잠금이 없어 두 TX가 결정 INSERT까지 함께 간다.
        val missing = command("ACQ.NOLANE11.TEST.20261103.001", 1)
        val sameStore = OverlappingDecisions(decisions, 2)
        val same = race(sameStore, missing, missing)
        // (b) 같은 승인 ID·다른 내용(다른 원 파일 — 다른 lane): 둘 다 새 파일을 봉인한 뒤 결정 INSERT에서 만난다.
        val (acqA, fileA, _) = rejectedFile(2_100)
        val (acqB, fileB, _) = rejectedFile(2_200)
        val sharedId = "apr_" + UUID.randomUUID().toString().replace("-", "").take(26)
        val diffStore = OverlappingDecisions(decisions, 2)
        val different = race(diffStore, command(fileA, 1, approvalId = sharedId), command(fileB, 1, approvalId = sharedId))
        pool.shutdown()

        Then("(a) both INSERTs ran, one hit 1062, rolled back and its retry replayed the committed decision — one decision row") {
            sameStore.appends.get() shouldBe 2
            same.map { it.shouldBeInstanceOf<ReacquisitionExecution.Decided>().let { d -> d.replayed to d.decision.rejectionCode } } shouldContainExactlyInAnyOrder
                listOf(false to "TARGET_NOT_FOUND", true to "TARGET_NOT_FOUND")
            count("SELECT COUNT(*) FROM acquisition_ops_decision WHERE approval_id = ?", missing.approvalId) shouldBe 1
        }
        And("(b) the loser's whole TX (its sealed new version included) rolled back on 1062 and the retry is a CONFLICT — no effect without a decision") {
            diffStore.appends.get() shouldBe 2
            different.count { it == ReacquisitionExecution.Conflict } shouldBe 1
            val winner = different.filterIsInstance<ReacquisitionExecution.Decided>().single()
            (winner.decision.outcome to winner.replayed) shouldBe (AcquisitionOpsOutcome.EXECUTED to false)
            count("SELECT COUNT(*) FROM acquisition_ops_decision WHERE approval_id = ?", sharedId) shouldBe 1
            // 새 버전 파일은 이긴 쪽 lane에만 있다(진 쪽 봉인은 결정과 함께 rollback).
            listOf(acqA, acqB).sumOf { count("SELECT COUNT(*) FROM acquisition_file WHERE acquirer_code = ? AND file_version = 2", it) } shouldBe 1
            listOf(fileA, fileB).sumOf { count("SELECT COUNT(*) FROM acquisition_file_record WHERE reacquisition_of = ?", "$it#1") } shouldBe 1
            count("SELECT COUNT(*) FROM acquisition_file WHERE file_id = ? AND original_file_id = ?", winner.decision.resultFileId!!.value, winner.decision.targetRef) shouldBe 1
        }
    }

    Given("RX-12 (rev 0.3 — m-3) re-acquisition vs the next regular seal of the same lane, both lock orders forced (I-5)") {
        // 두 경우 모두: 원 SALE 거절 뒤 취소가 도착했고 아직 판정 전(다음 봉인이 "취소 매입 불요"로 판정한다).
        fun scenario(amount: Long): Triple<String, String, AuthorizedPaymentFact> {
            val (acq, fileId, auths) = rejectedFile(amount)
            receive(PaymentFactFixture.cancelOf(auths[0], 1_000))
            return Triple(acq, fileId, auths[0])
        }
        val pool = Executors.newFixedThreadPool(2)
        val hold = Duration.ofMillis(800)

        // (1) 재매입이 먼저: 원 파일 행 + lane을 잡고 머문다 → 봉인은 lane에서 기다린다.
        val (acq1, file1, _) = scenario(9_000)
        val held1 = CountDownLatch(1)
        val released1 = AtomicLong()
        val sealLocked1 = AtomicLong()
        val reacq1 = pool.submit<ReacquisitionExecution> {
            service(laneStore = lanesWith { held1.countDown(); Thread.sleep(hold.toMillis()); released1.set(System.nanoTime()) }).execute(command(file1, 1))
        }
        held1.await(30, TimeUnit.SECONDS) shouldBe true
        val seal1 = pool.submit<SealAcquisitionFileResult> { seal(acq1, day1.plusDays(1), lanesWith { sealLocked1.set(System.nanoTime()) }) }
        val first1 = reacq1.get(60, TimeUnit.SECONDS)
        val sealed1 = seal1.get(60, TimeUnit.SECONDS)

        // (2) 봉인이 먼저: lane + 결제별 행을 잡고 머문다 → 재매입은 원 파일 행을 잡은 뒤 lane에서 기다린다(봉인은 파일 행을 잡지 않는다 — 순환 없음).
        val (acq2, file2, _) = scenario(9_500)
        val held2 = CountDownLatch(1)
        val released2 = AtomicLong()
        val reacqLocked2 = AtomicLong()
        val seal2 = pool.submit<SealAcquisitionFileResult> {
            seal(acq2, day1.plusDays(1), lanesWith { held2.countDown(); Thread.sleep(hold.toMillis()); released2.set(System.nanoTime()) })
        }
        held2.await(30, TimeUnit.SECONDS) shouldBe true
        val reacq2 = pool.submit<ReacquisitionExecution> { service(laneStore = lanesWith { reacqLocked2.set(System.nanoTime()) }).execute(command(file2, 1)) }
        val sealed2 = seal2.get(60, TimeUnit.SECONDS)
        val second2 = reacq2.get(60, TimeUnit.SECONDS)
        pool.shutdown()

        Then("(1) the seal waited for the re-acquisition's lane lock; the re-acquisition saw the cancellation undecided and refused; the seal then closed it as 'not required'") {
            (sealLocked1.get() > released1.get()) shouldBe true
            first1.shouldBeInstanceOf<ReacquisitionExecution.Decided>().decision.rejectionCode shouldBe "CANCELLATION_UNDECIDED"
            sealed1.shouldBeInstanceOf<SealAcquisitionFileResult.Sealed>().cancelNotRequired shouldBe 1
            // 다음 승인은 판정 끝난 취소를 한 번 반영한다.
            service().execute(command(file1, 1)).shouldBeInstanceOf<ReacquisitionExecution.Decided>().decision.outcome shouldBe AcquisitionOpsOutcome.EXECUTED
            jdbc.queryForMap("SELECT netted_cancel_amount_minor, record_amount_minor FROM acquisition_file_record WHERE reacquisition_of = ?", "$file1#1").values.toList() shouldBe
                listOf(1_000L, 8_000L)
        }
        And("(2) the re-acquisition waited for the seal's lane lock (no deadlock); it then saw the decided cancellation and executed at the net amount") {
            (reacqLocked2.get() > released2.get()) shouldBe true
            sealed2.shouldBeInstanceOf<SealAcquisitionFileResult.Sealed>().cancelNotRequired shouldBe 1
            second2.shouldBeInstanceOf<ReacquisitionExecution.Decided>().decision.outcome shouldBe AcquisitionOpsOutcome.EXECUTED
            jdbc.queryForMap("SELECT netted_cancel_amount_minor, record_amount_minor FROM acquisition_file_record WHERE reacquisition_of = ?", "$file2#1").values.toList() shouldBe
                listOf(1_000L, 8_500L)
            count("SELECT COUNT(*) FROM acquisition_file WHERE acquirer_code = ? AND business_date = ? AND file_version = 2", acq2, day1) shouldBe 1
        }
    }

    Given("RX-13 (rev 0.3 — m-5) a cancellation already netted in the original file plus one closed as 'not required' afterwards") {
        val acq = acquirers.next()
        val auth = authorize(acq, 10_000, 1)
        receive(PaymentFactFixture.cancelOf(auth, 2_000)) // 봉인 전 취소 → 원 파일에서 상계(원 레코드 상계액 2,000)
        val fileId = (seal(acq, day1) as SealAcquisitionFileResult.Sealed).fileId
        transmit(fileId)
        result(acq, fileId, 1, listOf(1 to LineResult.REJECTED))
        receive(PaymentFactFixture.cancelOf(auth, 1_000, returnSequence = 2, cumulativeMinor = 3_000))
        seal(acq, day1.plusDays(1)) // 원 SALE 거절 → "취소 매입 불요"
        val before = integrity()
        val d = service().execute(command(fileId, 1)).shouldBeInstanceOf<ReacquisitionExecution.Decided>().decision
        Then("the net counts each cancellation once (10,000 − 2,000 − 1,000 = 7,000, netted 3,000), and both integrity checks stay unchanged") {
            jdbc.queryForMap("SELECT netted_cancel_amount_minor, record_amount_minor FROM acquisition_file_record WHERE file_id = ? AND seq = 1", fileId).values.toList() shouldBe
                listOf(2_000L, 8_000L)
            d.outcome shouldBe AcquisitionOpsOutcome.EXECUTED
            jdbc.queryForMap("SELECT netted_cancel_amount_minor, record_amount_minor FROM acquisition_file_record WHERE reacquisition_of = ?", "$fileId#1").values.toList() shouldBe
                listOf(3_000L, 7_000L)
            integrity() shouldBe before
        }
    }

    Given("RX-14 (rev 0.3 — MJ-1) the decided-cancellation records disagree with the payment facts at execution (injected corruption — the in-TX check, I-17)") {
        val (acq, fileId, auths) = rejectedFile(7_000)
        // 손상 주입(의도된 Invalid 상태 — 커밋): 결제 사실에 없는 취소의 "취소 매입 불요" 행. 결제 사실 기준 순액은 7,000(상계 0)이지만 판정 기록 합은 500이다.
        jdbc.update(
            "INSERT INTO acquisition_cancel_not_required (cancellation_transaction_id, original_transaction_id, original_file_id, original_seq, amount_minor, currency, " +
                "business_date, decided_at) VALUES (?, ?, ?, 1, 500, 'KRW', ?, ?)",
            "ptx_injected_${UUID.randomUUID()}", auths[0].transactionId, fileId, day1.plusDays(1), java.sql.Timestamp.from(executeAt),
        )
        val cmd = command(fileId, 1)
        Then("the execution TX fails closed and rolls back entirely: no new version, no record, no decision, no approval") {
            val counter = registry.find("paycore.financial.acquiring.reacquisition.integrity_rejected").functionCounter()
            val before = counter?.count() ?: Double.NaN
            shouldThrow<IllegalStateException> { service().execute(cmd) }
            // B2(B1-F 리뷰 n-6): rollback돼 DB gauge에는 남지 않는 거절을 label 없는 counter가 한 번 센다(재시도하지 않는 실패 — 정확히 +1).
            requireNotNull(counter) { "integrity_rejected counter is registered" }.id.tags shouldBe emptyList()
            (counter.count() - before) shouldBe 1.0
            count("SELECT COUNT(*) FROM acquisition_file WHERE acquirer_code = ?", acq) shouldBe 1
            count("SELECT COUNT(*) FROM acquisition_file_record WHERE reacquisition_of = ?", "$fileId#1") shouldBe 0
            count("SELECT COUNT(*) FROM acquisition_ops_decision WHERE approval_id = ?", cmd.approvalId) shouldBe 0
            count("SELECT COUNT(*) FROM acquisition_reacquisition_approval WHERE file_id = ?", fileId) shouldBe 0
        }
    }
})

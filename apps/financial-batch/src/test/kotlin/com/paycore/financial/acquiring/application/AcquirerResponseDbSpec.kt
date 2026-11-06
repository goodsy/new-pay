package com.paycore.financial.acquiring.application

import com.paycore.financial.FinancialDbAccounts
import com.paycore.financial.FinancialMySqlContainerConfig
import com.paycore.financial.acquiring.adapter.integration.TestAcquirerSftpConfig
import com.paycore.financial.acquiring.adapter.integration.TestAcquirerSftpServer
import com.paycore.financial.acquiring.application.provided.AcquisitionSealRefused
import com.paycore.financial.acquiring.application.provided.CloseRunResult
import com.paycore.financial.acquiring.application.provided.ReadAcquisitionBacklogUseCase
import com.paycore.financial.acquiring.application.provided.ResponseRunResult
import com.paycore.financial.acquiring.application.provided.SealAcquisitionFileResult
import com.paycore.financial.acquiring.application.provided.TransmissionResult.Kind
import com.paycore.financial.acquiring.application.required.AcquirerChannelFailure
import com.paycore.financial.acquiring.application.required.AcquirerFileChannel
import com.paycore.financial.acquiring.application.required.AcquirerFileSession
import com.paycore.financial.acquiring.application.required.AcquiringClock
import com.paycore.financial.acquiring.application.required.AcquisitionFileRepository
import com.paycore.financial.acquiring.application.required.AcquisitionLaneStore
import com.paycore.financial.acquiring.application.required.AcquisitionMembershipStore
import com.paycore.financial.acquiring.application.required.AcquisitionPolicySource
import com.paycore.financial.acquiring.application.required.AcquisitionRecordResultStore
import com.paycore.financial.acquiring.application.required.AcquisitionResponseStore
import com.paycore.financial.acquiring.application.required.AcquisitionResultPolicy
import com.paycore.financial.acquiring.application.required.AcquisitionTransmissionStore
import com.paycore.financial.acquiring.application.required.AcquisitionTransportPolicy
import com.paycore.financial.acquiring.application.required.RecordRef
import com.paycore.financial.acquiring.domain.AckOutcome
import com.paycore.financial.acquiring.domain.AcquirerAck
import com.paycore.financial.acquiring.domain.AcquirerResponseFormat
import com.paycore.financial.acquiring.domain.AcquirerResultFile
import com.paycore.financial.acquiring.domain.AcquirerResultLine
import com.paycore.financial.acquiring.domain.AcquisitionFileId
import com.paycore.financial.acquiring.domain.AcquisitionRecordType
import com.paycore.financial.acquiring.domain.LineResult
import com.paycore.financial.acquiring.domain.RecordResultEntry
import com.paycore.financial.codeOf
import com.paycore.financial.paymentfact.adapter.web.PaymentFactEnvelopeFixture
import com.paycore.financial.paymentfact.application.PaymentFactInboxService
import com.paycore.financial.paymentfact.application.provided.PaymentFactAcquisitionSource
import com.paycore.financial.paymentfact.domain.AuthorizedPaymentFact
import com.paycore.financial.paymentfact.domain.PaymentFactFixture
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.mysql.MySQLContainer
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 기관 ACK·결과 수신·결과 마감·CANCEL 연결 실제 시험(ACQUIRING-001 2-F — AQ-R1..R12, AQ-X1..X3, AQ-I1..I2). 실제 MySQL(runtime 계정) + 실제 SFTP(시험 SSHD — outbox 파일은
 * 시험이 호스트 쪽에 직접 둔다, Simulator 코드 공유 없음 — I-27). 봉인·전송·수신·마감은 운영 Bean Port로 만든 서비스를 고정 시계로 부른다. 응답 바이트는 운영 렌더러로 만든다
 * (렌더러 = golden 바이트는 AcquirerResponseGoldenSpec이 고정). 시험마다 매입사 코드(lane)가 다르다(RESPONSE_ACQUIRERS — 전송 시험 lane과 겹치지 않음).
 */
@SpringBootTest
@Import(FinancialMySqlContainerConfig::class, TestAcquirerSftpConfig::class)
class AcquirerResponseDbSpec(
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
    private val board: AcquisitionWaitingBoard,
    private val channel: AcquirerFileChannel,
    private val transport: AcquisitionTransportPolicy,
    private val backlog: ReadAcquisitionBacklogUseCase,
    private val server: TestAcquirerSftpServer,
    private val jdbc: JdbcTemplate,
    private val container: MySQLContainer,
) : BehaviorSpec({

    val day = LocalDate.parse("2026-11-05")
    val afterCutoff = Instant.parse("2026-11-05T14:05:00Z")
    val receivedAt = Instant.parse("2026-11-05T15:00:00Z")

    class FixedClock(@Volatile var now: Instant) : AcquiringClock {
        override fun now(): Instant = now
    }

    val acquirers = TestAcquirerSftpConfig.RESPONSE_ACQUIRERS.drop(10).iterator()
    val tx = TransactionTemplate(transactionManager)

    fun authorize(acq: String, amount: Long, approvedAt: Instant = Instant.parse("2026-11-05T01:02:03.456Z")): AuthorizedPaymentFact =
        PaymentFactFixture.authorized(amountMinor = amount, acquirerCode = acq, approvedAt = approvedAt).also {
            inbox.receive(PaymentFactEnvelopeFixture.inbound(it, UUID.randomUUID().toString()))
        }

    fun cancel(original: AuthorizedPaymentFact, amount: Long) =
        PaymentFactFixture.cancelOf(original, amount).also { inbox.receive(PaymentFactEnvelopeFixture.inbound(it, UUID.randomUUID().toString())) }

    fun sealService(at: Instant, recordResults: AcquisitionRecordResultStore = results) =
        AcquisitionSealService(transactionManager, lanes, files, membership, source, policies, FixedClock(at), recordResults, board)

    fun seal(acq: String, date: LocalDate = day): SealAcquisitionFileResult =
        sealService(date.atTime(14, 5).atZone(java.time.ZoneOffset.UTC).toInstant()).seal(acq, "TEST", date)

    fun transmit(fileId: String) =
        AcquisitionTransmissionService(transactionManager, files, attempts, channel, transport, FixedClock(afterCutoff.plusSeconds(60))).transmit(fileId)

    fun receiver(at: Instant = receivedAt, via: AcquirerFileChannel = channel, resultStore: AcquisitionRecordResultStore = results) =
        AcquirerResponseService(transactionManager, files, responses, resultStore, via, FixedClock(at), membership)

    fun closer(at: Instant, deadline: Duration? = Duration.ofHours(1)) = AcquisitionResultCloseService(
        transactionManager, files, results, object : AcquisitionResultPolicy {
            override val resultDeadline: Duration? = deadline
        }, FixedClock(at),
    )

    fun sealed(fileId: String): Pair<String, Long> =
        jdbc.queryForMap("SELECT content_sha256, content_bytes FROM acquisition_file WHERE file_id = ?", fileId).let { it["content_sha256"] as String to (it["content_bytes"] as Number).toLong() }

    /** 레코드: 순번 → (거래 ID, 종류, 레코드 금액). */
    fun recordsOf(fileId: String): Map<Int, Triple<String, AcquisitionRecordType, Long>> = jdbc.query(
        "SELECT seq, transaction_id, record_type, record_amount_minor FROM acquisition_file_record WHERE file_id = ? ORDER BY seq", { rs, _ ->
            rs.getInt(1) to Triple(rs.getString(2), AcquisitionRecordType.valueOf(rs.getString(3)), rs.getLong(4))
        }, fileId,
    ).toMap()

    fun seqOf(fileId: String, txn: String) = recordsOf(fileId).entries.single { it.value.first == txn }.key

    fun outbox(name: String, bytes: ByteArray) {
        Files.write(server.outbox.resolve(name), bytes)
    }

    fun ack(fileId: String, outcome: AckOutcome, seq: Int = 1, reason: String? = null, sha: String? = null): String {
        val (s, b) = sealed(fileId)
        val id = AcquisitionFileId.parse(fileId)
        val a = AcquirerAck(id, seq, outcome, reason, sha ?: s, b, if (outcome == AckOutcome.RECEIVED) recordsOf(fileId).size else null, receivedAt.minusSeconds(30))
        return AcquirerResponseFormat.ackFileName(id, seq).also { outbox(it, AcquirerResponseFormat.renderAck(a)) }
    }

    /** 결과 파일: [lines] = (순번, 결과) — 거래 ID·종류·금액은 봉인 레코드 값. */
    fun result(fileId: String, seq: Int, lines: List<Pair<Int, LineResult>>, sha: String? = null, reason: String = "AMOUNT_MISMATCH"): String {
        val id = AcquisitionFileId.parse(fileId)
        val records = recordsOf(fileId)
        val r = AcquirerResultFile(
            id, sha ?: sealed(fileId).first, seq, receivedAt.minusSeconds(10),
            lines.map { (n, res) ->
                val (txn, type, amount) = records.getValue(n)
                AcquirerResultLine(n, txn, type, res, if (res == LineResult.REJECTED) reason else null, amount)
            },
        )
        return AcquirerResponseFormat.resultFileName(id, seq).also { outbox(it, AcquirerResponseFormat.renderResult(r)) }
    }

    fun axes(fileId: String): List<String> =
        jdbc.queryForMap("SELECT transmission_state, receipt_state, result_state FROM acquisition_file WHERE file_id = ?", fileId).values.map { it as String }

    fun entries(fileId: String): Map<Int, List<RecordResultEntry>> = tx.execute { results.entries(AcquisitionFileId.parse(fileId)) }

    /** 결과 행 요약: 순번 → [결과(관찰 결과)…]. */
    fun resultSummary(fileId: String): Map<Int, List<String>> =
        entries(fileId).mapValues { (_, list) -> list.map { e -> e.result.name + (e.observedResult?.let { "($it)" } ?: "") + (e.reasonCode?.let { ":$it" } ?: "") } }

    /** 응답 기록 요약: `{ACK|RES}.{nn} 처분 [격리 사유] [근거]`(id 순). */
    fun responseRows(acq: String): List<String> = jdbc.query(
        "SELECT file_name, disposition, quarantine_reason, evidence FROM acquisition_response WHERE acquirer_code = ? ORDER BY id", { rs, _ ->
            val parts = rs.getString(1).split('.')
            listOfNotNull("${parts.first()}.${parts[parts.size - 2]}", rs.getString(2), rs.getString(3), rs.getString(4)).joinToString(" ")
        }, acq,
    )

    fun count(sql: String, vararg args: Any): Int = jdbc.queryForObject(sql, Int::class.java, *args)!!

    fun sealAndTransmit(acq: String, vararg amounts: Long): String {
        amounts.forEach { authorize(acq, it) }
        val fileId = seal(acq).fileId
        transmit(fileId).outcome shouldBe Kind.TRANSMITTED
        return fileId
    }

    Given("AQ-R1 a transmitted file, a RECEIVED ack and a result file with one ACCEPTED and one REJECTED record") {
        val acq = acquirers.next()
        val fileId = sealAndTransmit(acq, 10_000, 20_000)
        val first = recordsOf(fileId).keys.first()
        val second = recordsOf(fileId).keys.last()
        ack(fileId, AckOutcome.RECEIVED)
        result(fileId, 1, listOf(first to LineResult.ACCEPTED, second to LineResult.REJECTED))
        val run = receiver().receive(acq, "TEST")

        Then("receipt RECEIVED, the result axis COMPLETE, one ACCEPTED (recognized at the receipt time — D-ACQ-010) and one REJECTED entry, both responses recorded") {
            listOf(run.outcome, run.applied, run.quarantined, run.recordsDecided, run.errors) shouldBe listOf(ResponseRunResult.Outcome.COMPLETED, 2, 0, 2, 0)
            axes(fileId) shouldBe listOf("TRANSMITTED", "RECEIVED", "COMPLETE")
            resultSummary(fileId) shouldBe mapOf(first to listOf("ACCEPTED"), second to listOf("REJECTED:AMOUNT_MISMATCH"))
            entries(fileId).getValue(first).single().recognizedAt shouldBe receivedAt
            responseRows(acq) shouldBe listOf("ACK.01 APPLIED ACK_HASH_MATCH", "RES.01 APPLIED RESULT_HASH_MATCH")
            // 받은 바이트를 보존한다(감사·재현 — 내용 hash와 크기가 기록과 같다).
            count("SELECT COUNT(*) FROM acquisition_response WHERE acquirer_code = ? AND content_bytes = LENGTH(content) AND content_sha256 = SHA2(content, 256)", acq) shouldBe 2
        }

        And("reading the outbox again records nothing new (processed by name and hash — idempotent)") {
            val again = receiver(receivedAt.plusSeconds(60)).receive(acq, "TEST")
            listOf(again.applied, again.quarantined, again.recordsDecided) shouldBe listOf(0, 0, 0)
            count("SELECT COUNT(*) FROM acquisition_response WHERE acquirer_code = ?", acq) shouldBe 2
            count("SELECT COUNT(*) FROM acquisition_record_result WHERE file_id = ?", fileId) shouldBe 2
        }
    }

    Given("AQ-R2 the ack is lost and the transmission outcome is unknown, then only the result file arrives (I-8 RESULT_IMPLIED, I-9 hash evidence)") {
        val acq = acquirers.next()
        authorize(acq, 10_000)
        val fileId = seal(acq).fileId
        server.dropRenameResponses.set(1)
        server.rejectLoginsAfterDroppedRename.set(1)
        transmit(fileId).outcome shouldBe Kind.OUTCOME_UNKNOWN
        result(fileId, 1, listOf(1 to LineResult.ACCEPTED))
        val run = receiver().receive(acq, "TEST")

        Then("the result file is the receipt evidence and its matching hash makes the file TRANSMITTED; the record is ACCEPTED") {
            run.applied shouldBe 1
            axes(fileId) shouldBe listOf("TRANSMITTED", "RECEIVED", "COMPLETE")
            jdbc.queryForObject("SELECT transmitted_at IS NOT NULL FROM acquisition_file WHERE file_id = ?", Boolean::class.java, fileId) shouldBe true
            resultSummary(fileId) shouldBe mapOf(1 to listOf("ACCEPTED"))
        }
    }

    Given("AQ-R3 an ack whose received hash differs from the sealed hash (same id, other content — I-8)") {
        val acq = acquirers.next()
        val fileId = sealAndTransmit(acq, 10_000)
        ack(fileId, AckOutcome.RECEIVED, sha = "0".repeat(64))
        val run = receiver().receive(acq, "TEST")
        result(fileId, 1, listOf(1 to LineResult.ACCEPTED))
        val later = receiver(receivedAt.plusSeconds(60)).receive(acq, "TEST")

        Then("it is quarantined (RECEIPT_CONFLICT), no record result is written, and a later valid result stays quarantined (AFTER_CONFLICT — no automatic apply)") {
            listOf(run.quarantined, later.quarantined, later.applied) shouldBe listOf(1, 1, 0)
            axes(fileId) shouldBe listOf("TRANSMITTED", "RECEIPT_CONFLICT", "AWAITING")
            responseRows(acq) shouldBe listOf("ACK.01 QUARANTINED HASH_MISMATCH", "RES.01 QUARANTINED AFTER_CONFLICT")
            count("SELECT COUNT(*) FROM acquisition_record_result WHERE file_id = ?", fileId) shouldBe 0
        }
    }

    Given("AQ-R4 the acquirer rejects the whole file (ACK FILE_REJECTED — D-ACQ-004, I-7)") {
        val acq = acquirers.next()
        val fileId = sealAndTransmit(acq, 10_000, 20_000)
        ack(fileId, AckOutcome.FILE_REJECTED, reason = "SCENARIO_FILE_REJECTED")
        receiver().receive(acq, "TEST")

        Then("every record is closed as REJECTED from the file rejection, the result axis is COMPLETE and receipt FILE_REJECTED (re-acquisition only by two-person approval)") {
            axes(fileId) shouldBe listOf("TRANSMITTED", "FILE_REJECTED", "COMPLETE")
            resultSummary(fileId).values.toSet() shouldBe setOf(listOf("REJECTED:SCENARIO_FILE_REJECTED"))
            count("SELECT COUNT(*) FROM acquisition_record_result WHERE file_id = ? AND source = 'FILE_REJECTED'", fileId) shouldBe 2
        }
    }

    Given("AQ-R5 duplicate and conflicting result files for the same record (Simulator DUPLICATE_RESULT / CONFLICTING_RESULT — D-ACQ-010)") {
        val acq = acquirers.next()
        val fileId = sealAndTransmit(acq, 10_000)
        result(fileId, 1, listOf(1 to LineResult.ACCEPTED))
        result(fileId, 2, listOf(1 to LineResult.ACCEPTED))
        result(fileId, 3, listOf(1 to LineResult.REJECTED))
        val run = receiver().receive(acq, "TEST")

        Then("the same result again is a duplicate (no entry), the different result appends a CONFLICT entry (quarantine) and the ACCEPTED entry stays") {
            listOf(run.applied, run.recordsDecided, run.duplicates, run.conflicts) shouldBe listOf(3, 1, 1, 1)
            resultSummary(fileId) shouldBe mapOf(1 to listOf("ACCEPTED", "CONFLICT(REJECTED):AMOUNT_MISMATCH"))
        }
    }

    Given("AQ-R6 no result by the result deadline, then the late result arrives (D-ACQ-004 UNKNOWN, D-ACQ-010)") {
        val acq = acquirers.next()
        val fileId = sealAndTransmit(acq, 10_000, 20_000)
        val transmittedAt = afterCutoff.plusSeconds(60)
        val early = closer(transmittedAt.plus(Duration.ofMinutes(59))).closeExpired(acq, "TEST", 10)
        val unconfigured = closer(transmittedAt.plus(Duration.ofHours(2)), deadline = null).closeExpired(acq, "TEST", 10)
        val closed = closer(transmittedAt.plus(Duration.ofHours(2))).closeExpired(acq, "TEST", 10)
        val again = closer(transmittedAt.plus(Duration.ofHours(3))).closeExpired(acq, "TEST", 10)

        Then("nothing before the deadline or without a configured deadline (fail-closed); after it both records are UNKNOWN once and the file DEADLINE_PASSED; no resend") {
            listOf(early.files, unconfigured.outcome, closed.files, closed.unknownRecords, again.files) shouldBe
                listOf(0, CloseRunResult.Outcome.NOT_CONFIGURED, 1, 2, 0)
            axes(fileId) shouldBe listOf("TRANSMITTED", "NONE", "DEADLINE_PASSED")
            resultSummary(fileId).values.toSet() shouldBe setOf(listOf("UNKNOWN"))
            count("SELECT COUNT(*) FROM acquisition_transmission_attempt WHERE file_id = ?", fileId) shouldBe 1
        }

        And("the late result is applied automatically, the UNKNOWN entries are preserved, ACCEPTED is recognized at its receipt time and the file becomes COMPLETE") {
            val lateAt = receivedAt.plus(Duration.ofHours(5))
            result(fileId, 1, recordsOf(fileId).keys.map { it to LineResult.ACCEPTED })
            receiver(lateAt).receive(acq, "TEST").recordsDecided shouldBe 2
            resultSummary(fileId).values.toSet() shouldBe setOf(listOf("UNKNOWN", "ACCEPTED"))
            entries(fileId).values.map { it.last().recognizedAt }.toSet() shouldBe setOf(lateAt)
            axes(fileId) shouldBe listOf("TRANSMITTED", "RECEIVED", "COMPLETE")
        }
    }

    Given("AQ-R7 a partially downloaded / damaged result file, then the same name with other bytes") {
        val acq = acquirers.next()
        val fileId = sealAndTransmit(acq, 10_000)
        val name = result(fileId, 1, listOf(1 to LineResult.ACCEPTED))
        val full = Files.readAllBytes(server.outbox.resolve(name))
        outbox(name, full.copyOf(full.size - 7))
        val damaged = receiver().receive(acq, "TEST")
        outbox(name, full)
        val reused = receiver(receivedAt.plusSeconds(60)).receive(acq, "TEST")

        Then("the damaged bytes are quarantined (FORMAT_INVALID — trailer hash), the different bytes under the same name are quarantined too (NAME_REUSED), nothing applied") {
            listOf(damaged.quarantined, reused.quarantined) shouldBe listOf(1, 1)
            responseRows(acq) shouldBe listOf("RES.01 QUARANTINED FORMAT_INVALID", "RES.01 QUARANTINED NAME_REUSED")
            count("SELECT COUNT(*) FROM acquisition_record_result WHERE file_id = ?", fileId) shouldBe 0
            axes(fileId) shouldBe listOf("TRANSMITTED", "NONE", "AWAITING")
        }
    }

    Given("AQ-R8 responses for a file FINANCIAL never sealed, and dot temp names being published") {
        val acq = acquirers.next()
        val ghost = AcquisitionFileId(acq, com.paycore.financial.acquiring.domain.AcquisitionEnvironment.TEST, day, 1)
        val a = AcquirerAck(ghost, 1, AckOutcome.RECEIVED, null, "a".repeat(64), 10, 0, receivedAt)
        outbox(AcquirerResponseFormat.ackFileName(ghost, 1), AcquirerResponseFormat.renderAck(a))
        outbox("." + AcquirerResponseFormat.ackFileName(ghost, 2) + ".tmp", AcquirerResponseFormat.renderAck(a.copy(ackSeq = 2)))
        val run = receiver().receive(acq, "TEST")

        Then("the unknown file's ack is quarantined without a file reference (UNKNOWN_FILE) and the temp name is ignored") {
            listOf(run.quarantined, run.applied) shouldBe listOf(1, 0)
            count("SELECT COUNT(*) FROM acquisition_response WHERE acquirer_code = ? AND file_id IS NULL AND quarantine_reason = 'UNKNOWN_FILE'", acq) shouldBe 1
            count("SELECT COUNT(*) FROM acquisition_response WHERE acquirer_code = ?", acq) shouldBe 1
        }
    }

    Given("AQ-R9 responses arrive while a transmission attempt still holds the file (TRANSMITTING)") {
        val acq = acquirers.next()
        val fileId = sealAndTransmit(acq, 10_000)
        // root(관찰·설치 전용)로 진행 중 시도를 흉내 낸다 — runtime 계정은 이 상태를 임의로 만들 경로가 없다.
        FinancialDbAccounts.root(container).use { c ->
            c.codeOf("UPDATE acquisition_file SET transmission_state = 'TRANSMITTING', transmitted_at = NULL, transmission_lease_until = '2026-11-05 16:00:00.000' WHERE file_id = '$fileId'") shouldBe null
        }
        val version = count("SELECT version FROM acquisition_file WHERE file_id = ?", fileId)
        result(fileId, 1, listOf(1 to LineResult.ACCEPTED))
        val deferred = receiver().receive(acq, "TEST")

        Then("they are deferred: nothing recorded and the file version unchanged (the attempt's expected version is not broken)") {
            listOf(deferred.deferred, deferred.applied) shouldBe listOf(1, 0)
            count("SELECT COUNT(*) FROM acquisition_response WHERE acquirer_code = ?", acq) shouldBe 0
            count("SELECT version FROM acquisition_file WHERE file_id = ?", fileId) shouldBe version
        }

        And("after the attempt finishes the same outbox file is applied on the next run") {
            FinancialDbAccounts.root(container).use { c ->
                c.codeOf("UPDATE acquisition_file SET transmission_state = 'TRANSMITTED', transmission_lease_until = NULL, transmitted_at = '2026-11-05 14:06:00.000' WHERE file_id = '$fileId'") shouldBe null
            }
            receiver(receivedAt.plusSeconds(60)).receive(acq, "TEST").applied shouldBe 1
            resultSummary(fileId) shouldBe mapOf(1 to listOf("ACCEPTED"))
        }
    }

    Given("AQ-R10 two instances receive the same outbox at the same time (real DB race)") {
        val acq = acquirers.next()
        val fileId = sealAndTransmit(acq, 10_000, 20_000)
        ack(fileId, AckOutcome.RECEIVED)
        result(fileId, 1, recordsOf(fileId).keys.map { it to LineResult.ACCEPTED })
        val barrier = CyclicBarrier(2)
        // 두 인스턴스가 목록을 다 읽은 뒤 동시에 반영을 시작하게 한다.
        val racing = object : AcquirerFileChannel by channel {
            override fun open(acquirerCode: String): AcquirerFileSession {
                val s = channel.open(acquirerCode)
                return object : AcquirerFileSession by s {
                    override fun close() {
                        s.close()
                        barrier.await(30, TimeUnit.SECONDS)
                    }
                }
            }
        }
        val pool = Executors.newFixedThreadPool(2)
        val runs = (1..2).map { pool.submit<ResponseRunResult> { receiver(via = racing).receive(acq, "TEST") } }.map { it.get(90, TimeUnit.SECONDS) }
        pool.shutdown()

        Then("each response is applied exactly once (file row lock + UNIQUE(name, hash)); the other instance sees it as processed; no errors") {
            runs.sumOf { it.applied } shouldBe 2
            runs.sumOf { it.alreadyProcessed } shouldBe 2
            runs.sumOf { it.errors } shouldBe 0
            count("SELECT COUNT(*) FROM acquisition_response WHERE acquirer_code = ?", acq) shouldBe 2
            resultSummary(fileId).values.toSet() shouldBe setOf(listOf("ACCEPTED"))
        }
    }

    Given("AQ-R11 the apply transaction fails after the response row and one result were written (rollback)") {
        val acq = acquirers.next()
        val fileId = sealAndTransmit(acq, 10_000, 20_000)
        result(fileId, 1, recordsOf(fileId).keys.map { it to LineResult.ACCEPTED })
        val appended = AtomicInteger()
        val failing = object : AcquisitionRecordResultStore by results {
            override fun append(record: RecordRef, entry: RecordResultEntry, responseId: Long?) {
                results.append(record, entry, responseId)
                if (appended.incrementAndGet() == 1) throw IllegalStateException("injected failure after the first result")
            }
        }
        val failed = receiver(resultStore = failing).receive(acq, "TEST")
        val countsAfterFailure = listOf(
            count("SELECT COUNT(*) FROM acquisition_response WHERE acquirer_code = ?", acq), count("SELECT COUNT(*) FROM acquisition_record_result WHERE file_id = ?", fileId),
        )
        val retried = receiver(receivedAt.plusSeconds(60)).receive(acq, "TEST")

        Then("nothing of that response survives (response row, result rows, receipt axis roll back together) and the next run applies it once") {
            listOf(failed.errors, failed.applied) shouldBe listOf(1, 0)
            countsAfterFailure shouldBe listOf(0, 0)
            retried.applied shouldBe 1
            axes(fileId) shouldBe listOf("TRANSMITTED", "RECEIVED", "COMPLETE")
            count("SELECT COUNT(*) FROM acquisition_record_result WHERE file_id = ?", fileId) shouldBe 2
        }
    }

    Given("AQ-R12 the outbox read breaks after the first file (connection lost while downloading)") {
        val acq = acquirers.next()
        val fileId = sealAndTransmit(acq, 10_000)
        ack(fileId, AckOutcome.RECEIVED)
        result(fileId, 1, listOf(1 to LineResult.ACCEPTED))
        val reads = AtomicInteger()
        val breaking = object : AcquirerFileChannel by channel {
            override fun open(acquirerCode: String): AcquirerFileSession {
                val s = channel.open(acquirerCode)
                return object : AcquirerFileSession by s {
                    override fun readOutbox(name: String): ByteArray =
                        if (reads.incrementAndGet() == 2) throw AcquirerChannelFailure.Interrupted(null) else s.readOutbox(name)
                }
            }
        }
        val partial = receiver(via = breaking).receive(acq, "TEST")
        val axesAfterPartial = axes(fileId)
        val rest = receiver(receivedAt.plusSeconds(60)).receive(acq, "TEST")

        Then("only the fully read file is applied (no partial bytes), the rest is applied on the next run") {
            listOf(partial.applied, partial.errors, rest.applied) shouldBe listOf(1, 1, 1)
            axesAfterPartial shouldBe listOf("TRANSMITTED", "RECEIVED", "AWAITING")
            axes(fileId) shouldBe listOf("TRANSMITTED", "RECEIVED", "COMPLETE")
        }
    }

    Given("AQ-X1 cancellations after membership (D-ACQ-009 → seal): original ACCEPTED, REJECTED, UNKNOWN, still awaiting") {
        val acq = acquirers.next()
        val a = authorize(acq, 10_000)
        val b = authorize(acq, 20_000)
        val d = authorize(acq, 7_000)
        val day1 = seal(acq).fileId
        transmit(day1)
        result(day1, 1, listOf(seqOf(day1, a.transactionId) to LineResult.ACCEPTED, seqOf(day1, b.transactionId) to LineResult.REJECTED))
        receiver().receive(acq, "TEST")
        closer(receivedAt.plus(Duration.ofHours(3))).closeExpired(acq, "TEST", 10) // d → UNKNOWN
        val c = authorize(acq, 5_000, Instant.parse("2026-11-06T01:00:00.000Z"))
        val cancelA = cancel(a, 3_000)
        val cancelB = cancel(b, 5_000)
        val cancelD = cancel(d, 1_000)
        val day2 = seal(acq, day.plusDays(1))

        Then("day 2 carries the new SALE and one CANCEL (original ACCEPTED); REJECTED → 'not required' record; UNKNOWN → reconcile (not sent, no record)") {
            day2.shouldBeInstanceOf<SealAcquisitionFileResult.Sealed>()
            listOf(day2.recordCount, day2.cancelRecords, day2.cancelNotRequired, day2.cancelReconcile) shouldBe listOf(2, 1, 1, 1)
            val cancelRow = jdbc.queryForMap(
                "SELECT record_type, original_transaction_id, approved_amount_minor, netted_cancel_amount_minor, record_amount_minor, generation FROM acquisition_file_record " +
                    "WHERE transaction_id = ?", cancelA.transactionId,
            )
            cancelRow.values.map { it.toString() } shouldBe listOf("CANCEL", a.transactionId, "10000", "0", "3000", "0")
            jdbc.queryForMap(
                "SELECT original_transaction_id, original_file_id, amount_minor FROM acquisition_cancel_not_required WHERE cancellation_transaction_id = ?", cancelB.transactionId,
            ).values.map { it.toString() } shouldBe listOf(b.transactionId, day1, "5000")
            count("SELECT COUNT(*) FROM acquisition_file_record WHERE transaction_id = ?", cancelD.transactionId) shouldBe 0
            count("SELECT COUNT(*) FROM acquisition_cancel_not_required WHERE cancellation_transaction_id = ?", cancelD.transactionId) shouldBe 0
            recordsOf(day2.fileId).values.map { it.first to it.second } shouldBe listOf(c.transactionId to AcquisitionRecordType.SALE, cancelA.transactionId to AcquisitionRecordType.CANCEL)
        }

        And("a restart re-seal of day 2 is AlreadySealed (stored SALE + CANCEL bytes re-render and netting matches)") {
            seal(acq, day.plusDays(1)).shouldBeInstanceOf<SealAcquisitionFileResult.AlreadySealed>()
        }

        And("the late result turns the UNKNOWN original ACCEPTED: day 3 sends its CANCEL; a cancel of the day 2 SALE waits for day 2's result") {
            result(day1, 2, listOf(seqOf(day1, d.transactionId) to LineResult.ACCEPTED))
            receiver(receivedAt.plus(Duration.ofHours(10))).receive(acq, "TEST")
            transmit(day2.fileId)
            val cancelC = PaymentFactFixture.cancelOf(c, 2_000).copy(cancelledAt = Instant.parse("2026-11-07T02:00:00.000Z"))
            inbox.receive(PaymentFactEnvelopeFixture.inbound(cancelC, UUID.randomUUID().toString()))
            val day3 = seal(acq, day.plusDays(2)) as SealAcquisitionFileResult.Sealed
            listOf(day3.cancelRecords, day3.cancelWaiting) shouldBe listOf(1, mapOf("ORIGINAL_AWAITING_RESULT" to 1))
            recordsOf(day3.fileId).values.map { it.first } shouldBe listOf(cancelD.transactionId)
            result(day2.fileId, 1, recordsOf(day2.fileId).keys.map { it to LineResult.ACCEPTED })
            receiver(receivedAt.plus(Duration.ofHours(30))).receive(acq, "TEST")
            val day4 = seal(acq, day.plusDays(3)) as SealAcquisitionFileResult.Sealed
            recordsOf(day4.fileId).values.map { it.first to it.third } shouldBe listOf(cancelC.transactionId to 2_000L)
            // 같은 취소가 두 번 봉인되지 않는다(UNIQUE(transaction_id, record_type, generation) + 판정 끝남 재확인).
            seal(acq, day.plusDays(4)).let { (it as SealAcquisitionFileResult.Sealed).cancelRecords } shouldBe 0
        }
    }

    Given("AQ-X2 a cancellation after membership whose value file v1 cannot carry (review NIT-3)") {
        val acq = acquirers.next()
        val a = authorize(acq, 10_000)
        val day1 = seal(acq).fileId
        transmit(day1)
        result(day1, 1, listOf(1 to LineResult.ACCEPTED))
        receiver().receive(acq, "TEST")
        // 의도: 첫 반환의 기관 거래번호에 공백(PAYMENT가 확정해 FINANCIAL이 받은 값 — 수신은 거절하지 않는다), 둘째 반환은 정상.
        val odd = PaymentFactFixture.cancelOf(a, 1_000, returnSequence = 1, cumulativeMinor = 1_000).copy(providerTransactionId = "VAN C 1")
        inbox.receive(PaymentFactEnvelopeFixture.inbound(odd, UUID.randomUUID().toString()))
        val ok = PaymentFactFixture.cancelOf(a, 2_000, returnSequence = 2, cumulativeMinor = 3_000)
        inbox.receive(PaymentFactEnvelopeFixture.inbound(ok, UUID.randomUUID().toString()))
        val day2 = seal(acq, day.plusDays(1)) as SealAcquisitionFileResult.Sealed

        Then("only that cancellation waits (UNREPRESENTABLE), the other is sealed as CANCEL and the lane continues") {
            day2.cancelWaiting shouldBe mapOf("UNREPRESENTABLE" to 1)
            recordsOf(day2.fileId).values.map { it.first } shouldBe listOf(ok.transactionId)
        }
    }

    Given("AQ-R13 hash-matching responses for a file whose transmission axis is quarantined (other bytes under the id — review m-2)") {
        val acq = acquirers.next()
        val fileId = sealAndTransmit(acq, 10_000)
        // root로 전송 축 격리를 만든다(I-23 — 기관 최종명에 다른 바이트가 있던 상황). runtime 계정은 이 상태를 임의로 만들 경로가 없다.
        FinancialDbAccounts.root(container).use { c ->
            c.codeOf("UPDATE acquisition_file SET transmission_state = 'TRANSMISSION_QUARANTINED', transmitted_at = NULL WHERE file_id = '$fileId'") shouldBe null
        }
        ack(fileId, AckOutcome.RECEIVED)
        result(fileId, 1, listOf(1 to LineResult.ACCEPTED))
        val run = receiver().receive(acq, "TEST")

        Then("both are quarantined (TRANSMISSION_QUARANTINED) and nothing is applied: axes unchanged, no record result") {
            listOf(run.quarantined, run.applied) shouldBe listOf(2, 0)
            responseRows(acq) shouldBe listOf("ACK.01 QUARANTINED TRANSMISSION_QUARANTINED", "RES.01 QUARANTINED TRANSMISSION_QUARANTINED")
            axes(fileId) shouldBe listOf("TRANSMISSION_QUARANTINED", "NONE", "AWAITING")
            count("SELECT COUNT(*) FROM acquisition_record_result WHERE file_id = ?", fileId) shouldBe 0
        }
    }

    Given("AQ-R14 a re-acquisition generation is ACCEPTED, then the late ACCEPTED of the earlier generation arrives (D-ACQ-014 — one ACCEPTED across generations)") {
        val acq = acquirers.next()
        val first = sealAndTransmit(acq, 10_000)
        val second = first.replace(".001", ".002")
        // 재매입 실행 경로는 ACQ-ADMIN-001이다 — 시험은 root로 세대 1 파일·레코드를 만든다(같은 봉인 hash·레코드, generation 1·원 참조).
        FinancialDbAccounts.root(container).use { c ->
            c.codeOf(
                "INSERT INTO acquisition_file (file_id, acquirer_code, environment, business_date, file_version, original_file_id, sender_id, cutoff_policy_version, " +
                    "cutoff_at, sealed_at, record_count, sale_count, sale_amount_minor, cancel_count, cancel_amount_minor, net_amount_minor, content_sha256, content_bytes, " +
                    "transmission_state, receipt_state, result_state, transmission_lease_until, transmitted_at, created_at, updated_at, version) " +
                    "SELECT '$second', acquirer_code, environment, business_date, 2, file_id, sender_id, cutoff_policy_version, cutoff_at, sealed_at, record_count, sale_count, " +
                    "sale_amount_minor, cancel_count, cancel_amount_minor, net_amount_minor, content_sha256, content_bytes, transmission_state, 'NONE', 'AWAITING', NULL, " +
                    "transmitted_at, created_at, updated_at, 0 FROM acquisition_file WHERE file_id = '$first'",
            ) shouldBe null
            c.codeOf(
                "INSERT INTO acquisition_file_record (file_id, seq, record_type, transaction_id, original_transaction_id, merchant_ref, approval_number, " +
                    "provider_transaction_id, original_provider_transaction_id, institution_transaction_date, event_at, currency, approved_amount_minor, " +
                    "netted_cancel_amount_minor, record_amount_minor, plan_kind, installment_months, card_type, issuer_code, generation, reacquisition_of) " +
                    "SELECT '$second', seq, record_type, transaction_id, original_transaction_id, merchant_ref, approval_number, provider_transaction_id, " +
                    "original_provider_transaction_id, institution_transaction_date, event_at, currency, approved_amount_minor, netted_cancel_amount_minor, " +
                    "record_amount_minor, plan_kind, installment_months, card_type, issuer_code, 1, CONCAT(file_id, '#', seq) FROM acquisition_file_record WHERE file_id = '$first'",
            ) shouldBe null
        }
        val txn = recordsOf(first).getValue(1).first
        result(second, 1, listOf(1 to LineResult.ACCEPTED))
        val reacquired = receiver().receive(acq, "TEST")
        result(first, 1, listOf(1 to LineResult.ACCEPTED))
        val late = receiver(receivedAt.plusSeconds(60)).receive(acq, "TEST")
        val duplicateMarker = FinancialDbAccounts.root(container).use { c ->
            c.codeOf(
                "INSERT INTO acquisition_accepted_record (transaction_id, record_type, file_id, seq, entry_no, generation, accepted_at) " +
                    "VALUES ('$txn', 'SALE', '$first', 1, 1, 0, NOW(3))",
            )
        }

        Then("the generation 1 ACCEPTED is the acquisition; the late generation 0 ACCEPTED is a CONFLICT entry (quarantine); the database refuses a second ACCEPTED marker") {
            listOf(reacquired.recordsDecided, late.recordsDecided, late.conflicts) shouldBe listOf(1, 0, 1)
            resultSummary(second) shouldBe mapOf(1 to listOf("ACCEPTED"))
            resultSummary(first) shouldBe mapOf(1 to listOf("CONFLICT(ACCEPTED)"))
            jdbc.queryForMap("SELECT file_id, seq, generation FROM acquisition_accepted_record WHERE transaction_id = ?", txn).values.map { it.toString() } shouldBe
                listOf(second, "1", "1")
            duplicateMarker shouldBe 1062
        }
    }

    Given("AQ-X3 the original SALE of a 'cancel not required' record later receives a conflicting result (review m-1)") {
        val acq = acquirers.next()
        val a = authorize(acq, 10_000)
        val day1 = seal(acq).fileId
        transmit(day1)
        result(day1, 1, listOf(1 to LineResult.REJECTED))
        receiver().receive(acq, "TEST")
        cancel(a, 3_000)
        (seal(acq, day.plusDays(1)) as SealAcquisitionFileResult.Sealed).cancelNotRequired shouldBe 1
        val before = backlog.backlog().cancelNotRequiredOriginalConflict
        result(day1, 2, listOf(1 to LineResult.ACCEPTED))
        val run = receiver(receivedAt.plus(Duration.ofHours(30))).receive(acq, "TEST")

        Then("the original becomes CONFLICT and the backlog count of 'not required' records with a conflicted original grows by one (gauge source, error log)") {
            run.conflicts shouldBe 1
            resultSummary(day1) shouldBe mapOf(1 to listOf("REJECTED:AMOUNT_MISMATCH", "CONFLICT(ACCEPTED)"))
            backlog.backlog().cancelNotRequiredOriginalConflict - before shouldBe 1L
        }
    }

    Given("AQ-I1 a stored record row that breaks the file v1 grammar (changed outside the application — review NIT-3)") {
        val acq = acquirers.next()
        authorize(acq, 10_000)
        val fileId = seal(acq).fileId
        FinancialDbAccounts.root(container).use { c ->
            c.codeOf("UPDATE acquisition_file_record SET approval_number = 'A 1' WHERE file_id = '$fileId'") shouldBe null
        }
        Then("the restart re-seal and the transmission refuse with INTEGRITY_VIOLATION instead of an unclassified failure") {
            shouldThrow<AcquisitionSealRefused> { seal(acq) }.reason shouldBe AcquisitionSealRefused.Reason.INTEGRITY_VIOLATION
            transmit(fileId).outcome shouldBe Kind.INTEGRITY_VIOLATION
            count("SELECT COUNT(*) FROM acquisition_transmission_attempt WHERE file_id = ?", fileId) shouldBe 0
        }
    }

    Given("AQ-I2 netting records that no longer add up to the sealed netted amount (§16 — reconciliation input integrity)") {
        val acq = acquirers.next()
        val a = authorize(acq, 10_000)
        cancel(a, 2_000)
        val fileId = seal(acq).fileId
        FinancialDbAccounts.root(container).use { c ->
            c.codeOf(
                "INSERT INTO acquisition_netting (cancellation_transaction_id, original_transaction_id, amount_minor, currency, file_id, seq, decided_at) " +
                    "VALUES ('ptx_forged_netting_$acq', '${a.transactionId}', 500, 'KRW', '$fileId', 1, NOW(3))",
            ) shouldBe null
        }
        Then("the bytes still match but the re-seal refuses with INTEGRITY_VIOLATION (netting DB check)") {
            shouldThrow<AcquisitionSealRefused> { seal(acq) }.reason shouldBe AcquisitionSealRefused.Reason.INTEGRITY_VIOLATION
        }
    }
})

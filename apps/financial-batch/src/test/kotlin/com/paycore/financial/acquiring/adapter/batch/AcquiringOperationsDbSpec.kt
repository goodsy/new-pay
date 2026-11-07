package com.paycore.financial.acquiring.adapter.batch

import com.paycore.financial.FinancialDbAccounts
import com.paycore.financial.FinancialMySqlContainerConfig
import com.paycore.financial.acquiring.adapter.integration.TestAcquirerSftpConfig
import com.paycore.financial.acquiring.adapter.integration.TestAcquirerSftpServer
import com.paycore.financial.acquiring.application.AcquirerResponseService
import com.paycore.financial.acquiring.application.AcquisitionResultCloseService
import com.paycore.financial.acquiring.application.AcquisitionSealService
import com.paycore.financial.acquiring.application.AcquisitionTransmissionService
import com.paycore.financial.acquiring.application.AcquisitionWaitingBoard
import com.paycore.financial.acquiring.application.provided.CloseAcquisitionResultsUseCase
import com.paycore.financial.acquiring.application.provided.NextSealDate
import com.paycore.financial.acquiring.application.provided.ReceiveAcquirerResponsesUseCase
import com.paycore.financial.acquiring.application.provided.SealAcquisitionFileUseCase
import com.paycore.financial.acquiring.application.provided.TransmissionResult.Kind
import com.paycore.financial.acquiring.application.provided.TransmitAcquisitionFileUseCase
import com.paycore.financial.acquiring.application.required.AcquirerFileChannel
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
import com.paycore.financial.acquiring.domain.AckOutcome
import com.paycore.financial.acquiring.domain.AcquirerAck
import com.paycore.financial.acquiring.domain.AcquirerResponseFormat
import com.paycore.financial.acquiring.domain.AcquirerResultFile
import com.paycore.financial.acquiring.domain.AcquirerResultLine
import com.paycore.financial.acquiring.domain.AcquisitionFileId
import com.paycore.financial.acquiring.domain.AcquisitionRecordType
import com.paycore.financial.acquiring.domain.LineResult
import com.paycore.financial.codeOf
import com.paycore.financial.paymentfact.adapter.web.PaymentFactEnvelopeFixture
import com.paycore.financial.paymentfact.application.PaymentFactInboxService
import com.paycore.financial.paymentfact.application.provided.PaymentFactAcquisitionSource
import com.paycore.financial.paymentfact.domain.PaymentFactFixture
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.launch.JobOperator
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.testcontainers.mysql.MySQLContainer
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 매입 운영 경로 실제 시험(ACQUIRING-001 2-F 지표·스케줄러, 2-G 재매입 기록 — AQ-M1, AQ-S1..S3, AQ-G1..G3). 실제 MySQL(runtime 계정) + 실제 SFTP(시험 SSHD).
 * 스케줄러는 운영 Bean(Use Case·Job·지표)으로 만든 인스턴스의 `runOnce`를 직접 부른다(기본 설정에서는 Bean이 없다 — fail-closed). 지표는 같은 DB를 쓰는 다른 시험의
 * 행이 섞이므로 **차이(delta)** 로 본다.
 */
@SpringBootTest
@Import(FinancialMySqlContainerConfig::class, TestAcquirerSftpConfig::class)
class AcquiringOperationsDbSpec(
    private val context: ApplicationContext,
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
    private val metrics: AcquiringMetrics,
    private val registry: MeterRegistry,
    private val jobOperator: JobOperator,
    @Qualifier(AcquisitionSubmissionJobConfig.JOB_NAME) private val job: Job,
    private val sealUseCase: SealAcquisitionFileUseCase,
    private val transmitUseCase: TransmitAcquisitionFileUseCase,
    private val receiveUseCase: ReceiveAcquirerResponsesUseCase,
    private val closeUseCase: CloseAcquisitionResultsUseCase,
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

    // 응답 시험(AcquirerResponseDbSpec)은 SFTPR11 이후를 쓴다 — 이 시험은 SFTPR1..10.
    val acquirers = TestAcquirerSftpConfig.RESPONSE_ACQUIRERS.take(10).iterator()

    fun authorize(acq: String, amount: Long, approvedAt: Instant = Instant.parse("2026-11-05T01:02:03.456Z"), approvalNumber: String = "30000001") =
        PaymentFactFixture.authorized(amountMinor = amount, acquirerCode = acq, approvedAt = approvedAt).copy(approvalNumber = approvalNumber).also {
            inbox.receive(PaymentFactEnvelopeFixture.inbound(it, UUID.randomUUID().toString()))
        }

    fun seal(acq: String, date: LocalDate = day) =
        AcquisitionSealService(transactionManager, lanes, files, membership, source, policies, FixedClock(date.atTime(14, 5).toInstant(ZoneOffset.UTC)), results, board)
            .seal(acq, "TEST", date)

    fun transmit(fileId: String) =
        AcquisitionTransmissionService(transactionManager, files, attempts, channel, transport, FixedClock(afterCutoff.plusSeconds(60))).transmit(fileId)

    fun receive(acq: String, at: Instant = receivedAt) =
        AcquirerResponseService(transactionManager, files, responses, results, channel, FixedClock(at), membership).receive(acq, "TEST")

    fun close(acq: String, at: Instant) = AcquisitionResultCloseService(
        transactionManager, files, results, object : AcquisitionResultPolicy {
            override val resultDeadline: Duration = Duration.ofHours(1)
        }, FixedClock(at),
    ).closeExpired(acq, "TEST", 10)

    fun recordsOf(fileId: String): Map<Int, Triple<String, AcquisitionRecordType, Long>> = jdbc.query(
        "SELECT seq, transaction_id, record_type, record_amount_minor FROM acquisition_file_record WHERE file_id = ? ORDER BY seq", { rs, _ ->
            rs.getInt(1) to Triple(rs.getString(2), AcquisitionRecordType.valueOf(rs.getString(3)), rs.getLong(4))
        }, fileId,
    ).toMap()

    fun sealedSha(fileId: String): Pair<String, Long> =
        jdbc.queryForMap("SELECT content_sha256, content_bytes FROM acquisition_file WHERE file_id = ?", fileId).let { it["content_sha256"] as String to (it["content_bytes"] as Number).toLong() }

    fun writeAck(fileId: String, sha: String? = null, at: Instant = receivedAt) {
        val id = AcquisitionFileId.parse(fileId)
        val (s, b) = sealedSha(fileId)
        val ack = AcquirerAck(id, 1, AckOutcome.RECEIVED, null, sha ?: s, b, recordsOf(fileId).size, at.truncatedTo(java.time.temporal.ChronoUnit.MILLIS))
        Files.write(server.outbox.resolve(AcquirerResponseFormat.ackFileName(id, 1)), AcquirerResponseFormat.renderAck(ack))
    }

    fun writeResult(fileId: String, seq: Int, lines: List<Pair<Int, LineResult>>, at: Instant = receivedAt) {
        val id = AcquisitionFileId.parse(fileId)
        val records = recordsOf(fileId)
        val r = AcquirerResultFile(
            id, sealedSha(fileId).first, seq, at.truncatedTo(java.time.temporal.ChronoUnit.MILLIS),
            lines.map { (n, res) ->
                val (txn, type, amount) = records.getValue(n)
                AcquirerResultLine(n, txn, type, res, if (res == LineResult.REJECTED) "AMOUNT_MISMATCH" else null, amount)
            },
        )
        Files.write(server.outbox.resolve(AcquirerResponseFormat.resultFileName(id, seq)), AcquirerResponseFormat.renderResult(r))
    }

    fun axes(fileId: String): List<String> =
        jdbc.queryForMap("SELECT transmission_state, receipt_state, result_state FROM acquisition_file WHERE file_id = ?", fileId).values.map { it as String }

    fun count(sql: String, vararg args: Any): Int = jdbc.queryForObject(sql, Int::class.java, *args)!!

    fun gauge(name: String, vararg tags: String): Double = registry.get("paycore.financial.acquiring.$name").tags(*tags).gauge().value()

    fun counter(name: String, vararg tags: String): Double = registry.find("paycore.financial.acquiring.$name").tags(*tags).counter()?.count() ?: 0.0

    fun scheduler(vararg lanes: Pair<String, LocalDate>, maxSeals: Int = 1) = AcquisitionScheduler(
        AcquiringScheduleSettings(true, Duration.ofMinutes(1), lanes.map { AcquiringScheduleSettings.Lane(it.first, "TEST", it.second) }, 10, maxSeals),
        jobOperator, job, sealUseCase, transmitUseCase, receiveUseCase, closeUseCase, metrics,
    )

    Given("AQ-M1 backlog that needs attention: unknown and conflicting records, a quarantined ack, an unknown transmission, repeated upload failures, a waiting approval") {
        val names = listOf(
            "files.transmission_unknown", "files.upload_failed_repeated", "files.receipt_conflict", "records.unknown", "records.conflict", "responses.quarantined",
            "integrity.netting_mismatch",
        )
        val before = metrics.refresh().let { names.associateWith { n -> gauge(n) } }
        // UNKNOWN 1건(결과 마감) + CONFLICT 1건(상충 결과)
        val acqA = acquirers.next()
        authorize(acqA, 10_000)
        authorize(acqA, 20_000)
        val fileA = seal(acqA).fileId
        transmit(fileA).outcome shouldBe Kind.TRANSMITTED
        val (first, second) = recordsOf(fileA).keys.toList()
        writeResult(fileA, 1, listOf(first to LineResult.ACCEPTED))
        writeResult(fileA, 2, listOf(first to LineResult.REJECTED))
        receive(acqA)
        close(acqA, receivedAt.plus(Duration.ofHours(3)))
        // hash 다른 ACK(격리 + RECEIPT_CONFLICT)
        val acqB = acquirers.next()
        authorize(acqB, 10_000)
        val fileB = seal(acqB).fileId
        transmit(fileB)
        writeAck(fileB, sha = "f".repeat(64))
        receive(acqB)
        // 결과 모름 전송 + 업로드 실패 3회(임시 파일 상한 거절과 같은 모양) + 표현 불가 승인 대기
        val acqC = acquirers.next()
        authorize(acqC, 10_000)
        authorize(acqC, 10_000, approvalNumber = "A 1")
        val fileC = seal(acqC).fileId
        repeat(3) {
            server.failNextWrite = true
            transmit(fileC).detail shouldBe "UPLOAD_FAILED"
        }
        val acqD = acquirers.next()
        authorize(acqD, 10_000)
        val fileD = seal(acqD).fileId
        server.dropRenameResponses.set(1)
        server.rejectLoginsAfterDroppedRename.set(1)
        transmit(fileD).outcome shouldBe Kind.OUTCOME_UNKNOWN
        val after = metrics.refresh().let { names.associateWith { n -> gauge(n) } }

        Then("each gauge moves by exactly what was created; netting integrity stays unchanged; label values carry no ids or amounts") {
            names.associateWith { after.getValue(it) - before.getValue(it) } shouldBe mapOf(
                "files.transmission_unknown" to 1.0, "files.upload_failed_repeated" to 1.0, "files.receipt_conflict" to 1.0, "records.unknown" to 1.0,
                "records.conflict" to 1.0, "responses.quarantined" to 1.0, "integrity.netting_mismatch" to 0.0,
            )
            gauge("waiting", "lane", "$acqC/TEST", "kind", "SALE", "reason", "UNREPRESENTABLE") shouldBe 1.0
            gauge("waiting.oldest_age_seconds", "lane", "$acqC/TEST", "kind", "SALE", "reason", "UNREPRESENTABLE") shouldBeGreaterThan 0.0
            // label 키는 허용 목록 안에만 있다(거래·파일 ID·금액 label 없음).
            registry.meters.filter { it.id.name.startsWith("paycore.financial.acquiring") }.flatMap { it.id.tags }.map { it.key }.toSet() -
                setOf("lane", "kind", "reason", "outcome", "detail", "result") shouldBe emptySet()
        }
    }

    Given("AQ-S1 the default configuration") {
        Then("no scheduler bean exists (fail-closed — scheduling only when explicitly enabled), and enabled settings without values or with a LIVE lane do not start") {
            context.getBeanNamesForType(AcquisitionScheduler::class.java).toList() shouldBe emptyList()
            context.getBeanNamesForType(AcquisitionScheduleConfiguration::class.java).toList() shouldBe emptyList()
            shouldThrow<IllegalArgumentException> { AcquiringScheduleSettings(true, null, null, null, null) }
            shouldThrow<IllegalArgumentException> {
                AcquiringScheduleSettings(true, Duration.ofMinutes(1), listOf(AcquiringScheduleSettings.Lane("SIMACQA", "LIVE", day)), 10, 1)
            }
            AcquiringScheduleSettings(false, null, null, null, null).lanes shouldBe emptyList()
        }
    }

    Given("AQ-S2 an enabled scheduler cycle: seal (Job) → transmit (stored bytes) → receive → close → metrics") {
        val acq = acquirers.next()
        val businessDay = LocalDate.parse("2026-10-02")
        authorize(acq, 12_000, approvedAt = Instant.parse("2026-10-02T01:00:00.000Z"))
        val s = scheduler(acq to businessDay)
        val sealsBefore = counter("seal", "outcome", "COMPLETED")
        val transmittedBefore = counter("transmission", "outcome", "TRANSMITTED", "detail", "RENAMED_AND_VERIFIED")
        val appliedBefore = counter("responses", "result", "applied")
        val firstFailures = s.runOnce()
        val firstFile = "ACQ.$acq.TEST.20261002.001"
        val firstAxes = axes(firstFile)
        writeAck(firstFile)
        writeResult(firstFile, 1, listOf(1 to LineResult.ACCEPTED))
        val secondFailures = s.runOnce()

        Then("cycle 1 sealed and transmitted the day (no responses yet); cycle 2 sealed the next day and applied the ack and result; counters moved; no step failed") {
            listOf(firstFailures, secondFailures) shouldBe listOf(0, 0)
            firstAxes shouldBe listOf("TRANSMITTED", "NONE", "AWAITING")
            axes(firstFile) shouldBe listOf("TRANSMITTED", "RECEIVED", "COMPLETE")
            axes("ACQ.$acq.TEST.20261003.001") shouldBe listOf("TRANSMITTED", "NONE", "AWAITING")
            listOf(
                counter("seal", "outcome", "COMPLETED") - sealsBefore,
                counter("transmission", "outcome", "TRANSMITTED", "detail", "RENAMED_AND_VERIFIED") - transmittedBefore,
                counter("responses", "result", "applied") - appliedBefore,
            ) shouldBe listOf(2.0, 2.0, 2.0)
            // 봉인은 Job으로만(같은 JobParameters — D-ACQ-007), 업무일마다 JobInstance 하나.
            count("SELECT COUNT(*) FROM BATCH_JOB_EXECUTION_PARAMS WHERE PARAMETER_NAME = 'acquirerCode' AND PARAMETER_VALUE = ?", acq) shouldBe 2
        }
    }

    Given("AQ-S3 two scheduler instances run the same lane at the same time (multi-instance safety without a distributed lock)") {
        val acq = acquirers.next()
        authorize(acq, 9_000, approvedAt = Instant.parse("2026-10-02T02:00:00.000Z"))
        val a = scheduler(acq to LocalDate.parse("2026-10-02"))
        val b = scheduler(acq to LocalDate.parse("2026-10-02"))
        val pool = Executors.newFixedThreadPool(2)
        listOf(pool.submit<Int> { a.runOnce() }, pool.submit<Int> { b.runOnce() }).forEach { it.get(180, TimeUnit.SECONDS) }
        pool.shutdown()
        // 한쪽이 Job 경합으로 봉인 단계를 건너뛰었을 수 있다 — 다음 회차가 수렴시킨다.
        a.runOnce()

        Then("one file per business day, one membership per approval, the stored bytes transmitted once (attempt history has one DELIVERED)") {
            count("SELECT COUNT(*) FROM acquisition_file WHERE acquirer_code = ? AND business_date = '2026-10-02'", acq) shouldBe 1
            count("SELECT COUNT(*) FROM acquisition_file_record r JOIN acquisition_file f ON f.file_id = r.file_id WHERE f.acquirer_code = ?", acq) shouldBe 1
            count(
                "SELECT COUNT(*) FROM acquisition_transmission_outcome WHERE file_id = ? AND outcome = 'DELIVERED'", "ACQ.$acq.TEST.20261002.001",
            ) shouldBe 1
            axes("ACQ.$acq.TEST.20261002.001").first() shouldBe "TRANSMITTED"
        }
    }

    Given("AQ-S4 the next business day of a lane has no cutoff policy version (review m-5 — distinguished from 'before the cutoff')") {
        val acq = acquirers.next()
        val before = counter("seal", "outcome", "NO_CUTOFF_POLICY")
        // Demo 정책은 2026-01-01부터 — 그 전 날은 정책이 없다.
        val failures = scheduler(acq to LocalDate.parse("2025-12-31")).runOnce()

        Then("the scheduler does not stop silently: seal{outcome=NO_CUTOFF_POLICY} grows, nothing is sealed, the cycle itself does not fail") {
            counter("seal", "outcome", "NO_CUTOFF_POLICY") - before shouldBe 1.0
            failures shouldBe 0
            count("SELECT COUNT(*) FROM acquisition_file WHERE acquirer_code = ?", acq) shouldBe 0
            sealUseCase.nextBusinessDateToSeal(acq, "TEST", LocalDate.parse("2025-12-31")) shouldBe NextSealDate.NoCutoffPolicy(LocalDate.parse("2025-12-31"))
            sealUseCase.nextBusinessDateToSeal(acq, "TEST", LocalDate.parse("2099-01-01")) shouldBe NextSealDate.NotDue(LocalDate.parse("2099-01-01"))
            sealUseCase.nextBusinessDateToSeal(acq, "LIVE", LocalDate.parse("2026-10-02")) shouldBe NextSealDate.Blocked
        }
    }

    // AQ-G1·G3(2-G: 서명 채널 없음·runtime INSERT 1142·실행 언제나 거절)은 ACQ-ADMIN-001 B1이 D-ACQ-011에 따라 입력 경로를 열면서 ReacquisitionExecutionDbSpec·
    // AdminAcquiringOperationsHttpSpec(조작 키 서명만, 결정 기록, 실행)으로 바뀌었다. 이 AQ-G2는 표 자체의 제약(CHECK·UNIQUE·결정 FK)을 계속 본다.
    Given("AQ-G2 the approval table's own constraints (installed rows only via root here — CHECK, UNIQUE and the decision FK hold regardless of the application)") {
        val acq = acquirers.next()
        authorize(acq, 10_000)
        val fileId = seal(acq).fileId
        val txn = recordsOf(fileId).getValue(1).first
        fun insert(id: String, requestedBy: String = "a", approvedBy: String = "b", target: Int = 0, next: Int = 1, channel: String = "ADMIN_SIGNED", of: String = "$fileId#1") =
            "INSERT INTO acquisition_reacquisition_approval (approval_id, file_id, seq, transaction_id, record_type, target_generation, new_generation, target_status, " +
                "reacquisition_of, requested_by, approved_by, reason_code, evidence_ref, channel, recorded_at) VALUES ('$id-$acq', '$fileId', 1, '$txn', 'SALE', $target, $next, " +
                "'REJECTED', '$of', '$requestedBy', '$approvedBy', 'R', 'E', '$channel', NOW(3))"
        fun decision(id: String) =
            "INSERT INTO acquisition_ops_decision (approval_id, kind, decision, rejection_code, target_ref, result_file_id, content_hash, requested_by, approved_by, " +
                "approved_at, execute_before, decided_at, origin) VALUES ('$id-$acq', 'REACQUISITION', 'NOT_REQUIRED', NULL, '$fileId', NULL, " +
                "CONCAT('acd1:', REPEAT('a', 64)), 'a', 'b', NOW(3), NOW(3) + INTERVAL 1 HOUR, NOW(3), 'EXECUTION_COMMAND')"
        val codes = FinancialDbAccounts.root(container).use { c ->
            listOf("p", "g", "c", "r", "v", "v2").forEach { c.codeOf(decision(it)) shouldBe null }
            mapOf(
                "no decision row (ACQ-ADMIN-001 FK)" to c.codeOf(insert("orphan")),
                "same person" to c.codeOf(insert("p", requestedBy = "x", approvedBy = "x")),
                "skipped generation" to c.codeOf(insert("g", next = 2)),
                "unsigned channel" to c.codeOf(insert("c", channel = "UNSIGNED")),
                "other reference" to c.codeOf(insert("r", of = "$fileId#2")),
                "valid" to c.codeOf(insert("v")),
                "second approval of the same generation" to c.codeOf(insert("v2")),
            )
        }
        Then("requester = approver, a skipped generation, an unsigned channel and a wrong reference are CHECK violations; one approval per (transaction, type, generation)") {
            codes shouldBe mapOf(
                "no decision row (ACQ-ADMIN-001 FK)" to 1452,
                "same person" to 3819, "skipped generation" to 3819, "unsigned channel" to 3819, "other reference" to 3819, "valid" to null,
                "second approval of the same generation" to 1062,
            )
        }
    }
})

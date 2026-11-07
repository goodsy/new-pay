package com.paycore.financial.acquiring.adapter.query

import com.paycore.financial.FinancialDbAccounts
import com.paycore.financial.FinancialMySqlContainerConfig
import com.paycore.financial.acquiring.adapter.integration.TestAcquirerSftpConfig
import com.paycore.financial.acquiring.adapter.integration.TestAcquirerSftpServer
import com.paycore.financial.acquiring.adapter.web.admin.AdminAcquisitionJson
import com.paycore.financial.acquiring.application.AcquirerResponseService
import com.paycore.financial.acquiring.application.AcquisitionResultCloseService
import com.paycore.financial.acquiring.application.AcquisitionSealService
import com.paycore.financial.acquiring.application.AcquisitionTransmissionService
import com.paycore.financial.acquiring.application.AcquisitionWaitingBoard
import com.paycore.financial.acquiring.application.provided.AcquisitionAttention
import com.paycore.financial.acquiring.application.provided.AcquisitionFileCursor
import com.paycore.financial.acquiring.application.provided.AcquisitionFileSearchCriteria
import com.paycore.financial.acquiring.application.provided.AcquisitionFileViewQuery
import com.paycore.financial.acquiring.application.provided.AcquisitionRecordSearchCriteria
import com.paycore.financial.acquiring.application.provided.AcquisitionViewFailure
import com.paycore.financial.acquiring.application.provided.TransmissionResult.Kind
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
import com.paycore.financial.acquiring.domain.RecordResultRule
import com.paycore.financial.column
import com.paycore.financial.paymentfact.adapter.web.PaymentFactEnvelopeFixture
import com.paycore.financial.paymentfact.application.PaymentFactInboxService
import com.paycore.financial.paymentfact.application.provided.PaymentFactAcquisitionSource
import com.paycore.financial.paymentfact.domain.PaymentFactFixture
import com.paycore.financial.scalar
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.QueryTimeoutException
import org.springframework.transaction.PlatformTransactionManager
import org.testcontainers.mysql.MySQLContainer
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * ACQ-VIEW-001 §3.3·§4·§5 매입 현황 조회(jOOQ Reader + Use Case) 실제 시험 — 실제 MySQL(runtime 계정) + 실제 SFTP(시험 SSHD). 파일은 **운영 봉인·전송·수신·결과 마감 서비스**로 만든
 * Named Fixture다(Fixture Monkey 미사용 — 레코드 금액·상계·결과 행은 서로 묶인 불변식이라 독립 random 생성 금지). 업무일 2026-10-20은 이 시험만 쓴다(같은 DB의 다른 시험 파일과
 * 섞이지 않게), lane은 TestAcquirerSftpConfig.VIEW_ACQUIRERS.
 *
 * 확인: 필터 정확 일치·기간·keyset(같은 업무일 동률 — fileId로 끊음)·needsAttention(Use Case 사유 계산과 같은 정의)·"확인 필요" 사유 8종·재매입 검토, 레코드 상태 SQL ↔
 * `RecordResultRule.status` 동치, 상세 모양·한 RO RR TX(스냅샷 probe)·Asia/Seoul JVM·질의 시간 상한 hint·EXPLAIN 색인, **비노출 대조군**(D-ACQV-002 값·송신자 ID·바이트가 DB에는
 * 있고 응답·SQL에는 없다).
 */
@SpringBootTest
@Import(FinancialMySqlContainerConfig::class, TestAcquirerSftpConfig::class, ViewSnapshotProbeConfig::class)
class AcquisitionFileViewReaderSpec(
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
    private val server: TestAcquirerSftpServer,
    private val container: MySQLContainer,
    private val query: AcquisitionFileViewQuery,
    private val reader: JooqAcquisitionFileViewReader,
    private val resultPolicy: AcquisitionResultPolicy,
    private val probe: ViewSnapshotProbe,
    private val dsl: org.jooq.DSLContext,
    private val approvals: com.paycore.financial.acquiring.application.required.ReacquisitionApprovalStore,
    private val decisions: com.paycore.financial.acquiring.application.required.AcquisitionOpsDecisionStore,
    private val integrityEvents: com.paycore.financial.acquiring.application.AcquiringIntegrityEvents,
) : BehaviorSpec({

    val day = LocalDate.parse("2026-10-20")
    val approvedAt = Instant.parse("2026-10-20T01:02:03.456Z")
    val sealAt = Instant.parse("2026-10-20T14:05:00Z")
    val transmitAt = Instant.parse("2026-10-20T14:06:00Z")
    val receivedAt = Instant.parse("2026-10-20T15:00:00Z")
    val json = JsonMapper.builder().build()

    class FixedClock(private val at: Instant) : AcquiringClock {
        override fun now(): Instant = at
    }

    val acquirers = TestAcquirerSftpConfig.VIEW_ACQUIRERS.iterator()

    afterEach { probe.disarm() }

    fun authorize(acq: String, amount: Long, at: Instant = approvedAt) = PaymentFactFixture.authorized(amountMinor = amount, acquirerCode = acq, approvedAt = at).also {
        inbox.receive(PaymentFactEnvelopeFixture.inbound(it, UUID.randomUUID().toString()))
    }

    fun seal(acq: String, on: LocalDate = day): String = AcquisitionSealService(
        transactionManager, lanes, files, membership, source, policies, FixedClock(sealAt.minus(Duration.ofDays(day.toEpochDay() - on.toEpochDay()))), results, board,
    ).seal(acq, "TEST", on).fileId

    fun transmit(fileId: String) = AcquisitionTransmissionService(transactionManager, files, attempts, channel, transport, FixedClock(transmitAt)).transmit(fileId)

    fun receive(acq: String, at: Instant = receivedAt) =
        AcquirerResponseService(transactionManager, files, responses, results, channel, FixedClock(at), membership).receive(acq, "TEST")

    fun close(acq: String) = AcquisitionResultCloseService(
        transactionManager, files, results, object : AcquisitionResultPolicy {
            override val resultDeadline: Duration? = Duration.ofHours(1)
        }, FixedClock(transmitAt.plus(Duration.ofHours(3))),
    ).closeExpired(acq, "TEST", 10)

    fun root(sql: String, vararg args: Any?): Any? = FinancialDbAccounts.root(container).use { it.scalar(sql, *args) }
    fun rootColumn(sql: String, vararg args: Any?): List<String> = FinancialDbAccounts.root(container).use { it.column(sql, *args) }

    fun sealed(fileId: String): Pair<String, Long> =
        root("SELECT content_sha256 FROM acquisition_file WHERE file_id = ?", fileId) as String to (root("SELECT content_bytes FROM acquisition_file WHERE file_id = ?", fileId) as Number).toLong()

    fun recordsOf(fileId: String): Map<Int, Triple<String, AcquisitionRecordType, Long>> = FinancialDbAccounts.root(container).use { c ->
        c.prepareStatement("SELECT seq, transaction_id, record_type, record_amount_minor FROM acquisition_file_record WHERE file_id = ? ORDER BY seq").use { ps ->
            ps.setString(1, fileId)
            ps.executeQuery().use { rs ->
                buildMap { while (rs.next()) put(rs.getInt(1), Triple(rs.getString(2), AcquisitionRecordType.valueOf(rs.getString(3)), rs.getLong(4))) }
            }
        }
    }

    fun ack(fileId: String, outcome: AckOutcome, reason: String? = null, sha: String? = null) {
        val (s, b) = sealed(fileId)
        val id = AcquisitionFileId.parse(fileId)
        val a = AcquirerAck(id, 1, outcome, reason, sha ?: s, b, if (outcome == AckOutcome.RECEIVED) recordsOf(fileId).size else null, receivedAt.minusSeconds(30))
        Files.write(server.outbox.resolve(AcquirerResponseFormat.ackFileName(id, 1)), AcquirerResponseFormat.renderAck(a))
    }

    fun result(fileId: String, seq: Int, lines: List<Pair<Int, LineResult>>, reason: String = "AMOUNT_MISMATCH", at: Instant = receivedAt.minusSeconds(10)): String {
        val id = AcquisitionFileId.parse(fileId)
        val records = recordsOf(fileId)
        val r = AcquirerResultFile(
            id, sealed(fileId).first, seq, at,
            lines.map { (n, res) ->
                val (txn, type, amount) = records.getValue(n)
                AcquirerResultLine(n, txn, type, res, if (res == LineResult.REJECTED) reason else null, amount)
            },
        )
        return AcquirerResponseFormat.resultFileName(id, seq).also { Files.write(server.outbox.resolve(it), AcquirerResponseFormat.renderResult(r)) }
    }

    fun sealAndTransmit(acq: String, vararg amounts: Long): String {
        amounts.forEach { authorize(acq, it) }
        return seal(acq).also { transmit(it).outcome shouldBe Kind.TRANSMITTED }
    }

    // ---- Named Fixture: 파일 하나에 저장 상태 하나(같은 업무일 2026-10-20, lane마다 다름) ----
    val fixtures = linkedMapOf<String, String>()
    fun fixture(name: String, build: (String) -> String): String = fixtures.getOrPut(name) { build(acquirers.next()) }

    fun completed() = fixture("completed") { acq ->
        sealAndTransmit(acq, 10_000, 20_000).also { f ->
            ack(f, AckOutcome.RECEIVED)
            result(f, 1, recordsOf(f).keys.map { it to LineResult.ACCEPTED })
            receive(acq)
        }
    }

    fun rejectedRecord() = fixture("rejectedRecord") { acq ->
        sealAndTransmit(acq, 10_000, 20_000).also { f ->
            val (a, b) = recordsOf(f).keys.toList()
            // 사유 코드는 숫자 시작(B2 리뷰 MAJOR-1 — FINANCIAL TOKEN 문법 `^[A-Z0-9_]{1,32}$`이 허용하는 기관 코드 모양).
            result(f, 1, listOf(a to LineResult.ACCEPTED, b to LineResult.REJECTED), reason = "05")
            receive(acq)
        }
    }

    fun unknownAfterDeadline() = fixture("unknownAfterDeadline") { acq ->
        sealAndTransmit(acq, 10_000).also { f ->
            ack(f, AckOutcome.RECEIVED)
            receive(acq)
            close(acq).files shouldBe 1
        }
    }

    fun conflict() = fixture("conflict") { acq ->
        sealAndTransmit(acq, 10_000).also { f ->
            result(f, 1, listOf(1 to LineResult.ACCEPTED))
            result(f, 2, listOf(1 to LineResult.REJECTED))
            receive(acq)
        }
    }

    fun transmissionUnknown() = fixture("transmissionUnknown") { acq ->
        authorize(acq, 10_000)
        seal(acq).also { f ->
            server.dropRenameResponses.set(1)
            server.rejectLoginsAfterDroppedRename.set(1)
            transmit(f).outcome shouldBe Kind.OUTCOME_UNKNOWN
        }
    }

    fun transmissionQuarantined() = fixture("transmissionQuarantined") { acq ->
        authorize(acq, 10_000)
        seal(acq).also { f ->
            Files.write(server.inboxFile("$f.dat"), "H|1|someone-else\n".toByteArray())
            transmit(f).outcome shouldBe Kind.QUARANTINED
        }
    }

    fun receiptConflict() = fixture("receiptConflict") { acq ->
        sealAndTransmit(acq, 10_000).also { f ->
            ack(f, AckOutcome.RECEIVED, sha = "0".repeat(64))
            receive(acq)
        }
    }

    fun damagedResult() = fixture("damagedResult") { acq ->
        sealAndTransmit(acq, 10_000).also { f ->
            val name = result(f, 1, listOf(1 to LineResult.ACCEPTED))
            val full = Files.readAllBytes(server.outbox.resolve(name))
            Files.write(server.outbox.resolve(name), full.copyOf(full.size - 7))
            ack(f, AckOutcome.RECEIVED)
            receive(acq)
        }
    }

    fun awaiting() = fixture("awaiting") { acq ->
        sealAndTransmit(acq, 10_000).also { f ->
            ack(f, AckOutcome.RECEIVED)
            receive(acq)
        }
    }

    fun fileRejected() = fixture("fileRejected") { acq ->
        sealAndTransmit(acq, 10_000, 20_000).also { f ->
            ack(f, AckOutcome.FILE_REJECTED, reason = "1001")
            receive(acq)
        }
    }

    fun sealedOnly() = fixture("sealedOnly") { acq ->
        authorize(acq, 10_000)
        seal(acq)
    }

    /** 리뷰 MINOR-4(a): 결과 완료(COMPLETE) 뒤에 온 결과 응답이 격리된 파일 — RESPONSE_QUARANTINED가 **유일한** 사유(다른 사유와 겹치지 않는다). */
    fun quarantinedAfterComplete() = fixture("quarantinedAfterComplete") { acq ->
        sealAndTransmit(acq, 10_000).also { f ->
            ack(f, AckOutcome.RECEIVED)
            result(f, 1, listOf(1 to LineResult.ACCEPTED))
            receive(acq)
            val name = result(f, 2, listOf(1 to LineResult.ACCEPTED))
            val full = Files.readAllBytes(server.outbox.resolve(name))
            Files.write(server.outbox.resolve(name), full.copyOf(full.size - 7))
            receive(acq, receivedAt.plusSeconds(120))
        }
    }

    /** 리뷰 MINOR-4(b): 하루 앞 업무일(2026-10-19) 파일 — keyset의 업무일 정렬(내림차순)을 파일 ID 정렬과 구별한다. 기간 검색 `period`(10-20 하루)에는 들지 않는다. */
    fun previousDay() = fixture("previousDay") { acq ->
        authorize(acq, 10_000, approvedAt.minus(Duration.ofDays(1)))
        seal(acq, day.minusDays(1))
    }

    fun all(): Map<String, String> {
        completed(); rejectedRecord(); unknownAfterDeadline(); conflict(); transmissionUnknown(); transmissionQuarantined(); receiptConflict(); damagedResult()
        awaiting(); fileRejected(); sealedOnly(); quarantinedAfterComplete(); previousDay()
        return fixtures
    }

    val period = AcquisitionFileSearchCriteria(businessDateFrom = day, businessDateTo = day, limit = 50)
    fun byId() = query.search(period).items.associateBy { it.summary.fileId }

    Given("the Named Fixture files of business day 2026-10-20 (one stored state each, real seal/transmit/receive/close services)") {
        val ids = all()

        Then("the attention reasons and the re-acquisition marker are exactly the ones each stored state calls for (§4.4, I-5)") {
            val items = byId()
            val expected = mapOf(
                "completed" to (emptyList<AcquisitionAttention>() to false),
                "rejectedRecord" to (emptyList<AcquisitionAttention>() to true),
                "unknownAfterDeadline" to (listOf(AcquisitionAttention.RECORD_UNKNOWN, AcquisitionAttention.RESULT_DEADLINE_PASSED) to false),
                "conflict" to (listOf(AcquisitionAttention.RECORD_CONFLICT) to false),
                "transmissionUnknown" to (listOf(AcquisitionAttention.TRANSMISSION_UNKNOWN) to false),
                "transmissionQuarantined" to (listOf(AcquisitionAttention.TRANSMISSION_QUARANTINED) to false),
                "receiptConflict" to (listOf(AcquisitionAttention.RECEIPT_CONFLICT, AcquisitionAttention.RESPONSE_QUARANTINED, AcquisitionAttention.RESULT_OVERDUE) to false),
                "damagedResult" to (listOf(AcquisitionAttention.RESPONSE_QUARANTINED, AcquisitionAttention.RESULT_OVERDUE) to false),
                "awaiting" to (listOf(AcquisitionAttention.RESULT_OVERDUE) to false),
                "fileRejected" to (emptyList<AcquisitionAttention>() to true),
                "sealedOnly" to (emptyList<AcquisitionAttention>() to false),
                "quarantinedAfterComplete" to (listOf(AcquisitionAttention.RESPONSE_QUARANTINED) to false),
            )
            expected.forEach { (name, want) ->
                withClue(name) {
                    val item = items.getValue(ids.getValue(name))
                    (item.attention to item.reacquisitionCandidate) shouldBe want
                }
            }
            items.getValue(ids.getValue("completed")).summary.let {
                listOf(it.transmissionState, it.receiptState, it.resultState) shouldBe listOf("TRANSMITTED", "RECEIVED", "COMPLETE")
                it.recordStatusCounts.accepted shouldBe 2
                it.netAmountMinor shouldBe 30_000
                it.transmittedAt shouldBe transmitAt
            }
            items.getValue(ids.getValue("fileRejected")).summary.recordStatusCounts.fileRejected shouldBe 2
        }

        Then("needsAttention = true returns exactly the files whose computed reasons are not empty (the SQL filter and the Use Case use one definition)") {
            val all = byId()
            val flagged = query.search(period.copy(needsAttention = true)).items.map { it.summary.fileId }.toSet()
            flagged shouldBe all.filterValues { it.attention.isNotEmpty() }.keys
        }

        Then("filters match exactly: acquirer, environment and each axis state; the exact file lookup ignores the period") {
            val acq = AcquisitionFileId.parse(ids.getValue("conflict")).acquirerCode
            query.search(period.copy(acquirerCode = acq)).items.map { it.summary.fileId } shouldBe listOf(ids.getValue("conflict"))
            query.search(period.copy(acquirerCode = acq, environment = "TEST")).items.size shouldBe 1
            query.search(period.copy(acquirerCode = acq, environment = "LIVE")).items.size shouldBe 0
            query.search(period.copy(environment = "LIVE")).items.size shouldBe 0
            query.search(period.copy(transmissionState = "TRANSMISSION_UNKNOWN")).items.map { it.summary.fileId } shouldBe listOf(ids.getValue("transmissionUnknown"))
            query.search(period.copy(receiptState = "FILE_REJECTED")).items.map { it.summary.fileId } shouldBe listOf(ids.getValue("fileRejected"))
            query.search(period.copy(resultState = "DEADLINE_PASSED")).items.map { it.summary.fileId } shouldBe listOf(ids.getValue("unknownAfterDeadline"))
            query.search(AcquisitionFileSearchCriteria(fileId = ids.getValue("awaiting"))).items.map { it.summary.fileId } shouldBe listOf(ids.getValue("awaiting"))
            query.search(AcquisitionFileSearchCriteria(fileId = "ACQ.SFTPV99.TEST.20261020.001")).items shouldBe emptyList()
            query.search(AcquisitionFileSearchCriteria(businessDateFrom = day.plusDays(1), businessDateTo = day.plusDays(1))).items.none { it.summary.fileId in ids.values } shouldBe true
            query.search(AcquisitionFileSearchCriteria(businessDateFrom = day.minusDays(30), businessDateTo = day)).items.map { it.summary.fileId }.toSet().containsAll(ids.values) shouldBe true
        }

        Then("keyset pages (latest business day first, ties on the day broken by fileId descending) never skip or repeat a file") {
            // 두 업무일(10-19·10-20): 하루 앞 파일은 파일 ID 문자열 순서로는 중간이지만 업무일 내림차순이라 맨 끝이다(리뷰 MINOR-4(b)).
            val twoDays = period.copy(businessDateFrom = day.minusDays(1))
            val summaries = query.search(twoDays).items.map { it.summary }
            val full = summaries.map { it.fileId }
            full shouldBe summaries.sortedWith(compareByDescending<com.paycore.financial.acquiring.application.provided.AcquisitionFileSummary> { it.businessDate }
                .thenByDescending { it.fileId }).map { it.fileId }
            full.last() shouldBe ids.getValue("previousDay")
            (full.sortedDescending() == full) shouldBe false
            val paged = mutableListOf<String>()
            var cursor: AcquisitionFileCursor? = null
            do {
                val page = query.search(twoDays.copy(limit = 3, after = cursor))
                (page.items.size <= 3) shouldBe true
                paged += page.items.map { it.summary.fileId }
                cursor = page.nextCursor
            } while (cursor != null)
            paged shouldContainExactly full
        }

        Then("the record status computed in SQL equals RecordResultRule.status for every record of every fixture file") {
            ids.values.forEach { fileId ->
                val entries = transactionManager.let { org.springframework.transaction.support.TransactionTemplate(it).execute { results.entries(AcquisitionFileId.parse(fileId)) } }
                val page = query.records(fileId, AcquisitionRecordSearchCriteria(limit = 50))!!
                page.items.size shouldBe recordsOf(fileId).size
                page.items.forEach { r ->
                    withClue("$fileId#${r.seq}") { r.status shouldBe RecordResultRule.status(entries[r.seq].orEmpty()).name }
                }
            }
            val conflict = query.records(ids.getValue("conflict"), AcquisitionRecordSearchCriteria())!!.items.single()
            conflict.status shouldBe "CONFLICT"
            conflict.results.map { listOf(it.result, it.observedResult, it.source, it.responseRef?.kind, it.responseRef?.sequenceNo) } shouldBe listOf(
                listOf("ACCEPTED", null, "RESULT_FILE", "RESULT", 1), listOf("CONFLICT", "REJECTED", "RESULT_FILE", "RESULT", 2),
            )
            query.records(ids.getValue("unknownAfterDeadline"), AcquisitionRecordSearchCriteria())!!.items.single().results.single().let {
                listOf(it.result, it.source, it.responseRef) shouldBe listOf("UNKNOWN", "DEADLINE", null)
            }
            // 리뷰 MINOR-2: 결과 행의 응답 참조는 명세 §4.3 모양 `responseRef {kind, sequenceNo} | null`(B2 ADMIN 허용 모양 계약).
            fun resultsJson(fileId: String) = json.readTree(json.writeValueAsString(AdminAcquisitionJson.records(query.records(fileId, AcquisitionRecordSearchCriteria())!!)))
                .get("items").first().get("results")
            resultsJson(ids.getValue("conflict")).first().let { r ->
                r.properties().map { it.key } shouldBe listOf("entryNo", "result", "observedResult", "reasonCode", "source", "responseRef", "recognizedAt")
                r.get("responseRef").properties().map { it.key } shouldBe listOf("kind", "sequenceNo")
                (r.get("responseRef").get("kind").asString() to r.get("responseRef").get("sequenceNo").asInt()) shouldBe ("RESULT" to 1)
            }
            resultsJson(ids.getValue("unknownAfterDeadline")).first().get("responseRef").isNull shouldBe true
        }

        Then("record pages filter by status and page by seq; a missing file is null") {
            val f = ids.getValue("rejectedRecord")
            query.records(f, AcquisitionRecordSearchCriteria(status = "REJECTED"))!!.items.map { it.status } shouldBe listOf("REJECTED")
            query.records(f, AcquisitionRecordSearchCriteria(status = "UNKNOWN"))!!.items shouldBe emptyList()
            val first = query.records(f, AcquisitionRecordSearchCriteria(limit = 1))!!
            first.items.size shouldBe 1
            val second = query.records(f, AcquisitionRecordSearchCriteria(afterSeq = first.nextCursor!!, limit = 1))!!
            second.items.single().seq shouldBe first.items.single().seq + 1
            second.nextCursor.shouldBeNull()
            query.records("ACQ.SFTPV99.TEST.20261020.001", AcquisitionRecordSearchCriteria()).shouldBeNull()
        }

        Then("the detail: file summary, seal evidence, attempt history, response history and the first record page with results") {
            val f = ids.getValue("completed")
            val d = query.detail(f)!!
            d.file.summary.fileId shouldBe f
            d.cutoffPolicyVersion shouldBe "acq-cutoff-demo-1"
            d.cutoffAt shouldBe Instant.parse("2026-10-20T14:00:00Z")
            (d.contentSha256 to d.contentBytes.toLong()) shouldBe sealed(f)
            d.attempts.total shouldBe 1
            d.attempts.latest.single().let { listOf(it.attemptNo, it.resumeState, it.outcome, it.evidence, it.resultingState) } shouldBe
                listOf(1, "SEALED", "DELIVERED", "RENAMED_AND_VERIFIED", "TRANSMITTED")
            d.responses.total shouldBe 2
            d.responses.latest.map { listOf(it.kind, it.sequenceNo, it.disposition, it.evidence) } shouldBe listOf(
                listOf("RESULT", 1, "APPLIED", "RESULT_HASH_MATCH"), listOf("ACK", 1, "APPLIED", "ACK_HASH_MATCH"),
            )
            d.records.items.map { it.status } shouldBe listOf("ACCEPTED", "ACCEPTED")
            d.records.items.map { it.recordAmountMinor }.toSet() shouldBe setOf(10_000L, 20_000L)
            d.records.items.first().let {
                it.merchantAccountId shouldBe "demo-mid-001"
                it.recordType shouldBe "SALE"
                it.results.single().recognizedAt shouldBe receivedAt
            }
            query.detail("ACQ.SFTPV99.TEST.20261020.001").shouldBeNull()
            val rq = query.detail(ids.getValue("receiptConflict"))!!
            rq.responses.latest.single().let { listOf(it.kind, it.disposition, it.quarantineReason) } shouldBe listOf("ACK", "QUARANTINED", "HASH_MISMATCH")
            println("ACQ-VIEW-SAMPLE-DETAIL " + json.writeValueAsString(AdminAcquisitionJson.detail(d)))
            println("ACQ-VIEW-SAMPLE-LIST " + json.writeValueAsString(AdminAcquisitionJson.page(query.search(period.copy(limit = 3)))))
        }

        Then("numeric reason codes are read back as stored: a REJECTED result line `05`, an ACK FILE_REJECTED `1001` and the FILE_REJECTED records it closed (B2 review MAJOR-1)") {
            query.records(ids.getValue("rejectedRecord"), AcquisitionRecordSearchCriteria(status = "REJECTED"))!!.items.single().results.single().reasonCode shouldBe "05"
            val rejectedFile = query.detail(ids.getValue("fileRejected"))!!
            rejectedFile.responses.latest.single().let { listOf(it.kind, it.ackOutcome, it.ackReasonCode) } shouldBe listOf("ACK", "FILE_REJECTED", "1001")
            rejectedFile.records.items.flatMap { r -> r.results.map { listOf(r.status, it.source, it.reasonCode) } }.toSet() shouldBe
                setOf(listOf("FILE_REJECTED", "FILE_REJECTED", "1001"))
            println("ACQ-VIEW-SAMPLE-FILE-REJECTED " + json.writeValueAsString(AdminAcquisitionJson.detail(rejectedFile)))
        }

        Then("non-exposure control: D-ACQV-002 values, the sender id and the stored bytes are in the DB but not in any response (list, detail, record page)") {
            val f = ids.getValue("completed")
            val approval = rootColumn("SELECT approval_number FROM acquisition_file_record WHERE file_id = ?", f).first()
            val ptx = rootColumn("SELECT provider_transaction_id FROM acquisition_file_record WHERE file_id = ?", f).first()
            val issuer = rootColumn("SELECT issuer_code FROM acquisition_file_record WHERE file_id = ?", f).first()
            val sender = rootColumn("SELECT sender_id FROM acquisition_file WHERE file_id = ?", f).single()
            val content = String(FinancialDbAccounts.root(container).use { c -> c.scalar("SELECT content FROM acquisition_file_content WHERE file_id = ?", f) as ByteArray }, Charsets.US_ASCII)
            withClue("control: the values exist in FINANCIAL") {
                listOf(approval, ptx, issuer, sender).forEach { it.isNotBlank() shouldBe true }
                content shouldContain ptx
            }
            val bodies = listOf(
                json.writeValueAsString(AdminAcquisitionJson.detail(query.detail(f)!!)),
                json.writeValueAsString(AdminAcquisitionJson.page(query.search(period))),
                json.writeValueAsString(AdminAcquisitionJson.records(query.records(f, AcquisitionRecordSearchCriteria())!!)),
            )
            bodies.forEach { body ->
                listOf(approval, ptx, issuer, "\"$sender\"", "CREDIT", "LUMP_SUM", content.lineSequence().first()).forEach { body shouldNotContain it }
                listOf("approvalNumber", "providerTransactionId", "originalProviderTransactionId", "issuerCode", "cardType", "planKind", "installmentMonths", "senderId",
                    "content\"", "fileName").forEach { body shouldNotContain it }
            }
        }
    }

    Given("a result file committed between the detail's first statement and the rest (another connection)") {
        Then("every statement of one detail read runs in one read-only REPEATABLE READ transaction (no torn detail)") {
            val acq = acquirers.next()
            val f = sealAndTransmit(acq, 10_000)
            ack(f, AckOutcome.RECEIVED)
            receive(acq)
            result(f, 1, listOf(1 to LineResult.ACCEPTED))
            probe.arm("view_file_head") {
                CompletableFuture.supplyAsync { receive(acq, receivedAt.plusSeconds(60)) }.get(30, TimeUnit.SECONDS).recordsDecided shouldBe 1
            }
            val d = query.detail(f)!!
            probe.observed.get() shouldBe ViewSnapshotProbe.TxState("REPEATABLE-READ", 1)
            d.file.summary.resultState shouldBe "AWAITING"
            d.file.summary.recordStatusCounts.awaiting shouldBe 1
            d.records.items.single().let { (it.status to it.results) shouldBe ("AWAITING" to emptyList()) }
            d.responses.total shouldBe 1
            val after = query.detail(f)!!
            after.file.summary.resultState shouldBe "COMPLETE"
            after.records.items.single().status shouldBe "ACCEPTED"
            after.responses.total shouldBe 2
        }
    }

    Given("a JVM whose default time zone is Asia/Seoul (TZ-FIX-001)") {
        Then("business dates and times are the stored values (UTC wall clock, not shifted by 9 hours)") {
            val f = completed()
            val previous = TimeZone.getDefault()
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))
            try {
                val d = query.detail(f)!!
                d.file.summary.businessDate shouldBe day
                d.file.summary.sealedAt shouldBe sealAt
                d.file.summary.transmittedAt shouldBe transmitAt
                d.records.items.first().institutionTransactionDate shouldBe LocalDate.parse("2026-11-05")
                d.records.items.first().eventAt shouldBe approvedAt
                query.search(period).items.map { it.summary.businessDate }.toSet() shouldBe setOf(day)
            } finally {
                TimeZone.setDefault(previous)
            }
            sealAt.atOffset(ZoneOffset.UTC).toLocalDate() shouldBe day
        }
    }

    Given("the statements the reader sends") {
        Then("every statement carries the query time limit hint; the period search without an acquirer uses ix_acquisition_file_business_date, with one the lane UNIQUE (no full scan)") {
            val f = completed()
            probe.capture()
            query.search(period)
            val periodSql = probe.captured.toList()
            query.search(period.copy(acquirerCode = AcquisitionFileId.parse(f).acquirerCode, needsAttention = true))
            val laneSql = probe.captured.toList().drop(periodSql.size)
            query.detail(f)
            query.records(f, AcquisitionRecordSearchCriteria(status = "ACCEPTED"))
            val statements = probe.captured.toList()
            probe.disarm()
            (statements.size >= 6) shouldBe true
            statements.forEach { withClue(it.take(120)) { it.lowercase() shouldStartWith "select /*+ max_execution_time(2000) */" } }
            statements.forEach { sql ->
                listOf("approval_number", "provider_transaction_id", "issuer_code", "card_type", "plan_kind", "installment_months", "sender_id", "`content`", "file_name")
                    .forEach { col -> withClue(col) { sql shouldNotContain col } }
            }
            fun explainKey(sql: String): List<Pair<String, String?>> = FinancialDbAccounts.root(container).use { c ->
                c.createStatement().use { s ->
                    s.executeQuery("EXPLAIN $sql").use { rs -> buildList { while (rs.next()) add((rs.getString("table") ?: "") to rs.getString("key")) } }
                }
            }
            explainKey(periodSql.first()).first { it.first == "acquisition_file" }.second shouldBe "ix_acquisition_file_business_date"
            explainKey(laneSql.first()).first { it.first == "acquisition_file" }.second shouldBe "uk_acquisition_file_lane_date_version"
        }
        Then("a real MySQL 3024 (statement over its MAX_EXECUTION_TIME on the runtime account) is classified TimedOut (review MINOR-5)") {
            all()
            // 표 행마다 0.2초를 자는 SELECT에 50ms 상한 — 행이 10개 이상이라 상한을 반드시 넘는다. 실제 서버 오류가 3024인지 원인 사슬의 vendor code로 먼저 확인한다(대조군).
            val sql = "select /*+ MAX_EXECUTION_TIME(50) */ file_id from acquisition_file where sleep(0.2) = 0"
            val raw = shouldThrow<RuntimeException> { dsl.fetch(sql) }
            generateSequence<Throwable>(raw) { it.cause }.filterIsInstance<java.sql.SQLException>().map { it.errorCode }.toList().contains(3024) shouldBe true
            shouldThrow<AcquisitionViewFailure.TimedOut> { AcquisitionReadLimits.guard { dsl.fetch(sql) } }
        }
        Then("a MySQL query timeout (3024) is TimedOut and any other access failure is Unavailable (no message carried)") {
            shouldThrow<AcquisitionViewFailure.TimedOut> { AcquisitionReadLimits.guard { throw QueryTimeoutException("Query execution was interrupted, maximum statement execution time exceeded") } }
            shouldThrow<AcquisitionViewFailure.Unavailable> { AcquisitionReadLimits.guard { throw org.springframework.dao.DataAccessResourceFailureException("mid_demo") } }
                .message!! shouldNotContain "mid_demo"
            shouldThrow<AcquisitionViewFailure.Unavailable> { AcquisitionReadLimits.guard { throw org.jooq.exception.DataAccessException("x") } }
        }
        Then("the configured result deadline is the one the Use Case uses for RESULT_OVERDUE") {
            resultPolicy.resultDeadline shouldBe Duration.ofHours(1)
            reader.search(period, null, 50).isNotEmpty() shouldBe true
        }
    }

    Given("AC9 (ACQ-ADMIN-001 §5.1 L-9 — MINOR-6) the re-acquisition badge through a real re-acquisition: candidate → in progress → none once the new generation is ACCEPTED") {
        // 업무일 2026-10-18(이 Given 전용 — period 10-20·keyset 두 날 10-19·10-20 검색에 들지 않는다), lane = VIEW_ACQUIRERS 다음 것.
        val acq = acquirers.next()
        val d = day.minusDays(2)
        val executeAt = Instant.parse("2026-10-21T15:00:00Z")
        authorize(acq, 10_000, approvedAt.minus(Duration.ofDays(2)))
        val f0 = seal(acq, d)
        transmit(f0).outcome shouldBe Kind.TRANSMITTED
        result(f0, 1, listOf(1 to LineResult.REJECTED))
        receive(acq)

        fun badge(id: String) = query.search(AcquisitionFileSearchCriteria(fileId = id)).items.single().let { it.reacquisitionCandidate to it.reacquisitionInProgress }

        /** 동치 기준(I-9): 레코드마다 Domain 규칙(RecordResultRule.status + 표지·불요·더 높은 세대)으로 계산한 파일 배지. */
        fun domainBadge(id: String): com.paycore.financial.acquiring.domain.ReacquisitionBadge {
            val fileId = AcquisitionFileId.parse(id)
            val stages = org.springframework.transaction.support.TransactionTemplate(transactionManager).execute {
                val entries = results.entries(fileId)
                files.records(fileId).map { r ->
                    fun exists(sql: String, vararg args: Any) = (root(sql, *args) as Number).toLong() > 0
                    com.paycore.financial.acquiring.domain.ReacquisitionBadgeRule.recordStage(
                        RecordResultRule.status(entries[r.seq].orEmpty()),
                        accepted = exists("SELECT COUNT(*) FROM acquisition_accepted_record WHERE transaction_id = ? AND record_type = ?", r.transactionId, r.type.name),
                        notRequired = exists("SELECT COUNT(*) FROM acquisition_reacquisition_not_required WHERE transaction_id = ? AND record_type = ?", r.transactionId, r.type.name),
                        higherGeneration = exists(
                            "SELECT COUNT(*) FROM acquisition_file_record WHERE transaction_id = ? AND record_type = ? AND generation > ?", r.transactionId, r.type.name, r.generation,
                        ),
                    )
                }
            }
            return com.paycore.financial.acquiring.domain.ReacquisitionBadgeRule.fileBadge(stages)
        }

        fun state(id: String) = when (domainBadge(id)) {
            com.paycore.financial.acquiring.domain.ReacquisitionBadge.CANDIDATE -> true to false
            com.paycore.financial.acquiring.domain.ReacquisitionBadge.IN_PROGRESS -> false to true
            com.paycore.financial.acquiring.domain.ReacquisitionBadge.NONE -> false to false
        }

        val rejected = badge(f0) to state(f0)
        val approvedAt2 = executeAt.minusSeconds(10)
        val g1 = com.paycore.financial.acquiring.application.ReacquisitionExecutionService(
            transactionManager, files, lanes, results, membership, approvals, decisions, source, policies,
            object : com.paycore.financial.acquiring.application.required.ReacquisitionExecutionPolicy {
                override val maxExecutionWindow: Duration = Duration.ofHours(24)
                override val clockWindow: Duration = Duration.ofMinutes(5)
            },
            FixedClock(executeAt), integrityEvents,
        ).execute(
            com.paycore.financial.acquiring.domain.ReacquisitionCommand(
                approvalId = "apr_ac9_" + UUID.randomUUID().toString().replace("-", "").take(20), originalFileId = AcquisitionFileId.parse(f0),
                items = listOf(com.paycore.financial.acquiring.domain.ReacquisitionItem(1, 0, com.paycore.financial.acquiring.domain.ReacquisitionTargetStatus.REJECTED)),
                requestedBy = "adm_operator_1", approvedBy = "adm_approver_1", reasonCode = "INSTITUTION_REQUESTED", evidenceRef = "INQ-2026-0001",
                approvedAt = approvedAt2, executeBefore = approvedAt2.plus(Duration.ofHours(1)),
            ),
        ).let { (it as com.paycore.financial.acquiring.application.provided.ReacquisitionExecution.Decided).decision.resultFileId!!.value }
        val inProgress = listOf(badge(f0) to state(f0), badge(g1) to state(g1))
        AcquisitionTransmissionService(transactionManager, files, attempts, channel, transport, FixedClock(executeAt.plusSeconds(60))).transmit(g1).outcome shouldBe
            Kind.TRANSMITTED
        result(g1, 1, listOf(1 to LineResult.ACCEPTED), at = executeAt.plusSeconds(3_000))
        receive(acq, executeAt.plusSeconds(3_600))
        val accepted = listOf(badge(f0) to state(f0), badge(g1) to state(g1))

        Then("the original file is a candidate while nothing newer exists, in progress once generation 1 is sealed, and loses the badge when generation 1 is ACCEPTED") {
            rejected shouldBe ((true to false) to (true to false))
            inProgress shouldBe listOf((false to true) to (false to true), (false to false) to (false to false))
            accepted shouldBe listOf((false to false) to (false to false), (false to false) to (false to false))
        }
        Then("history is unchanged: the original record still reads REJECTED, the new one ACCEPTED (the badge is a display class only)") {
            query.records(f0, AcquisitionRecordSearchCriteria())!!.items.single().status shouldBe "REJECTED"
            query.records(g1, AcquisitionRecordSearchCriteria())!!.items.single().status shouldBe "ACCEPTED"
        }
    }
})

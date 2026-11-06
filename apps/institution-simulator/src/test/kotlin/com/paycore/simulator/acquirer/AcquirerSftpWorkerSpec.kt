package com.paycore.simulator.acquirer

import com.paycore.simulator.acquirer.adapter.integration.AcquirerSftpServer
import com.paycore.simulator.acquirer.application.provided.RunAcquirerWorkerUseCase
import com.paycore.simulator.acquirer.domain.AcknowledgementFileV1
import com.paycore.simulator.acquirer.domain.AcquirerFixture
import com.paycore.simulator.acquirer.domain.AcquirerFixture.cancel
import com.paycore.simulator.acquirer.domain.AcquirerFixture.sale
import com.paycore.simulator.acquirer.domain.AcquirerReceipt
import com.paycore.simulator.acquirer.domain.FileRejectionReason
import com.paycore.simulator.acquirer.domain.PlannedResultFile
import com.paycore.simulator.acquirer.domain.PlannedResultLine
import com.paycore.simulator.acquirer.domain.ReceiptOutcome
import com.paycore.simulator.acquirer.domain.ReceivedRecordType
import com.paycore.simulator.acquirer.domain.RecordResult
import com.paycore.simulator.acquirer.domain.ResultFileV1
import com.paycore.simulator.support.AcquirerSftpTestClient
import com.paycore.simulator.support.AcquirerTestConfig
import com.paycore.simulator.support.AcquirerTestEnvironment
import com.paycore.simulator.support.MutableSimulatorClock
import com.paycore.simulator.support.SimulatorDb
import com.paycore.simulator.support.SimulatorMySqlContainerConfig
import com.paycore.simulator.support.SimulatorTestConfig
import com.paycore.simulator.van.application.required.VanCancelLedgerStore
import com.paycore.simulator.van.application.required.VanLedgerStore
import com.paycore.simulator.van.domain.VanApproval
import com.paycore.simulator.van.domain.VanCancelEntry
import com.paycore.simulator.van.domain.VanCancelTerms
import com.paycore.simulator.van.domain.VanLedgerEntry
import com.paycore.simulator.van.domain.VanNamespace
import com.paycore.simulator.van.domain.VanPlan
import com.paycore.simulator.van.domain.VanUlid
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.apache.sshd.sftp.common.SftpConstants
import org.apache.sshd.sftp.common.SftpException
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.mysql.MySQLContainer
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

/**
 * 가상 매입사 SFTP·Worker 통합 시험(ACQUIRING-001 2-E §7·§9 — 실제 Simulator MySQL(Testcontainers, runtime 계정) + 실제 SFTP 왕복(시험 클라이언트 = sshd
 * `SshClient`/`SftpClient`, 임시 ECDSA 키)). Worker 주기는 끄고(PT1H) 시험이 runOnce를 부르며, 시각은 Simulator 가상 시계로 옮긴다.
 * 매입 파일 바이트는 FINANCIAL golden(같은 사본) 또는 시험 작성기(AcquirerFixture — golden과 같은 바이트임을 AG-2가 확인)로 만든다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = ["PAYCORE_SIMULATOR_PORT=0"])
@Import(SimulatorMySqlContainerConfig::class, SimulatorTestConfig::class, AcquirerTestConfig::class)
class AcquirerSftpWorkerSpec(
    private val server: AcquirerSftpServer,
    private val worker: RunAcquirerWorkerUseCase,
    private val clock: MutableSimulatorClock,
    private val ledger: VanLedgerStore,
    private val cancels: VanCancelLedgerStore,
    private val container: MySQLContainer,
    transactionManager: PlatformTransactionManager,
) : FunSpec({

    val env = AcquirerTestEnvironment
    val tx = TransactionTemplate(transactionManager)
    val seq = AtomicInteger(500)
    val vanAt = Instant.parse("2026-11-05T00:00:00.000Z")
    val outbox = env.root.resolve("SIMACQA").resolve("outbox")
    val inbox = env.root.resolve("SIMACQA").resolve("inbox")
    afterTest { clock.reset() }

    fun clientA() = AcquirerSftpTestClient.connect(server.port, AcquirerTestEnvironment.USER_A, env.clientA, env.hostKey.public)
    fun ulid(prefix: String) = prefix + VanUlid.format(vanAt, seq.incrementAndGet())

    fun seedAuthorization(ptx: String, amount: Long, approval: String = AcquirerFixture.APPROVAL, date: LocalDate = AcquirerFixture.BUSINESS_DATE): VanLedgerEntry {
        val ns = VanNamespace(AcquirerTestEnvironment.VAN_CLIENT, "TEST", "SIMMERCH0001", VanNamespace.AUTHORIZE, ulid("ar_"))
        val entry = VanLedgerEntry.approved(ns, ulid("ad_"), "0".repeat(64), "ev-$ptx", null,
            VanApproval(approval, ptx, amount, "KRW", VanPlan.LUMP_SUM, "CREDIT", date, date), vanAt)
        tx.executeWithoutResult { ledger.insert(entry) }
        return entry
    }

    fun seedCancel(original: VanLedgerEntry, cancelPtx: String, amount: Long, sequence: Int = 1) {
        val ns = VanNamespace(AcquirerTestEnvironment.VAN_CLIENT, "TEST", "SIMMERCH0001", VanNamespace.CANCEL, ulid("cr_"))
        val approval = checkNotNull(original.approval)
        val entry = VanCancelEntry.approved(ns, ulid("cd_"), "1".repeat(64), original.namespace.requestId, null,
            VanCancelTerms(null, cancelPtx, approval.providerTransactionId, amount, "KRW", approval.institutionTransactionDate, approval.institutionTransactionDate),
            sequence, vanAt)
        tx.executeWithoutResult { cancels.insert(entry) }
    }

    fun rows(sql: String, vararg args: Any): List<List<Any?>> =
        DriverManager.getConnection(SimulatorDb.url(container), "root", container.password).use { c ->
            c.prepareStatement(sql).use { st ->
                args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
                st.executeQuery().use { rs -> buildList { while (rs.next()) add((1..rs.metaData.columnCount).map { rs.getObject(it) }) } }
            }
        }

    fun receipts(fileId: String) = rows(
        "SELECT receipt_ordinal, outcome, reason_code, record_count FROM acquirer_received_file WHERE acquirer_code = 'SIMACQA' AND file_id = ? ORDER BY receipt_ordinal",
        fileId,
    ).map { it.joinToString("|") { v -> v?.toString().orEmpty() } }

    fun decisions(fileId: String) = rows(
        "SELECT d.record_seq, d.decision, d.reason_code, d.scenario FROM acquirer_record_decision d JOIN acquirer_received_file r ON r.receipt_id = d.receipt_id " +
            "WHERE r.acquirer_code = 'SIMACQA' AND r.file_id = ? ORDER BY d.record_seq",
        fileId,
    ).map { it.joinToString(":") { v -> v?.toString().orEmpty() } }

    fun outboxNames(fileId: String): List<String> = Files.list(outbox).use { s -> s.map { it.fileName.toString() }.filter { fileId in it }.sorted().toList() }

    /** 결과 파일의 R 줄 → "recordSeq:result:reason". */
    fun resultLines(name: String): List<String> = String(Files.readAllBytes(outbox.resolve(name)), Charsets.US_ASCII).lines().filter { it.startsWith("R|") }
        .map { l -> l.split("|").let { "${it[1]}:${it[4]}:${it[5]}" } }

    fun upload(fileId: String, content: ByteArray) = clientA().use { it.upload("$fileId.dat", content) }

    test("AS-1 only public-key logins of the configured account succeed: password, a stranger key and another acquirer's key are refused") {
        clientA().use { it.list("/inbox") }
        AcquirerSftpTestClient.tryPassword(server.port, AcquirerTestEnvironment.USER_A, "any-password", env.hostKey.public).isFailure shouldBe true
        shouldThrow<Exception> { AcquirerSftpTestClient.connect(server.port, AcquirerTestEnvironment.USER_A, env.stranger, env.hostKey.public).close() }
        shouldThrow<Exception> { AcquirerSftpTestClient.connect(server.port, AcquirerTestEnvironment.USER_B, env.clientA, env.hostKey.public).close() }
        // 기대한 host key가 아니면 클라이언트가 접속을 거부한다(host key는 설정 파일의 키 — 서버가 다른 키를 만들어 쓰지 않는다).
        shouldThrow<Exception> { AcquirerSftpTestClient.connect(server.port, AcquirerTestEnvironment.USER_A, env.clientA, env.stranger.public).close() }
        // 리뷰 m-1: 서버 알고리즘 allowlist(FINANCIAL 클라이언트와 같은 집합) — 목록 밖 암호만 제안하는 클라이언트는 KEX에서 끊긴다.
        shouldThrow<Exception> {
            AcquirerSftpTestClient.connect(server.port, AcquirerTestEnvironment.USER_A, env.clientA, env.hostKey.public,
                ciphers = listOf(org.apache.sshd.common.cipher.BuiltinCiphers.cc20p1305_openssh)).close()
        }
        // 리뷰 m-1: 계정당 동시 세션 상한 4(Demo) — 다섯째 로그인은 거부된다.
        val open = (1..4).map { clientA() }
        try {
            shouldThrow<Exception> { clientA().close() }
        } finally {
            open.forEach { it.close() }
        }
        clientA().close()
    }

    test("AS-2 chroot and file policy: no final-name or outbox writes, no delete/mkdir/link/setstat, rename only a temp name to its own final name without overwrite, no escape") {
        val id = "ACQ.SIMACQA.TEST.20261031.001"
        val t1 = AcquirerSftpTestClient.temp("$id.dat", 1)
        Files.writeString(outbox.resolve(".ACK.$id.01.dat.tmp"), "publishing\n")
        clientA().use { c ->
            fun denied(block: () -> Unit) = shouldThrow<SftpException>(block).status shouldBe SftpConstants.SSH_FX_PERMISSION_DENIED
            denied { c.write("/inbox/$id.dat", "x".toByteArray()) }
            denied { c.write("/outbox/$id.dat", "x".toByteArray()) }
            denied { c.write("/outbox/.$id.dat.1.part", "x".toByteArray()) }
            denied { c.write("/$id.dat", "x".toByteArray()) }
            denied { c.read("/outbox/.ACK.$id.01.dat.tmp") }
            denied { c.sftp.mkdir("/inbox/sub") }
            denied { c.list("/") }
            c.write(t1, "x".toByteArray())
            denied { c.sftp.setStat(t1, org.apache.sshd.sftp.client.SftpClient.Attributes().perms(0b111_111_111)) }
            denied { c.sftp.rename(t1, "/inbox/ACQ.SIMACQA.TEST.20261031.002.dat") }
            denied { c.sftp.rename(t1, "/outbox/$id.dat") }
            denied { c.sftp.remove(t1) }
            denied { c.sftp.symLink("/inbox/link.dat", t1) }
            c.sftp.rename(t1, "/inbox/$id.dat")
            c.write(t1, "y".toByteArray())
            shouldThrow<SftpException> { c.sftp.rename(t1, "/inbox/$id.dat") }
            String(c.read("/inbox/$id.dat")) shouldBe "x"
            denied { c.sftp.remove("/inbox/$id.dat") }
            denied { c.sftp.setStat("/inbox/$id.dat", org.apache.sshd.sftp.client.SftpClient.Attributes().perms(0b111_111_111)) }
            shouldThrow<SftpException> { c.read("/../../../../etc/hostname") }
            shouldThrow<SftpException> { c.read("/inbox/../../SIMACQB/inbox/x") }
        }
        AcquirerSftpTestClient.connect(server.port, AcquirerTestEnvironment.USER_B, env.clientB, env.hostKey.public).use { b ->
            b.list("/inbox") shouldNotContain "$id.dat"
        }
        String(Files.readAllBytes(inbox.resolve("$id.dat"))) shouldBe "x"
    }

    test("AS-3 FINANCIAL golden files go through SFTP: .part is ignored, the renamed file is received and the ACK/result bytes equal the ACK·result golden") {
        val golden = JsonMapper.builder().build().readTree(AcquirerSftpWorkerSpec::class.java.getResourceAsStream("/contracts/acquiring-file-v1.golden.json"))
        val out = JsonMapper.builder().build().readTree(AcquirerSftpWorkerSpec::class.java.getResourceAsStream("/contracts/acquiring-ack-result-v1.golden.json"))
        fun content(name: String) = golden.get("vectors").first { it.get("name").asString() == name }.get("expected").get("content").asString().toByteArray(Charsets.US_ASCII)
        fun expected(kind: String, name: String) = out.get(kind).first { it.get("name").asString() == name }.get("expected").let {
            it.get("fileName").asString() to it.get("content").asString()
        }
        // 레코드 1(ptx_auth_b): VAN 승인 20000 + 승인된 취소 5000(상계 근거) → ACCEPTED. 레코드 2(ptx_auth_a): VAN 승인액 31000 ≠ 30000 → AMOUNT_MISMATCH.
        val b = seedAuthorization("VAN-T-ptx_auth_b", 20_000)
        seedCancel(b, "VAN-C-ptx_cancel_b", 5_000)
        seedAuthorization("VAN-T-ptx_auth_a", 31_000)
        val id = "ACQ.SIMACQA.TEST.20261105.001"
        clientA().use { it.write(AcquirerSftpTestClient.temp("$id.dat", 1), content("regular-two-sales-one-netted")) }
        worker.runOnce()
        receipts(id) shouldBe emptyList()

        clock.set(Instant.parse("2026-11-05T14:06:01.250Z"))
        clientA().use { it.sftp.rename(AcquirerSftpTestClient.temp("$id.dat", 1), "/inbox/$id.dat") }
        worker.runOnce()
        receipts(id) shouldBe listOf("1|RECEIVED||2")
        decisions(id) shouldBe listOf("1:ACCEPTED::", "2:REJECTED:AMOUNT_MISMATCH:")
        val (ackName, ackContent) = expected("acks", "received-regular")
        clientA().use { c ->
            c.list("/outbox") shouldContain ackName
            String(c.read("/outbox/$ackName"), Charsets.US_ASCII) shouldBe ackContent
        }
        outboxNames(id) shouldBe listOf(ackName)

        clock.set(Instant.parse("2026-11-05T14:06:31.250Z"))
        worker.runOnce()
        val (resName, resContent) = expected("results", "regular-one-accepted-one-rejected")
        String(Files.readAllBytes(outbox.resolve(resName)), Charsets.US_ASCII) shouldBe resContent

        val empty = "ACQ.SIMACQA.TEST.20261106.001"
        clock.set(Instant.parse("2026-11-06T14:06:00.000Z"))
        upload(empty, content("empty-business-day"))
        worker.runOnce()
        clock.set(Instant.parse("2026-11-06T14:06:30.000Z"))
        worker.runOnce()
        listOf(expected("acks", "received-empty-day"), expected("results", "empty-business-day")).forEach { (name, text) ->
            String(Files.readAllBytes(outbox.resolve(name)), Charsets.US_ASCII) shouldBe text
        }
        rows("SELECT state, COUNT(*) FROM acquirer_work_item w JOIN acquirer_received_file r ON r.receipt_id = w.receipt_id WHERE r.file_id IN (?, ?) GROUP BY state",
            id, empty).map { "${it[0]}=${it[1]}" } shouldBe listOf("PUBLISHED=4")
        rows("SELECT COUNT(*) FROM acquirer_outbound_file WHERE file_name LIKE ? OR file_name LIKE ?", "%$id%", "%$empty%").single().single() shouldBe 4L
    }

    test("AS-4 same file id: re-scans and the same bytes re-uploaded are idempotent; different bytes are RECEIPT_CONFLICT with ACK 02, records untouched") {
        val id = "ACQ.SIMACQA.TEST.20261102.001"
        val first = AcquirerFixture.file(id, listOf(sale(1, "t4-a", 7_000)))
        upload(id, first)
        worker.runOnce().newReceipts shouldBe 1
        worker.runOnce().newReceipts shouldBe 0
        // 저장소 유실을 흉내 낸다(최종 파일은 SFTP로 지울 수 없다 — 호스트에서 지운다). 같은 바이트 재업로드는 새 기록이 없다.
        Files.delete(inbox.resolve("$id.dat"))
        upload(id, first)
        worker.runOnce().newReceipts shouldBe 0
        Files.delete(inbox.resolve("$id.dat"))
        val other = AcquirerFixture.file(id, listOf(sale(1, "t4-a", 7_001)))
        upload(id, other)
        worker.runOnce().newReceipts shouldBe 1
        worker.runOnce().newReceipts shouldBe 0
        receipts(id) shouldBe listOf("1|RECEIVED||1", "2|RECEIPT_CONFLICT|CONTENT_MISMATCH|")
        decisions(id) shouldBe listOf("1:REJECTED:NO_MATCHING_AUTHORIZATION:")
        val ack2 = String(Files.readAllBytes(outbox.resolve("ACK.$id.02.dat")), Charsets.US_ASCII).lines().first()
        ack2.split("|").subList(3, 9) shouldBe listOf("02", "RECEIPT_CONFLICT", "CONTENT_MISMATCH", AcquirerFixture.sha256(other), other.size.toString(), "")
    }

    test("AS-5 a body changed under its trailer is FILE_REJECTED(BODY_HASH_MISMATCH): ACK only, no record judgement, no result file") {
        val id = "ACQ.SIMACQA.TEST.20261103.001"
        val tampered = String(AcquirerFixture.file(id, listOf(sale(1, "t5-a", 9_000))), Charsets.US_ASCII).replace("|9000|0|9000|", "|9001|0|9001|")
        upload(id, tampered.toByteArray(Charsets.US_ASCII))
        val t0 = clock.now()
        worker.runOnce()
        clock.set(clock.now().plus(Duration.ofHours(1)))
        worker.runOnce()
        receipts(id) shouldBe listOf("1|FILE_REJECTED|BODY_HASH_MISMATCH|")
        decisions(id) shouldBe emptyList()
        outboxNames(id) shouldBe listOf("ACK.$id.01.dat")
        String(Files.readAllBytes(outbox.resolve("ACK.$id.01.dat")), Charsets.US_ASCII) shouldBe String(
            AcknowledgementFileV1.render(
                AcquirerReceipt("SIMACQA", id, 1, AcquirerFixture.sha256(tampered.toByteArray()), tampered.length.toLong(), null, ReceiptOutcome.FILE_REJECTED,
                    FileRejectionReason.BODY_HASH_MISMATCH, t0),
            ),
            Charsets.US_ASCII,
        )
    }

    test("AS-6 record scenarios: REJECT by amount, WITHHOLD (judged, never published), DELAY (later file), DUPLICATE and CONFLICTING (echo file)") {
        val id = "ACQ.SIMACQA.TEST.20261104.001"
        val txns = listOf("t6-ok", "t6-reject", "sc-withhold", "sc-delay", "sc-dup", "sc-conflict")
        val records = txns.mapIndexed { i, t ->
            val amount = if (t == "t6-reject") AcquirerTestEnvironment.REJECT_AMOUNT else 10_000L
            seedAuthorization("VAN-T-$t", amount)
            sale(i + 1, t, amount)
        }
        upload(id, AcquirerFixture.file(id, records))
        val t0 = clock.now()
        worker.runOnce()
        decisions(id) shouldBe listOf(
            "1:ACCEPTED::", "2:REJECTED:DEMO_REJECTED:REJECT", "3:ACCEPTED::WITHHOLD_RESULT", "4:ACCEPTED::DELAY_RESULT", "5:ACCEPTED::DUPLICATE_RESULT",
            "6:ACCEPTED::CONFLICTING_RESULT",
        )
        clock.set(t0.plusSeconds(30))
        worker.runOnce()
        outboxNames(id) shouldBe listOf("ACK.$id.01.dat", "RES.$id.01.dat", "RES.$id.02.dat")
        resultLines("RES.$id.01.dat") shouldBe listOf("1:ACCEPTED:", "2:REJECTED:DEMO_REJECTED", "5:ACCEPTED:", "6:ACCEPTED:")
        resultLines("RES.$id.02.dat") shouldBe listOf("5:ACCEPTED:", "6:REJECTED:SCENARIO_CONFLICT")
        clock.set(t0.plusSeconds(30).plus(Duration.ofHours(2)))
        worker.runOnce()
        resultLines("RES.$id.03.dat") shouldBe listOf("4:ACCEPTED:")
        clock.set(t0.plus(Duration.ofDays(3)))
        worker.runOnce()
        outboxNames(id).size shouldBe 4
        outboxNames(id).flatMap { if (it.startsWith("RES.")) resultLines(it) else emptyList() }.filter { it.startsWith("3:") } shouldBe emptyList()
    }

    test("AS-7 file scenarios: DROP_ACK publishes results without ACK, DELAY_ACK publishes the ACK late, FILE_REJECT answers SCENARIO_FILE_REJECTED") {
        val files = AcquirerTestEnvironment.ScenarioFiles
        listOf(files.DROP_ACK, files.DELAY_ACK, files.FILE_REJECT).forEachIndexed { i, id -> upload(id, AcquirerFixture.file(id, listOf(sale(1, "t7-$i", 4_000)))) }
        val t0 = clock.now()
        worker.runOnce()
        clock.set(t0.plusSeconds(30))
        worker.runOnce()
        outboxNames(files.DROP_ACK) shouldBe listOf("RES.${files.DROP_ACK}.01.dat")
        outboxNames(files.DELAY_ACK) shouldBe listOf("RES.${files.DELAY_ACK}.01.dat")
        outboxNames(files.FILE_REJECT) shouldBe listOf("ACK.${files.FILE_REJECT}.01.dat")
        receipts(files.FILE_REJECT) shouldBe listOf("1|FILE_REJECTED|SCENARIO_FILE_REJECTED|")
        decisions(files.FILE_REJECT) shouldBe emptyList()
        clock.set(t0.plus(Duration.ofHours(1)))
        worker.runOnce()
        outboxNames(files.DELAY_ACK) shouldBe listOf("ACK.${files.DELAY_ACK}.01.dat", "RES.${files.DELAY_ACK}.01.dat")
        outboxNames(files.DROP_ACK) shouldBe listOf("RES.${files.DROP_ACK}.01.dat")
        rows("SELECT COUNT(*) FROM acquirer_work_item w JOIN acquirer_received_file r ON r.receipt_id = w.receipt_id WHERE r.file_id = ? AND w.kind = 'ACK'",
            files.DROP_ACK).single().single() shouldBe 0L
    }

    test("AS-8 SFTP faults: upload cut before rename leaves no final file (retry succeeds); a dropped rename response still leaves the final file, received once") {
        val files = AcquirerTestEnvironment.ScenarioFiles
        val cut = AcquirerFixture.file(files.DISCONNECT, listOf(sale(1, "t8-cut", 3_000)))
        shouldThrow<Exception> { upload(files.DISCONNECT, cut) }
        Files.exists(inbox.resolve("${files.DISCONNECT}.dat")) shouldBe false
        worker.runOnce()
        receipts(files.DISCONNECT) shouldBe emptyList()
        clientA().use { it.upload("${files.DISCONNECT}.dat", cut, attempt = 2) }
        worker.runOnce()
        receipts(files.DISCONNECT) shouldBe listOf("1|RECEIVED||1")

        val dropped = AcquirerFixture.file(files.DROP_RENAME, listOf(sale(1, "t8-rename", 3_000)))
        shouldThrow<Exception> { upload(files.DROP_RENAME, dropped) }
        clientA().use { c -> c.read("/inbox/${files.DROP_RENAME}.dat").contentEquals(dropped) shouldBe true }
        worker.runOnce()
        worker.runOnce()
        receipts(files.DROP_RENAME) shouldBe listOf("1|RECEIVED||1")
    }

    test("AS-9 publication after a lost DB commit: the same bytes already at the final name are recorded, different bytes are never overwritten") {
        val same = "ACQ.SIMACQA.TEST.20261110.001"
        val different = "ACQ.SIMACQA.TEST.20261111.001"
        val sameBytes = AcquirerFixture.file(same, listOf(sale(1, "t9-same", 2_000)))
        upload(same, sameBytes)
        upload(different, AcquirerFixture.file(different, listOf(sale(1, "t9-diff", 2_000))))
        val t0 = clock.now()
        worker.runOnce()
        val receipt = AcquirerReceipt("SIMACQA", same, 1, AcquirerFixture.sha256(sameBytes), sameBytes.size.toLong(), 1, ReceiptOutcome.RECEIVED, null, t0)
        val expected = ResultFileV1.render(receipt, PlannedResultFile(1, t0.plusSeconds(30),
            listOf(PlannedResultLine(1, "t9-same", ReceivedRecordType.SALE, RecordResult.REJECTED, "NO_MATCHING_AUTHORIZATION", 2_000))))
        Files.write(outbox.resolve("RES.$same.01.dat"), expected)
        Files.writeString(outbox.resolve("RES.$different.01.dat"), "planted\n")
        clock.set(t0.plusSeconds(30))
        val report = worker.runOnce()
        (report.publishRefused >= 1) shouldBe true
        fun state(fileId: String) = rows("SELECT w.state FROM acquirer_work_item w JOIN acquirer_received_file r ON r.receipt_id = w.receipt_id " +
            "WHERE r.file_id = ? AND w.kind = 'RESULT'", fileId).single().single()
        state(same) shouldBe "PUBLISHED"
        state(different) shouldBe "PENDING"
        Files.readAllBytes(outbox.resolve("RES.$same.01.dat")).contentEquals(expected) shouldBe true
        Files.readString(outbox.resolve("RES.$different.01.dat")) shouldBe "planted\n"
        rows("SELECT content_sha256 FROM acquirer_outbound_file WHERE file_name = ?", "RES.$same.01.dat").single().single() shouldBe AcquirerFixture.sha256(expected)
    }

    test("AS-10 CANCEL after its SALE was accepted matches the VAN cancellation; an unknown original is ORIGINAL_NOT_ACQUIRED; a second SALE is DUPLICATE_RECORD") {
        val auth = seedAuthorization("VAN-T-t10-sale", 20_000)
        seedCancel(auth, "VAN-C-c10-cancel", 5_000)
        val saleFile = "ACQ.SIMACQA.TEST.20261112.001"
        upload(saleFile, AcquirerFixture.file(saleFile, listOf(sale(1, "t10-sale", 20_000))))
        worker.runOnce()
        decisions(saleFile) shouldBe listOf("1:ACCEPTED::")
        val cancelFile = "ACQ.SIMACQA.TEST.20261113.001"
        upload(cancelFile, AcquirerFixture.file(cancelFile, listOf(
            cancel(1, "c10-cancel", "t10-sale", 20_000, 5_000), cancel(2, "c10-orphan", "t10-never", 3_000, 1_000), sale(3, "t10-sale", 20_000),
        )))
        worker.runOnce()
        decisions(cancelFile) shouldBe listOf("1:ACCEPTED::", "2:REJECTED:ORIGINAL_NOT_ACQUIRED:", "3:REJECTED:DUPLICATE_RECORD:")
    }

    // 리뷰 MJ-1: 이중 매입 방어 기준 = 기관 근거(VAN 원장 행). 같은 VAN 승인을 다른 거래 ID로, 상계된 취소를 별도 CANCEL로, 같은 VAN 취소를 두 CANCEL로,
    // 수락된 원 SALE과 다른 원거래 참조의 CANCEL을 모두 거절한다(기존 사유 코드 재사용).
    test("AS-10b double acquisition is keyed on institution evidence: same VAN approval, netted cancel re-sent, same VAN cancel twice, CANCEL of another original") {
        val a = seedAuthorization("VAN-T-t14-a", 30_000)
        seedCancel(a, "VAN-C-c14-1", 5_000, sequence = 1)
        seedCancel(a, "VAN-C-c14-2", 2_000, sequence = 2)
        val other = seedAuthorization("VAN-T-t14-other", 9_000)
        seedCancel(other, "VAN-C-c14-x", 1_000)
        val saleFile = "ACQ.SIMACQA.TEST.20261114.001"
        upload(saleFile, AcquirerFixture.file(saleFile, listOf(sale(1, "t14-a", 30_000, 5_000))))
        worker.runOnce()
        decisions(saleFile) shouldBe listOf("1:ACCEPTED::")
        val f2 = "ACQ.SIMACQA.TEST.20261115.001"
        upload(f2, AcquirerFixture.file(f2, listOf(
            sale(1, "t14-dup", 30_000, ptx = "VAN-T-t14-a"),
            cancel(2, "c14-1", "t14-a", 30_000, 5_000, ptx = "VAN-C-c14-1", originalPtx = "VAN-T-t14-a"),
            cancel(3, "c14-2a", "t14-a", 30_000, 2_000, ptx = "VAN-C-c14-2", originalPtx = "VAN-T-t14-a"),
            cancel(4, "c14-2b", "t14-a", 30_000, 2_000, ptx = "VAN-C-c14-2", originalPtx = "VAN-T-t14-a"),
            cancel(5, "c14-x", "t14-a", 30_000, 1_000, ptx = "VAN-C-c14-x", originalPtx = "VAN-T-t14-other"),
        )))
        worker.runOnce()
        decisions(f2) shouldBe listOf(
            "1:REJECTED:DUPLICATE_RECORD:", "2:REJECTED:NETTED_CANCEL_MISMATCH:", "3:ACCEPTED::", "4:REJECTED:DUPLICATE_RECORD:",
            "5:REJECTED:CANCELLATION_MISMATCH:",
        )
    }

    // Coordinator 결정 (9) Q-2D-1: 시도별 임시명 `.{id}.dat.{n}.part`(n = 1~6자리, 앞 0 없음). 두 시도의 임시 파일이 따로 남고 하나만 최종명이 된다.
    test("AS-11 per-attempt temp names: two attempts keep separate temp files, only one becomes the final file; non-pair, legacy and malformed names are refused") {
        val id = "ACQ.SIMACQA.TEST.20261116.001"
        clientA().use { c ->
            fun denied(block: () -> Unit) = shouldThrow<SftpException>(block).status shouldBe SftpConstants.SSH_FX_PERMISSION_DENIED
            c.write(AcquirerSftpTestClient.temp("$id.dat", 1), "first".toByteArray())
            c.write(AcquirerSftpTestClient.temp("$id.dat", 2), "second".toByteArray())
            c.sftp.rename(AcquirerSftpTestClient.temp("$id.dat", 2), "/inbox/$id.dat")
            shouldThrow<SftpException> { c.sftp.rename(AcquirerSftpTestClient.temp("$id.dat", 1), "/inbox/$id.dat") }
            denied { c.sftp.rename(AcquirerSftpTestClient.temp("$id.dat", 1), "/inbox/ACQ.SIMACQA.TEST.20261117.001.dat") }
            String(c.read("/inbox/$id.dat")) shouldBe "second"
            String(c.read(AcquirerSftpTestClient.temp("$id.dat", 1))) shouldBe "first"
            denied { c.write("/inbox/.$id.dat.part", "legacy".toByteArray()) }
            denied { c.write("/inbox/.$id.dat.01.part", "x".toByteArray()) }
            denied { c.write("/inbox/.$id.dat.1234567.part", "x".toByteArray()) }
            c.write("/inbox/.$id.dat.999999.part", "max".toByteArray())
        }
    }

    // 리뷰 m-2·m-1: 쓰기 핸들이 열린 임시 파일은 rename할 수 없고, 임시 파일 쓰기는 16 MiB - 1을 넘을 수 없다.
    test("AS-12 an open write handle blocks the rename of its temp file; a write past the 16 MiB - 1 limit is refused") {
        val id = "ACQ.SIMACQA.TEST.20261030.001"
        val temp = AcquirerSftpTestClient.temp("$id.dat", 3)
        clientA().use { c ->
            val h = c.sftp.open(temp, org.apache.sshd.sftp.client.SftpClient.OpenMode.Write, org.apache.sshd.sftp.client.SftpClient.OpenMode.Create)
            c.sftp.write(h, 0, "partial".toByteArray())
            shouldThrow<SftpException> { c.sftp.rename(temp, "/inbox/$id.dat") }.status shouldBe SftpConstants.SSH_FX_PERMISSION_DENIED
            shouldThrow<SftpException> { c.sftp.write(h, 16L * 1024 * 1024 - 1, "xy".toByteArray()) }
            c.sftp.close(h)
            c.sftp.rename(temp, "/inbox/$id.dat")
            String(c.read("/inbox/$id.dat")) shouldBe "partial"
        }
    }

    // 리뷰 m-4: 파일 하나의 수신 실패(읽기 권한 없음)는 그 파일만 실패시키고 다른 파일의 수신·게시는 계속된다.
    test("AS-13 a failing inbox file is isolated: other files are still received and their ACKs published") {
        val broken = "ACQ.SIMACQA.TEST.20261118.001"
        val good = "ACQ.SIMACQA.TEST.20261119.001"
        val brokenPath = inbox.resolve("$broken.dat")
        Files.write(brokenPath, AcquirerFixture.file(broken, listOf(sale(1, "t13-b", 1_000))))
        Files.setPosixFilePermissions(brokenPath, java.nio.file.attribute.PosixFilePermissions.fromString("---------"))
        try {
            upload(good, AcquirerFixture.file(good, listOf(sale(1, "t13-g", 1_000))))
            worker.runOnce()
            receipts(broken) shouldBe emptyList()
            receipts(good) shouldBe listOf("1|RECEIVED||1")
            outboxNames(good) shouldBe listOf("ACK.$good.01.dat")
        } finally {
            Files.setPosixFilePermissions(brokenPath, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
            Files.delete(brokenPath)
        }
    }

    // 리뷰 m-6: 크기 상한 초과는 FILE_TOO_LARGE(ACK만), 같은 ID 다른 내용이 순번 상한(99)을 넘으면 기록·ACK 없음(Exhausted).
    test("AS-14 an oversized stored file is FILE_TOO_LARGE; a conflict beyond ACK sequence 99 records nothing") {
        val big = "ACQ.SIMACQA.TEST.20261120.001"
        Files.write(inbox.resolve("$big.dat"), ByteArray(16 * 1024 * 1024) { 'A'.code.toByte() })
        val full = "ACQ.SIMACQA.TEST.20261121.001"
        DriverManager.getConnection(SimulatorDb.url(container), "root", container.password).use { c ->
            (1..99).forEach { n ->
                val shape = if (n == 1) "0, 'RECEIVED', NULL" else "NULL, 'RECEIPT_CONFLICT', 'CONTENT_MISMATCH'"
                c.createStatement().use {
                    it.executeUpdate(
                        "INSERT INTO acquirer_received_file (acquirer_code, file_id, receipt_ordinal, content_sha256, content_bytes, record_count, outcome, " +
                            "reason_code, received_at) VALUES ('SIMACQA', '$full', $n, LPAD('$n', 64, '0'), 1, $shape, NOW(3))",
                    )
                }
            }
        }
        Files.write(inbox.resolve("$full.dat"), "another content\n".toByteArray())
        worker.runOnce()
        receipts(big) shouldBe listOf("1|FILE_REJECTED|FILE_TOO_LARGE|")
        outboxNames(big) shouldBe listOf("ACK.$big.01.dat")
        receipts(full).size shouldBe 99
        outboxNames(full) shouldBe emptyList()
        Files.delete(inbox.resolve("$big.dat"))
    }

    // 리뷰 m-1: 이미 판정한 inbox 파일은 (이름, 크기, 수정 시각)이 그대로면 다시 읽어 hash하지 않는다. 수정 시각이 바뀌면 다시 읽지만 같은 내용이면 기록은 없다.
    test("AS-15 an already decided inbox file is not hashed again until its size or mtime changes") {
        val id = "ACQ.SIMACQA.TEST.20261122.001"
        upload(id, AcquirerFixture.file(id, listOf(sale(1, "t15-a", 1_500))))
        (worker.runOnce().hashedFiles >= 1) shouldBe true
        worker.runOnce().hashedFiles shouldBe 0
        Files.setLastModifiedTime(inbox.resolve("$id.dat"), java.nio.file.attribute.FileTime.from(Instant.now().plusSeconds(60)))
        val report = worker.runOnce()
        (report.hashedFiles to report.newReceipts) shouldBe (1 to 0)
        receipts(id) shouldBe listOf("1|RECEIVED||1")
    }

    // 리뷰 m-1(디스크 상한): inbox 하나의 업로드 임시 파일은 32개까지(넘으면 새 임시 파일 쓰기 거부), 보존 시간(시험 PT1H)이 지난 임시 파일은 Worker가 지운다.
    test("AS-16 upload temp files are capped per inbox and stale ones are swept by the worker") {
        val inboxB = env.root.resolve("SIMACQB").resolve("inbox")
        val names = (1..32).map { ".ACQ.SIMACQB.TEST.20261201.001.dat.$it.part" }
        names.forEach { Files.writeString(inboxB.resolve(it), "x") }
        fun clientB() = AcquirerSftpTestClient.connect(server.port, AcquirerTestEnvironment.USER_B, env.clientB, env.hostKey.public)
        clientB().use { b ->
            shouldThrow<SftpException> { b.write("/inbox/.ACQ.SIMACQB.TEST.20261202.001.dat.1.part", "y".toByteArray()) }.status shouldBe
                SftpConstants.SSH_FX_PERMISSION_DENIED
            b.write("/inbox/${names.first()}", "overwrite of an existing temp is allowed".toByteArray())
        }
        val old = java.nio.file.attribute.FileTime.from(Instant.now().minus(Duration.ofHours(2)))
        names.drop(1).forEach { Files.setLastModifiedTime(inboxB.resolve(it), old) }
        (worker.runOnce().sweptTemps >= 31) shouldBe true
        names.drop(1).none { Files.exists(inboxB.resolve(it)) } shouldBe true
        Files.exists(inboxB.resolve(names.first())) shouldBe true
        clientB().use { b -> b.write("/inbox/.ACQ.SIMACQB.TEST.20261202.001.dat.1.part", "y".toByteArray()) }
    }

    // 재리뷰 m-2: 16 MiB - 1 상한은 쓰기 이벤트가 아니라 열린 채널에서 지킨다 — copy-data 확장(서버 안 복사)도 넘지 못한다.
    test("AS-17 the 16 MiB - 1 upload limit holds on every write path, including the copy-data extension") {
        val id = "ACQ.SIMACQA.TEST.20261123.001"
        clientA().use { c ->
            val src = AcquirerSftpTestClient.temp("$id.dat", 1)
            val dst = AcquirerSftpTestClient.temp("$id.dat", 2)
            c.write(src, ByteArray(16) { 'x'.code.toByte() })
            val r = c.sftp.open(src, org.apache.sshd.sftp.client.SftpClient.OpenMode.Read)
            val w = c.sftp.open(dst, org.apache.sshd.sftp.client.SftpClient.OpenMode.Write, org.apache.sshd.sftp.client.SftpClient.OpenMode.Create)
            val copy = c.sftp.getExtension(org.apache.sshd.sftp.client.extensions.CopyDataExtension::class.java)
            copy.copyData(r, 0, 16, w, 0)
            shouldThrow<java.io.IOException> { copy.copyData(r, 0, 16, w, 16L * 1024 * 1024 - 8) }
            c.sftp.close(w)
            c.sftp.close(r)
        }
        Files.size(inbox.resolve(".$id.dat.2.part")) shouldBe 16L
    }

    // 재리뷰 m-6: 상한(32)에 닿으면 새 시도 임시 파일을 열 때 같은 파일 ID의 닫힌(쓰기 핸들 없는) 이전 시도 임시 파일을 먼저 지운다 — FINANCIAL 재시도가
    // 자기 잔여물 때문에 막히지 않는다. 다른 파일 ID의 임시 파일은 건드리지 않고, 그래도 상한이면 거부한다(AS-16).
    test("AS-18 at the temp cap a new attempt clears its own file id's closed earlier attempts; other file ids' temps stay") {
        val inboxB = env.root.resolve("SIMACQB").resolve("inbox")
        Files.list(inboxB).use { s -> s.toList() }.filter { it.fileName.toString().endsWith(".part") }.forEach { Files.delete(it) }
        val same = "ACQ.SIMACQB.TEST.20261203.001"
        val other = (1..2).map { inboxB.resolve(".ACQ.SIMACQB.TEST.20261204.001.dat.$it.part") }
        other.forEach { Files.writeString(it, "other") }
        (1..30).forEach { Files.writeString(inboxB.resolve(".$same.dat.$it.part"), "old attempt") }
        AcquirerSftpTestClient.connect(server.port, AcquirerTestEnvironment.USER_B, env.clientB, env.hostKey.public).use { b ->
            b.upload("$same.dat", "attempt 31".toByteArray(), attempt = 31)
        }
        String(Files.readAllBytes(inboxB.resolve("$same.dat"))) shouldBe "attempt 31"
        (1..30).none { Files.exists(inboxB.resolve(".$same.dat.$it.part")) } shouldBe true
        other.all { Files.exists(it) } shouldBe true
    }
})


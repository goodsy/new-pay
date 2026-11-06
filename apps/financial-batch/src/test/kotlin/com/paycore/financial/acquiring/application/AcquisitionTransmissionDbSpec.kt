package com.paycore.financial.acquiring.application

import com.paycore.financial.FinancialDbAccounts
import com.paycore.financial.FinancialMySqlContainerConfig
import com.paycore.financial.acquiring.adapter.integration.TestAcquirerSftpConfig
import com.paycore.financial.acquiring.adapter.integration.TestAcquirerSftpServer
import com.paycore.financial.acquiring.application.provided.SealAcquisitionFileUseCase
import com.paycore.financial.acquiring.application.provided.TransmissionResult.Kind
import com.paycore.financial.acquiring.application.required.AcquirerFileChannel
import com.paycore.financial.acquiring.application.required.AcquirerFileSession
import com.paycore.financial.acquiring.application.required.AcquiringClock
import com.paycore.financial.acquiring.application.required.AcquisitionFileRepository
import com.paycore.financial.acquiring.application.required.AcquisitionLaneStore
import com.paycore.financial.acquiring.application.required.AcquisitionMembershipStore
import com.paycore.financial.acquiring.application.required.AcquisitionPolicySource
import com.paycore.financial.acquiring.application.required.AcquisitionRecordResultStore
import com.paycore.financial.acquiring.application.required.AcquisitionTransmissionStore
import com.paycore.financial.acquiring.application.required.AcquisitionTransportPolicy
import com.paycore.financial.acquiring.domain.AcquisitionFileId
import com.paycore.financial.acquiring.domain.TransmissionState
import com.paycore.financial.paymentfact.adapter.web.PaymentFactEnvelopeFixture
import com.paycore.financial.paymentfact.application.PaymentFactInboxService
import com.paycore.financial.paymentfact.application.provided.PaymentFactAcquisitionSource
import com.paycore.financial.paymentfact.domain.PaymentFactFixture
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.testcontainers.mysql.MySQLContainer
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.jvmErasure

/**
 * 매입 파일 SFTP 전송 실제 시험(ACQUIRING-001 2-D — AQ-T1..T10). 실제 MySQL(runtime 계정) + 실제 SFTP 왕복(시험 SSHD 서버, 임시 키쌍, host key 고정).
 * 봉인은 운영 봉인 서비스를 고정 시계(마감 뒤)로 부르고, 전송은 운영 Bean Port로 만든 서비스를 고정 시계로 부른다(lease 만료 시험이 시계를 옮긴다).
 * 시험마다 매입사 코드(lane)가 다르다(TestAcquirerSftpConfig.ACQUIRERS).
 */
@SpringBootTest
@Import(FinancialMySqlContainerConfig::class, TestAcquirerSftpConfig::class)
class AcquisitionTransmissionDbSpec(
    private val inbox: PaymentFactInboxService,
    private val transactionManager: PlatformTransactionManager,
    private val lanes: AcquisitionLaneStore,
    private val files: AcquisitionFileRepository,
    private val membership: AcquisitionMembershipStore,
    private val source: PaymentFactAcquisitionSource,
    private val policies: AcquisitionPolicySource,
    private val attempts: AcquisitionTransmissionStore,
    private val channel: AcquirerFileChannel,
    private val transport: AcquisitionTransportPolicy,
    private val server: TestAcquirerSftpServer,
    private val results: AcquisitionRecordResultStore,
    private val board: AcquisitionWaitingBoard,
    private val jdbc: JdbcTemplate,
    private val container: MySQLContainer,
) : BehaviorSpec({

    val day = LocalDate.parse("2026-11-05")
    val afterCutoff = Instant.parse("2026-11-05T14:05:00Z")

    class FixedClock(@Volatile var now: Instant) : AcquiringClock {
        override fun now(): Instant = now
    }

    val acquirers = TestAcquirerSftpConfig.ACQUIRERS.iterator()

    fun sealOne(acquirer: String, amountMinor: Long = 10_000): String {
        inbox.receive(PaymentFactEnvelopeFixture.inbound(PaymentFactFixture.authorized(amountMinor = amountMinor, acquirerCode = acquirer), UUID.randomUUID().toString()))
        return AcquisitionSealService(transactionManager, lanes, files, membership, source, policies, FixedClock(afterCutoff), results, board).seal(acquirer, "TEST", day).fileId
    }

    fun service(clock: AcquiringClock = FixedClock(afterCutoff.plusSeconds(60)), via: AcquirerFileChannel = channel) =
        AcquisitionTransmissionService(transactionManager, files, attempts, via, transport, clock)

    fun storedContent(fileId: String): ByteArray = jdbc.queryForObject("SELECT content FROM acquisition_file_content WHERE file_id = ?", ByteArray::class.java, fileId)!!
    fun state(fileId: String): Map<String, Any?> = jdbc.queryForMap(
        "SELECT transmission_state, transmission_lease_until IS NOT NULL AS leased, transmitted_at IS NOT NULL AS transmitted, version FROM acquisition_file WHERE file_id = ?",
        fileId,
    ).let { mapOf("state" to it["transmission_state"], "leased" to (it["leased"] as Number).toInt(), "transmitted" to (it["transmitted"] as Number).toInt(), "version" to (it["version"] as Number).toLong()) }
    fun history(fileId: String) = attempts.historyInTx(transactionManager, fileId)
    fun finalName(fileId: String) = AcquisitionFileId.parse(fileId).fileName

    /** 이 파일에 대해 서버가 쓰기로 연 inbox 이름(순서대로). */
    fun tempNames(fileId: String) = server.writeOpenedNames.toList().filter { it.contains(finalName(fileId)) }

    Given("AQ-T1 a sealed file and a reachable acquirer") {
        val acq = acquirers.next()
        val fileId = sealOne(acq)
        val result = service().transmit(fileId)

        Then("it uploads .part, renames to the final name, re-reads it on a new connection and records TRANSMITTED with RENAMED_AND_VERIFIED") {
            (result.outcome to result.detail) shouldBe (Kind.TRANSMITTED to "RENAMED_AND_VERIFIED")
            Files.readAllBytes(server.inboxFile(finalName(fileId))).contentEquals(storedContent(fileId)) shouldBe true
            Files.exists(server.inboxFile(".${finalName(fileId)}.part")) shouldBe false
            // C-9: 시도별 임시명(.{id}.dat.{attemptNo}.part)만 쓰고 옛 공용 임시명은 쓰지 않는다. rename 뒤 임시명은 남지 않는다.
            tempNames(fileId) shouldBe listOf(".${finalName(fileId)}.1.part")
            Files.exists(server.inboxFile(".${finalName(fileId)}.1.part")) shouldBe false
            state(fileId) shouldBe mapOf("state" to "TRANSMITTED", "leased" to 0, "transmitted" to 1, "version" to 2L)
            history(fileId).map { listOf(it.attemptNo, it.resumeState, it.outcome, it.evidence, it.resultingState) } shouldBe
                listOf(listOf(1, TransmissionState.SEALED, "DELIVERED", "RENAMED_AND_VERIFIED", TransmissionState.TRANSMITTED))
        }

        And("a second call does not claim or upload again (ALREADY_TRANSMITTED — same file id, no new attempt)") {
            val writes = server.writeRequests.get()
            service().transmit(fileId).outcome shouldBe Kind.ALREADY_TRANSMITTED
            server.writeRequests.get() shouldBe writes
            history(fileId).size shouldBe 1
            jdbc.queryForObject("SELECT COUNT(*) FROM acquisition_file WHERE acquirer_code = ?", Int::class.java, acq) shouldBe 1
        }
    }

    Given("AQ-T2 the connection drops while the .part is being written") {
        val fileId = sealOne(acquirers.next())
        server.failNextWrite = true
        val first = service().transmit(fileId)

        Then("nothing was renamed: NOT_DELIVERED(UPLOAD_FAILED) back to SEALED, no final file") {
            (first.outcome to first.detail) shouldBe (Kind.NOT_DELIVERED to "UPLOAD_FAILED")
            state(fileId)["state"] shouldBe "SEALED"
            Files.exists(server.inboxFile(finalName(fileId))) shouldBe false
        }

        And("the retry re-uploads the same file id with the same sealed bytes and is TRANSMITTED") {
            service().transmit(fileId).outcome shouldBe Kind.TRANSMITTED
            Files.readAllBytes(server.inboxFile(finalName(fileId))).contentEquals(storedContent(fileId)) shouldBe true
            history(fileId).map { it.outcome to it.reason } shouldBe listOf("NOT_DELIVERED" to "UPLOAD_FAILED", "DELIVERED" to null)
            // 시도마다 다른 n(C-9): 재시도는 앞 시도의 임시 파일을 다시 열지 않는다.
            tempNames(fileId) shouldBe listOf(".${finalName(fileId)}.1.part", ".${finalName(fileId)}.2.part")
        }
    }

    Given("AQ-T3 the rename is performed but its response is lost") {
        val fileId = sealOne(acquirers.next())
        val renames = server.renames.get()
        server.dropRenameResponses.set(1)
        val result = service().transmit(fileId)

        Then("the re-check on a new connection finds the sealed bytes: TRANSMITTED with VERIFIED_FINAL_PRESENT (one rename)") {
            (result.outcome to result.detail) shouldBe (Kind.TRANSMITTED to "VERIFIED_FINAL_PRESENT")
            server.renames.get() shouldBe renames + 1
            state(fileId)["state"] shouldBe "TRANSMITTED"
        }
    }

    Given("AQ-T4 the rename response is lost and the re-check connection fails too") {
        val acq = acquirers.next()
        val fileId = sealOne(acq)
        server.dropRenameResponses.set(1)
        server.rejectLoginsAfterDroppedRename.set(1)
        val first = service().transmit(fileId)

        Then("the outcome is unknown: TRANSMISSION_UNKNOWN(RENAME_OUTCOME_UNKNOWN), not SEALED and not TRANSMITTED") {
            (first.outcome to first.detail) shouldBe (Kind.OUTCOME_UNKNOWN to "RENAME_OUTCOME_UNKNOWN")
            state(fileId)["state"] shouldBe "TRANSMISSION_UNKNOWN"
        }

        And("the recovery sweep verifies the final name first and records TRANSMITTED without uploading again") {
            val writes = server.writeRequests.get()
            val results = service().transmitPending(acq, "TEST", 10)
            results.map { it.fileId to it.outcome } shouldBe listOf(fileId to Kind.TRANSMITTED)
            results.single().detail shouldBe "VERIFIED_FINAL_PRESENT"
            server.writeRequests.get() shouldBe writes
            history(fileId).map { it.resumeState to it.resultingState } shouldBe listOf(
                TransmissionState.SEALED to TransmissionState.TRANSMISSION_UNKNOWN,
                TransmissionState.TRANSMISSION_UNKNOWN to TransmissionState.TRANSMITTED,
            )
        }
    }

    Given("AQ-T5 the acquirer already holds different bytes under the same file id") {
        val fileId = sealOne(acquirers.next())
        val planted = "H|1|someone-else\n".toByteArray()
        Files.write(server.inboxFile(finalName(fileId)), planted)
        val result = service().transmit(fileId)

        Then("the file is quarantined, the planted file is not overwritten and later calls do not claim it") {
            result.outcome shouldBe Kind.QUARANTINED
            Files.readAllBytes(server.inboxFile(finalName(fileId))).contentEquals(planted) shouldBe true
            state(fileId)["state"] shouldBe "TRANSMISSION_QUARANTINED"
            service().transmit(fileId).outcome shouldBe Kind.QUARANTINED
            history(fileId).map { it.outcome } shouldBe listOf("CONTENT_MISMATCH")
        }
    }

    Given("AQ-T6 the server presents a host key that is not the pinned fingerprint") {
        val acq = TestAcquirerSftpConfig.WRONG_HOST_KEY_ACQUIRER
        val fileId = sealOne(acq)
        val logins = server.logins.get()
        val writes = server.writeRequests.get()
        val result = service().transmit(fileId)

        Then("the connection is refused before authentication: NOT_DELIVERED(HOST_KEY_REJECTED), SEALED, nothing written") {
            (result.outcome to result.detail) shouldBe (Kind.NOT_DELIVERED to "HOST_KEY_REJECTED")
            state(fileId)["state"] shouldBe "SEALED"
            server.logins.get() shouldBe logins
            server.writeRequests.get() shouldBe writes
            Files.exists(server.inboxFile(finalName(fileId))) shouldBe false
        }
    }

    Given("AQ-T7 a lane without transport settings (fail-closed — no default endpoint)") {
        val acq = PaymentFactFixture.nextAcquirerCode()
        val fileId = sealOne(acq)
        val result = service().transmit(fileId)

        Then("it is NOT_CONFIGURED: no claim, no attempt row, still SEALED") {
            result.outcome shouldBe Kind.NOT_CONFIGURED
            state(fileId)["state"] shouldBe "SEALED"
            history(fileId) shouldBe emptyList()
        }
    }

    Given("AQ-T8 an attempt holds the lease and stalls before connecting") {
        val fileId = sealOne(acquirers.next())
        val clock = FixedClock(afterCutoff.plusSeconds(60))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stalling = object : AcquirerFileChannel {
            @Volatile var armed = true
            override fun isConfigured(acquirerCode: String) = channel.isConfigured(acquirerCode)
            override fun open(acquirerCode: String): AcquirerFileSession {
                if (armed) {
                    armed = false
                    entered.countDown()
                    release.await(60, TimeUnit.SECONDS)
                }
                return channel.open(acquirerCode)
            }
        }
        val pool = Executors.newSingleThreadExecutor()
        val stalled = pool.submit<Any> { service(clock, stalling).transmit(fileId) }
        entered.await(30, TimeUnit.SECONDS) shouldBe true

        Then("a second attempt within the lease is IN_FLIGHT and changes nothing") {
            service(clock).transmit(fileId).outcome shouldBe Kind.IN_FLIGHT
            state(fileId)["state"] shouldBe "TRANSMITTING"
        }

        And("after the lease expires another attempt takes over (resume = TRANSMISSION_UNKNOWN, verify first) and the stalled attempt is SUPERSEDED") {
            clock.now = clock.now.plus(transport.transmissionLease).plus(Duration.ofSeconds(1))
            service(clock).transmit(fileId).outcome shouldBe Kind.TRANSMITTED
            release.countDown()
            val late = stalled.get(60, TimeUnit.SECONDS) as com.paycore.financial.acquiring.application.provided.TransmissionResult
            pool.shutdown()
            late.outcome shouldBe Kind.SUPERSEDED
            state(fileId)["state"] shouldBe "TRANSMITTED"
            // 리뷰 m-5: 늦은 시도의 관찰도 감사 행으로 남는다(SUPERSEDED, observed = 그 시도가 본 결과, 상태는 이어받은 시도의 결과 그대로).
            history(fileId).first().let { listOf(it.outcome, it.observed, it.resultingState) } shouldBe
                listOf("SUPERSEDED", "DELIVERED", TransmissionState.TRANSMITTED)
            history(fileId).map { Triple(it.attemptNo, it.resumeState, it.outcome) } shouldBe listOf(
                Triple(1, TransmissionState.SEALED, "SUPERSEDED"),
                Triple(2, TransmissionState.TRANSMISSION_UNKNOWN, "DELIVERED"),
            )
        }
    }

    Given("AQ-T9 the stored sealed bytes were altered behind the application (root account)") {
        val fileId = sealOne(acquirers.next())
        FinancialDbAccounts.root(container).use { c ->
            c.prepareStatement("UPDATE acquisition_file_content SET content = CONCAT(content, 'X') WHERE file_id = ?").use {
                it.setString(1, fileId)
                it.executeUpdate()
            }
        }
        val result = service().transmit(fileId)

        Then("it refuses before claiming (INTEGRITY_VIOLATION): no attempt, still SEALED, nothing uploaded") {
            result.outcome shouldBe Kind.INTEGRITY_VIOLATION
            state(fileId)["state"] shouldBe "SEALED"
            history(fileId) shouldBe emptyList()
            Files.exists(server.inboxFile(finalName(fileId))) shouldBe false
        }
    }

    Given("AQ-T10 review NIT-1: transmission reads the stored sealed bytes and never re-seals") {
        Then("the transmission service depends on neither the seal use case nor the cutoff policy source") {
            val types = AcquisitionTransmissionService::class.primaryConstructor!!.parameters.map { it.type.jvmErasure }
            types.contains(SealAcquisitionFileUseCase::class) shouldBe false
            types.contains(AcquisitionPolicySource::class) shouldBe false
            types.contains(AcquisitionSealService::class) shouldBe false
        }
    }

    Given("AQ-T11 review MJ-2: the rename is not performed and its response is lost (re-check finds no final file)") {
        val fileId = sealOne(acquirers.next())
        server.dropRenameBeforeMove.set(1)
        val first = service().transmit(fileId)

        Then("absence after an unanswered rename is not proof of non-receipt: TRANSMISSION_UNKNOWN(RENAME_OUTCOME_UNKNOWN), not SEALED") {
            (first.outcome to first.detail) shouldBe (Kind.OUTCOME_UNKNOWN to "RENAME_OUTCOME_UNKNOWN")
            state(fileId)["state"] shouldBe "TRANSMISSION_UNKNOWN"
            Files.exists(server.inboxFile(finalName(fileId))) shouldBe false
        }

        And("an attempt resumed from TRANSMISSION_UNKNOWN that fails before rename stays TRANSMISSION_UNKNOWN (review m-4), and a later attempt delivers") {
            server.failNextWrite = true
            val again = service().transmit(fileId)
            (again.outcome to again.detail) shouldBe (Kind.NOT_DELIVERED to "UPLOAD_FAILED")
            state(fileId)["state"] shouldBe "TRANSMISSION_UNKNOWN"
            service().transmit(fileId).outcome shouldBe Kind.TRANSMITTED
            history(fileId).map { Triple(it.resumeState, it.outcome, it.resultingState) } shouldBe listOf(
                Triple(TransmissionState.SEALED, "UNKNOWN", TransmissionState.TRANSMISSION_UNKNOWN),
                Triple(TransmissionState.TRANSMISSION_UNKNOWN, "NOT_DELIVERED", TransmissionState.TRANSMISSION_UNKNOWN),
                Triple(TransmissionState.TRANSMISSION_UNKNOWN, "DELIVERED", TransmissionState.TRANSMITTED),
            )
        }
    }

    Given("AQ-T12 the server explicitly refuses the rename and no final file exists") {
        val fileId = sealOne(acquirers.next())
        server.refuseNextRenames.set(1)
        val result = service().transmit(fileId)
        Then("it is NOT_DELIVERED(RENAME_REFUSED) back to SEALED") {
            (result.outcome to result.detail) shouldBe (Kind.NOT_DELIVERED to "RENAME_REFUSED")
            state(fileId)["state"] shouldBe "SEALED"
        }
    }

    Given("AQ-T13 the rename succeeds but the final file is gone on the re-check") {
        val fileId = sealOne(acquirers.next())
        server.removeAfterRename.set(1)
        val result = service().transmit(fileId)
        Then("it is TRANSMISSION_UNKNOWN(RENAMED_BUT_ABSENT) — reconcile, not resend as new") {
            (result.outcome to result.detail) shouldBe (Kind.OUTCOME_UNKNOWN to "RENAMED_BUT_ABSENT")
            state(fileId)["state"] shouldBe "TRANSMISSION_UNKNOWN"
        }
    }

    Given("AQ-T14 the rename succeeds and the re-check connection is refused") {
        val fileId = sealOne(acquirers.next())
        server.rejectLoginsAfterRename.set(1)
        val result = service().transmit(fileId)
        Then("the rename success response is the evidence: TRANSMITTED(RENAMED)") {
            (result.outcome to result.detail) shouldBe (Kind.TRANSMITTED to "RENAMED")
            state(fileId)["state"] shouldBe "TRANSMITTED"
        }
    }

    Given("AQ-T15 the server rejects the client key") {
        val fileId = sealOne(acquirers.next())
        server.rejectNextLogins.set(1)
        val writes = server.writeRequests.get()
        val result = service().transmit(fileId)
        Then("it is NOT_DELIVERED(AUTHENTICATION_FAILED) back to SEALED with nothing written") {
            (result.outcome to result.detail) shouldBe (Kind.NOT_DELIVERED to "AUTHENTICATION_FAILED")
            state(fileId)["state"] shouldBe "SEALED"
            server.writeRequests.get() shouldBe writes
        }
    }

    Given("AQ-T16 review MJ-1: attempt A wrote the .part and stalls before rename, B takes over after the lease and is mid-write on the same .part") {
        val fileId = sealOne(acquirers.next())
        val clock = FixedClock(afterCutoff.plusSeconds(60))
        val aWritten = CountDownLatch(1)
        val aRelease = CountDownLatch(1)
        val bHalf = CountDownLatch(1)
        val bRelease = CountDownLatch(1)
        fun gated(onWrite: (AcquirerFileSession, String, ByteArray) -> Unit) = object : AcquirerFileChannel {
            override fun isConfigured(acquirerCode: String) = channel.isConfigured(acquirerCode)
            override fun open(acquirerCode: String): AcquirerFileSession {
                val d = channel.open(acquirerCode)
                return object : AcquirerFileSession by d {
                    override fun writeInbox(name: String, content: ByteArray) = onWrite(d, name, content)
                }
            }
        }
        val a = gated { d, name, content ->
            d.writeInbox(name, content)
            aWritten.countDown()
            aRelease.await(60, TimeUnit.SECONDS)
        }
        val b = gated { d, name, content ->
            d.writeInbox(name, content.copyOf(content.size / 2))
            bHalf.countDown()
            bRelease.await(60, TimeUnit.SECONDS)
            d.writeInbox(name, content)
        }
        val pool = Executors.newFixedThreadPool(2)
        val fa = pool.submit<Any> { service(clock, a).transmit(fileId) }
        aWritten.await(30, TimeUnit.SECONDS) shouldBe true
        clock.now = clock.now.plus(transport.transmissionLease).plus(Duration.ofSeconds(1))
        val fb = pool.submit<Any> { service(clock, b).transmit(fileId) }
        bHalf.await(30, TimeUnit.SECONDS) shouldBe true
        aRelease.countDown()
        val ra = fa.get(60, TimeUnit.SECONDS) as com.paycore.financial.acquiring.application.provided.TransmissionResult
        bRelease.countDown()
        val rb = fb.get(60, TimeUnit.SECONDS) as com.paycore.financial.acquiring.application.provided.TransmissionResult
        pool.shutdown()

        Then("A does not rename with its lease gone (no half-written bytes published); B delivers the sealed bytes") {
            ra.outcome shouldBe Kind.SUPERSEDED
            (rb.outcome to rb.detail) shouldBe (Kind.TRANSMITTED to "RENAMED_AND_VERIFIED")
            Files.readAllBytes(server.inboxFile(finalName(fileId))).contentEquals(storedContent(fileId)) shouldBe true
            history(fileId).map { listOf(it.attemptNo, it.outcome, it.observed, it.reason) } shouldBe listOf(
                listOf(1, "SUPERSEDED", "NOT_DELIVERED", null),
                listOf(2, "DELIVERED", null, null),
            )
            state(fileId)["state"] shouldBe "TRANSMITTED"
        }
    }

    Given("AQ-T17 review m-4: two threads claim the same SEALED file at the same moment (real DB race)") {
        val fileId = sealOne(acquirers.next())
        val firstDone = CountDownLatch(1)
        val gate = object : AcquirerFileChannel {
            override fun isConfigured(acquirerCode: String) = channel.isConfigured(acquirerCode)
            override fun open(acquirerCode: String): AcquirerFileSession {
                firstDone.await(30, TimeUnit.SECONDS)
                return channel.open(acquirerCode)
            }
        }
        val barrier = java.util.concurrent.CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        val futures = (1..2).map {
            pool.submit<com.paycore.financial.acquiring.application.provided.TransmissionResult> {
                barrier.await(30, TimeUnit.SECONDS)
                service(via = gate).transmit(fileId).also { r -> if (r.outcome != Kind.TRANSMITTED) firstDone.countDown() }
            }
        }
        val results = futures.map { it.get(90, TimeUnit.SECONDS) }
        pool.shutdown()

        Then("exactly one attempt row exists; one call transmits, the other is IN_FLIGHT") {
            results.map { it.outcome }.sortedBy { it.name } shouldBe listOf(Kind.IN_FLIGHT, Kind.TRANSMITTED)
            history(fileId).size shouldBe 1
            jdbc.queryForObject("SELECT COUNT(*) FROM acquisition_transmission_attempt WHERE file_id = ?", Int::class.java, fileId) shouldBe 1
        }
    }

    Given("AQ-T18 review m-4: the outcome transaction commit fails or its answer is lost") {
        class FlakyCommits(private val delegate: PlatformTransactionManager, private val failAt: Int, private val committed: Boolean) :
            PlatformTransactionManager by delegate {
            private var commits = 0
            override fun commit(status: org.springframework.transaction.TransactionStatus) {
                commits++
                if (commits != failAt) return delegate.commit(status)
                if (committed) delegate.commit(status) else delegate.rollback(status)
                throw org.springframework.transaction.TransactionSystemException("injected commit failure")
            }
        }
        val lost = sealOne(acquirers.next())
        val lostClock = FixedClock(afterCutoff.plusSeconds(60))
        val lostResult = AcquisitionTransmissionService(FlakyCommits(transactionManager, 2, false), files, attempts, channel, transport, lostClock).transmit(lost)
        val answerLost = sealOne(acquirers.next())
        val answerResult = AcquisitionTransmissionService(FlakyCommits(transactionManager, 2, true), files, attempts, channel, transport, FixedClock(afterCutoff.plusSeconds(60)))
            .transmit(answerLost)

        Then("a rolled-back outcome leaves TRANSMITTING (lease) with an attempt but no outcome; after the lease the sweep verifies without re-uploading") {
            (lostResult.outcome to lostResult.detail) shouldBe (Kind.OUTCOME_UNKNOWN to "COMMIT_OUTCOME_UNKNOWN")
            state(lost)["state"] shouldBe "TRANSMITTING"
            history(lost).map { it.outcome } shouldBe listOf(null)
            val writes = server.writeRequests.get()
            lostClock.now = lostClock.now.plus(transport.transmissionLease).plus(Duration.ofSeconds(1))
            service(lostClock).transmitPending(AcquisitionFileId.parse(lost).acquirerCode, "TEST", 10).map { it.outcome to it.detail } shouldBe
                listOf(Kind.TRANSMITTED to "VERIFIED_FINAL_PRESENT")
            server.writeRequests.get() shouldBe writes
        }

        And("a committed outcome whose answer was lost is already TRANSMITTED and the next call does nothing") {
            (answerResult.outcome to answerResult.detail) shouldBe (Kind.OUTCOME_UNKNOWN to "COMMIT_OUTCOME_UNKNOWN")
            state(answerLost)["state"] shouldBe "TRANSMITTED"
            service().transmit(answerLost).outcome shouldBe Kind.ALREADY_TRANSMITTED
        }
    }

    Given("AQ-T20 C-9: attempt A passed its lease check and stalls inside rename, B takes over and is mid-write on its own temp name") {
        val fileId = sealOne(acquirers.next())
        val clock = FixedClock(afterCutoff.plusSeconds(60))
        val aInRename = CountDownLatch(1)
        val aRelease = CountDownLatch(1)
        val bHalf = CountDownLatch(1)
        val bRelease = CountDownLatch(1)
        fun wrapped(rename: ((AcquirerFileSession, String, String) -> Unit)?, write: ((AcquirerFileSession, String, ByteArray) -> Unit)?) =
            object : AcquirerFileChannel {
                override fun isConfigured(acquirerCode: String) = channel.isConfigured(acquirerCode)
                override fun open(acquirerCode: String): AcquirerFileSession {
                    val d = channel.open(acquirerCode)
                    return object : AcquirerFileSession by d {
                        override fun writeInbox(name: String, content: ByteArray) = write?.invoke(d, name, content) ?: d.writeInbox(name, content)
                        override fun renameInbox(from: String, to: String) = rename?.invoke(d, from, to) ?: d.renameInbox(from, to)
                    }
                }
            }
        val a = wrapped(rename = { d, from, to ->
            aInRename.countDown()
            aRelease.await(60, TimeUnit.SECONDS)
            d.renameInbox(from, to)
        }, write = null)
        val b = wrapped(rename = null, write = { d, name, content ->
            d.writeInbox(name, content.copyOf(content.size / 2))
            bHalf.countDown()
            bRelease.await(60, TimeUnit.SECONDS)
            d.writeInbox(name, content)
        })
        val pool = Executors.newFixedThreadPool(2)
        val fa = pool.submit<Any> { service(clock, a).transmit(fileId) }
        aInRename.await(30, TimeUnit.SECONDS) shouldBe true
        clock.now = clock.now.plus(transport.transmissionLease).plus(Duration.ofSeconds(1))
        val fb = pool.submit<Any> { service(clock, b).transmit(fileId) }
        bHalf.await(30, TimeUnit.SECONDS) shouldBe true
        aRelease.countDown()
        val ra = fa.get(60, TimeUnit.SECONDS) as com.paycore.financial.acquiring.application.provided.TransmissionResult
        val publishedAfterA = Files.readAllBytes(server.inboxFile(finalName(fileId)))
        bRelease.countDown()
        val rb = fb.get(60, TimeUnit.SECONDS) as com.paycore.financial.acquiring.application.provided.TransmissionResult
        pool.shutdown()

        Then("A can only rename its own fully written .1.part: the published bytes are the sealed bytes, never B's half-written .2.part") {
            publishedAfterA.contentEquals(storedContent(fileId)) shouldBe true
            ra.outcome shouldBe Kind.SUPERSEDED
            (rb.outcome to rb.detail) shouldBe (Kind.TRANSMITTED to "VERIFIED_FINAL_PRESENT")
            Files.readAllBytes(server.inboxFile(finalName(fileId))).contentEquals(storedContent(fileId)) shouldBe true
            tempNames(fileId) shouldBe listOf(".${finalName(fileId)}.1.part", ".${finalName(fileId)}.2.part", ".${finalName(fileId)}.2.part")
            history(fileId).map { listOf(it.attemptNo, it.outcome, it.observed) } shouldBe listOf(
                listOf(1, "SUPERSEDED", "DELIVERED"),
                listOf(2, "DELIVERED", null),
            )
        }
    }

    Given("AQ-T21 C-9 server contract: the temp file was removed (part-retention) right before rename, and names outside the contract") {
        val acq = acquirers.next()
        val fileId = sealOne(acq)
        server.deletePartBeforeRename.set(1)
        val first = service().transmit(fileId)

        Then("rename NO_SUCH_FILE is a refused rename: NOT_DELIVERED(RENAME_REFUSED) back to SEALED, and the retry with n = 2 delivers") {
            (first.outcome to first.detail) shouldBe (Kind.NOT_DELIVERED to "RENAME_REFUSED")
            state(fileId)["state"] shouldBe "SEALED"
            service().transmit(fileId).outcome shouldBe Kind.TRANSMITTED
            tempNames(fileId) shouldBe listOf(".${finalName(fileId)}.1.part", ".${finalName(fileId)}.2.part")
        }

        Then("the emulated server refuses the old shared temp name, n outside 1..999999 and the final name, and renames only a temp name to its pair (PERMISSION_DENIED → Refused)") {
            val other = sealOne(acquirers.next())
            val f = finalName(other)
            channel.open(AcquisitionFileId.parse(other).acquirerCode).use { s ->
                listOf(".$f.part", ".$f.0.part", ".$f.01.part", ".$f.1000000.part", f).map { name ->
                    runCatching { s.writeInbox(name, "x".toByteArray()) }.exceptionOrNull()?.javaClass?.simpleName
                } shouldBe List(5) { "Refused" }
                runCatching { s.renameInbox(".$f.part", f) }.exceptionOrNull()?.javaClass?.simpleName shouldBe "Refused"
            }
        }
    }

    Given("AQ-T19 review m-3 and NIT: LIVE files and calls inside a transaction") {
        Then("a LIVE file id is refused before any lookup or connection (fail-closed)") {
            service().transmit("ACQ.SFTPT1.LIVE.20261105.001").outcome shouldBe Kind.LIVE_BLOCKED
        }
        Then("transmission inside an active transaction is refused (no lock or connection held across SFTP)") {
            val error = runCatching {
                org.springframework.transaction.support.TransactionTemplate(transactionManager).execute { service().transmit("ACQ.SFTPT1.TEST.20261105.001") }
            }.exceptionOrNull()
            (error is IllegalStateException) shouldBe true
        }
    }
})

/** 시험 조회: 저장 Port는 호출자 TX 필수(MANDATORY)라 읽기 TX를 연다. */
private fun AcquisitionTransmissionStore.historyInTx(transactionManager: PlatformTransactionManager, fileId: String) =
    org.springframework.transaction.support.TransactionTemplate(transactionManager).execute { history(AcquisitionFileId.parse(fileId)) }

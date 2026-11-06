package com.paycore.simulator.acquirer.application

import com.paycore.simulator.acquirer.application.provided.AcquirerWorkerReport
import com.paycore.simulator.acquirer.application.provided.RunAcquirerWorkerUseCase
import com.paycore.simulator.acquirer.application.required.AcquirerClock
import com.paycore.simulator.acquirer.application.required.AcquirerFileArea
import com.paycore.simulator.acquirer.application.required.AcquirerPublicationStore
import com.paycore.simulator.acquirer.application.required.AcquirerReceiptStore
import com.paycore.simulator.acquirer.application.required.DuplicateAcquirerRecord
import com.paycore.simulator.acquirer.application.required.InboxContent
import com.paycore.simulator.acquirer.application.required.InboxEntry
import com.paycore.simulator.acquirer.application.required.NewPublication
import com.paycore.simulator.acquirer.application.required.PendingPublication
import com.paycore.simulator.acquirer.application.required.PublicationKind
import com.paycore.simulator.acquirer.application.required.PublishOutcome
import com.paycore.simulator.acquirer.application.required.VirtualAcquirer
import com.paycore.simulator.acquirer.application.required.VirtualAcquirers
import com.paycore.simulator.acquirer.domain.AcceptedSale
import com.paycore.simulator.acquirer.domain.AcknowledgementFileV1
import com.paycore.simulator.acquirer.domain.AcquirerReceipt
import com.paycore.simulator.acquirer.domain.AcquisitionFileRef
import com.paycore.simulator.acquirer.domain.AcquisitionFileV1Reader
import com.paycore.simulator.acquirer.domain.AuthorizationEvidence
import com.paycore.simulator.acquirer.domain.CancellationEvidence
import com.paycore.simulator.acquirer.domain.FileRejectionReason
import com.paycore.simulator.acquirer.domain.JudgedRecord
import com.paycore.simulator.acquirer.domain.PlannedResultFile
import com.paycore.simulator.acquirer.domain.PublicationPlan
import com.paycore.simulator.acquirer.domain.PublicationPlanner
import com.paycore.simulator.acquirer.domain.ReceiptOutcome
import com.paycore.simulator.acquirer.domain.ReceiptRule
import com.paycore.simulator.acquirer.domain.ReceivedAcquisitionFile
import com.paycore.simulator.acquirer.domain.ReceivedRecord
import com.paycore.simulator.acquirer.domain.ReceivedRecordType
import com.paycore.simulator.acquirer.domain.RecordJudgement
import com.paycore.simulator.acquirer.domain.RecordJudgementRule
import com.paycore.simulator.acquirer.domain.RecordResult
import com.paycore.simulator.acquirer.domain.ResultFileV1
import com.paycore.simulator.acquirer.domain.Sha256
import com.paycore.simulator.van.application.provided.VanAcquiringEvidenceQuery
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * 가상 매입사 Worker(ACQUIRING-001 2-E §7 rev 0.7). 한 회차 = 매입사마다 (오래된 업로드 임시 파일 정리 → 수신) → 게시.
 *
 * 수신(파일마다 로컬 TX 하나): inbox 최종명만 본다(업로드 임시명은 목록에 없다). 같은 (이름, 크기, 수정 시각)으로 이미 판정한 파일은 다시 읽지 않는다(리뷰 m-1 —
 * 프로세스 안 표, 재기동 뒤 한 번 다시 hash). 파일 읽기·hash는 TX 밖. TX 안에서 같은 파일 ID의 수신 기록과 대조해 같은 내용이면 아무것도 쓰지 않고(멱등),
 * 첫 수신이면 형식·hash·합계 판정 → 레코드마다 VAN 원장 대조 판정(기관 근거 기준 이중 매입 방어 — 리뷰 MJ-1) → 게시 계획 → 진행 표 PENDING을 **한 TX**로 기록한다.
 * 같은 ID 다른 내용은 RECEIPT_CONFLICT(레코드 처리 없음 — SFTP 경로로는 생기지 않는다: 최종명 덮어쓰기·삭제가 거부되므로 저장 파일이 SFTP 밖에서 바뀐 경우만).
 * UNIQUE 충돌은 TX를 버리고 다음 회차에 다시 판정한다.
 *
 * 실패 격리(리뷰 m-4): 파일 하나·작업 하나의 예외는 그 파일·작업만 다음 회차로 넘기고(error 로그 — 매입사·파일 ID·예외 종류만) 나머지 수신·게시는 계속한다.
 *
 * 게시(작업마다): 기록된 계획에서 바이트를 다시 만든다 → outbox 임시명 쓰기·rename(TX 밖) → 로컬 TX에서 게시 기록 + 작업 PUBLISHED(@Version). 최종명에 다른 바이트가
 * 있으면 덮어쓰지 않고 PENDING으로 남기며 그 작업은 [REFUSED_RECHECK] 동안 묶음에서 빼고(리뷰 n-3 — 독점 방지) error 로그는 처음 한 번만 남긴다.
 */
@Service
class AcquirerWorkerService(
    private val acquirers: VirtualAcquirers,
    private val files: AcquirerFileArea,
    private val receipts: AcquirerReceiptStore,
    private val publications: AcquirerPublicationStore,
    private val van: VanAcquiringEvidenceQuery,
    private val clock: AcquirerClock,
    transactionManager: PlatformTransactionManager,
) : RunAcquirerWorkerUseCase {

    private val tx = TransactionTemplate(transactionManager)
    private val log = LoggerFactory.getLogger(javaClass)
    private val reportedNames = ConcurrentHashMap.newKeySet<String>()

    /** (매입사/이름) → 이미 판정한 파일의 (크기, 수정 시각). */
    private val decided = ConcurrentHashMap<String, Pair<Long, Instant>>()

    /** 덮어쓰기 거부로 미룬 작업 → 다시 볼 시각. */
    private val refusedUntil = ConcurrentHashMap<Long, Instant>()

    private class Round {
        var received = 0
        var hashed = 0
        var failed = 0
        var swept = 0
        var published = 0
        var refused = 0
    }

    @Synchronized
    override fun runOnce(): AcquirerWorkerReport {
        val round = Round()
        acquirers.acquirers.forEach { acquirer ->
            try {
                round.swept += files.sweepTemps(acquirer.code, acquirers.partRetention)
                files.inboxFiles(acquirer.code).forEach { entry -> intake(acquirer, entry, round) }
            } catch (e: Exception) {
                round.failed++
                log.error("acquirer inbox round failed acquirer={} ({}); retried next round", acquirer.code, e.javaClass.simpleName)
            }
        }
        publishDue(round)
        if (round.swept > 0) log.info("acquirer stale upload temp files removed count={}", round.swept)
        return AcquirerWorkerReport(round.received, round.published, round.refused, round.hashed, round.failed, round.swept)
    }

    private fun intake(acquirer: VirtualAcquirer, entry: InboxEntry, round: Round) {
        val ref = AcquisitionFileRef.fromFinalName(entry.name)
        if (ref == null) {
            if (reportedNames.add("${acquirer.code}/${entry.name}")) log.warn("acquirer inbox file ignored (name outside the file contract) acquirer={}", acquirer.code)
            return
        }
        val key = "${acquirer.code}/${entry.name}"
        if (decided[key] == (entry.size to entry.modifiedAt)) return
        try {
            val content = files.readInbox(acquirer.code, entry.name, MAX_FILE_BYTES) ?: return
            round.hashed++
            if (tx.execute { receive(acquirer, ref, content) } == true) round.received++
            decided[key] = entry.size to entry.modifiedAt
        } catch (e: DuplicateAcquirerRecord) {
            log.info("acquirer receipt raced with another worker; decided again next round acquirer={} fileId={}", acquirer.code, ref.value)
        } catch (e: Exception) {
            round.failed++
            log.error("acquirer receipt failed acquirer={} fileId={} ({}); retried next round", acquirer.code, ref.value, e.javaClass.simpleName)
        }
    }

    private fun receive(acquirer: VirtualAcquirer, ref: AcquisitionFileRef, content: InboxContent): Boolean {
        val prior = receipts.receipts(acquirer.code, ref.value)
        val receivedAt = clock.now().truncatedTo(ChronoUnit.MILLIS)
        return when (val decision = ReceiptRule.decide(prior.map { it.receipt }, content.contentSha256)) {
            ReceiptRule.Decision.AlreadyReceived -> false
            ReceiptRule.Decision.Exhausted -> {
                if (reportedNames.add("${acquirer.code}/${ref.value}#exhausted")) {
                    log.error("acquirer receipt conflicts exceeded the ACK sequence limit; no record acquirer={} fileId={}", acquirer.code, ref.value)
                }
                false
            }
            is ReceiptRule.Decision.Conflict -> {
                val receipt = AcquirerReceipt(
                    acquirer.code, ref.value, decision.ordinal, content.contentSha256, content.size, null, ReceiptOutcome.RECEIPT_CONFLICT,
                    FileRejectionReason.CONTENT_MISMATCH, receivedAt,
                )
                record(acquirer, receipt, emptyList(), receivedAt)
                log.error("acquirer receipt conflict: same file id, different content acquirer={} fileId={} ordinal={}", acquirer.code, ref.value, decision.ordinal)
                true
            }
            ReceiptRule.Decision.First -> {
                val read = when (content) {
                    is InboxContent.TooLarge -> AcquisitionFileV1Reader.Outcome.Rejected(FileRejectionReason.FILE_TOO_LARGE)
                    is InboxContent.Complete -> AcquisitionFileV1Reader.read(ref, acquirer.code, content.bytes)
                }
                val accepted = (read as? AcquisitionFileV1Reader.Outcome.Accepted)?.file?.takeUnless { acquirer.scenarios.fileRejected(ref) }
                val reason = when {
                    accepted != null -> null
                    read is AcquisitionFileV1Reader.Outcome.Rejected -> read.reason
                    else -> FileRejectionReason.SCENARIO_FILE_REJECTED
                }
                val receipt = AcquirerReceipt(
                    acquirer.code, ref.value, 1, content.contentSha256, content.size, accepted?.records?.size,
                    if (accepted != null) ReceiptOutcome.RECEIVED else ReceiptOutcome.FILE_REJECTED, reason, receivedAt,
                )
                val judged = accepted?.let { judge(acquirer, it) }.orEmpty()
                record(acquirer, receipt, judged, receivedAt)
                log.info(
                    "acquirer file received acquirer={} fileId={} outcome={} reason={} records={} accepted={} rejected={}", acquirer.code, ref.value,
                    receipt.outcome, reason, judged.size, judged.count { it.judgement.result == RecordResult.ACCEPTED },
                    judged.count { it.judgement.result == RecordResult.REJECTED },
                )
                true
            }
        }
    }

    /**
     * 이 매입사의 수락 상태(DB) + 같은 파일 앞 레코드의 수락을 합쳐 본다(리뷰 MJ-1). 앞 레코드가 수락되면 거래 ID·기관 근거·원 SALE·원승인별 취소 소비에 바로 반영된다.
     */
    private inner class AcceptanceView(private val acquirer: VirtualAcquirer) {
        private val transactions = mutableSetOf<Pair<String, ReceivedRecordType>>()
        private val evidence = mutableSetOf<Pair<ReceivedRecordType, String>>()
        private val sales = mutableMapOf<String, AcceptedSale>()
        private val consumed = mutableMapOf<String, Long>()

        fun transactionAccepted(txn: String, type: ReceivedRecordType) = (txn to type) in transactions || receipts.transactionAccepted(acquirer.code, txn, type)

        fun evidenceAccepted(type: ReceivedRecordType, entryId: String) = (type to entryId) in evidence || receipts.evidenceAccepted(acquirer.code, type, entryId)

        fun acceptedSale(txn: String): AcceptedSale? = sales[txn] ?: receipts.acceptedSale(acquirer.code, txn)

        fun consumedCancels(authorizationEntryId: String): Long =
            receipts.consumedCancels(acquirer.code, authorizationEntryId) + (consumed[authorizationEntryId] ?: 0L)

        fun accept(r: ReceivedRecord, j: RecordJudgement) {
            val entry = checkNotNull(j.evidenceEntryId)
            val auth = checkNotNull(j.authorizationEntryId)
            transactions += r.transactionId to r.type
            evidence += r.type to entry
            if (r.type == ReceivedRecordType.SALE) sales[r.transactionId] = AcceptedSale(r.transactionId, r.providerTransactionId, auth)
            val used = if (r.type == ReceivedRecordType.SALE) r.nettedCancelAmountMinor else r.recordAmountMinor
            consumed[auth] = (consumed[auth] ?: 0L) + used
        }
    }

    /** 레코드를 순서대로 판정한다(같은 파일 앞 레코드의 수락이 뒤 레코드 판정에 반영된다). */
    private fun judge(acquirer: VirtualAcquirer, file: ReceivedAcquisitionFile): List<JudgedRecord> {
        val view = AcceptanceView(acquirer)
        return file.records.map { r ->
            val scenario = acquirer.scenarios.forRecord(file.header.ref, r)
            val judgement = when (r.type) {
                ReceivedRecordType.SALE -> {
                    val e = van.authorization(acquirer.vanClientId, r.providerTransactionId)?.let {
                        AuthorizationEvidence(it.entryId, it.providerTransactionId, it.approvalNumber, it.amountMinor, it.currency, it.institutionTransactionDate,
                            it.approvedCancelSumMinor)
                    }
                    RecordJudgementRule.judgeSale(
                        r, scenario, view.transactionAccepted(r.transactionId, r.type), e, e?.let { view.evidenceAccepted(r.type, it.entryId) } == true,
                        e?.let { view.consumedCancels(it.entryId) } ?: 0L,
                    )
                }
                ReceivedRecordType.CANCEL -> {
                    val original = view.acceptedSale(checkNotNull(r.originalTransactionId))
                    val e = van.cancellation(acquirer.vanClientId, r.providerTransactionId)?.let {
                        CancellationEvidence(it.entryId, it.originalAuthorizationEntryId, it.providerTransactionId, it.originalProviderTransactionId,
                            it.cancelledAmountMinor, it.currency, it.institutionTransactionDate)
                    }
                    val cancelSum = original?.let { van.authorization(acquirer.vanClientId, it.providerTransactionId)?.approvedCancelSumMinor } ?: 0L
                    RecordJudgementRule.judgeCancel(
                        r, scenario, view.transactionAccepted(r.transactionId, r.type), original, e,
                        e?.let { view.evidenceAccepted(r.type, it.entryId) } == true, original?.let { view.consumedCancels(it.authorizationEntryId) } ?: 0L, cancelSum,
                    )
                }
            }
            if (judgement.result == RecordResult.ACCEPTED) view.accept(r, judgement)
            JudgedRecord(r, judgement, scenario)
        }
    }

    private fun record(acquirer: VirtualAcquirer, receipt: AcquirerReceipt, judged: List<JudgedRecord>, at: Instant) {
        val plan: PublicationPlan = PublicationPlanner.plan(receipt, judged, acquirer.scenarios, acquirers.ackDelay, acquirers.resultDelay)
        val receiptId = receipts.append(receipt)
        receipts.appendJudgements(receiptId, acquirer.code, judged)
        receipts.appendResultLines(receiptId, plan.resultFiles)
        val items = buildList {
            plan.ackDueAt?.let { add(NewPublication(receiptId, acquirer.code, PublicationKind.ACK, receipt.ordinal, AcknowledgementFileV1.fileName(receipt), it)) }
            plan.resultFiles.forEach { f -> add(NewPublication(receiptId, acquirer.code, PublicationKind.RESULT, f.seq, ResultFileV1.fileName(receipt, f.seq), f.dueAt)) }
        }
        publications.schedule(items, at)
        if (plan.ackDueAt == null) log.info("acquirer ACK withheld by scenario (DROP_ACK) acquirer={} fileId={}", acquirer.code, receipt.fileId)
    }

    private fun publishDue(round: Round) {
        val now = clock.now()
        refusedUntil.entries.removeIf { it.value <= now }
        val due = try {
            checkNotNull(tx.execute { publications.due(now, PUBLISH_BATCH, refusedUntil.keys.toSet()) })
        } catch (e: Exception) {
            round.failed++
            log.error("acquirer publication round failed ({}); retried next round", e.javaClass.simpleName)
            return
        }
        due.forEach { p ->
            try {
                publishOne(p, now, round)
            } catch (e: Exception) {
                round.failed++
                log.error("acquirer publication failed acquirer={} name={} ({}); retried next round", p.acquirerCode, p.fileName, e.javaClass.simpleName)
            }
        }
    }

    private fun publishOne(p: PendingPublication, now: Instant, round: Round) {
        val content = checkNotNull(tx.execute { render(p) })
        when (files.publish(p.acquirerCode, p.fileName, content)) {
            PublishOutcome.PUBLISHED, PublishOutcome.ALREADY_PRESENT_SAME -> {
                val recorded = try {
                    tx.execute { publications.markPublished(p, Sha256.hex(content), content.size, clock.now().truncatedTo(ChronoUnit.MILLIS)) } == true
                } catch (e: DuplicateAcquirerRecord) {
                    false // 다른 Worker가 같은 작업을 먼저 기록했다(같은 바이트 — 파일은 그대로).
                }
                if (recorded) {
                    round.published++
                    log.info("acquirer file published acquirer={} name={}", p.acquirerCode, p.fileName)
                }
            }
            PublishOutcome.ALREADY_PRESENT_DIFFERENT -> {
                round.refused++
                if (refusedUntil.put(p.workItemId, now.plus(REFUSED_RECHECK)) == null && reportedNames.add("refused#${p.workItemId}")) {
                    log.error("acquirer outbox already holds different bytes under the planned name; not overwritten acquirer={} name={}", p.acquirerCode, p.fileName)
                }
            }
        }
    }

    private fun render(p: PendingPublication): ByteArray {
        val stored = receipts.receipt(p.receiptId)
        return when (p.kind) {
            PublicationKind.ACK -> AcknowledgementFileV1.render(stored.receipt)
            PublicationKind.RESULT -> ResultFileV1.render(stored.receipt, PlannedResultFile(p.seq, p.dueAt, receipts.resultLines(p.receiptId, p.seq)))
        }
    }

    private companion object {
        /** 받는 파일 상한(16 MiB − 1 — FINANCIAL 봉인 바이트 상한 MEDIUMBLOB과 같다). */
        const val MAX_FILE_BYTES = 16 * 1024 * 1024 - 1
        const val PUBLISH_BATCH = 200

        /** 덮어쓰기를 거부한 작업을 다시 볼 때까지(가상 시계 기준 — Demo). */
        val REFUSED_RECHECK: Duration = Duration.ofMinutes(10)
    }
}

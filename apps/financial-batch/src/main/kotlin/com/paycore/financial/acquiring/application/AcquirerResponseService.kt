package com.paycore.financial.acquiring.application

import com.paycore.financial.acquiring.application.provided.ReceiveAcquirerResponsesUseCase
import com.paycore.financial.acquiring.application.provided.ResponseRunResult
import com.paycore.financial.acquiring.application.provided.ResponseRunResult.Outcome
import com.paycore.financial.acquiring.application.required.AcquirerChannelFailure
import com.paycore.financial.acquiring.application.required.AcquirerFileChannel
import com.paycore.financial.acquiring.application.required.AcquiringClock
import com.paycore.financial.acquiring.application.required.AcquiringPersistenceFailure
import com.paycore.financial.acquiring.application.required.AcquisitionFileRepository
import com.paycore.financial.acquiring.application.required.AcquisitionMembershipStore
import com.paycore.financial.acquiring.application.required.AcquisitionRecordResultStore
import com.paycore.financial.acquiring.application.required.AcquisitionResponseStore
import com.paycore.financial.acquiring.application.required.RecordRef
import com.paycore.financial.acquiring.application.required.ResponseDisposition
import com.paycore.financial.acquiring.application.required.ResponseRecord
import com.paycore.financial.acquiring.domain.AcquirerResponseFormat
import com.paycore.financial.acquiring.domain.AcquirerResponseKind
import com.paycore.financial.acquiring.domain.AcquirerResponseName
import com.paycore.financial.acquiring.domain.AcquirerResponseRule
import com.paycore.financial.acquiring.domain.AcquisitionContractViolation
import com.paycore.financial.acquiring.domain.AcquisitionEnvironment
import com.paycore.financial.acquiring.domain.AcquisitionFile
import com.paycore.financial.acquiring.domain.AcquisitionFileFormat
import com.paycore.financial.acquiring.domain.AcquisitionFileId
import com.paycore.financial.acquiring.domain.AcquisitionRecord
import com.paycore.financial.acquiring.domain.IncomingRecordResult
import com.paycore.financial.acquiring.domain.LineResult
import com.paycore.financial.acquiring.domain.QuarantineReason
import com.paycore.financial.acquiring.domain.RecordResult
import com.paycore.financial.acquiring.domain.RecordResultApplication
import com.paycore.financial.acquiring.domain.RecordResultRule
import com.paycore.financial.acquiring.domain.RecordResultSource
import com.paycore.financial.acquiring.domain.ResponseEvidence
import com.paycore.financial.acquiring.domain.ResponseJudgement
import com.paycore.financial.acquiring.domain.ResultState
import com.paycore.financial.acquiring.domain.TransmissionState
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * [ReceiveAcquirerResponsesUseCase] 구현(ACQUIRING-001 §4.1·§5.1 — 2-F). 외부 호출(SFTP 목록·읽기)은 DB TX 밖이고, 반영은 응답 파일마다 로컬 TX 하나다:
 * ```
 * SFTP(TX 밖): outbox 목록 → 계약 이름·이 lane만 → 아직 기록 안 된 (이름, 크기)만 읽기
 * 응답마다 TX(REQUIRES_NEW, READ COMMITTED): 파일 행 FOR UPDATE → (이름, hash) 기록 확인(멱등) → 전송 중이면 미룸 → 해석·대조(AcquirerResponseRule)
 *   → 파일 행 수신·결과 축(+ hash 일치 응답의 전송 완료 근거) + 응답 처리 기록 + 레코드 결과 행(RecordResultRule) 함께 커밋
 * ```
 * 처리 순서는 파일 ID → ACK(순번) → 결과(회차)다. 한 응답의 예기치 않은 실패는 그 응답만 다음 회차로 넘긴다(error — 파일 ID·이름만, 예외 클래스).
 * 동시 처리(여러 인스턴스): 파일 행 잠금이 같은 파일의 반영을 직렬화하고, UNIQUE(매입사, 이름, hash)·결과 행 PK가 이중 기록을 막는다(위반 → 새 TX로 다시 확인).
 */
@Service
class AcquirerResponseService(
    transactionManager: PlatformTransactionManager,
    private val files: AcquisitionFileRepository,
    private val responses: AcquisitionResponseStore,
    private val results: AcquisitionRecordResultStore,
    private val channel: AcquirerFileChannel,
    private val clock: AcquiringClock,
    private val membership: AcquisitionMembershipStore,
) : ReceiveAcquirerResponsesUseCase {

    private val transactions = AcquiringLocalTransactions(transactionManager, MAX_ATTEMPTS)
    private val log = LoggerFactory.getLogger(AcquirerResponseService::class.java)

    override fun receive(acquirerCode: String, environment: String): ResponseRunResult {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) { "acquirer response receipt must not run inside a transaction" }
        val env = AcquisitionEnvironment.entries.firstOrNull { it.name == environment } ?: throw IllegalArgumentException("environment must be TEST or LIVE")
        if (env == AcquisitionEnvironment.LIVE) {
            log.error("acquirer response receipt for LIVE refused acquirer={} (D-ACQ-008)", logSafe(acquirerCode))
            return ResponseRunResult(Outcome.LIVE_BLOCKED)
        }
        if (!channel.isConfigured(acquirerCode)) {
            log.error("acquirer response receipt not configured acquirer={} — not receiving (fail-closed)", logSafe(acquirerCode))
            return ResponseRunResult(Outcome.NOT_CONFIGURED)
        }
        val fetched = try {
            fetch(acquirerCode, env)
        } catch (e: AcquirerChannelFailure) {
            log.warn("acquirer outbox could not be read acquirer={} failure={}", logSafe(acquirerCode), e.javaClass.simpleName)
            return ResponseRunResult(Outcome.CHANNEL_FAILED)
        }
        var run = ResponseRunResult(Outcome.COMPLETED, errors = fetched.readErrors)
        fetched.files.sortedWith(compareBy({ it.name.fileId.value }, { it.name.kind.ordinal }, { it.name.seq })).forEach { f ->
            run = try {
                run + transactions.execute(::isRetryable) { apply(acquirerCode, env, f) }
            } catch (e: RuntimeException) {
                log.error("acquirer response not applied fileId={} name={} failure={}", f.name.fileId, f.name.name, e.javaClass.simpleName)
                run.copy(errors = run.errors + 1)
            }
        }
        log.info(
            "acquirer responses acquirer={} applied={} quarantined={} deferred={} alreadyProcessed={} decided={} duplicates={} conflicts={} errors={}",
            logSafe(acquirerCode), run.applied, run.quarantined, run.deferred, run.alreadyProcessed, run.recordsDecided, run.duplicates, run.conflicts, run.errors,
        )
        return run
    }

    private class Fetched(val name: AcquirerResponseName, val content: ByteArray)

    private class FetchResult(val files: List<Fetched>, val readErrors: Int)

    /** TX 밖: 목록 → 계약 이름·이 lane → 기록되지 않은 (이름, 크기)만 읽는다. 처리 여부 조회는 짧은 읽기 TX(SFTP 호출을 그 안에서 하지 않는다). */
    private fun fetch(acquirerCode: String, env: AcquisitionEnvironment): FetchResult = channel.open(acquirerCode).use { session ->
        val listing = session.listOutbox().mapNotNull { entry ->
            AcquirerResponseFormat.parseName(entry.name)?.takeIf { it.fileId.acquirerCode == acquirerCode && it.fileId.environment == env }?.let { it to entry.size }
        }
        if (listing.isEmpty()) return@use FetchResult(emptyList(), 0)
        val known = transactions.execute({ false }) { responses.known(acquirerCode, listing.map { it.first.name }) }
        var errors = 0
        val wanted = listing.filter { (name, size) -> known[name.name].orEmpty().none { it.contentBytes == size } }
        val files = mutableListOf<Fetched>()
        for ((name, _) in wanted) {
            try {
                files += Fetched(name, session.readOutbox(name.name))
            } catch (e: AcquirerChannelFailure.Refused) {
                // 읽기 거절(상한 초과 등): 이 파일만 건너뛴다(기록 없음 — hash를 알 수 없다). 다음 회차에 다시 시도한다.
                log.error("acquirer response could not be read fileId={} name={} failure={}", name.fileId, name.name, e.javaClass.simpleName)
                errors++
            } catch (e: AcquirerChannelFailure.Interrupted) {
                // 연결이 끊겼다: 이미 다 읽은 파일만 반영하고 나머지는 다음 회차(부분 바이트는 받지 않는다).
                log.warn("acquirer outbox read interrupted acquirer={} read={} remaining={}", logSafe(acquirerCode), files.size, wanted.size - files.size)
                errors++
                break
            }
        }
        FetchResult(files, errors)
    }

    private fun apply(acquirerCode: String, env: AcquisitionEnvironment, f: Fetched): ResponseRunResult {
        val now = clock.now().truncatedTo(ChronoUnit.MILLIS)
        val sha256 = AcquisitionFileFormat.sha256(f.content)
        // 같은 파일 반영을 직렬화한다(잠금 뒤 처리 기록 조회 = 최신 커밋 값).
        val file = files.lockFile(f.name.fileId)
        val known = responses.known(acquirerCode, listOf(f.name.name))[f.name.name].orEmpty()
        if (known.any { it.contentSha256 == sha256 }) return ResponseRunResult(Outcome.COMPLETED, alreadyProcessed = 1)
        val draft = Draft(acquirerCode, env, f, sha256, now)
        if (known.isNotEmpty()) {
            log.error("acquirer response name reused with different content fileId={} name={} — quarantined (outbox files are never overwritten)", f.name.fileId, f.name.name)
            return quarantine(draft, file?.id, QuarantineReason.NAME_REUSED)
        }
        if (file == null) {
            log.error("acquirer response for an unknown file fileId={} name={} — quarantined", f.name.fileId, f.name.name)
            return quarantine(draft, null, QuarantineReason.UNKNOWN_FILE)
        }
        if (file.transmissionState == TransmissionState.TRANSMITTING) {
            // 전송 시도가 진행 중이다: 그 시도의 @Version 기대값을 깨지 않도록 이번 회차에는 반영하지 않는다(outbox 파일은 남아 다음 회차에 다시 읽는다).
            return ResponseRunResult(Outcome.COMPLETED, deferred = 1)
        }
        return when (f.name.kind) {
            AcquirerResponseKind.ACK -> applyAck(draft, file)
            AcquirerResponseKind.RESULT -> applyResult(draft, file)
        }
    }

    private class Draft(val acquirerCode: String, val env: AcquisitionEnvironment, val fetched: Fetched, val sha256: String, val now: Instant)

    private fun applyAck(d: Draft, file: AcquisitionFile): ResponseRunResult {
        val ack = try {
            AcquirerResponseFormat.parseAck(d.fetched.content)
        } catch (e: AcquisitionContractViolation) {
            log.error("acquirer ack violates the v1 contract fileId={} name={} — quarantined", file.id, d.fetched.name.name)
            return quarantine(d, file.id, QuarantineReason.FORMAT_INVALID)
        }
        if (ack.fileId != d.fetched.name.fileId || ack.ackSeq != d.fetched.name.seq) return quarantine(d, file.id, QuarantineReason.NAME_MISMATCH)
        return when (val j = AcquirerResponseRule.judgeAck(file, ack)) {
            is ResponseJudgement.Quarantine -> {
                files.update(file.recordResponse(j.receiptAfter, false, d.now), d.now)
                log.error("acquirer ack quarantined fileId={} name={} reason={} outcome={}", file.id, d.fetched.name.name, j.reason, ack.outcome)
                quarantine(d, file.id, j.reason, ack.outcome.name, ack.reasonCode, ack.receivedAt)
            }
            is ResponseJudgement.Accept -> {
                var next = file.recordResponse(j.receiptAfter, true, d.now)
                val responseId = record(d, file.id, ResponseDisposition.APPLIED, null, ack.outcome.name, ack.reasonCode, ack.receivedAt, j.evidence)
                var run = ResponseRunResult(Outcome.COMPLETED, applied = 1)
                j.fileRejectionReason?.let { reason ->
                    // D-ACQ-004·I-7: 파일 전체 거절 → 그 파일 레코드가 모두 종결(재매입은 2인 승인 — D-ACQ-011).
                    val records = files.records(file.id)
                    run += applyLines(
                        file.id, records, records.map { it.seq to IncomingRecordResult(LineResult.REJECTED, reason, RecordResultSource.FILE_REJECTED) }, responseId, d.now,
                    )
                    next = summarize(next, records)
                    log.warn("acquisition file rejected by the acquirer fileId={} reason={} records={} — closed, re-acquisition needs two-person approval", file.id, reason, records.size)
                }
                files.update(next, d.now)
                log.info("acquirer ack applied fileId={} outcome={} receipt={} transmission={}", file.id, ack.outcome, next.receiptState, next.transmissionState)
                run
            }
        }
    }

    private fun applyResult(d: Draft, file: AcquisitionFile): ResponseRunResult {
        val result = try {
            AcquirerResponseFormat.parseResult(d.fetched.content)
        } catch (e: AcquisitionContractViolation) {
            log.error("acquirer result file violates the v1 contract fileId={} name={} — quarantined", file.id, d.fetched.name.name)
            return quarantine(d, file.id, QuarantineReason.FORMAT_INVALID)
        }
        if (result.fileId != d.fetched.name.fileId || result.resultSeq != d.fetched.name.seq) return quarantine(d, file.id, QuarantineReason.NAME_MISMATCH)
        val records = files.records(file.id)
        return when (val j = AcquirerResponseRule.judgeResult(file, records, result)) {
            is ResponseJudgement.Quarantine -> {
                files.update(file.recordResponse(j.receiptAfter, false, d.now), d.now)
                log.error("acquirer result file quarantined fileId={} name={} reason={}", file.id, d.fetched.name.name, j.reason)
                quarantine(d, file.id, j.reason, institutionAt = result.producedAt)
            }
            is ResponseJudgement.Accept -> {
                var next = file.recordResponse(j.receiptAfter, true, d.now)
                val responseId = record(d, file.id, ResponseDisposition.APPLIED, null, null, null, result.producedAt, j.evidence)
                val run = ResponseRunResult(Outcome.COMPLETED, applied = 1) +
                    applyLines(
                        file.id, records, result.lines.map { it.recordSeq to IncomingRecordResult(it.result, it.reasonCode, RecordResultSource.RESULT_FILE) }, responseId, d.now,
                    )
                next = summarize(next, records)
                files.update(next, d.now)
                log.info(
                    "acquirer result file applied fileId={} resultSeq={} lines={} decided={} duplicates={} conflicts={} resultState={}",
                    file.id, result.resultSeq, result.lines.size, run.recordsDecided, run.duplicates, run.conflicts, next.resultState,
                )
                run
            }
        }
    }

    /**
     * 레코드별 결과 반영(D-ACQ-010 — append-only, 같은 결과 = 중복, 다른 결과 = CONFLICT 행). ACCEPTED 인정 시각 = [now](FINANCIAL 수신 시각).
     * D-ACQ-014(rev 1.1): 같은 거래·종류의 다른 세대 레코드가 이미 ACCEPTED면 이 ACCEPTED는 CONFLICT 행(격리). 결정 ACCEPTED는 매입 확정 표지(PK(거래, 종류))와 같은 TX에 쓴다.
     */
    private fun applyLines(
        fileId: AcquisitionFileId,
        records: List<AcquisitionRecord>,
        incoming: List<Pair<Int, IncomingRecordResult>>,
        responseId: Long,
        now: Instant,
    ): ResponseRunResult {
        val bySeq = records.associateBy { it.seq }
        val entries = results.entries(fileId).mapValues { it.value.toMutableList() }.toMutableMap()
        var decided = 0
        var duplicates = 0
        var conflicts = 0
        incoming.forEach { (seq, result) ->
            val current = entries.getOrPut(seq) { mutableListOf() }
            val record = bySeq.getValue(seq)
            val ref = RecordRef(fileId, seq)
            val otherGeneration = result.result == LineResult.ACCEPTED &&
                results.acceptedRecordOf(record.transactionId, record.type)?.let { it != ref } == true
            when (val a = RecordResultRule.apply(current, result, now, otherGeneration)) {
                is RecordResultApplication.Append -> {
                    results.append(ref, a.entry, responseId)
                    if (a.entry.result == RecordResult.ACCEPTED) results.markAccepted(ref, record.transactionId, record.type, record.generation, a.entry)
                    current += a.entry
                    decided++
                }
                is RecordResultApplication.Conflict -> {
                    results.append(ref, a.entry, responseId)
                    current += a.entry
                    conflicts++
                    log.error(
                        "acquisition record result conflicts with the recorded result fileId={} seq={} otherGenerationAccepted={} — quarantined (reconcile)",
                        fileId, seq, otherGeneration,
                    )
                    // 리뷰 m-1: 이 레코드를 원 SALE로 "취소 매입 불요" 판정한 취소가 있으면 그 판정 근거(REJECTED)가 흔들린 것이다 — 대사·경보(지표 cancel.not_required_original_conflict).
                    val notRequired = membership.notRequiredCancellationsOf(ref)
                    if (notRequired > 0) {
                        log.error("acquisition original sale of 'cancel not required' records became CONFLICT fileId={} seq={} cancellations={} — reconcile", fileId, seq, notRequired)
                    }
                }
                RecordResultApplication.Duplicate -> duplicates++
                RecordResultApplication.AlreadyConflicted -> conflicts++
            }
        }
        return ResponseRunResult(Outcome.COMPLETED, recordsDecided = decided, duplicates = duplicates, conflicts = conflicts)
    }

    /** 결과 축 요약: 모든 레코드가 결정을 가지면 COMPLETE(AWAITING·DEADLINE_PASSED에서 — 늦은 결과로 완료, UNKNOWN 행은 보존). */
    private fun summarize(file: AcquisitionFile, records: List<AcquisitionRecord>): AcquisitionFile {
        if (file.resultState == ResultState.COMPLETE) return file
        val entries = results.entries(file.id)
        val statuses = records.map { RecordResultRule.status(entries[it.seq].orEmpty()) }
        return if (RecordResultRule.allDecided(statuses)) file.withResultState(ResultState.COMPLETE) else file
    }

    private fun quarantine(
        d: Draft,
        fileId: AcquisitionFileId?,
        reason: QuarantineReason,
        ackOutcome: String? = null,
        ackReasonCode: String? = null,
        institutionAt: Instant? = null,
    ): ResponseRunResult {
        record(d, fileId, ResponseDisposition.QUARANTINED, reason, ackOutcome, ackReasonCode, institutionAt, null)
        return ResponseRunResult(Outcome.COMPLETED, quarantined = 1)
    }

    @Suppress("LongParameterList")
    private fun record(
        d: Draft,
        fileId: AcquisitionFileId?,
        disposition: ResponseDisposition,
        reason: QuarantineReason?,
        ackOutcome: String?,
        ackReasonCode: String?,
        institutionAt: Instant?,
        evidence: ResponseEvidence?,
    ): Long = responses.append(
        ResponseRecord(
            acquirerCode = d.acquirerCode, environment = d.env, fileName = d.fetched.name.name, content = d.fetched.content, contentSha256 = d.sha256,
            kind = d.fetched.name.kind, fileId = fileId, seq = d.fetched.name.seq, disposition = disposition, quarantineReason = reason, ackOutcome = ackOutcome,
            ackReasonCode = ackReasonCode, institutionAt = institutionAt, evidence = evidence, observedAt = d.now,
        ),
    )

    private operator fun ResponseRunResult.plus(o: ResponseRunResult) = ResponseRunResult(
        outcome = outcome, applied = applied + o.applied, quarantined = quarantined + o.quarantined, deferred = deferred + o.deferred,
        alreadyProcessed = alreadyProcessed + o.alreadyProcessed, recordsDecided = recordsDecided + o.recordsDecided, duplicates = duplicates + o.duplicates,
        conflicts = conflicts + o.conflicts, errors = errors + o.errors,
    )

    private fun isRetryable(e: RuntimeException): Boolean =
        e is AcquiringPersistenceFailure.Deadlock || e is AcquiringPersistenceFailure.OptimisticConflict || e is AcquiringPersistenceFailure.UniqueConflict

    private fun logSafe(value: String): String = LOG_UNSAFE.replace(value, "?").take(LOG_MAX)

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val LOG_MAX = 64
        val LOG_UNSAFE = Regex("[^A-Za-z0-9._:-]")
    }
}

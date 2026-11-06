package com.paycore.financial.acquiring.application

import com.paycore.financial.acquiring.application.provided.CloseAcquisitionResultsUseCase
import com.paycore.financial.acquiring.application.provided.CloseRunResult
import com.paycore.financial.acquiring.application.required.AcquiringClock
import com.paycore.financial.acquiring.application.required.AcquiringPersistenceFailure
import com.paycore.financial.acquiring.application.required.AcquisitionFileRepository
import com.paycore.financial.acquiring.application.required.AcquisitionRecordResultStore
import com.paycore.financial.acquiring.application.required.AcquisitionResultPolicy
import com.paycore.financial.acquiring.application.required.RecordRef
import com.paycore.financial.acquiring.domain.AcquisitionEnvironment
import com.paycore.financial.acquiring.domain.AcquisitionFileId
import com.paycore.financial.acquiring.domain.RecordResultRule
import com.paycore.financial.acquiring.domain.ResultState
import com.paycore.financial.acquiring.domain.TransmissionState
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * [CloseAcquisitionResultsUseCase] 구현(2-F — D-ACQ-004). 파일마다 로컬 TX 하나: 파일 행 FOR UPDATE → 다시 확인(결과 축 AWAITING, 전송 완료, 마감 경과) →
 * 결과가 없는 레코드에 UNKNOWN 행(출처 DEADLINE) → 결과 축 DEADLINE_PASSED(모두 결정돼 있으면 COMPLETE). 외부 호출 없음. 마감 기준 = 전송 완료 시각
 * (Implementer 해석 I-49 — 기관이 파일을 받기 전 시간을 마감에 넣지 않는다. 전송이 끝나지 않은 파일은 전송 축 지표·대사가 맡는다).
 */
@Service
class AcquisitionResultCloseService(
    transactionManager: PlatformTransactionManager,
    private val files: AcquisitionFileRepository,
    private val results: AcquisitionRecordResultStore,
    private val policy: AcquisitionResultPolicy,
    private val clock: AcquiringClock,
) : CloseAcquisitionResultsUseCase {

    private val transactions = AcquiringLocalTransactions(transactionManager, MAX_ATTEMPTS)
    private val log = LoggerFactory.getLogger(AcquisitionResultCloseService::class.java)

    override fun closeExpired(acquirerCode: String, environment: String, limit: Int): CloseRunResult {
        require(limit in 1..MAX_BATCH) { "limit must be within 1..$MAX_BATCH" }
        check(!TransactionSynchronizationManager.isActualTransactionActive()) { "result close must not run inside a transaction" }
        val env = AcquisitionEnvironment.entries.firstOrNull { it.name == environment } ?: throw IllegalArgumentException("environment must be TEST or LIVE")
        if (env == AcquisitionEnvironment.LIVE) return CloseRunResult(CloseRunResult.Outcome.LIVE_BLOCKED)
        val deadline = policy.resultDeadline ?: run {
            log.error("acquisition result deadline is not configured — not marking records UNKNOWN (fail-closed)")
            return CloseRunResult(CloseRunResult.Outcome.NOT_CONFIGURED)
        }
        val now = clock.now().truncatedTo(ChronoUnit.MILLIS)
        val ids = transactions.execute({ false }) { files.awaitingResults(acquirerCode, env, now.minus(deadline), limit) }
        var closed = 0
        var unknown = 0
        var errors = 0
        ids.forEach { id ->
            try {
                val n = transactions.execute(::isRetryable) { closeOne(id, now, now.minus(deadline)) }
                if (n != null) {
                    closed++
                    unknown += n
                }
            } catch (e: RuntimeException) {
                errors++
                log.error("acquisition result close failed fileId={} failure={}", id, e.javaClass.simpleName)
            }
        }
        return CloseRunResult(CloseRunResult.Outcome.COMPLETED, closed, unknown, errors)
    }

    /** 반환 = 새 UNKNOWN 행 수(이 파일을 닫지 않았으면 null). */
    private fun closeOne(id: AcquisitionFileId, now: Instant, transmittedUpTo: Instant): Int? {
        val file = files.lockFile(id) ?: return null
        val transmittedAt = file.transmittedAt
        if (file.resultState != ResultState.AWAITING || file.transmissionState != TransmissionState.TRANSMITTED || transmittedAt == null ||
            transmittedAt.isAfter(transmittedUpTo)
        ) {
            return null
        }
        val records = files.records(id)
        val entries = results.entries(id)
        val unknown = records.mapNotNull { r -> RecordResultRule.closeAtDeadline(entries[r.seq].orEmpty(), now)?.let { r.seq to it } }
        unknown.forEach { (seq, entry) -> results.append(RecordRef(id, seq), entry, null) }
        val after = results.entries(id)
        val decided = RecordResultRule.allDecided(records.map { RecordResultRule.status(after[it.seq].orEmpty()) })
        files.update(file.withResultState(if (decided) ResultState.COMPLETE else ResultState.DEADLINE_PASSED), now)
        if (unknown.isNotEmpty()) {
            // D-ACQ-004: UNKNOWN은 대사 입력이다 — 자동 재전송하지 않는다. 늦은 결과는 응답 수신이 반영한다(D-ACQ-010).
            log.error("acquisition records without a result at the deadline fileId={} unknown={} — reconcile, no automatic resend", id, unknown.size)
        }
        return unknown.size
    }

    private fun isRetryable(e: RuntimeException): Boolean =
        e is AcquiringPersistenceFailure.Deadlock || e is AcquiringPersistenceFailure.OptimisticConflict || e is AcquiringPersistenceFailure.UniqueConflict

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val MAX_BATCH = 100
    }
}

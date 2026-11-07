package com.paycore.financial.acquiring.application

import com.paycore.financial.acquiring.application.provided.ExecuteReacquisitionUseCase
import com.paycore.financial.acquiring.application.provided.ReacquisitionExecution
import com.paycore.financial.acquiring.application.provided.ReacquisitionTargetItem
import com.paycore.financial.acquiring.application.provided.ReacquisitionTargetQuery
import com.paycore.financial.acquiring.application.provided.ReacquisitionTargets
import com.paycore.financial.acquiring.application.required.AcquiringClock
import com.paycore.financial.acquiring.application.required.AcquiringPersistenceFailure
import com.paycore.financial.acquiring.application.required.AcquisitionFileRepository
import com.paycore.financial.acquiring.application.required.AcquisitionLaneStore
import com.paycore.financial.acquiring.application.required.AcquisitionMembershipStore
import com.paycore.financial.acquiring.application.required.AcquisitionOpsDecisionStore
import com.paycore.financial.acquiring.application.required.AcquisitionPolicySource
import com.paycore.financial.acquiring.application.required.AcquisitionRecordResultStore
import com.paycore.financial.acquiring.application.required.ReacquisitionApprovalStore
import com.paycore.financial.acquiring.application.required.ReacquisitionExecutionPolicy
import com.paycore.financial.acquiring.domain.AcquisitionFile
import com.paycore.financial.acquiring.domain.AcquisitionFileHeader
import com.paycore.financial.acquiring.domain.AcquisitionFileId
import com.paycore.financial.acquiring.domain.AcquisitionOpsDecision
import com.paycore.financial.acquiring.domain.AcquisitionOpsKind
import com.paycore.financial.acquiring.domain.AcquisitionOpsOrigin
import com.paycore.financial.acquiring.domain.AcquisitionOpsOutcome
import com.paycore.financial.acquiring.domain.AcquisitionRecord
import com.paycore.financial.acquiring.domain.AcquisitionRecordType
import com.paycore.financial.acquiring.domain.ReacquisitionCommand
import com.paycore.financial.acquiring.domain.ReacquisitionExecutionRule
import com.paycore.financial.acquiring.domain.ReacquisitionFunding
import com.paycore.financial.acquiring.domain.ReacquisitionItemTarget
import com.paycore.financial.acquiring.domain.ReacquisitionPlanDecision
import com.paycore.financial.acquiring.domain.ReacquisitionTarget
import com.paycore.financial.acquiring.domain.RecordResultRule
import com.paycore.financial.paymentfact.application.provided.AcquisitionSourcePayment
import com.paycore.financial.paymentfact.application.provided.PaymentFactAcquisitionSource
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 재매입 실행·대상 미리 보기(ACQ-ADMIN-001 B1 — 명세 §3.2). 실행 한 번 = FINANCIAL 로컬 TX 하나(REQUIRES_NEW, READ COMMITTED):
 * ```
 * 결정 기록 조회(승인 ID) → 있으면 같은 내용이면 재생, 다르면 Conflict(아무것도 쓰지 않음)
 * 원 파일 행 FOR UPDATE(응답 TX와 같은 행 — 늦은 결과와 직렬화) → 없으면 REJECTED(TARGET_NOT_FOUND) 결정
 * lane FOR UPDATE(봉인과 같은 행 — 버전 번호·같은 거래의 다른 승인 직렬화) → 결제별 직렬화 행 FOR UPDATE(payment_id 순, 봉인·취소 Inbox와 같은 행)
 * → 레코드 상태·최신 세대·ACCEPTED·불요·취소·보류 읽기 → ReacquisitionExecutionRule.plan
 * → 거절: REJECTED(code) 결정만 / 통과: 새 버전 파일(레코드가 있을 때) → 결정(EXECUTED·NOT_REQUIRED) → 승인 기록 → 불요 기록
 * COMMIT
 * ```
 * 잠금 순서(명세 I-5 — 착수 때 확정): 원 파일 행 → lane → 결제별 행. 봉인 TX(lane → 결제별 행)·응답 TX(파일 행만)·취소 Inbox(결제별 행만)·다른 재매입(같은 순서)과
 * 순환이 없다. 원 파일 행을 먼저 잡는 이유: 없는 파일 ID로 lane 행을 만들지 않는다(lane 잠금은 IODKU). 외부 호출 없음.
 * 같은 승인 ID 동시 두 요청 → 결정 UNIQUE 1062 → TX 전체 rollback(잡아서 계속하지 않는다) → 새 TX가 커밋된 결정을 재생.
 */
@Service
class ReacquisitionExecutionService(
    transactionManager: PlatformTransactionManager,
    private val files: AcquisitionFileRepository,
    private val lanes: AcquisitionLaneStore,
    private val results: AcquisitionRecordResultStore,
    private val membership: AcquisitionMembershipStore,
    private val approvals: ReacquisitionApprovalStore,
    private val decisions: AcquisitionOpsDecisionStore,
    private val source: PaymentFactAcquisitionSource,
    private val policies: AcquisitionPolicySource,
    private val policy: ReacquisitionExecutionPolicy,
    private val clock: AcquiringClock,
    private val integrityEvents: AcquiringIntegrityEvents,
) : ExecuteReacquisitionUseCase, ReacquisitionTargetQuery {

    private val transactions = AcquiringLocalTransactions(transactionManager, MAX_ATTEMPTS)
    private val reads = AcquiringLocalTransactions(transactionManager, 1)
    private val log = LoggerFactory.getLogger(ReacquisitionExecutionService::class.java)

    override fun execute(command: ReacquisitionCommand): ReacquisitionExecution {
        val result = transactions.execute(::isRetryable) { once(command) }
        when (result) {
            is ReacquisitionExecution.Decided -> log.info(
                "re-acquisition decided approvalId={} decision={} code={} resultFile={} replayed={}", command.approvalId, result.decision.outcome,
                result.decision.rejectionCode, result.decision.resultFileId, result.replayed,
            )
            ReacquisitionExecution.Conflict -> log.error("re-acquisition approval id reused with different content approvalId={} — integrity alert", command.approvalId)
            ReacquisitionExecution.NotDecided -> Unit
        }
        return result
    }

    override fun decision(approvalId: String): AcquisitionOpsDecision? = reads.execute({ false }) { decisions.find(approvalId) }

    override fun confirmDeadline(command: ReacquisitionCommand): ReacquisitionExecution = transactions.execute(::isRetryable) {
        val hash = command.contentHash()
        decisions.find(command.approvalId)?.let { return@execute replay(it, hash) }
        val now = now()
        if (now.isBefore(command.executeBefore)) return@execute ReacquisitionExecution.NotDecided
        // D-OPS-009 선례: 결정 없이 기한이 지났다 — 효과 없는 EXPIRED만 기록한다(재매입을 시작하지 않는다).
        val expired = decision(command, hash, AcquisitionOpsOutcome.REJECTED, AcquisitionOpsDecision.EXPIRED, null, now, AcquisitionOpsOrigin.DEADLINE_CONFIRMATION)
        decisions.append(expired)
        log.info("re-acquisition deadline confirmed approvalId={} — expired without execution", command.approvalId)
        ReacquisitionExecution.Decided(expired, replayed = false, confirmed = true)
    }

    override fun targets(fileId: String, seqs: List<Int>): ReacquisitionTargets? {
        val id = runCatching { AcquisitionFileId.parse(fileId) }.getOrNull() ?: return null
        return reads.execute({ false }) {
            files.findFile(id) ?: return@execute null
            val now = now()
            val targets = itemTargets(id, seqs.distinct().sorted(), locked = false)
            val items = seqs.distinct().sorted().map { seq ->
                val t = targets[seq] ?: return@map ReacquisitionTargetItem(seq, null, null, null, null, false, TARGET_NOT_FOUND, null, null, null)
                val (refusal, net) = ReacquisitionExecutionRule.preview(seq, t, now)
                ReacquisitionTargetItem(
                    seq = seq, transactionId = t.target.record.transactionId, recordType = t.target.record.type.name, generation = t.target.record.generation,
                    status = t.target.status.name, eligible = refusal == null, refusal = refusal?.name, approvedAmountMinor = t.target.record.approvedAmountMinor,
                    cancelledAmountMinor = t.funding?.cancellationAmountsMinor?.sum(), expectedNetMinor = net,
                )
            }
            ReacquisitionTargets(id.value, items, now)
        }
    }

    private fun once(command: ReacquisitionCommand): ReacquisitionExecution {
        val hash = command.contentHash()
        decisions.find(command.approvalId)?.let { return replay(it, hash) }
        val original = command.originalFileId
        val file = files.lockFile(original) ?: return reject(command, hash, TARGET_NOT_FOUND, now())
        lanes.lock(original.acquirerCode, original.environment, now())
        // 잠금 뒤 다시 본다(같은 승인 ID의 경쟁 TX가 먼저 커밋했으면 여기서 보인다 — 권위는 UNIQUE(approval_id) + 재시도, 이것은 빠른 경로).
        decisions.find(command.approvalId)?.let { return replay(it, hash) }
        val targets = itemTargets(original, command.items.map { it.seq }, locked = true)
        // rev 0.3(리뷰 m-1): 판정 시각은 **모든 잠금(파일·lane·결제별 행)을 얻은 뒤** 읽는다 — 잠금 대기 중 실행 기한이 지나면 실행하지 않는다(EXPIRED).
        // 기한 판정·decided_at·새 파일 봉인 시각·불요 기록 시각이 모두 이 값이다.
        val now = now()
        return when (val plan = ReacquisitionExecutionRule.plan(command, targets, now, policy.maxExecutionWindow, policy.clockWindow)) {
            is ReacquisitionPlanDecision.Refused -> {
                log.warn("re-acquisition refused approvalId={} reason={} seq={}", command.approvalId, plan.reason, plan.seq)
                reject(command, hash, plan.reason.name, now)
            }
            is ReacquisitionPlanDecision.Planned -> {
                val resultFile = if (plan.records.isEmpty()) null else seal(original, file, plan.records, now) ?: return reject(command, hash, VERSION_EXHAUSTED, now)
                val outcome = if (resultFile == null) AcquisitionOpsOutcome.NOT_REQUIRED else AcquisitionOpsOutcome.EXECUTED
                val recorded = decision(command, hash, outcome, null, resultFile, now, AcquisitionOpsOrigin.EXECUTION_COMMAND)
                decisions.append(recorded)
                approvals.append(plan.approvals)
                approvals.appendNotRequired(plan.notRequired, now)
                ReacquisitionExecution.Decided(recorded, replayed = false)
            }
        }
    }

    /** 새 버전 파일 봉인(같은 lane·업무일 최대 버전 + 1, 원 파일 마감 시각·정책 버전 그대로). 버전 상한(999)을 넘으면 null. */
    private fun seal(original: AcquisitionFileId, file: AcquisitionFile, records: List<AcquisitionRecord>, now: Instant): AcquisitionFileId? {
        val latest = files.latestVersion(original.acquirerCode, original.environment, original.businessDate) ?: original.version
        if (latest >= AcquisitionFileId.MAX_VERSION) return null
        val id = AcquisitionFileId(original.acquirerCode, original.environment, original.businessDate, latest + 1)
        val header = AcquisitionFileHeader(id, policies.senderId, now, file.header.cutoffPolicyVersion, original)
        val (sealed, rendered) = AcquisitionFile.sealReacquisition(header, file.cutoffAt, records)
        files.insert(sealed, rendered)
        // rev 0.3(리뷰 MJ-1, I-17): 새 레코드 상계액(= 원승인액 − 순액)이 판정 기록(원 파일 상계 + 낮은 세대 불요 + 세대 0 CANCEL) 합과 같아야 한다. 다르면 순액 입력(결제 사실
        // 투영)과 판정 기록이 어긋난 것 — 프로그래밍 오류·데이터 손상으로 보고 TX 전체 rollback(봉인 TX의 §16 NIT 검사와 같은 처리). 결정은 기록하지 않는다(ADMIN 503 → 회복).
        if (membership.reacquisitionNettingMismatches(id) != 0) {
            log.error("re-acquisition netting integrity violation fileId={} original={} — rolled back, not sealed", id, original)
            // B2(B1-F 리뷰 n-6): rollback돼 DB gauge에 남지 않으므로 거절 횟수를 따로 센다(label 없는 counter).
            integrityEvents.reacquisitionIntegrityRejected()
            throw IllegalStateException("re-acquisition records disagree with the decided cancellations of their authorizations ($id)")
        }
        log.info("re-acquisition file sealed fileId={} original={} records={} sha256={}", id, original, records.size, sealed.contentSha256)
        return id
    }

    /**
     * 항목 검사 입력. [locked] = 실행(결제별 행 FOR UPDATE — 판정 근거), 아니면 미리 보기(잠금 없음 — 비권위). 순번이 파일에 없으면 그 항목은 빠진다(TARGET_NOT_FOUND).
     */
    private fun itemTargets(fileId: AcquisitionFileId, seqs: List<Int>, locked: Boolean): Map<Int, ReacquisitionItemTarget> {
        val records = files.records(fileId).associateBy { it.seq }.filterKeys { it in seqs }
        val entries = results.entries(fileId)
        val sales = records.values.filter { it.type == AcquisitionRecordType.SALE }.map { it.transactionId }
        val paymentOf = source.paymentIdsOf(sales)
        val payments = paymentOf.values.distinct().let { if (locked) source.lockAndRead(it) else source.readWithoutLock(it) }.associateBy { it.paymentId }
        val cancellationIds = payments.values.flatMap { p -> p.cancellations.filter { it.originalTransactionId in sales }.map { it.transactionId } }
        val decidedCancellations = membership.decidedCancellationIds(cancellationIds)
        return records.mapValues { (_, record) ->
            val status = RecordResultRule.status(entries[record.seq].orEmpty())
            val latest = maxOf(record.generation, approvals.latestGeneration(record.transactionId, record.type) ?: record.generation)
            val accepted = results.acceptedRecordOf(record.transactionId, record.type) != null
            val target = ReacquisitionTarget(fileId, record, status, latest, accepted)
            val funding = paymentOf[record.transactionId]?.let { payments[it] }?.let { funding(record, it, decidedCancellations) }
            ReacquisitionItemTarget(target, approvals.isNotRequired(record.transactionId, record.type), funding)
        }
    }

    /** D-ACQ-013 입력: 원승인액(투영) + 그 원승인의 모든 취소(투영). 통화가 다른 취소가 있으면 null(무결성 거절 — 금액을 섞지 않는다). */
    private fun funding(record: AcquisitionRecord, payment: AcquisitionSourcePayment, decided: Set<String>): ReacquisitionFunding? {
        val authorization = payment.authorizations.singleOrNull { it.transactionId == record.transactionId } ?: return null
        val cancellations = payment.cancellations.filter { it.originalTransactionId == record.transactionId }
        if (cancellations.any { it.currency != authorization.currency }) return null
        return ReacquisitionFunding(
            approvedAmountMinor = authorization.amountMinor, currency = authorization.currency, cancellationAmountsMinor = cancellations.map { it.amountMinor },
            undecidedCancellations = cancellations.count { it.transactionId !in decided }, paymentHeld = payment.unreleasedHoldTransactionIds.isNotEmpty(),
        )
    }

    private fun replay(existing: AcquisitionOpsDecision, hash: String): ReacquisitionExecution =
        if (existing.contentHash == hash) ReacquisitionExecution.Decided(existing, replayed = true) else ReacquisitionExecution.Conflict

    private fun reject(command: ReacquisitionCommand, hash: String, code: String, now: Instant): ReacquisitionExecution {
        val recorded = decision(command, hash, AcquisitionOpsOutcome.REJECTED, code, null, now, AcquisitionOpsOrigin.EXECUTION_COMMAND)
        decisions.append(recorded)
        return ReacquisitionExecution.Decided(recorded, replayed = false)
    }

    @Suppress("LongParameterList")
    private fun decision(
        command: ReacquisitionCommand,
        hash: String,
        outcome: AcquisitionOpsOutcome,
        code: String?,
        resultFile: AcquisitionFileId?,
        now: Instant,
        origin: AcquisitionOpsOrigin,
    ) = AcquisitionOpsDecision(
        approvalId = command.approvalId, kind = AcquisitionOpsKind.REACQUISITION, outcome = outcome, rejectionCode = code, targetRef = command.originalFileId.value,
        resultFileId = resultFile, contentHash = hash, requestedBy = command.requestedBy, approvedBy = command.approvedBy, approvedAt = command.approvedAt,
        executeBefore = command.executeBefore, decidedAt = now, origin = origin,
    )

    private fun now(): Instant = clock.now().truncatedTo(ChronoUnit.MILLIS)

    private fun isRetryable(e: RuntimeException): Boolean =
        e is AcquiringPersistenceFailure.Deadlock || e is AcquiringPersistenceFailure.OptimisticConflict || e is AcquiringPersistenceFailure.UniqueConflict

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val TARGET_NOT_FOUND = "TARGET_NOT_FOUND"
        const val VERSION_EXHAUSTED = "VERSION_EXHAUSTED"
    }
}

package com.paycore.financial.acquiring.application

import com.paycore.financial.acquiring.application.provided.AcquisitionSealRefused
import com.paycore.financial.acquiring.application.provided.AcquisitionSealRefused.Reason
import com.paycore.financial.acquiring.application.provided.NextSealDate
import com.paycore.financial.acquiring.application.provided.SealAcquisitionFileResult
import com.paycore.financial.acquiring.application.provided.SealAcquisitionFileUseCase
import com.paycore.financial.acquiring.application.provided.WaitingEntry
import com.paycore.financial.acquiring.application.required.AcquiringClock
import com.paycore.financial.acquiring.application.required.AcquiringPersistenceFailure
import com.paycore.financial.acquiring.application.required.AcquisitionFileRepository
import com.paycore.financial.acquiring.application.required.AcquisitionLaneStore
import com.paycore.financial.acquiring.application.required.AcquisitionMembershipStore
import com.paycore.financial.acquiring.application.required.AcquisitionPolicySource
import com.paycore.financial.acquiring.application.required.AcquisitionRecordResultStore
import com.paycore.financial.acquiring.application.required.CancelNotRequiredRecord
import com.paycore.financial.acquiring.application.required.ExclusionRecord
import com.paycore.financial.acquiring.application.required.NettingRecord
import com.paycore.financial.acquiring.application.required.RecordRef
import com.paycore.financial.acquiring.domain.AcquisitionCandidateRule
import com.paycore.financial.acquiring.domain.AcquisitionContractViolation
import com.paycore.financial.acquiring.domain.AcquisitionEnvironment
import com.paycore.financial.acquiring.domain.AcquisitionFile
import com.paycore.financial.acquiring.domain.AcquisitionFileHeader
import com.paycore.financial.acquiring.domain.AcquisitionFileId
import com.paycore.financial.acquiring.domain.CancelCandidate
import com.paycore.financial.acquiring.domain.CancelCandidateRule
import com.paycore.financial.acquiring.domain.CancelDecision
import com.paycore.financial.acquiring.domain.CandidateDecision
import com.paycore.financial.acquiring.domain.KnownCancellation
import com.paycore.financial.acquiring.domain.RecordResultRule
import com.paycore.financial.acquiring.domain.RecordStatus
import com.paycore.financial.acquiring.domain.SaleCandidate
import com.paycore.financial.acquiring.domain.WaitReason
import com.paycore.financial.paymentfact.application.provided.AcquisitionCancellationKey
import com.paycore.financial.paymentfact.application.provided.AcquisitionSourceAuthorization
import com.paycore.financial.paymentfact.application.provided.AcquisitionSourcePayment
import com.paycore.financial.paymentfact.application.provided.PaymentFactAcquisitionSource
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * [SealAcquisitionFileUseCase] 구현(ACQUIRING-001 §4.2 배치1 + 2-F CANCEL 연결). 한 봉인 = FINANCIAL 로컬 TX 하나:
 * ```
 * lane IODKU + FOR UPDATE(같은 lane 봉인 직렬화)
 *   → 같은 파일 ID가 있으면 저장 바이트 재렌더링 대조 + 상계 금액 DB 대조 → AlreadySealed(또는 INTEGRITY_VIOLATION — 저장 행이 계약 위반이어도)
 *   → 업무일 순서·마감 경과 확인
 *   → 후보 키(승인 ≤ 마감, 미판정) + 전송 후 취소 키(원승인이 이 lane, 취소 ≤ 마감, 미판정)
 *   → 결제별 직렬화 행 FOR UPDATE(payment_id 순 한 문장) 후 승인·취소·미해제 보류 읽기 → 판정 끝남 재확인
 *   → SALE 판정(Include·Exclude·Wait) + CANCEL 판정(D-ACQ-009: 원 SALE 결과 → Include·NotRequired·Reconcile·Wait)
 *   → 파일 행 + 바이트 + SALE·CANCEL 레코드 + 상계 + 제외 + "취소 매입 불요" + lane 갱신 → 상계 금액 DB 대조(어긋나면 rollback)
 * COMMIT = membership 확정(D-ACQ-012)
 * ```
 * 취소 Inbox TX는 같은 결제별 행을 잠그므로, 이 TX가 잠그기 전 커밋된 취소는 상계되고 잠금을 기다린 취소는 커밋 뒤(= membership 확정 뒤)에 반영되어 다음 봉인의
 * CANCEL 후보가 된다(최종 순액 동일 — D-ACQ-012).
 */
@Service
class AcquisitionSealService(
    transactionManager: PlatformTransactionManager,
    private val lanes: AcquisitionLaneStore,
    private val files: AcquisitionFileRepository,
    private val membership: AcquisitionMembershipStore,
    private val source: PaymentFactAcquisitionSource,
    private val policies: AcquisitionPolicySource,
    private val clock: AcquiringClock,
    private val results: AcquisitionRecordResultStore,
    private val board: AcquisitionWaitingBoard,
) : SealAcquisitionFileUseCase {

    private val transactions = AcquiringLocalTransactions(transactionManager, MAX_ATTEMPTS)
    private val log = LoggerFactory.getLogger(AcquisitionSealService::class.java)

    override fun seal(acquirerCode: String, environment: String, businessDate: LocalDate): SealAcquisitionFileResult {
        val env = AcquisitionEnvironment.entries.firstOrNull { it.name == environment }
            ?: throw AcquisitionSealRefused(Reason.INVALID_LANE, "environment must be TEST or LIVE")
        if (env == AcquisitionEnvironment.LIVE) {
            throw AcquisitionSealRefused(Reason.LIVE_BLOCKED, "LIVE acquisition is blocked until the institution contract reference is delivered (D-ACQ-008)")
        }
        val fileId = try {
            AcquisitionFileId(acquirerCode, env, businessDate, AcquisitionFileId.REGULAR_VERSION)
        } catch (e: AcquisitionContractViolation) {
            throw AcquisitionSealRefused(Reason.INVALID_LANE, "acquirer code must match ^[A-Z0-9]{1,32}$")
        }
        val (result, waits) = transactions.execute(::isRetryable) { once(fileId) }
        when (result) {
            is SealAcquisitionFileResult.Sealed -> {
                log.info(
                    "acquisition file sealed fileId={} records={} excluded={} netted={} waiting={} cancels={} cancelNotRequired={} cancelReconcile={} cancelWaiting={} sha256={}",
                    result.fileId, result.recordCount, result.excluded, result.nettedCancellations, result.waiting, result.cancelRecords, result.cancelNotRequired,
                    result.cancelReconcile, result.cancelWaiting, result.contentSha256,
                )
                // 커밋 뒤에만 지표 원천을 바꾼다(rollback된 판정이 지표에 남지 않게).
                board.report(laneKey(fileId), waits, result.cancelReconcile)
            }
            is SealAcquisitionFileResult.AlreadySealed -> log.info("acquisition file already sealed fileId={} sha256={}", result.fileId, result.contentSha256)
        }
        return result
    }

    override fun nextBusinessDateToSeal(acquirerCode: String, environment: String, firstBusinessDate: LocalDate): NextSealDate {
        val env = AcquisitionEnvironment.entries.firstOrNull { it.name == environment } ?: return NextSealDate.Blocked
        if (env == AcquisitionEnvironment.LIVE) return NextSealDate.Blocked
        val last = transactions.execute({ false }) { lanes.lastSealed(acquirerCode, env) }
        val candidate = last?.plusDays(1) ?: firstBusinessDate
        val cutoff = try {
            policies.cutoffPolicy.cutoffInstant(candidate)
        } catch (e: AcquisitionContractViolation) {
            // 리뷰 m-5: 정책 없음은 "마감 전"과 다르다 — 호출자(스케줄러)가 error 로그·지표로 드러낸다.
            return NextSealDate.NoCutoffPolicy(candidate)
        }
        return if (clock.now().isBefore(cutoff)) NextSealDate.NotDue(candidate) else NextSealDate.Due(candidate)
    }

    private fun once(fileId: AcquisitionFileId): Pair<SealAcquisitionFileResult, List<WaitingEntry>> {
        val now = clock.now().truncatedTo(ChronoUnit.MILLIS)
        val lastSealed = lanes.lock(fileId.acquirerCode, fileId.environment, now)
        // 리뷰 m-3: 이미 봉인된 파일 확인을 마감 정책 조회보다 앞에 둔다(재시작은 정책 설정과 무관하게 저장 상태로 수렴). 다만 저장된 (정책 버전, 마감 시각)이
        // 지금 설정과 다르면 버전 관리(D-ACQ-003)가 깨진 것이므로 거부한다.
        storedFile(fileId)?.let { stored ->
            if (!stored.intact()) integrityViolation(fileId, "stored bytes differ from the stored header and records")
            if (membership.nettingMismatches(fileId) != 0) integrityViolation(fileId, "netting records disagree with the sealed netted amounts")
            val configured = runCatching { policies.cutoffPolicy.let { it.versionFor(fileId.businessDate).version to it.cutoffInstant(fileId.businessDate) } }.getOrNull()
            if (configured != stored.file.header.cutoffPolicyVersion to stored.file.cutoffAt) {
                log.error("acquisition cutoff policy changed for a sealed business day fileId={} storedVersion={}", fileId, stored.file.header.cutoffPolicyVersion)
                throw AcquisitionSealRefused(
                    Reason.CUTOFF_POLICY_CHANGED,
                    "the cutoff policy version or time in effect for ${fileId.businessDate} differs from the sealed file $fileId (add a new version instead)",
                )
            }
            return SealAcquisitionFileResult.AlreadySealed(fileId.value, stored.file.contentSha256) to emptyList()
        }
        val policy = policies.cutoffPolicy
        val version = try {
            policy.versionFor(fileId.businessDate)
        } catch (e: AcquisitionContractViolation) {
            throw AcquisitionSealRefused(Reason.NO_CUTOFF_POLICY, "no cutoff policy is in effect for ${fileId.businessDate}")
        }
        val cutoff = policy.cutoffInstant(fileId.businessDate)
        lastSealed?.let { last ->
            if (fileId.businessDate != last.plusDays(1)) {
                throw AcquisitionSealRefused(Reason.OUT_OF_ORDER, "lane ${fileId.acquirerCode}/${fileId.environment} expects business date ${last.plusDays(1)}")
            }
        }
        if (now.isBefore(cutoff)) throw AcquisitionSealRefused(Reason.BEFORE_CUTOFF, "the cutoff of ${fileId.businessDate} has not passed")

        val keys = source.authorizationKeys(fileId.acquirerCode, fileId.environment.name, cutoff)
        val decided = membership.decidedSaleTransactionIds(keys.map { it.transactionId })
        val pending = keys.filter { it.transactionId !in decided }
        // 2-F(D-ACQ-009): 전송 후 취소 후보 키 — 상계·CANCEL 레코드·"취소 매입 불요" 기록이 없는 것.
        val cancelKeys = source.cancellationKeys(fileId.acquirerCode, fileId.environment.name, cutoff)
        val cancelDecided = membership.decidedCancellationIds(cancelKeys.map { it.transactionId })
        val pendingCancels = cancelKeys.filter { it.transactionId !in cancelDecided }
        val payments = source.lockAndRead((pending.map { it.paymentId } + pendingCancels.map { it.paymentId }).distinct()).associateBy { it.paymentId }
        // 리뷰 m-2: 결제별 행 잠금 뒤 "판정 끝남"을 다시 확인한다. 같은 승인·취소를 판정하는 TX는 모두 그 결제별 행을 잠그므로(READ COMMITTED), 잠금 뒤 보이는
        // 판정 기록이 최종이다 — lane 잠금과 별개로 SALE membership과 제외, CANCEL과 상계가 같은 거래에 겹치지 않는다.
        val decidedUnderLock = membership.decidedSaleTransactionIds(pending.map { it.transactionId })
        val saleWaits = mutableListOf<Pair<String, Instant>>()
        val decisions = pending.filter { it.transactionId !in decidedUnderLock }.map { key ->
            val payment = payments.getValue(key.paymentId)
            val authorization = payment.authorizations.single { it.transactionId == key.transactionId }
            val decision = AcquisitionCandidateRule.decide(candidate(authorization, payment), cutoff)
            if (decision is CandidateDecision.Wait) {
                saleWaits += decision.reason.name to authorization.approvedAt
                if (decision.reason == WaitReason.UNREPRESENTABLE) {
                    // 리뷰 MJ-1: 그 거래만 대기, lane은 계속. 값은 로그에 남기지 않는다(거래 ID만 — 문자 정리).
                    log.error("acquisition candidate not representable in file v1 (waiting) fileId={} transactionId={}", fileId, logSafe(key.transactionId))
                }
            }
            decision
        }
        val included = decisions.filterIsInstance<CandidateDecision.Include>()
        val excluded = decisions.filterIsInstance<CandidateDecision.Exclude>()
        val waiting = decisions.filterIsInstance<CandidateDecision.Wait>().groupingBy { it.reason.name }.eachCount()

        val cancelWaits = mutableListOf<Pair<String, Instant>>()
        val cancelDecisions = decideCancels(fileId, cutoff, pendingCancels, payments, cancelWaits)
        val cancelIncluded = cancelDecisions.filterIsInstance<CancelDecision.Include>()
        val notRequired = cancelDecisions.filterIsInstance<CancelDecision.NotRequired>()
        val reconcile = cancelDecisions.filterIsInstance<CancelDecision.Reconcile>()

        val header = AcquisitionFileHeader(fileId, policies.senderId, now, version.version, null)
        val (file, rendered) = AcquisitionFile.seal(header, cutoff, included, cancelIncluded)
        files.insert(file, rendered)
        val seqByTransaction = rendered.records.associate { it.transactionId to it.seq }
        val nettings = included.flatMap { inc ->
            inc.netted.map { c ->
                NettingRecord(c.transactionId, inc.sale.transactionId, c.amountMinor, c.currency, fileId, seqByTransaction.getValue(inc.sale.transactionId), now)
            }
        } + excluded.flatMap { exc -> exc.netted.map { c -> NettingRecord(c.transactionId, exc.sale.transactionId, c.amountMinor, c.currency, null, null, now) } }
        membership.insertNettings(nettings)
        membership.insertExclusions(
            excluded.map {
                ExclusionRecord(
                    it.sale.transactionId, fileId.acquirerCode, fileId.environment, it.reason, it.sale.amountMinor, it.nettedAmountMinor, it.sale.currency,
                    fileId.businessDate, now,
                )
            },
        )
        membership.insertCancelNotRequired(
            notRequired.map {
                val c = it.candidate
                CancelNotRequiredRecord(
                    c.transactionId, c.original.transactionId, c.originalSaleFileId, c.originalSale.seq, c.amountMinor, c.currency, fileId.businessDate, now,
                )
            },
        )
        lanes.markSealed(fileId.acquirerCode, fileId.environment, fileId.businessDate, now)
        // §16 리뷰 NIT(대사 입력 무결성): 방금 쓴 상계 기록 합 = 레코드 상계액, 제외 승인액 = 상계 합. 어긋나면 프로그래밍 오류 — 전부 rollback.
        if (membership.nettingMismatches(fileId) != 0) integrityViolation(fileId, "netting records disagree with the netted amounts being sealed")
        if (waiting.isNotEmpty()) log.warn("acquisition candidates waiting fileId={} reasons={}", fileId, waiting)
        val cancelWaiting = cancelWaits.groupingBy { it.first }.eachCount()
        val lane = laneKey(fileId)
        val entries = AcquisitionWaitingBoard.group(lane, SALE, saleWaits) + AcquisitionWaitingBoard.group(lane, CANCEL, cancelWaits)
        return SealAcquisitionFileResult.Sealed(
            fileId = fileId.value, contentSha256 = file.contentSha256, recordCount = rendered.totals.recordCount, saleAmountMinor = rendered.totals.saleAmountMinor,
            nettedCancellations = nettings.size, excluded = excluded.size, waiting = waiting, cancelRecords = cancelIncluded.size,
            cancelNotRequired = notRequired.size, cancelReconcile = reconcile.size, cancelWaiting = cancelWaiting,
        ) to entries
    }

    /** D-ACQ-009 판정. 같은 원승인의 여러 취소는 (취소 시각, 거래 ID) 순으로 누적 금액을 더해 가며 본다(이번 봉인에 넣는 CANCEL 포함). */
    private fun decideCancels(
        fileId: AcquisitionFileId,
        cutoff: Instant,
        pendingCancels: List<AcquisitionCancellationKey>,
        payments: Map<String, AcquisitionSourcePayment>,
        waits: MutableList<Pair<String, Instant>>,
    ): List<CancelDecision> {
        if (pendingCancels.isEmpty()) return emptyList()
        val underLock = membership.decidedCancellationIds(pendingCancels.map { it.transactionId })
        val candidates = pendingCancels.filter { it.transactionId !in underLock }
        val originals = candidates.map { it.originalTransactionId }.distinct()
        val memberships = membership.saleMemberships(originals)
        val cancelled = membership.cancelledAmounts(originals).toMutableMap()
        val statuses = results.entriesOf(memberships.values.map { RecordRef(it.fileId, it.record.seq) }).mapValues { RecordResultRule.status(it.value) }
        val ordered = candidates.mapNotNull { key ->
            val payment = payments.getValue(key.paymentId)
            val cancellation = payment.cancellations.single { it.transactionId == key.transactionId }
            // 원승인이 아직 membership이 아니면(이번 봉인에 SALE로 들어가 상계되거나 대기 중) 전송 후 취소가 아니다.
            val sale = memberships[key.originalTransactionId] ?: return@mapNotNull null
            val authorization = payment.authorizations.singleOrNull { it.transactionId == key.originalTransactionId } ?: return@mapNotNull null
            Triple(key, cancellation, sale) to authorization
        }.sortedWith(compareBy({ it.first.second.cancelledAt }, { it.first.first.transactionId }))
        return ordered.map { (triple, authorization) ->
            val (key, cancellation, sale) = triple
            val payment = payments.getValue(key.paymentId)
            val candidate = CancelCandidate(
                transactionId = cancellation.transactionId, original = candidate(authorization, payment), originalSaleFileId = sale.fileId, originalSale = sale.record,
                alreadyCancelledMinor = cancelled[key.originalTransactionId] ?: 0L, merchantAccountId = cancellation.merchantAccountId,
                amountMinor = cancellation.amountMinor, currency = cancellation.currency, providerTransactionId = cancellation.providerTransactionId,
                originalProviderTransactionId = cancellation.originalProviderTransactionId, institutionTransactionDate = cancellation.institutionTransactionDate,
                cancelledAt = cancellation.cancelledAt, held = cancellation.transactionId in payment.unreleasedHoldTransactionIds,
            )
            val status = statuses[RecordRef(sale.fileId, sale.record.seq)] ?: RecordStatus.AWAITING
            val decision = CancelCandidateRule.decide(candidate, RecordResultRule.toOriginalSaleResult(status), cutoff)
            when (decision) {
                is CancelDecision.Include -> cancelled[key.originalTransactionId] = Math.addExact(cancelled[key.originalTransactionId] ?: 0L, cancellation.amountMinor)
                is CancelDecision.Wait -> {
                    waits += decision.reason.name to cancellation.cancelledAt
                    if (decision.reason == WaitReason.UNREPRESENTABLE) {
                        log.error("acquisition cancellation not representable in file v1 (waiting) fileId={} transactionId={}", fileId, logSafe(key.transactionId))
                    }
                }
                is CancelDecision.Reconcile -> log.error(
                    "acquisition cancellation after membership whose original sale is {} — not sent, reconcile fileId={} transactionId={} original={}",
                    decision.originalResult, fileId, logSafe(key.transactionId), logSafe(key.originalTransactionId),
                )
                is CancelDecision.NotRequired -> log.info(
                    "acquisition cancellation not required (original sale rejected) fileId={} transactionId={}", fileId, logSafe(key.transactionId),
                )
            }
            decision
        }
    }

    /** 저장 파일 읽기. 저장 행이 계약(문법·불변식)을 어기면 INTEGRITY_VIOLATION(리뷰 NIT-3 — 재시작이 예외로 lane을 멈추지 않고 분류된 거부로 끝난다). */
    private fun storedFile(fileId: AcquisitionFileId) = try {
        files.find(fileId)
    } catch (e: AcquisitionContractViolation) {
        integrityViolation(fileId, "stored rows violate the acquisition file v1 contract")
    }

    private fun integrityViolation(fileId: AcquisitionFileId, why: String): Nothing {
        log.error("acquisition file integrity violation fileId={} — {}; not transmitting", fileId, why)
        throw AcquisitionSealRefused(Reason.INTEGRITY_VIOLATION, "stored file $fileId: $why")
    }

    private fun candidate(a: AcquisitionSourceAuthorization, payment: AcquisitionSourcePayment) = SaleCandidate(
        transactionId = a.transactionId, paymentId = a.paymentId, environment = AcquisitionEnvironment.valueOf(a.environment),
        merchantAccountId = a.merchantAccountId, acquirerCode = a.acquirerCode, amountMinor = a.amountMinor, currency = a.currency, planKind = a.planKind,
        installmentMonths = a.installmentMonths, cardType = a.cardType, issuerCode = a.issuerCode, approvalNumber = a.approvalNumber,
        providerTransactionId = a.providerTransactionId, institutionTransactionDate = a.institutionTransactionDate, approvedAt = a.approvedAt,
        held = a.transactionId in payment.unreleasedHoldTransactionIds,
        paymentHeld = payment.unreleasedHoldTransactionIds.isNotEmpty(),
        cancellations = payment.cancellations.filter { it.originalTransactionId == a.transactionId }.map {
            KnownCancellation(it.transactionId, it.amountMinor, it.currency, it.transactionId in payment.unreleasedHoldTransactionIds)
        },
    )

    private fun laneKey(fileId: AcquisitionFileId) = "${fileId.acquirerCode}/${fileId.environment.name}"

    private fun logSafe(value: String): String = LOG_UNSAFE.replace(value, "?").take(LOG_MAX)

    private fun isRetryable(e: RuntimeException): Boolean =
        e is AcquiringPersistenceFailure.Deadlock || e is AcquiringPersistenceFailure.OptimisticConflict || e is AcquiringPersistenceFailure.UniqueConflict

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val LOG_MAX = 64
        const val SALE = "SALE"
        const val CANCEL = "CANCEL"
        val LOG_UNSAFE = Regex("[^A-Za-z0-9._:-]")
    }
}

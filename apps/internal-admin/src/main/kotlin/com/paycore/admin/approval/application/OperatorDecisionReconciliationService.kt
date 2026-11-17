package com.paycore.admin.approval.application

import com.paycore.admin.approval.application.provided.ReconcileOperatorDecisionsUseCase
import com.paycore.admin.approval.application.provided.ReconciliationReport
import com.paycore.admin.approval.application.required.ApprovalPersistenceFailure
import com.paycore.admin.approval.application.required.ApprovalRequestRepository
import com.paycore.admin.approval.application.required.OperatorCancelCommandStore
import com.paycore.admin.approval.application.required.OwnerExecutionRepository
import com.paycore.admin.approval.application.required.OwnerOpsMetrics
import com.paycore.admin.approval.application.required.ReconciliationCheckpoint
import com.paycore.admin.approval.application.required.ReconciliationCheckpointStore
import com.paycore.admin.approval.application.required.ReconciliationFindingRecord
import com.paycore.admin.approval.application.required.ReconciliationFindingStore
import com.paycore.admin.approval.domain.ApprovalStatus
import com.paycore.admin.approval.domain.ApprovalTarget
import com.paycore.admin.approval.domain.OperatorDecisionReconciliation
import com.paycore.admin.approval.domain.ReconciliationFindingKind
import com.paycore.admin.audit.application.provided.AdminAuditRecorder
import com.paycore.admin.paymentops.application.provided.DecisionListItem
import com.paycore.admin.paymentops.application.provided.DecisionPage
import com.paycore.admin.paymentops.application.provided.PaymentDecisionListQuery
import com.paycore.admin.paymentops.application.provided.PaymentOpsPolicy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import java.time.Duration
import java.time.Instant

/**
 * [ReconcileOperatorDecisionsUseCase](ADMIN-PAYMENT-OPS-001 §4.17, D-OPS-008, I-30·I-36). **승인 상태를 바꾸지 않는다** — 발견 기록·감사·지표만(ADMIN 수렴은 회복 job).
 * PAYMENT에는 결정 목록 조회 전용 Port([PaymentDecisionListQuery])만 쓴다 — 실행 POST·기한 확인을 보낼 수 없다(S7 리뷰 L-2, ArchitectureSpec).
 * - 증분: checkpoint watermark를 잠그지 않고 읽어 결정 목록을 최대 N페이지 읽는다. 페이지마다 항목 판정 → 발견 INSERT(발견마다 짧은 TX, UNIQUE 1062는 "이미 기록됨" —
 *   지표만) → checkpoint를 PAYMENT의 **안정 watermark**(`nextWatermark` — 반환한 최대 seq가 아니다)로 전진(잠금 짧은 TX, 뒤로 가지 않음). 판정 오류 항목이 있으면
 *   **그 항목 직전 seq까지만** 전진하고 멈춘다 — 다음 실행이 그 항목을 다시 본다(S7 리뷰 L-3, I-119).
 * - 전체 스캔(최종 안전망): 마지막 전체 스캔이 주기보다 오래됐으면 seq 0부터 끝까지 다시 읽는다. 다음 페이지 기준은 **페이지의 최대 seq**(전체 스캔은 watermark를 저장하지
 *   않는다 — M-1(b)). 그 뒤 `MISSING_DECISION` 후보 = 실행 행 **마지막 응답 시각**이 `스캔 시작 − (안정 지연 + 시각 여유)`보다 이른 종료 승인(늦게 커밋되는 결정·스캔 중
 *   종료를 오탐하지 않게 — M-1(a), I-120), PAYMENT 400 응답으로 끝난 승인은 제외(결정 행이 없는 것이 정상 — 분류기 400 경로와 같은 code 집합, M-1(c)).
 *   checkpoint watermark는 바꾸지 않는다(`last_full_scan_at`만).
 * - `last_run_at`은 **완전 성공**(PAYMENT 장애·판정 오류·미완료 전체 스캔 없음)일 때만 갱신한다 — lag gauge = 마지막 완전 성공 이후(S7 리뷰 L-4).
 * - ADMIN 쪽 대조 재료는 승인·명령·실행 행의 비잠금 읽기(JPA find — I-116).
 */
@Service
class OperatorDecisionReconciliationService(
    transactionManager: PlatformTransactionManager,
    private val repository: ApprovalRequestRepository,
    private val commands: OperatorCancelCommandStore,
    private val executions: OwnerExecutionRepository,
    private val checkpoints: ReconciliationCheckpointStore,
    private val findings: ReconciliationFindingStore,
    private val recorder: AdminAuditRecorder,
    private val decisions: PaymentDecisionListQuery,
    private val policy: PaymentOpsPolicy,
    private val opsMetrics: OwnerOpsMetrics,
    private val memory: ReconciliationRunMemory,
) : ReconcileOperatorDecisionsUseCase {

    private val transactions = LocalTransactions(transactionManager, maxAttempts = 1)

    /** S7 리뷰 L-A: 완전 성공이 한 번도 없으면(`last_run_at` NULL) lag는 이 인스턴스 기동 시각부터 센다(0으로 숨기지 않는다 — checkpoint 행에 생성 시각 열이 없다). */
    private val startedAt: Instant = policy.now()

    private class Counter(var scanned: Int = 0, var recorded: Int = 0, var itemErrors: Int = 0)

    /** [ITEM_ERRORS]: 전체 스캔을 끝까지 읽었지만 항목 판정·기록 오류가 있어 완료로 표시하지 않음(S7 리뷰 L-C — 다음 실행에 다시 전체 스캔). */
    private enum class Scan { DONE, PAYMENT_UNAVAILABLE, INCOMPLETE, ITEM_ERRORS }

    override fun reconcile(): ReconciliationReport {
        val now = policy.now()
        val start = transactions.execute({ false }) { checkpoints.read() }
        val counter = Counter()
        var scan = incremental(start.watermark, now, counter)
        var fullScan = false
        if (scan == Scan.DONE && fullScanDue(start, now)) {
            scan = fullScan(counter)
            fullScan = scan == Scan.DONE
        }
        val result = when {
            scan == Scan.PAYMENT_UNAVAILABLE -> PAYMENT_UNAVAILABLE
            scan == Scan.INCOMPLETE -> FULL_SCAN_INCOMPLETE
            counter.itemErrors > 0 -> ITEM_ERROR
            else -> OK
        }
        if (result == OK) transactions.execute({ false }) { checkpoints.markRun(now) }
        opsMetrics.reconRun(result)
        memory.record(result)
        opsMetrics.reconLag(if (result == OK) 0 else Duration.between(start.lastRunAt ?: startedAt, now).seconds.coerceAtLeast(0))
        refreshOpenFindings()
        log.info(
            "operation=ops-reconciliation result={} scanned={} recorded={} itemErrors={} fullScan={}", result, counter.scanned, counter.recorded, counter.itemErrors, fullScan,
        )
        return ReconciliationReport(result, counter.scanned, counter.recorded, fullScan)
    }

    private fun fullScanDue(start: ReconciliationCheckpoint, now: Instant): Boolean =
        start.lastFullScanAt == null || !now.isBefore(start.lastFullScanAt.plus(policy.reconciliationFullScanInterval))

    /** 증분. 중간 페이지까지의 전진은 남는다(PAYMENT 장애로 끝나도). */
    private fun incremental(from: Long, now: Instant, counter: Counter): Scan {
        var watermark = from
        var pages = 0
        while (pages++ < policy.reconciliationMaxPages) {
            val page = decisions.decisionsAfter(watermark) as? DecisionPage.Page ?: return Scan.PAYMENT_UNAVAILABLE
            val failedSeq = page.items.filterNot { judgeAndRecord(it, now, counter) }.minOfOrNull { it.seq }
            // L-3(I-119): 판정 오류 항목이 있으면 그 직전까지만 — 오류 항목과 그 뒤는 다음 실행에 다시 본다(이미 기록된 발견은 UNIQUE로 한 번만).
            val target = if (failedSeq == null) page.nextWatermark else minOf(page.nextWatermark, failedSeq - 1)
            val advanced = target > watermark
            if (advanced) {
                watermark = target
                transactions.execute({ false }) { checkpoints.advance(target) }
            }
            if (failedSeq != null || !advanced || page.items.isEmpty()) break
        }
        return Scan.DONE
    }

    /** seq 0부터 끝까지. 다음 페이지 기준은 읽은 최대 seq(M-1(b)) — watermark는 바꾸지 않는다. */
    private fun fullScan(counter: Counter): Scan {
        val scanStart = policy.now()
        val errorsAtScanStart = counter.itemErrors
        val seen = HashSet<String>()
        var after = 0L
        var pages = 0
        var complete = false
        // 상한(MAX_FULL_SCAN_PAGES × 200행)은 Demo 규모 안전장치 — 운영 규모·주기·방식은 §13 LIVE Gate(전체 스캔 규모·주기).
        while (pages++ < MAX_FULL_SCAN_PAGES) {
            val page = decisions.decisionsAfter(after) as? DecisionPage.Page ?: return Scan.PAYMENT_UNAVAILABLE
            page.items.forEach {
                seen += it.approvalId
                judgeAndRecord(it, scanStart, counter)
            }
            val last = page.items.maxOfOrNull { it.seq }
            if (last == null || last <= after) {
                complete = true
                break
            }
            after = last
        }
        if (!complete) {
            // 끝까지 읽지 못하면 MISSING_DECISION을 판정하지 않는다(본 집합이 불완전 — 오탐 방지). 다음 실행에 다시(경보: run{result=FULL_SCAN_INCOMPLETE}).
            log.warn("operation=ops-reconciliation-full-scan result=incomplete pages={}", MAX_FULL_SCAN_PAGES)
            return Scan.INCOMPLETE
        }
        val cutoff = scanStart.minus(policy.decisionStableDelay.plus(policy.deadlineSkew))
        val decisionless = decisions.decisionlessRejectionCodes
        val terminal = transactions.execute({ false }) { repository.terminalOwnerApprovals(respondedBefore = cutoff) }
        terminal.filter { it.id !in seen && !(it.status == ApprovalStatus.OWNER_REJECTED && it.resultCode in decisionless) }.forEach { approval ->
            try {
                record(ReconciliationFindingKind.MISSING_DECISION, approval.id, (approval.target as? ApprovalTarget.Payment)?.paymentId, null, scanStart, counter)
            } catch (e: RuntimeException) {
                counter.itemErrors++
                log.warn("operation=ops-reconciliation-missing approvalId={} result=error failure={}", approval.id, e.javaClass.simpleName)
            }
        }
        // S7 리뷰 L-C: 이 전체 스캔에서 항목 오류가 하나라도 있었으면 완료로 표시하지 않는다(다음 실행이 다시 전체 스캔 — I-119).
        if (counter.itemErrors > errorsAtScanStart) return Scan.ITEM_ERRORS
        transactions.execute({ false }) { checkpoints.markFullScan(scanStart) }
        return Scan.DONE
    }

    private fun refreshOpenFindings() {
        try {
            opsMetrics.openFindings(transactions.execute({ false }) { findings.openCounts() })
        } catch (e: RuntimeException) {
            log.warn("operation=ops-reconciliation-open-findings result=error failure={}", e.javaClass.simpleName)
        }
    }

    /** 항목 하나 판정·기록. 예외면 false(그 항목만 — 호출자가 watermark를 그 앞에 멈춘다). */
    private fun judgeAndRecord(item: DecisionListItem, now: Instant, counter: Counter): Boolean {
        counter.scanned++
        return try {
            val admin = adminView(item.approvalId)
            val decision = OperatorDecisionReconciliation.PaymentDecision(
                item.approvalId, item.decision, item.code, item.cancellationId, item.content.paymentId, item.content.amountMinor, item.content.currency,
                item.content.requestedBy, item.content.approvedBy, item.content.approvedTargetFingerprint, item.recordedAt,
            )
            val unresolvedAfter = policy.decisionStableDelay.plus(policy.backoffMax)
            OperatorDecisionReconciliation.judge(decision, admin, now, unresolvedAfter)?.let { record(it.kind, item.approvalId, item.content.paymentId, it.detail, now, counter) }
            true
        } catch (e: RuntimeException) {
            counter.itemErrors++
            log.warn("operation=ops-reconciliation-item approvalId={} seq={} result=error failure={}", item.approvalId, item.seq, e.javaClass.simpleName)
            false
        }
    }

    private fun adminView(approvalId: String): OperatorDecisionReconciliation.AdminView? = transactions.execute({ false }) {
        val request = repository.find(approvalId) ?: return@execute null
        val command = commands.find(approvalId)
        val execution = executions.find(approvalId)
        OperatorDecisionReconciliation.AdminView(
            request.kind, request.status, request.resultCode, (request.target as? ApprovalTarget.Payment)?.paymentId, request.requestedBy, command?.amountMinor,
            command?.currency, execution?.executorActorId, execution?.fingerprint, execution?.ownerReference,
        )
    }

    @Suppress("LongParameterList")
    private fun record(kind: ReconciliationFindingKind, approvalId: String, paymentRef: String?, detail: String?, now: Instant, counter: Counter) {
        try {
            transactions.execute({ false }) {
                findings.record(ReconciliationFindingRecord(kind, approvalId, paymentRef?.take(MAX_REF), detail, now))
                // MERCHANT-CODE-001 MC-3a(§5.6.4 — C-36): 대상 열은 ApprovalAuditTarget에서만(형식 검사한 paymentId — 값 불변).
                recorder.record(
                    ApprovalAuditTarget.ofPaymentRef(paymentRef?.takeIf { PAYMENT_ID.matches(it) }).auditRecord(
                        occurredAt = now, actorKind = "SYSTEM_JOB", actorId = null, actorDisplayName = null, action = "OPS_RECON_FINDING", resultCode = kind.name,
                        correlationId = "job-reconciliation", approvalId = approvalId.takeIf { APPROVAL_ID.matches(it) },
                        detail = listOfNotNull("kind=${kind.name}", detail?.let { "detail=$it" }).joinToString(";"),
                    ),
                )
            }
            counter.recorded++
            opsMetrics.reconFinding(kind.name)
        } catch (_: ApprovalPersistenceFailure.DuplicateFinding) {
            opsMetrics.reconFindingDuplicate()
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(OperatorDecisionReconciliationService::class.java)
        const val OK = "OK"
        const val PAYMENT_UNAVAILABLE = "PAYMENT_UNAVAILABLE"
        const val ITEM_ERROR = "ITEM_ERROR"
        const val FULL_SCAN_INCOMPLETE = "FULL_SCAN_INCOMPLETE"

        /** 전체 스캔 페이지 상한(Demo 안전장치 — 200행 × 100,000 = 2천만 결정). 운영 규모·주기·방식은 §13 LIVE Gate "전체 스캔 규모·주기". */
        const val MAX_FULL_SCAN_PAGES = 100_000
        const val MAX_REF = 64
        val APPROVAL_ID = Regex("^apr_[0-9A-HJKMNP-TV-Z]{26}$")
        val PAYMENT_ID = Regex("^pay_[0-9A-HJKMNP-TV-Z]{26}$")
    }
}

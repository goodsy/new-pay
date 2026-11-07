package com.paycore.financial.acquiring.domain

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 재매입 실행 판정(ACQ-ADMIN-001 B1 — 명세 §3.2). ADMIN 2인 승인 명령(같은 원 파일의 레코드 묶음 — D-ACQA-003)을 FINANCIAL이 다시 검사하고(D-ADMIN-007), 통과하면
 * 새 버전 파일에 넣을 레코드와 "재매입 불요" 종결을 정한다. 순수 규칙이다(잠금·저장은 Application).
 *
 * 사용자 결정:
 * - D-ACQ-011: 재매입은 2인 승인 — 요청자 ≠ 승인자.
 * - D-ACQ-013: 재매입 레코드 금액 = 원승인액 − 실행 시점까지 받은 모든 취소(각 한 번) — [ReacquisitionRule.netAmountMinor].
 * - D-ACQ-014: UNKNOWN 대상은 대사 미매입 확정 근거 전 거절, 어느 세대든 ACCEPTED면 거절 — 레코드마다 [ReacquisitionRule.decide].
 * - D-ACQA-003: 승인 하나 = 같은 원 파일 레코드 1..[MAX_ITEMS]건. **하나라도 거절이면 승인 전체 거절**(부분 실행 없음).
 * - D-ACQA-004: 순액 0이면 레코드를 만들지 않고 "재매입 불요"로 종결한다.
 *
 * Implementer 해석(가역 — 명세 §11): 실행 기한 검사(I-2, D-OPS-002 선례), CANCEL 레코드는 B1 범위 밖(I-7 — CANCEL_NOT_SUPPORTED), 아직 판정 안 된 취소·미해제 보류가
 * 있으면 거절(취소를 한 번씩만 셀 수 없거나 사실이 확정되지 않았다 — fail-closed).
 */
object ReacquisitionExecutionRule {

    const val MAX_ITEMS = 50

    fun plan(
        command: ReacquisitionCommand,
        targets: Map<Int, ReacquisitionItemTarget>,
        now: Instant,
        maxExecutionWindow: Duration,
        clockWindow: Duration,
    ): ReacquisitionPlanDecision {
        if (Duration.between(command.approvedAt, command.executeBefore) > maxExecutionWindow || command.approvedAt.isAfter(now.plus(clockWindow))) {
            return ReacquisitionPlanDecision.Refused(ReacquisitionRefusal.APPROVAL_WINDOW_INVALID, null)
        }
        if (!now.isBefore(command.executeBefore)) return ReacquisitionPlanDecision.Refused(ReacquisitionRefusal.APPROVAL_EXECUTION_EXPIRED, null)
        if (command.requestedBy == command.approvedBy) return ReacquisitionPlanDecision.Refused(ReacquisitionRefusal.SAME_PERSON, null)
        val approvals = mutableListOf<ReacquisitionApproval>()
        val sends = mutableListOf<Pair<ReacquisitionApproval, Long>>()
        val notRequired = mutableListOf<ReacquisitionNotRequired>()
        for (item in command.items) {
            val target = targets[item.seq] ?: return ReacquisitionPlanDecision.Refused(ReacquisitionRefusal.TARGET_NOT_FOUND, item.seq)
            if (target.notRequired) return ReacquisitionPlanDecision.Refused(ReacquisitionRefusal.ALREADY_NOT_REQUIRED, item.seq)
            val approval = when (val d = ReacquisitionRule.decide(command.itemCommand(item), target.target, now)) {
                is ReacquisitionDecision.Refused -> return ReacquisitionPlanDecision.Refused(d.reason, item.seq)
                is ReacquisitionDecision.Approved -> d.approval
            }
            if (approval.recordType != AcquisitionRecordType.SALE) return ReacquisitionPlanDecision.Refused(ReacquisitionRefusal.CANCEL_NOT_SUPPORTED, item.seq)
            val funding = target.funding ?: return ReacquisitionPlanDecision.Refused(ReacquisitionRefusal.TARGET_INCONSISTENT, item.seq)
            if (funding.paymentHeld) return ReacquisitionPlanDecision.Refused(ReacquisitionRefusal.PAYMENT_HELD, item.seq)
            if (funding.undecidedCancellations > 0) return ReacquisitionPlanDecision.Refused(ReacquisitionRefusal.CANCELLATION_UNDECIDED, item.seq)
            if (funding.approvedAmountMinor != target.target.record.approvedAmountMinor || funding.currency != target.target.record.currency) {
                return ReacquisitionPlanDecision.Refused(ReacquisitionRefusal.TARGET_INCONSISTENT, item.seq)
            }
            val net = try {
                ReacquisitionRule.netAmountMinor(funding.approvedAmountMinor, funding.cancellationAmountsMinor)
            } catch (e: AcquisitionContractViolation) {
                return ReacquisitionPlanDecision.Refused(ReacquisitionRefusal.TARGET_INCONSISTENT, item.seq)
            }
            approvals += approval
            if (net == 0L) {
                notRequired += ReacquisitionNotRequired(approval, funding.approvedAmountMinor, funding.approvedAmountMinor, funding.currency)
            } else {
                sends += approval to net
            }
        }
        val records = sends.mapIndexed { i, (approval, net) ->
            val original = targets.getValue(approval.seq).target.record
            original.copy(
                seq = i + 1, nettedCancelAmountMinor = original.approvedAmountMinor - net, recordAmountMinor = net, generation = approval.newGeneration,
                reacquisitionOf = approval.reacquisitionOf,
            )
        }
        return ReacquisitionPlanDecision.Planned(approvals, records, notRequired)
    }

    /**
     * 미리 보기(ADMIN 요청 화면·승인 A 단계 — 비권위): 지금 상태 그대로 요청한다면 [plan]이 이 항목을 어떻게 판정하는가. 사람·기한 검사는 빼고(요청 전이라 없다) 같은 항목
     * 규칙을 그대로 쓴다(규칙 중복 없음). 결과 = (거절 사유 또는 null, 예상 순액 또는 null).
     */
    fun preview(seq: Int, target: ReacquisitionItemTarget, now: Instant): Pair<ReacquisitionRefusal?, Long?> {
        val expected = when (target.target.status) {
            RecordStatus.FILE_REJECTED -> ReacquisitionTargetStatus.FILE_REJECTED
            RecordStatus.UNKNOWN -> ReacquisitionTargetStatus.UNKNOWN
            else -> ReacquisitionTargetStatus.REJECTED
        }
        val at = now.truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
        val probe = ReacquisitionCommand(
            approvalId = PREVIEW, originalFileId = target.target.fileId, items = listOf(ReacquisitionItem(seq, target.target.record.generation, expected)),
            requestedBy = PREVIEW_REQUESTER, approvedBy = PREVIEW_APPROVER, reasonCode = PREVIEW_REASON, evidenceRef = PREVIEW, approvedAt = at,
            executeBefore = at.plus(PREVIEW_WINDOW),
        )
        return when (val d = plan(probe, mapOf(seq to target), at, PREVIEW_WINDOW, Duration.ZERO)) {
            is ReacquisitionPlanDecision.Refused -> d.reason to null
            is ReacquisitionPlanDecision.Planned -> null to (d.records.singleOrNull()?.recordAmountMinor ?: 0L)
        }
    }

    private const val PREVIEW = "preview"
    private const val PREVIEW_REQUESTER = "preview-requester"
    private const val PREVIEW_APPROVER = "preview-approver"
    private const val PREVIEW_REASON = "PREVIEW"
    private val PREVIEW_WINDOW: Duration = Duration.ofMinutes(1)
}

/** 승인 묶음의 한 항목(요청 시점 대상 버전). */
data class ReacquisitionItem(val seq: Int, val expectedGeneration: Int, val expectedStatus: ReacquisitionTargetStatus)

/**
 * ADMIN 재매입 실행 명령(D-ADMIN-007 — 요청자·승인자·승인 ID·대상 버전, 실행 기한). [items]는 순번 오름차순·중복 없음, 1..[ReacquisitionExecutionRule.MAX_ITEMS]건.
 * [contentHash] = 같은 승인 ID 재전송이 "같은 명령"인지 판정하는 지문(correlationId 제외 — 업무 의미 없음).
 */
data class ReacquisitionCommand(
    val approvalId: String,
    val originalFileId: AcquisitionFileId,
    val items: List<ReacquisitionItem>,
    val requestedBy: String,
    val approvedBy: String,
    val reasonCode: String,
    val evidenceRef: String,
    val approvedAt: Instant,
    val executeBefore: Instant,
) {
    init {
        if (items.size !in 1..ReacquisitionExecutionRule.MAX_ITEMS) throw AcquisitionContractViolation("a re-acquisition approval covers 1..50 records")
        if (items.map { it.seq } != items.map { it.seq }.sorted().distinct()) throw AcquisitionContractViolation("items are in ascending seq order without duplicates")
        if (!executeBefore.isAfter(approvedAt)) throw AcquisitionContractViolation("the execution deadline follows the approval")
        if (approvedAt.nano % NANOS_PER_MILLI != 0 || executeBefore.nano % NANOS_PER_MILLI != 0) throw AcquisitionContractViolation("times are truncated to milliseconds")
        // ACQ-ADMIN-001 rev 0.7(B2 — I-23 권장안): 근거 참조에 카드 번호 패턴이 있으면 명령이 아니다(ADMIN과 같은 판정 — 값은 예외 문구에 넣지 않는다).
        if (CardNumberPattern.matches(evidenceRef)) throw AcquisitionContractViolation("evidence reference must not look like a card number")
        // 항목 검사(문법)는 레코드 하나 명령과 같다.
        items.forEach { itemCommand(it) }
    }

    fun itemCommand(item: ReacquisitionItem) = ReacquisitionApprovalCommand(
        approvalId = approvalId, fileId = originalFileId, seq = item.seq, expectedTargetGeneration = item.expectedGeneration, expectedTargetStatus = item.expectedStatus,
        requestedBy = requestedBy, approvedBy = approvedBy, reasonCode = reasonCode, evidenceRef = evidenceRef, channel = ApprovalChannel.ADMIN_SIGNED,
    )

    /** `acd1:` + SHA-256(길이 접두 canonical — `<UTF-8 바이트 수>:<값>`). ADMIN-PAYMENT-OPS-001 §4.4.2 `opd1:`과 같은 방식. */
    fun contentHash(): String {
        val parts = listOf(TAG, VERSION, approvalId, KIND, originalFileId.value, items.size.toString()) +
            items.flatMap { listOf(it.seq.toString(), it.expectedGeneration.toString(), it.expectedStatus.name) } +
            listOf(requestedBy, approvedBy, reasonCode, evidenceRef, TIME.format(approvedAt), TIME.format(executeBefore))
        val canonical = parts.joinToString("") { "${it.toByteArray(StandardCharsets.UTF_8).size}:$it" }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(StandardCharsets.UTF_8))
        return PREFIX + digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val PREFIX = "acd1:"
        const val KIND = "REACQUISITION"
        private const val TAG = "paycore.acquisition-ops-decision-content"
        private const val VERSION = "1"
        private const val NANOS_PER_MILLI = 1_000_000
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    }
}

/**
 * 항목 하나의 검사 입력. [target] = 레코드·현재 상태·최신 세대·ACCEPTED 여부, [notRequired] = 이미 "재매입 불요"로 종결(D-ACQA-004), [funding] = 결제별 행 잠금 아래 읽은
 * 원승인과 취소(SALE만 — 없으면 무결성 거절).
 */
data class ReacquisitionItemTarget(val target: ReacquisitionTarget, val notRequired: Boolean, val funding: ReacquisitionFunding?)

/**
 * D-ACQ-013 입력: 원승인액·통화, 실행 시점까지 받은 모든 취소 금액(상계·취소 매입 불요·전송 후 취소 — 투영에 있는 그 원승인의 취소 전부),
 * [undecidedCancellations] = 그중 아직 매입 판정(상계·CANCEL 레코드·불요)이 없는 수, [paymentHeld] = 그 Payment에 미해제 보류가 있음.
 */
data class ReacquisitionFunding(
    val approvedAmountMinor: Long,
    val currency: String,
    val cancellationAmountsMinor: List<Long>,
    val undecidedCancellations: Int,
    val paymentHeld: Boolean,
)

/** "재매입 불요" 종결(D-ACQA-004): 순액 0 — 승인액 = 받은 취소 합. */
data class ReacquisitionNotRequired(val approval: ReacquisitionApproval, val approvedAmountMinor: Long, val cancelledAmountMinor: Long, val currency: String) {
    init {
        if (approvedAmountMinor < 1 || cancelledAmountMinor != approvedAmountMinor) throw AcquisitionContractViolation("not required only when fully cancelled")
    }
}

sealed interface ReacquisitionPlanDecision {
    /** [records] = 새 버전 파일 레코드(원 순번 순으로 1..n), 비어 있으면 파일 없음(모두 불요). [approvals] = 묶음 전체(불요 포함). */
    data class Planned(val approvals: List<ReacquisitionApproval>, val records: List<AcquisitionRecord>, val notRequired: List<ReacquisitionNotRequired>) :
        ReacquisitionPlanDecision

    /** [seq] = 거절한 항목(묶음 공통 사유면 null). 승인 전체 거절(D-ACQA-003). */
    data class Refused(val reason: ReacquisitionRefusal, val seq: Int?) : ReacquisitionPlanDecision
}

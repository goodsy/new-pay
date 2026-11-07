package com.paycore.financial.acquiring.domain

import java.time.Instant

/**
 * 재매입 2인 승인 기록의 검사(2-G — D-ACQ-011, D-ADMIN-007 양쪽 검사). 소유 서비스(FINANCIAL)는 ADMIN이 보낸 승인 명령의 요청자·승인자·승인 ID·대상 버전을 받아
 * 다시 검사하고 자기 감사 기록을 남긴다. 레코드 하나의 검사다 — 승인 묶음(D-ACQA-003)과 실행(새 버전 파일 봉인)은 [ReacquisitionExecutionRule](ACQ-ADMIN-001 B1).
 *
 * 사용자 결정:
 * - D-ACQ-011: 재매입(파일 전체 거절 뒤 재매입 포함 — I-7)은 2인 승인.
 * - D-ACQ-004: 재매입은 원파일 참조 새 버전(generation + 1, `reacquisitionOf = {fileId}#{seq}`). 이중 매입 방지 UNIQUE(transaction_id, record_type, generation) 유지.
 * - D-ADMIN-003·007: 요청자 ≠ 승인자, 대상 버전이 바뀌었으면 무효, 소유 서비스가 다시 검사.
 *
 * - D-ACQ-013(rev 1.1): 재매입 레코드 금액 = 원승인액 − 그때까지 받은 모든 취소(순액), 취소 매입 불요 기록은 유지 — 금액은 **실행(봉인) 시점**에 [netAmountMinor]로 정한다.
 * - D-ACQ-014(rev 1.1): 결과 UNKNOWN 레코드는 대사로 미매입이 확정된 경우에만 재매입 대상. 재매입 뒤 앞 세대 ACCEPTED가 오면 승인 무효(TARGET_CHANGED — [revalidate]),
 *   거래·종류당 세대를 넘는 ACCEPTED는 하나.
 *
 * Implementer 해석(사용자 결정 아님 — I-50 rev 1.1): 대상 = 현재 상태가 REJECTED·FILE_REJECTED인 레코드의 **가장 높은 generation**(그 거래·종류의 다음 세대가 없을 것),
 * 그 거래·종류의 어느 세대도 ACCEPTED가 아닐 것. UNKNOWN은 대사 미매입 확정 근거가 필요한데 대사(RECON) 기능이 아직 없어 그 근거를 받을 수단이 없다 — 지금은 거절한다
 * (UNKNOWN_NOT_RECONCILED). 대상 버전 = (대상 generation, 대상 상태) — 명령이 본 값과 지금 값이 다르면 거절(TARGET_CHANGED). 근거(evidenceRef)·사유 코드 필수, 채널은
 * ADMIN 서명 채널만. 승인 기록에는 금액을 넣지 않는다(I-54): 승인과 실행 사이에 취소가 더 올 수 있어 승인 시점 금액은 D-ACQ-013 "그때까지"와 어긋날 수 있다.
 */
object ReacquisitionRule {

    fun decide(command: ReacquisitionApprovalCommand, target: ReacquisitionTarget, now: Instant): ReacquisitionDecision {
        if (command.channel != ApprovalChannel.ADMIN_SIGNED) return ReacquisitionDecision.Refused(ReacquisitionRefusal.CHANNEL_NOT_SIGNED)
        if (command.requestedBy == command.approvedBy) return ReacquisitionDecision.Refused(ReacquisitionRefusal.SAME_PERSON)
        if (target.acceptedInAnyGeneration) return ReacquisitionDecision.Refused(ReacquisitionRefusal.ALREADY_ACCEPTED)
        val targetStatus = when (target.status) {
            RecordStatus.REJECTED -> ReacquisitionTargetStatus.REJECTED
            RecordStatus.FILE_REJECTED -> ReacquisitionTargetStatus.FILE_REJECTED
            // D-ACQ-014: 대사 미매입 확정 근거가 있어야 한다 — 대사 기능 전에는 받을 수 없다.
            RecordStatus.UNKNOWN -> return ReacquisitionDecision.Refused(ReacquisitionRefusal.UNKNOWN_NOT_RECONCILED)
            else -> return ReacquisitionDecision.Refused(ReacquisitionRefusal.TARGET_NOT_REACQUIRABLE)
        }
        if (target.record.generation != target.latestGeneration) return ReacquisitionDecision.Refused(ReacquisitionRefusal.NOT_LATEST_GENERATION)
        if (command.expectedTargetGeneration != target.record.generation || command.expectedTargetStatus != targetStatus) {
            return ReacquisitionDecision.Refused(ReacquisitionRefusal.TARGET_CHANGED)
        }
        return ReacquisitionDecision.Approved(
            ReacquisitionApproval(
                approvalId = command.approvalId, fileId = target.fileId, seq = target.record.seq, transactionId = target.record.transactionId,
                recordType = target.record.type, targetGeneration = target.record.generation, newGeneration = target.record.generation + 1,
                targetStatus = targetStatus, reacquisitionOf = "${target.fileId.value}#${target.record.seq}", requestedBy = command.requestedBy,
                approvedBy = command.approvedBy, reasonCode = command.reasonCode, evidenceRef = command.evidenceRef, channel = command.channel, recordedAt = now,
            ),
        )
    }

    /**
     * 승인 무효 재검사(D-ACQ-014 — 실행 직전과 ACQ-ADMIN-001 실행 경로가 부른다). 승인 뒤 대상이 바뀌었으면 TARGET_CHANGED: 앞 세대(어느 세대든)가 ACCEPTED가 됐다
     * (늦은 ACCEPTED), 대상 상태가 승인 때와 다르다, 대상 세대 위에 이 승인 말고 다른 세대가 생겼다. [target].latestGeneration은 이 승인의 새 세대까지 셈한 값이다.
     */
    fun revalidate(approval: ReacquisitionApproval, target: ReacquisitionTarget): ReacquisitionDecision {
        val status = when (target.status) {
            RecordStatus.REJECTED -> ReacquisitionTargetStatus.REJECTED
            RecordStatus.FILE_REJECTED -> ReacquisitionTargetStatus.FILE_REJECTED
            RecordStatus.UNKNOWN -> ReacquisitionTargetStatus.UNKNOWN
            else -> null
        }
        val unchanged = !target.acceptedInAnyGeneration && status == approval.targetStatus && target.fileId == approval.fileId &&
            target.record.seq == approval.seq && target.record.generation == approval.targetGeneration && target.latestGeneration == approval.newGeneration
        return if (unchanged) ReacquisitionDecision.Approved(approval) else ReacquisitionDecision.Refused(ReacquisitionRefusal.TARGET_CHANGED)
    }

    /**
     * D-ACQ-013 재매입 레코드 금액 = 원승인액 − 실행 시점까지 받은 모든 취소(상계·취소 매입 불요·전송 후 취소 — 각 취소는 한 번만). 0이면 보낼 금액이 없다(실행 경로가 정한다 —
     * ACQ-ADMIN-001). 음수(취소 합 > 승인액)는 비정합 — 계약 위반.
     */
    fun netAmountMinor(approvedAmountMinor: Long, cancellationAmountsMinor: List<Long>): Long {
        if (approvedAmountMinor < 1 || cancellationAmountsMinor.any { it < 1 }) throw AcquisitionContractViolation("amounts are positive minor-unit integers")
        val net = approvedAmountMinor - cancellationAmountsMinor.fold(0L) { acc, a -> Math.addExact(acc, a) }
        if (net < 0) throw AcquisitionContractViolation("cancellations exceed the approved amount")
        return net
    }
}

/**
 * ADMIN 승인 명령(D-ADMIN-007 — 요청자·승인자·승인 ID·대상 버전). [approvalId]·[requestedBy]·[approvedBy] = ADMIN 내부 ID(개인정보 없음), [evidenceRef] = 근거 참조
 * (대사 Case·기관 문의 번호 등 — 값 문법만 검사), [expectedTargetGeneration]·[expectedTargetStatus] = 요청 시점 대상 버전.
 */
data class ReacquisitionApprovalCommand(
    val approvalId: String,
    val fileId: AcquisitionFileId,
    val seq: Int,
    val expectedTargetGeneration: Int,
    val expectedTargetStatus: ReacquisitionTargetStatus,
    val requestedBy: String,
    val approvedBy: String,
    val reasonCode: String,
    val evidenceRef: String,
    val channel: ApprovalChannel,
) {
    init {
        AcquisitionText.id("approvalId", approvalId)
        AcquisitionText.id("requestedBy", requestedBy)
        AcquisitionText.id("approvedBy", approvedBy)
        AcquisitionText.token("reasonCode", reasonCode)
        if (!EVIDENCE.matches(evidenceRef)) throw AcquisitionContractViolation("evidenceRef must be 1..128 characters of [A-Za-z0-9._:#/-]")
        if (seq < 1 || expectedTargetGeneration < 0) throw AcquisitionContractViolation("target record seq ≥ 1 and generation ≥ 0")
    }

    private companion object {
        val EVIDENCE = Regex("^[A-Za-z0-9._:#/-]{1,128}$")
    }
}

/**
 * 검사 대상: 레코드와 그 현재 상태, 같은 거래·종류의 가장 높은 generation(레코드·승인 기록), [acceptedInAnyGeneration] = 같은 거래·종류의 어느 세대 레코드든 ACCEPTED인가
 * (D-ACQ-014 — 세대를 넘는 ACCEPTED 하나).
 */
data class ReacquisitionTarget(
    val fileId: AcquisitionFileId,
    val record: AcquisitionRecord,
    val status: RecordStatus,
    val latestGeneration: Int,
    val acceptedInAnyGeneration: Boolean,
)

/** 기록(append-only — 승인 ID당 하나, (거래, 종류, 새 generation)당 하나). */
data class ReacquisitionApproval(
    val approvalId: String,
    val fileId: AcquisitionFileId,
    val seq: Int,
    val transactionId: String,
    val recordType: AcquisitionRecordType,
    val targetGeneration: Int,
    val newGeneration: Int,
    val targetStatus: ReacquisitionTargetStatus,
    val reacquisitionOf: String,
    val requestedBy: String,
    val approvedBy: String,
    val reasonCode: String,
    val evidenceRef: String,
    val channel: ApprovalChannel,
    val recordedAt: Instant,
) {
    init {
        if (requestedBy == approvedBy) throw AcquisitionContractViolation("the requester cannot approve their own request")
        if (newGeneration != targetGeneration + 1) throw AcquisitionContractViolation("a re-acquisition is the next generation of its target")
        if (reacquisitionOf != "${fileId.value}#$seq") throw AcquisitionContractViolation("a re-acquisition refers to its original record")
    }
}

sealed interface ReacquisitionDecision {
    data class Approved(val approval: ReacquisitionApproval) : ReacquisitionDecision

    data class Refused(val reason: ReacquisitionRefusal) : ReacquisitionDecision
}

/** 재매입 대상 상태(DB CHECK와 같은 집합). UNKNOWN은 대사 미매입 확정 근거가 생길 때(D-ACQ-014 — 대사 기능 후) 쓰인다 — 지금 규칙은 UNKNOWN 대상을 거절한다. */
enum class ReacquisitionTargetStatus { REJECTED, UNKNOWN, FILE_REJECTED }

/** 승인 명령이 들어온 채널. ADMIN_SIGNED = ADMIN 조작 전용 서명 채널(ACQ-ADMIN-001 — D-ACQA-001). */
enum class ApprovalChannel { ADMIN_SIGNED, UNSIGNED }

/**
 * UNKNOWN_NOT_RECONCILED·ALREADY_ACCEPTED = D-ACQ-014(rev 1.1). ACQ-ADMIN-001 B1 실행 검사([ReacquisitionExecutionRule]): TARGET_NOT_FOUND, ALREADY_NOT_REQUIRED(D-ACQA-004
 * 종결 뒤), CANCEL_NOT_SUPPORTED(B1은 SALE만 — 명세 I-7), PAYMENT_HELD(미해제 보류 — fail-closed), CANCELLATION_UNDECIDED(아직 판정 안 된 취소가 있어 "그때까지 받은 모든
 * 취소"(D-ACQ-013)를 한 번씩만 셀 수 없다 — 다음 봉인 뒤 다시 요청), TARGET_INCONSISTENT(레코드 승인액 ≠ 투영 승인액 등 — 무결성), APPROVAL_WINDOW_INVALID·
 * APPROVAL_EXECUTION_EXPIRED(실행 기한 — D-OPS-002 선례, 명세 I-2).
 */
enum class ReacquisitionRefusal {
    CHANNEL_NOT_SIGNED,
    SAME_PERSON,
    TARGET_NOT_REACQUIRABLE,
    NOT_LATEST_GENERATION,
    TARGET_CHANGED,
    UNKNOWN_NOT_RECONCILED,
    ALREADY_ACCEPTED,
    TARGET_NOT_FOUND,
    ALREADY_NOT_REQUIRED,
    CANCEL_NOT_SUPPORTED,
    PAYMENT_HELD,
    CANCELLATION_UNDECIDED,
    TARGET_INCONSISTENT,
    APPROVAL_WINDOW_INVALID,
    APPROVAL_EXECUTION_EXPIRED,
}

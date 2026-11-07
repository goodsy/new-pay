package com.paycore.financial.acquiring.domain

import java.time.Instant

/**
 * ADMIN 조작 승인 ID별 **최초 확정 결정**(ACQ-ADMIN-001 B1 — D-ADMIN-007 양쪽 검사의 FINANCIAL 자기 기록, ADMIN-PAYMENT-OPS-001 D-OPS-005 선례 — 명세 I-3).
 * 같은 승인 ID에는 언제나 이 결정을 돌려준다(재전송·회복 job 조회). [contentHash]가 다른 같은 승인 ID 명령은 이 결정이 아니라 파생 충돌이다.
 * - EXECUTED: 업무 효과가 결정과 같은 로컬 TX에 커밋됐다(재매입 = 새 버전 파일 [resultFileId]).
 * - NOT_REQUIRED: 효과 없이 종결(재매입 순액이 모두 0 — D-ACQA-004).
 * - REJECTED: 업무 거절([rejectionCode]) — 효과 없음, 승인 소진(같은 승인 ID로 다시 실행되지 않는다).
 */
data class AcquisitionOpsDecision(
    val approvalId: String,
    val kind: AcquisitionOpsKind,
    val outcome: AcquisitionOpsOutcome,
    val rejectionCode: String?,
    val targetRef: String,
    val resultFileId: AcquisitionFileId?,
    val contentHash: String,
    val requestedBy: String,
    val approvedBy: String,
    val approvedAt: Instant,
    val executeBefore: Instant,
    val decidedAt: Instant,
    val origin: AcquisitionOpsOrigin,
) {
    init {
        AcquisitionText.id("approvalId", approvalId)
        AcquisitionText.id("targetRef", targetRef)
        if (!HASH.matches(contentHash)) throw AcquisitionContractViolation("content hash is acd1: + SHA-256 hex")
        val shape = when (outcome) {
            AcquisitionOpsOutcome.EXECUTED -> resultFileId != null && rejectionCode == null
            AcquisitionOpsOutcome.NOT_REQUIRED -> resultFileId == null && rejectionCode == null
            AcquisitionOpsOutcome.REJECTED -> resultFileId == null && rejectionCode != null && CODE.matches(rejectionCode)
        }
        if (!shape) throw AcquisitionContractViolation("decision shape: EXECUTED has a result file, REJECTED has a code")
        if (origin == AcquisitionOpsOrigin.DEADLINE_CONFIRMATION && rejectionCode != EXPIRED) {
            throw AcquisitionContractViolation("a deadline confirmation only records the expiry")
        }
        if (!executeBefore.isAfter(approvedAt)) throw AcquisitionContractViolation("the execution deadline follows the approval")
    }

    companion object {
        const val EXPIRED = "APPROVAL_EXECUTION_EXPIRED"
        private val HASH = Regex("^acd1:[0-9a-f]{64}$")
        private val CODE = Regex("^[A-Z][A-Z0-9_]{0,47}$")
    }
}

/** 조작 종류(DB CHECK와 같은 집합). B3·B4의 해소 조작은 그 배치에서 더한다(사용자 질문 Q-ACQA-05..07 답 전 없음). */
enum class AcquisitionOpsKind { REACQUISITION }

enum class AcquisitionOpsOutcome { EXECUTED, NOT_REQUIRED, REJECTED }

/** EXECUTION_COMMAND = 실행 명령의 판정, DEADLINE_CONFIRMATION = 결정 없이 기한이 지난 승인의 기한 확인 기록(D-OPS-009 선례 — 업무 효과 없음). */
enum class AcquisitionOpsOrigin { EXECUTION_COMMAND, DEADLINE_CONFIRMATION }

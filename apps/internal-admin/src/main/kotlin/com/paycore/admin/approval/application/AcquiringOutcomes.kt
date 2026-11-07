package com.paycore.admin.approval.application

import com.paycore.admin.approval.domain.ApprovalRequest
import com.paycore.admin.approval.domain.OwnerExecution
import com.paycore.admin.financialops.application.provided.AcquiringOpsDecision
import com.paycore.admin.financialops.application.provided.AcquiringOpsResult

/**
 * FINANCIAL 매입 조작 결과 → ADMIN 결과 반영 값(ACQ-ADMIN-001 B1-A — 웹 TX2와 회복 job 공용, 운영자 보정 취소의 `toOutcome`·`alertOf`·내용 대조와 같은 규칙):
 * - 결정 EXECUTED → EXECUTED(새 파일 ID 참조), NOT_REQUIRED → EXECUTED(code NOT_REQUIRED — D-ACQA-004, I-19), REJECTED → OWNER_REJECTED(code) — FINANCIAL은 REJECTED도 200(I-16).
 * - 400 형식 → OWNER_REJECTED(code, 무결성 경보 — 결정 없이 끝남, 같은 바이트면 같은 판정). 결정 없음(실행 응답) → 결과 불명확.
 * - 그 밖 → EXECUTION_UNKNOWN(파생 409면 무결성 플래그 — 조회로만 수렴).
 * 내용 대조(L-b): 승인 ID·원 파일·요청자·승인자·내용 hash(= 실행 행 지문)가 ADMIN 값과 다르면 플래그 먼저 + 경보, 그 뒤 반영(조용한 EXECUTED 없음 — I-35).
 */
internal object AcquiringOutcomes {
    const val DECISION_CONTENT_MISMATCH = "DECISION_CONTENT_MISMATCH"

    fun outcome(result: AcquiringOpsResult): OwnerOutcomeApplier.Outcome = when (result) {
        is AcquiringOpsResult.Decided -> decided(result.decision)
        AcquiringOpsResult.None -> OwnerOutcomeApplier.Outcome.Unknown("DECISION_NONE", null)
        is AcquiringOpsResult.Malformed -> OwnerOutcomeApplier.Outcome.Rejected(result.code)
        is AcquiringOpsResult.Unknown -> OwnerOutcomeApplier.Outcome.Unknown(result.failure, result.integrityFlag)
    }

    fun decided(d: AcquiringOpsDecision): OwnerOutcomeApplier.Outcome = when (d.decision) {
        "EXECUTED" -> OwnerOutcomeApplier.Outcome.Started(requireNotNull(d.resultFileId) { "an executed decision has its new file" })
        "NOT_REQUIRED" -> OwnerOutcomeApplier.Outcome.Completed("NOT_REQUIRED")
        else -> OwnerOutcomeApplier.Outcome.Rejected(requireNotNull(d.code) { "a rejected decision has its code" })
    }

    fun mismatch(d: AcquiringOpsDecision, request: ApprovalRequest, execution: OwnerExecution): Boolean =
        d.approvalId != request.id || d.targetRef != request.target.ref || d.requestedBy != request.requestedBy || d.approvedBy != execution.executorActorId ||
            d.contentHash != execution.fingerprint

    /** 경보 code: 400 형식(무결성), 파생 409 플래그, 401(운영). 그 밖 null. */
    fun alert(result: AcquiringOpsResult): String? = when (result) {
        is AcquiringOpsResult.Malformed -> result.code
        is AcquiringOpsResult.Unknown -> result.integrityFlag ?: result.failure.takeIf { result.alert }
        else -> null
    }
}

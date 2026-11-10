package com.paycore.master.merchant.application.required

import com.paycore.master.merchant.application.provided.AdminOperation
import com.paycore.master.merchant.application.provided.ApprovalDecidedVia
import com.paycore.master.merchant.application.provided.ApprovalOutcome
import com.paycore.master.merchant.domain.ApprovalEvidence
import com.paycore.master.merchant.domain.MasterRejection
import java.time.Instant

/**
 * MASTER 승인 근거 행 하나(`admin_approval_evidence` — MERCHANT-OPS-002 §9.3 V9, INSERT-only). 같은 TX의 `admin_request` 결과와 [requestRecordId]로 1:1(n-a 불변식 —
 * `APPROVAL_ID_ALREADY_USED` 거절만 행이 없다). [resultReference]는 EXECUTED만, [resultCode]는 REJECTED만 값이 있다(DB CHECK와 같은 규칙).
 */
data class ApprovalEvidenceRecord(
    val evidence: ApprovalEvidence,
    val operation: AdminOperation,
    val requestRecordId: String,
    val outcome: ApprovalOutcome,
    val resultCode: MasterRejection?,
    val resultReference: String?,
    val decidedVia: ApprovalDecidedVia,
    val recordedAt: Instant,
) {
    init {
        require((outcome == ApprovalOutcome.EXECUTED) == (resultCode == null && resultReference != null)) { "an executed record carries only a reference" }
        require((outcome == ApprovalOutcome.REJECTED) == (resultCode != null && resultReference == null)) { "a rejected record carries only a code" }
    }

    override fun toString(): String =
        "ApprovalEvidenceRecord(approvalId=${evidence.approvalId}, operation=${operation.code}, outcome=$outcome, resultCode=$resultCode, decidedVia=$decidedVia)"
}

/**
 * 승인 근거 기록 저장 Port(MERCHANT-OPS-002 §9.3 — 기록 저장 Port `…Store`, ARCHITECTURE-CONVENTIONS §14). append-only: 갱신·삭제 경로 없음(runtime 권한도 SELECT·INSERT만).
 *
 * - [find]: approvalId(PK) 한 행 비잠금 읽기(판정 3 존재 확인·결정 조회). 없으면 null.
 * - [record]: INSERT 뒤 **즉시 flush**한다(review m-1). PK(approvalId) 충돌은 [PersistenceFailure.UniqueConflict]([StoreConstraint.APPROVAL_EVIDENCE], businessKey = true)로
 *   번역되어 실행기의 업무 유일키 재시도(새 TX)로 들어가고, 새 TX의 판정 3이 `APPROVAL_ID_ALREADY_USED`로 수렴한다(commit 시점 예외로 500이 나지 않게).
 */
interface ApprovalEvidenceStore {
    fun find(approvalId: String): ApprovalEvidenceRecord?

    fun record(record: ApprovalEvidenceRecord)
}

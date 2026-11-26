package com.paycore.master.merchant.domain

import java.time.Instant

/**
 * 접수 건 전이 이력 한 행(§4A.3 — INSERT 전용 표 `contract_intake_transition`, §4A.14 M-2). [from] null = 생성 행(일반 생성 → [ContractIntakeStatus.CONSULTING],
 * 이관 → [ContractIntakeStatus.COMPLETED]). [migrated]는 이관 행 표시(리뷰 n-2 — 같은 표 CHECK가 "계약 완료 행 근거 참조 NOT NULL, 이관 행만 NULL"을 강제).
 * [reason]('사유 및 비고' — 되돌리기·계약 불가는 필수)·[document](계약서 수령 근거 — 일반 계약 완료 행에만, 필수)는 DB CHECK(`ck_cint_reason_required`·`ck_cint_document`·
 * `ck_cint_document_target`)와 같은 규칙을 행 불변식으로도 둔다(FLOW-2a MINOR-1 — 입력 검사는 [ContractIntake.transitionTo]가 고정 code로 먼저 한다).
 * 순번은 저장 Port가 사업자 행 잠금 아래 부여한다. 원문은 toString에 넣지 않는다(값 객체가 가린다).
 */
data class ContractIntakeTransition(
    val businessId: BusinessId,
    val intakeSeq: Int,
    val from: ContractIntakeStatus?,
    val to: ContractIntakeStatus,
    val migrated: Boolean,
    val at: Instant,
    val by: ActorRef,
    val reason: BusinessChangeReason? = null,
    val document: ContractDocumentRef? = null,
) {
    init {
        require(intakeSeq >= 1) { "intake sequence starts at 1" }
        require(from == null || from.canMoveTo(to)) { "transition must be one of the allowed transitions" }
        require(from != null || to == (if (migrated) ContractIntakeStatus.COMPLETED else ContractIntakeStatus.CONSULTING)) {
            "a creation row opens CONSULTING (or COMPLETED for a migration row)"
        }
        require(!migrated || (from == null && intakeSeq == 1 && reason == null && document == null)) { "a migration row is the creation row of intake 1 without reason or document" }
        val completes = from != null && to == ContractIntakeStatus.COMPLETED
        require(completes == (document != null)) { "a document reference is on (and only on) a non-migrated COMPLETED move" }
        require(!requiresReason(from, to) || reason != null) { "going back and rejecting need a reason" }
    }

    companion object {
        /** '사유 및 비고' 필수 전이(D-FLOW-004 ②, D-FLOW-005 ② — BR-BIZ-339): 계약서 접수 → 상담 중(되돌리기), → 계약 불가. */
        fun requiresReason(from: ContractIntakeStatus?, to: ContractIntakeStatus): Boolean =
            from != null && ((from == ContractIntakeStatus.DOCUMENT_RECEIVED && to == ContractIntakeStatus.CONSULTING) || to == ContractIntakeStatus.REJECTED)
    }
}

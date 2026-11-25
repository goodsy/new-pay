package com.paycore.master.merchant.domain

import java.time.Instant

/**
 * 접수 건 전이 이력 한 행(§4A.3 — INSERT 전용 표 `contract_intake_transition`, §4A.14 M-2). [from] null = 생성 행(일반 생성 → [ContractIntakeStatus.CONSULTING],
 * 이관 → [ContractIntakeStatus.COMPLETED]). [migrated]는 이관 행 표시(리뷰 n-2 — 같은 표 CHECK가 "계약 완료 행 근거 참조 NOT NULL, 이관 행만 NULL"을 강제).
 * '사유 및 비고'·계약서 수령 근거 참조 입력은 전이 명령(FLOW-2 — `ContractDocumentRef`, 카드번호 판정)이 더한다. 순번은 저장 Port가 사업자 행 잠금 아래 부여한다.
 */
data class ContractIntakeTransition(
    val businessId: BusinessId,
    val intakeSeq: Int,
    val from: ContractIntakeStatus?,
    val to: ContractIntakeStatus,
    val migrated: Boolean,
    val at: Instant,
    val by: ActorRef,
) {
    init {
        require(intakeSeq >= 1) { "intake sequence starts at 1" }
        require(from == null || from.canMoveTo(to)) { "transition must be one of the allowed transitions" }
        require(from != null || to == (if (migrated) ContractIntakeStatus.COMPLETED else ContractIntakeStatus.CONSULTING)) {
            "a creation row opens CONSULTING (or COMPLETED for a migration row)"
        }
        require(!migrated || (from == null && intakeSeq == 1)) { "a migration row is the creation row of intake 1" }
    }
}

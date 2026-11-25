package com.paycore.master.merchant.domain

/**
 * 계약 접수 건 상태(MERCHANT-FLOW-001 §4A.3 — D-FLOW-001 ①②, D-FLOW-003 ①②, D-FLOW-004 ①②, D-FLOW-005 ①②). 이름은 영속 값(DB CHECK `ck_cin_status`)이라 바꾸지 않는다.
 *
 * - 열린 건: [CONSULTING](상담 중)·[DOCUMENT_RECEIVED](계약서 접수).
 * - 종결: [COMPLETED](계약 완료 — 되돌리지 못하고 [TERMINATED]로만 이동)·[REJECTED](계약 불가 — 끝)·[TERMINATED](계약 해지 — 끝).
 *
 * 허용 전이는 [allowedTargets]의 6개(+ 생성 → [CONSULTING] = 표의 7종)뿐이다. 그 밖 전이·같은 상태로의 전이는 모두 거절한다.
 * 상담 중 → 계약 완료(계약서 접수 건너뛰기)는 표에 없다(D-FLOW-005 ①).
 */
enum class ContractIntakeStatus {
    CONSULTING,
    DOCUMENT_RECEIVED,
    COMPLETED,
    REJECTED,
    TERMINATED,
    ;

    /** 열린 건(사업자당 ≤ 1 — D-FLOW-003 ①). */
    val isOpen: Boolean get() = this == CONSULTING || this == DOCUMENT_RECEIVED

    /** 새 접수 건을 막는 상태(열린 건 또는 계약 완료 — D-FLOW-004 ① "해지 뒤에만 새 접수"). DB 생성 열 UNIQUE `uk_cin_active`와 같은 집합이다. */
    val blocksNewIntake: Boolean get() = isOpen || this == COMPLETED

    /** 이 상태에서 갈 수 있는 상태(§4A.3 허용 전이 표 — 생성 행 제외). */
    val allowedTargets: Set<ContractIntakeStatus>
        get() = when (this) {
            CONSULTING -> setOf(DOCUMENT_RECEIVED, REJECTED)
            DOCUMENT_RECEIVED -> setOf(CONSULTING, COMPLETED, REJECTED)
            COMPLETED -> setOf(TERMINATED)
            REJECTED, TERMINATED -> emptySet()
        }

    fun canMoveTo(target: ContractIntakeStatus): Boolean = target in allowedTargets
}

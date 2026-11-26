package com.paycore.master.merchant.domain

/**
 * 계약 접수 건 상태(MERCHANT-FLOW-001 §4A.3 — D-FLOW-001 ①②, D-FLOW-003 ①, D-FLOW-005 ①②, [rev 0.4] BR-BIZ-338). 이름은 영속 값(DB CHECK `ck_cin_status`)이라 바꾸지 않는다.
 *
 * - 열린 건: [CONSULTING](상담 중)·[DOCUMENT_RECEIVED](계약서 접수) — 사업자당 ≤ 1(DB 생성 열 UNIQUE `uk_cin_open`과 같은 집합).
 * - 종결: [COMPLETED](계약 완료 — 끝. 사업자 계약을 만들거나 새 버전을 더한다)·[REJECTED](계약 불가 — 끝).
 * - **[rev 0.4] `TERMINATED`(계약 해지) 값은 없다**(D-GOV-018 ②ⓑ — 계약 해지는 사업자 계약 명령). 영속 문자열 `TERMINATED`는 다시 쓰지 않는다(V21이 CHECK에서 제거).
 *
 * 허용 전이는 [allowedTargets]의 5개(+ 생성 → [CONSULTING] = 표의 6종)뿐이다. 그 밖 전이·같은 상태로의 전이는 모두 거절한다.
 * 상담 중 → 계약 완료(계약서 접수 건너뛰기)는 표에 없다(D-FLOW-005 ①).
 */
enum class ContractIntakeStatus {
    CONSULTING,
    DOCUMENT_RECEIVED,
    COMPLETED,
    REJECTED,
    ;

    /** 열린 건(사업자당 ≤ 1 — D-FLOW-003 ①). 새 접수 건은 열린 건이 있을 때만 막힌다(D-GOV-014 ① — 완료 건은 막지 않는다). */
    val isOpen: Boolean get() = this == CONSULTING || this == DOCUMENT_RECEIVED

    /** 이 상태에서 갈 수 있는 상태(§4A.3 허용 전이 표 — 생성 행 제외). 종결 상태는 빈 집합. */
    val allowedTargets: Set<ContractIntakeStatus>
        get() = when (this) {
            CONSULTING -> setOf(DOCUMENT_RECEIVED, REJECTED)
            DOCUMENT_RECEIVED -> setOf(CONSULTING, COMPLETED, REJECTED)
            COMPLETED, REJECTED -> emptySet()
        }

    fun canMoveTo(target: ContractIntakeStatus): Boolean = target in allowedTargets
}

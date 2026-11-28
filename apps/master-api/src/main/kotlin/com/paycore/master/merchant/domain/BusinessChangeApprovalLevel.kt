package com.paycore.master.merchant.domain

/**
 * 사업자 수정 한 건의 처리 인원(BIZ-CRIT-APPROVAL-001 §1.1 인원 판정표 — D-GOV-016 ③·D-GOV-025·D-GOV-026). 선언 순서 = 약 → 강(섞인 저장은 가장 강한 칸 — P-8).
 * 판정 입력: 등록 상태(DRAFT·REGISTERED만 — INACTIVE·TERMINATED는 [Business]가 먼저 거절), 변경 계산([BusinessProfileChange]), 계약·가맹점 이력([BusinessContractMerchantHistory]).
 */
enum class BusinessChangeApprovalLevel {
    /** 일반정보만: 1인, 사유 선택(BR-BIZ-376). */
    SINGLE,

    /** 이력 없는 DRAFT의 중요정보(구분·법인번호 포함) — 등록 정보 수정: 1인 + 사유·이력(BR-BIZ-374, 근거 선택 BR-BIZ-391). */
    SINGLE_WITH_REASON,

    /** 2인(근거·대조 필수): REGISTERED 중요정보(BR-BIZ-373), 이력 있는 DRAFT 중요정보(BR-BIZ-375·394). */
    DUAL,

    /** 2인 정정(근거·대조 필수): 이력 없는 REGISTERED의 기존 값 있는 구분·법인등록번호 변경·삭제(BR-BIZ-384). */
    DUAL_CORRECTION,

    /** 차단: 이력 있음 + 기존 값 있는 구분·법인등록번호 변경·삭제(BR-BIZ-385 — DRAFT 여부 무관). */
    BLOCKED,
    ;

    /** 2인 칸(제안·근거 실행 경로). */
    val requiresApproval: Boolean get() = this == DUAL || this == DUAL_CORRECTION

    companion object {
        /**
         * §1.1 한 칸. 정정 대상([BusinessProfileChange.subjectCorrection])은 중요 변경의 부분집합이고 그 칸이 같은 행의 중요 칸 이상으로 강하므로,
         * '가장 강한 칸'(P-8)은 정정 대상이 있으면 정정 열, 없고 중요 변경이 있으면 중요 열, 그 밖은 일반 열이다. '중요' 분류 자체는 [BusinessProfileChange] 그대로(BR-BIZ-016·017·310).
         */
        fun of(status: BusinessRegistrationStatus, change: BusinessProfileChange, history: BusinessContractMerchantHistory): BusinessChangeApprovalLevel {
            require(status == BusinessRegistrationStatus.DRAFT || status == BusinessRegistrationStatus.REGISTERED) { "approval level is judged for DRAFT or REGISTERED only" }
            val draft = status == BusinessRegistrationStatus.DRAFT
            return when {
                change.subjectCorrection -> when {
                    history.exists -> BLOCKED
                    draft -> SINGLE_WITH_REASON
                    else -> DUAL_CORRECTION
                }
                change.important -> if (draft && !history.exists) SINGLE_WITH_REASON else DUAL
                else -> SINGLE
            }
        }
    }
}

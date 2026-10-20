package com.paycore.simulator.van.domain

/** 가상 VAN 취소 조회 판정 결과([VanCancelInquiry.judge]). 조회는 원 취소 요청 ID 참조만 쓰고 새 기관 요청 ID를 만들지 않는다. 취소 PENDING 예약은 없다(CN-23). */
sealed interface VanCancelInquiryJudgement {
    data class Found(val entry: VanCancelEntry) : VanCancelInquiryJudgement

    /** 기존 무효과 종결 표지(같은 closureId 재응답). */
    data class NoEffectConfirmed(val entry: VanCancelEntry) : VanCancelInquiryJudgement

    /** 새 무효과 종결 표지(원장 INSERT 뒤 NO_EFFECT_CONFIRMED). */
    data class Close(val entry: VanCancelEntry) : VanCancelInquiryJudgement

    /** 기록 없음(무효과 근거가 아니다). */
    data object NotFound : VanCancelInquiryJudgement

    /** 일시적으로 답할 수 없음·보존기간 경과(PG가 "없음"으로 해석하지 않게). */
    data object Unavailable : VanCancelInquiryJudgement
}

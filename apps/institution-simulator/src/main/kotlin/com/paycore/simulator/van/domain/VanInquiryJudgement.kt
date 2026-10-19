package com.paycore.simulator.van.domain

/** 가상 VAN 조회 판정 결과([VanInquiry.judge]). 조회는 원 요청 ID 참조만 쓰고 새 기관 요청 ID를 만들지 않는다(AUTH-001 §5.4). */
sealed interface VanInquiryJudgement {
    /** 원장 결정(FOUND). */
    data class Found(val entry: VanLedgerEntry) : VanInquiryJudgement

    /** 기존 무효과 종결 표지(NO_EFFECT_CONFIRMED — 같은 closureId 재응답). */
    data class NoEffectConfirmed(val entry: VanLedgerEntry) : VanInquiryJudgement

    /** 새 무효과 종결 표지(원장 INSERT 뒤 NO_EFFECT_CONFIRMED). */
    data class Close(val entry: VanLedgerEntry) : VanInquiryJudgement

    /** 처리 중 예약(PENDING — 종결 대상 아님, rev 0.3 H-3). */
    data class StillPending(val pending: VanPendingDecision) : VanInquiryJudgement

    /** 기한이 된 예약을 결정으로 확정(원장 INSERT + 예약 RESOLVED 뒤 FOUND). */
    data class ResolvePending(val pending: VanPendingDecision, val entry: VanLedgerEntry) : VanInquiryJudgement

    /** 기록 없음(무효과 근거가 아니다 — 시도 §2). */
    data object NotFound : VanInquiryJudgement

    /** 일시적으로 답할 수 없음·보존기간 경과(PG가 "없음"으로 해석하지 않게, m-7). */
    data object Unavailable : VanInquiryJudgement
}

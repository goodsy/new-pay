package com.paycore.admin.merchantops.application.required

/**
 * 조회 속도 제한 값 Port(MERCHANT-OPS-001 §5.5 — 구현 `adapter.integration.MasterOpsSettings`, **Demo 값, 운영값 아님** — LIVE Gate). actor별 분 단위 고정 창(인스턴스 메모리).
 * [lookupPerMinute]: 목록·상세·이력 공용, [registrationNumberSearchesPerMinute]: 사업자등록번호 검색 전용(oracle 방어 — 더 낮다).
 */
interface MerchantOpsLimits {
    val lookupPerMinute: Int
    val registrationNumberSearchesPerMinute: Int

    /** 등록 명령(MERCHANT-OPS-002 §4.4 3 — actor별 분당, 조회 버킷과 별개). 사업자 등록은 이에 더해 [registrationNumberSearchesPerMinute] 버킷에서 1회를 예약한다(C-13). */
    val registrationsPerMinute: Int
}

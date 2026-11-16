package com.paycore.admin.paymentops.application.provided

/**
 * 화면 운영 홈 오늘 KPI(ADMIN-HOME-KPI-001 §4.2·§6). 조회자는 요청마다 계정 현재 행(ACTIVE + VIEW 4역할 VIEWER·OPERATOR·APPROVER·SECURITY_ADMIN — C-2)으로 판정한다.
 * **감사 없음**(환경·통화별 합계뿐 — D-TXOPS-002·D-MOPS-002와 같은 이유). 속도 제한은 actor별 분당 창 `home-kpi-per-minute`(거래 조회 창과 별개 — C-4). PAYMENT 호출은 DB TX 밖, 기존
 * `lookup` permit pool. 실패는 결과 값이다. 결과 문서는 PAYMENT 응답을 fail-closed 검사·재구성한 비권위 표시값(정산 금액 아님 — L-4).
 */
interface HomeKpiUseCase {
    fun today(viewerActorId: String): HomeKpiResult
}

sealed interface HomeKpiResult {
    /** `{asOf, businessDate, timeZone, from, items}` — 금액은 minor unit 10진 문자열(§4.1). */
    data class Found(val document: Map<String, Any?>) : HomeKpiResult {
        override fun toString(): String = "Found"
    }

    data object Forbidden : HomeKpiResult

    data object RateLimited : HomeKpiResult

    data class Unavailable(val reason: String) : HomeKpiResult
}

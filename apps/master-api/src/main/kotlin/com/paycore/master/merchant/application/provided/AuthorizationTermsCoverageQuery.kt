package com.paycore.master.merchant.application.provided

/** 승인 시작 조건 누락 점검(CQRS, jOOQ — 명세 §9). 운영 지표·점검용(비권위). 화면은 Q-ADMIN-AUTH 이후. */
interface AuthorizationTermsCoverageQuery {
    fun overview(): TermsCoverageOverview
}

/** 지금 시각 기준 누락 수(MID·통화 식별자 없이 수만 — 지표 label에 식별자를 쓰지 않는다). */
data class TermsCoverageOverview(
    val enabledCurrenciesWithoutLimitPolicy: Long,
    val enabledCurrenciesWithoutFeeAssignment: Long,
    val enabledCurrenciesWithoutSettlementAssignment: Long,
    val activeMethodsWithoutCardRoute: Long,
)

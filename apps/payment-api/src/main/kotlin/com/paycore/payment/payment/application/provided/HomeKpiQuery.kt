package com.paycore.payment.payment.application.provided

import com.paycore.payment.payment.domain.HomeKpiFigures
import java.time.Instant
import java.time.LocalDate

/**
 * 운영 홈 오늘 KPI 조회(ADMIN-HOME-KPI-001 §4.1 — 진입점은 ADMIN 채널 `AdminHomeKpiController`만, ArchUnit). **비권위** 집계 — 정산·대사 판정에 쓰지 않는다(L-4).
 * asOf는 PAYMENT 시계로 조회 TX 시작 직전에 한 번 정한다(§3.1). 저장소 실패·질의 시간 초과는 [OperatorQueryFailure]. 개별 거래·가맹점 식별자는 어느 타입에도 없다(§6 감사 없음 근거).
 */
interface HomeKpiQuery {
    fun today(): HomeKpiSnapshot
}

/** 한 조회의 결과(§4.1 본문 순서). [items]는 environment LIVE → TEST, currency 오름차순(application 정렬 — §3.4), 빈 날은 빈 목록. */
data class HomeKpiSnapshot(val asOf: Instant, val businessDate: LocalDate, val timeZone: String, val from: Instant, val items: List<HomeKpiItem>)

/** 행 키 (environment, currency) — 통화·환경 간 합산 없음(D-CPD-001·D-CP-002). */
data class HomeKpiItem(val environment: String, val currency: String, val figures: HomeKpiFigures)

package com.paycore.payment.payment.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 운영 홈 KPI 집계 구간(ADMIN-HOME-KPI-001 §3.1 — D-ADMIN-HOME-001 ② 완료일 기준, D-ADMIN-HOME-002 ③ PG 확정 시각). 순수 값: [asOf](밀리초 절사)의 Asia/Seoul 날짜가
 * [businessDate], 그 날 00:00 Asia/Seoul의 순간이 [from], 구간은 **[from, asOf)**. asOf가 정확히 그 날 00:00:00.000이면 빈 구간([isEmpty]).
 * 정산일·대사 판정에 쓰지 않는 비권위 표시 구간이다(L-4).
 */
class HomeKpiWindow private constructor(val asOf: Instant, val businessDate: LocalDate, val from: Instant) {

    /** asOf = from(KST 자정 정각) — 집계할 사실이 없다(§3.1). */
    val isEmpty: Boolean get() = from == asOf

    override fun equals(other: Any?): Boolean = other is HomeKpiWindow && other.asOf == asOf && other.businessDate == businessDate && other.from == from

    override fun hashCode(): Int = asOf.hashCode()

    override fun toString(): String = "HomeKpiWindow(asOf=$asOf, businessDate=$businessDate, from=$from)"

    companion object {
        /** 업무일 시간대(D-ADMIN-HOME-001 ② — 화면 표기 `timeZone`). */
        val ZONE: ZoneId = ZoneId.of("Asia/Seoul")

        fun of(asOf: Instant): HomeKpiWindow {
            val at = asOf.truncatedTo(ChronoUnit.MILLIS)
            val date = LocalDate.ofInstant(at, ZONE)
            return HomeKpiWindow(at, date, date.atStartOfDay(ZONE).toInstant())
        }
    }
}

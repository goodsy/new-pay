package com.paycore.master.merchant.domain

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** 일 한도 날짜 기준(D-AUTH-007)과 같은 Asia/Seoul 자정 경계(D-MS2-002). */
object SeoulMidnight {
    val ZONE: ZoneId = ZoneId.of("Asia/Seoul")

    fun isBoundary(at: Instant): Boolean = at.nano == 0 && at.atZone(ZONE).toLocalTime() == LocalTime.MIDNIGHT

    /** [at]이 속한 Asia/Seoul 날짜의 00:00(UTC 시각) — 계약 시작 소급 판정의 "오늘" 기준(MERCHANT-OPS-002 §3.4, D-MOPS-006 ⑤). */
    fun startOfDay(at: Instant): Instant = at.atZone(ZONE).toLocalDate().atStartOfDay(ZONE).toInstant()
}

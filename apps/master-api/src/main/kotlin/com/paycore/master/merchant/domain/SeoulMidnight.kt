package com.paycore.master.merchant.domain

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** 일 한도 날짜 기준(D-AUTH-007)과 같은 Asia/Seoul 자정 경계(D-MS2-002). */
object SeoulMidnight {
    val ZONE: ZoneId = ZoneId.of("Asia/Seoul")

    fun isBoundary(at: Instant): Boolean = at.nano == 0 && at.atZone(ZONE).toLocalTime() == LocalTime.MIDNIGHT
}

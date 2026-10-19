package com.paycore.simulator.van.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 가상 VAN 날짜(Demo): 기관 거래일·영업일 = 결정 시각의 Asia/Seoul 날짜. 실제 기관 영업일 규칙이 아니다(PayCore 미결 Q-CALENDAR). */
object VanDates {
    private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

    fun seoulDate(at: Instant): LocalDate = at.atZone(SEOUL).toLocalDate()
}

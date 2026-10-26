package com.paycore.admin.account.application.required

import java.time.Instant

/** UTC 서버 시계(만료·잠금 시각의 권위 — §4.3.6 "서버 시계로 TX 안 판정"). 저장 정밀도(ms) 자르기는 사용하는 쪽이 한다. */
interface AdminClock {
    fun now(): Instant
}

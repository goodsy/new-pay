package com.paycore.simulator.van.application.required

import java.time.Instant

/** 가상 VAN 시계(판정·원장 시각). 인증 모듈 시계와 같은 시각원이어야 증거 유효기간 재검증이 맞다(운영 구현은 시스템 시계, 시험은 같은 시험 시계 하나가 둘 다 구현). */
fun interface VanClock {
    fun now(): Instant
}

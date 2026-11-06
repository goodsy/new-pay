package com.paycore.simulator.acquirer.application.required

import java.time.Instant

/** 가상 매입사 시계(수신 시각·게시 예정 시각 판정). 운영 구현은 시스템 시계, 시험은 Simulator 시험 시계 하나가 인증·VAN·가맹점 수신기와 함께 구현한다(가상 시계). */
fun interface AcquirerClock {
    fun now(): Instant
}

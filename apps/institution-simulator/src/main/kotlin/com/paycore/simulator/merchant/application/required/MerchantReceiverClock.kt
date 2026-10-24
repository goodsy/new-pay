package com.paycore.simulator.merchant.application.required

import java.time.Instant

/** 가상 가맹점 수신기 시계(서명 재전송 창·수신 시각). 운영 구현은 시스템 시계, 시험은 Simulator 시험 시계 하나가 인증·VAN과 함께 구현한다. */
fun interface MerchantReceiverClock {
    fun now(): Instant
}

package com.paycore.payment.entitlement.application.required

import java.time.Instant

/** PAYMENT 서버 시각(판정 now·수신 시각). 시험은 대역으로 고정한다. */
interface EntitlementClock {
    fun now(): Instant
}

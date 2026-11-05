package com.paycore.financial.paymentfact.application.required

import java.time.Instant

/** 수신 시각(시험이 바꿀 수 있게 Port로 둔다). */
interface PaymentFactClock {
    fun now(): Instant
}

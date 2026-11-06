package com.paycore.financial.acquiring.application.required

import java.time.Instant

/** 매입 시각(시험이 바꿀 수 있게 Port로 둔다). */
interface AcquiringClock {
    fun now(): Instant
}

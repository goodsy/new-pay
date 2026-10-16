package com.paycore.payment.payment.application.required

import java.time.Instant

/** 서버 시계(CP-CONTRACT-001 §2 evaluatedAt·createdAt). 가맹점 시각을 쓰지 않는다. 시험은 고정·이동 가능한 대역을 쓴다. */
interface PaymentClock {
    fun now(): Instant
}

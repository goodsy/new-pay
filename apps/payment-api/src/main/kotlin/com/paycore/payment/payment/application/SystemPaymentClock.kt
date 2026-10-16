package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.required.PaymentClock
import org.springframework.stereotype.Component
import java.time.Instant

/** 운영 서버 시계(UTC Instant). */
@Component
class SystemPaymentClock : PaymentClock {
    override fun now(): Instant = Instant.now()
}

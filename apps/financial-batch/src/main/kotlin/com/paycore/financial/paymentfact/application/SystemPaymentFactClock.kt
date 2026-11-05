package com.paycore.financial.paymentfact.application

import com.paycore.financial.paymentfact.application.required.PaymentFactClock
import org.springframework.stereotype.Component
import java.time.Instant

/** 시스템 시계(UTC Instant). 시험은 다른 [PaymentFactClock] Bean으로 바꾼다. */
@Component
class SystemPaymentFactClock : PaymentFactClock {
    override fun now(): Instant = Instant.now()
}

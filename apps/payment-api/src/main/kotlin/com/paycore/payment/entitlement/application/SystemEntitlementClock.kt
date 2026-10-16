package com.paycore.payment.entitlement.application

import com.paycore.payment.entitlement.application.required.EntitlementClock
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

/** UTC 시스템 시계(JDK Clock만). */
@Component
class SystemEntitlementClock : EntitlementClock {
    private val clock: Clock = Clock.systemUTC()

    override fun now(): Instant = clock.instant()
}

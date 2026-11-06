package com.paycore.financial.acquiring.application

import com.paycore.financial.acquiring.application.required.AcquiringClock
import org.springframework.stereotype.Component
import java.time.Instant

/** 시스템 시계(UTC Instant). */
@Component
class SystemAcquiringClock : AcquiringClock {
    override fun now(): Instant = Instant.now()
}

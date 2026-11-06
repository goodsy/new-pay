package com.paycore.simulator.acquirer.application

import com.paycore.simulator.acquirer.application.required.AcquirerClock
import org.springframework.stereotype.Component
import java.time.Instant

/** 시스템 시계(운영). 시험은 @Primary 시험 시계(인증·VAN·가맹점 수신기와 같은 객체 — 가상 시계)로 바꾼다. */
@Component
class SystemAcquirerClock : AcquirerClock {
    override fun now(): Instant = Instant.now()
}

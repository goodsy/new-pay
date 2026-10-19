package com.paycore.simulator.van.application

import com.paycore.simulator.van.application.required.VanClock
import org.springframework.stereotype.Component
import java.time.Instant

/** 시스템 시계(운영). 시험은 @Primary 시험 시계(인증 시계와 같은 객체)로 바꾼다. */
@Component
class SystemVanClock : VanClock {
    override fun now(): Instant = Instant.now()
}

package com.paycore.simulator.authentication.application

import com.paycore.simulator.authentication.application.required.SimulatorClock
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.temporal.ChronoUnit

/** 운영 Simulator 시계(UTC, ms 단위 — 계약 시각 표기와 같은 정밀도). */
@Component
class SystemSimulatorClock : SimulatorClock {
    override fun now(): Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS)
}

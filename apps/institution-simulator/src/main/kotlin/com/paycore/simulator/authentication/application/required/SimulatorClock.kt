package com.paycore.simulator.authentication.application.required

import java.time.Instant

/** Simulator 시계(시험에서 조정 — §13.2). */
interface SimulatorClock {
    fun now(): Instant
}

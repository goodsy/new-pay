package com.paycore.master.merchant.application

import com.paycore.master.merchant.application.required.MasterClock
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

/** UTC 시스템 시계(JDK `Clock`만 — 외부 기술 의존 없음). 저장 정밀도(ms) 자르기는 사용하는 쪽([AdminCommandExecutor])이 한다. */
@Component
class SystemMasterClock : MasterClock {
    private val clock: Clock = Clock.systemUTC()

    override fun now(): Instant = clock.instant()
}

package com.paycore.admin.audit.adapter.integration

import com.paycore.admin.audit.application.required.AuditClock
import org.springframework.stereotype.Component
import java.time.Instant

/** [AuditClock] 시스템 UTC 구현. */
@Component
class SystemAuditClock : AuditClock {
    override fun now(): Instant = Instant.now()
}

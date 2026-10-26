package com.paycore.admin.audit.application

import com.paycore.admin.audit.application.provided.AdminSecurityEventRecorder
import com.paycore.admin.audit.application.provided.AuditRecord
import com.paycore.admin.audit.application.provided.AdminAuditRecorder
import com.paycore.admin.audit.application.required.AuditClock
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import java.time.temporal.ChronoUnit

/** [AdminSecurityEventRecorder]: 감사 1행을 자기 로컬 TX로 커밋한다(호출자 TX 없음 — servlet filter 등). */
@Service
class AdminSecurityEventService(
    transactionManager: PlatformTransactionManager,
    private val recorder: AdminAuditRecorder,
    private val clock: AuditClock,
) : AdminSecurityEventRecorder {

    private val transactions = LocalTransactions(transactionManager, maxAttempts = 1)

    private companion object {
        val AREAS = setOf("AUTH", "ENROLL")
    }

    override fun recordIpDenied(remoteIp: String, correlationId: String, area: String) {
        require(area in AREAS) { "unknown ip-denied area" }
        transactions.execute({ false }) {
            recorder.record(
                AuditRecord(
                    occurredAt = clock.now().truncatedTo(ChronoUnit.MILLIS), actorKind = "ANONYMOUS", actorId = null, actorDisplayName = null,
                    action = "IP_DENIED", resultCode = "DENIED", correlationId = correlationId, remoteIp = remoteIp.take(45), detail = "path=$area",
                ),
            )
        }
    }
}

package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.provided.AuthorizationBacklog
import com.paycore.payment.payment.application.provided.AuthorizationBacklogQuery
import com.paycore.payment.payment.application.required.AuthorizationBacklogReader
import com.paycore.payment.payment.application.required.PaymentClock
import org.springframework.stereotype.Service
import java.time.Duration

/**
 * 승인 적체 관찰(AUTH-001 §9 Observability): jOOQ 수와 지금 시각(서버 clock) 기준 최고 연령 — 판정이 아니라 gauge 값이다(회복 지연·이관 누적·보류 누적 관찰).
 * 이관·보류 수가 늘어도 자동으로 해결하지 않는다(해결은 RECON-001 대사·MATCH).
 */
@Service
class AuthorizationBacklogService(private val clock: PaymentClock, private val reader: AuthorizationBacklogReader) : AuthorizationBacklogQuery {
    override fun backlog(): AuthorizationBacklog {
        val c = reader.counts()
        val age = c.oldestUnknownPreparedAt?.let { Duration.between(it, clock.now()).seconds.coerceAtLeast(0) } ?: 0
        return AuthorizationBacklog(c.unknownAttempts, age, c.escalatedOpenSchedules, c.activeIncidentHolds)
    }
}

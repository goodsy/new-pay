package com.paycore.payment.entitlement.adapter.integration

import com.paycore.payment.entitlement.application.required.EntitlementMetrics
import com.paycore.payment.entitlement.domain.InboxOutcome
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/**
 * PAYMENT 권리 지표(Micrometer, 명세 §11). 모니터링 시스템 연동이라 adapter.integration에 둔다. label은 분류값만(outcome·result·reason) —
 * MID·merchantId·eventId 금지(그 값은 구조화 로그에만).
 */
@Component
class MicrometerEntitlementMetrics(private val registry: MeterRegistry) : EntitlementMetrics {
    override fun inboxOutcome(outcome: InboxOutcome) {
        registry.counter(INBOX_OUTCOMES, "outcome", outcome.name).increment()
    }

    override fun inboxRejected(kind: String) {
        registry.counter(INBOX_REJECTED, "kind", kind).increment()
    }

    override fun eligibility(result: String, reason: String?) {
        registry.counter(ELIGIBILITY, "result", result, "reason", reason ?: "NONE").increment()
    }

    override fun authorizationTerms(result: String, reason: String?) {
        registry.counter(AUTHORIZATION_TERMS, "result", result, "reason", reason ?: "NONE").increment()
    }

    override fun merchantCodeChangedWithMid() {
        registry.counter(MERCHANT_CODE_CHANGED_WITH_MID).increment()
    }

    companion object {
        const val INBOX_OUTCOMES = "paycore.payment.entitlement.inbox.outcomes"
        const val INBOX_REJECTED = "paycore.payment.entitlement.inbox.rejected"
        const val ELIGIBILITY = "paycore.payment.entitlement.eligibility"
        const val AUTHORIZATION_TERMS = "paycore.payment.entitlement.authorization.terms"

        /** MERCHANT-CODE-001 §6·§9 Observability 경보 지표(이름 끝 = 명세 이름 `merchant_code_changed_with_mid`). label 없음. */
        const val MERCHANT_CODE_CHANGED_WITH_MID = "paycore.payment.entitlement.merchant_code_changed_with_mid"
        const val BLOCKED_HEADS = "paycore.payment.entitlement.heads.blocked"
        const val EXPIRED_HEADS = "paycore.payment.entitlement.heads.expired"
        const val MIN_LEASE_REMAINING = "paycore.payment.entitlement.lease.min.remaining"
    }
}

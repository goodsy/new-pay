package com.paycore.admin.approval.adapter.integration

import com.paycore.admin.approval.application.required.OwnerOpsMetrics
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 소유 서비스 운영 지표(Micrometer — ADMIN-PAYMENT-OPS-001 §6·§4.16). 경보 기준(운영 설정 — Demo 값): [INTEGRITY_ALERT]·[RESEND_HALTED]·[OUTCOME_DIVERGENCE] 1건 이상 즉시,
 * [UNKNOWN_OLDEST_AGE] 15분(900초) 초과. gauge 값은 회복 job 실행마다 갱신한다. label은 고정 분류값만.
 */
@Component
class MicrometerOwnerOpsMetrics(private val registry: MeterRegistry) : OwnerOpsMetrics {
    private val unresolvedGauges = ConcurrentHashMap<String, AtomicLong>()
    private val oldestAge: AtomicLong = requireNotNull(registry.gauge(UNKNOWN_OLDEST_AGE, AtomicLong(0)))

    override fun integrityAlert(code: String) {
        registry.counter(INTEGRITY_ALERT, "code", code).increment()
    }

    override fun resend(result: String) {
        registry.counter(RESEND, "result", result).increment()
    }

    override fun resendHalted(reason: String) {
        registry.counter(RESEND_HALTED, "reason", reason).increment()
    }

    override fun outcomeDivergence() {
        registry.counter(OUTCOME_DIVERGENCE).increment()
    }

    override fun unresolved(status: String, count: Long) {
        unresolvedGauges.computeIfAbsent(status) { s -> requireNotNull(registry.gauge(UNRESOLVED, listOf(Tag.of("status", s)), AtomicLong(0))) }.set(count)
    }

    override fun unknownOldestAge(seconds: Long) {
        oldestAge.set(seconds)
    }

    private val reconLagSeconds: AtomicLong = requireNotNull(registry.gauge(RECON_LAG, AtomicLong(0)))

    override fun reconRun(result: String) {
        registry.counter(RECON_RUN, "result", result).increment()
    }

    override fun reconFinding(kind: String) {
        registry.counter(RECON_FINDING, "kind", kind).increment()
    }

    override fun reconFindingDuplicate() {
        registry.counter(RECON_FINDING_DUPLICATE).increment()
    }

    override fun reconLag(seconds: Long) {
        reconLagSeconds.set(seconds)
    }

    private val openFindingGauges: Map<String, AtomicLong> = com.paycore.admin.approval.domain.ReconciliationFindingKind.entries.associate { kind ->
        kind.name to requireNotNull(registry.gauge(RECON_OPEN_FINDINGS, listOf(Tag.of("kind", kind.name)), AtomicLong(0)))
    }

    override fun justificationRateLimited() {
        registry.counter(JUSTIFICATION_RATE_LIMITED).increment()
    }

    override fun openFindings(counts: Map<String, Long>) {
        openFindingGauges.forEach { (kind, gauge) -> gauge.set(counts[kind] ?: 0) }
    }

    companion object {
        const val INTEGRITY_ALERT = "paycore.admin.ops.integrity_alert"
        const val RESEND = "paycore.admin.ops.resend"
        const val RESEND_HALTED = "paycore.admin.ops.resend_halted"
        const val OUTCOME_DIVERGENCE = "paycore.admin.ops.outcome_divergence"
        const val UNRESOLVED = "paycore.admin.ops.unresolved"
        const val UNKNOWN_OLDEST_AGE = "paycore.admin.ops.unknown.oldest_age_seconds"
        const val RECON_RUN = "paycore.admin.ops.recon.run"
        const val RECON_FINDING = "paycore.admin.ops.recon.finding"
        const val RECON_FINDING_DUPLICATE = "paycore.admin.ops.recon.finding_duplicate"
        const val RECON_LAG = "paycore.admin.ops.recon.lag_seconds"
        const val RECON_OPEN_FINDINGS = "paycore.admin.ops.recon.open_findings"
        const val JUSTIFICATION_RATE_LIMITED = "paycore.admin.approval.justification_rate_limited"
    }
}

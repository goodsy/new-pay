package com.paycore.master.merchant.adapter.integration

import com.paycore.master.merchant.application.provided.EntitlementPropagationQuery
import com.paycore.master.merchant.application.required.MasterOperationalMetrics
import com.paycore.master.merchant.domain.SnapshotPriority
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration

/**
 * MASTER 운영 지표(Micrometer, 명세 §11 Observability · MASTER-DOMAIN-001 review m-7·m-8). 모니터링 시스템으로 내보내는 바깥 연동이라
 * adapter.integration에 둔다(application은 [MasterOperationalMetrics] Port만 안다). **label은 낮은 cardinality 분류값만**:
 * 연산 코드·결과·우선순위. MID·merchantId·eventId를 label로 쓰지 않는다(그 값은 구조화 로그에만).
 */
@Component
class MicrometerMasterOperationalMetrics(private val registry: MeterRegistry) : MasterOperationalMetrics {

    override fun adminOutcomeUnknown(operation: String) = adminFailure("outcome_unknown", operation)

    override fun adminRetriesExhausted(operation: String) = adminFailure("retries_exhausted", operation)

    override fun adminIdentifierCollision(operation: String) = adminFailure("identifier_collision", operation)

    override fun snapshotIssued(priority: SnapshotPriority) {
        registry.counter(SNAPSHOTS_ISSUED, "priority", priority.name).increment()
    }

    override fun relayResult(result: String) {
        registry.counter(RELAY_RESULTS, "result", result).increment()
    }

    override fun revisionHashDrift() {
        registry.counter(HASH_DRIFT).increment()
    }

    private fun adminFailure(kind: String, operation: String) {
        registry.counter(ADMIN_FAILURES, "kind", kind, "operation", operation).increment()
    }

    companion object {
        const val ADMIN_FAILURES = "paycore.master.admin.failures"
        const val SNAPSHOTS_ISSUED = "paycore.master.entitlement.snapshots.issued"
        const val RELAY_RESULTS = "paycore.master.entitlement.relay.results"
        const val HASH_DRIFT = "paycore.master.entitlement.revision.hash.drift"
        const val PENDING = "paycore.master.entitlement.outbox.pending"
        const val OLDEST_PENDING_AGE = "paycore.master.entitlement.outbox.oldest.pending.age"
        const val QUARANTINED = "paycore.master.entitlement.outbox.quarantined"
        const val UNCONFIRMED = "paycore.master.entitlement.effect.unconfirmed"
    }
}

/**
 * 전파 상태 gauge(명세 §11): 미전달 Snapshot 수·최고 대기 연령(초)·격리 수·효력 미확인 최신 revision 수. 값은 조회 시점마다 jOOQ 조회 use case
 * ([EntitlementPropagationQuery], 읽기 전용 TX)로 계산한다 — 프로세스 안에 별도 상태를 두지 않는다.
 */
@Component
class EntitlementPropagationGauges(registry: MeterRegistry, private val query: EntitlementPropagationQuery) {
    private val clock: Clock = Clock.systemUTC()

    init {
        Gauge.builder(MicrometerMasterOperationalMetrics.PENDING) { query.overview().pendingSnapshots.toDouble() }.register(registry)
        Gauge.builder(MicrometerMasterOperationalMetrics.QUARANTINED) { query.overview().quarantinedSnapshots.toDouble() }.register(registry)
        Gauge.builder(MicrometerMasterOperationalMetrics.UNCONFIRMED) { query.overview().unconfirmedLatestRevisions.toDouble() }.register(registry)
        Gauge.builder(MicrometerMasterOperationalMetrics.OLDEST_PENDING_AGE) {
            query.overview().oldestPendingCreatedAt?.let { Duration.between(it, clock.instant()).toMillis() / MILLIS_PER_SECOND } ?: 0.0
        }.baseUnit("seconds").register(registry)
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000.0
    }
}

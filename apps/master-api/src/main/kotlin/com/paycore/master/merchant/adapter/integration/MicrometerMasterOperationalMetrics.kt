package com.paycore.master.merchant.adapter.integration

import com.paycore.master.merchant.application.required.MasterOperationalMetrics
import com.paycore.master.merchant.domain.SnapshotPriority
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

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

    override fun schemaPromotion() {
        registry.counter(SCHEMA_PROMOTIONS).increment()
    }

    private fun adminFailure(kind: String, operation: String) {
        registry.counter(ADMIN_FAILURES, "kind", kind, "operation", operation).increment()
    }

    companion object {
        const val ADMIN_FAILURES = "paycore.master.admin.failures"
        const val SNAPSHOTS_ISSUED = "paycore.master.entitlement.snapshots.issued"
        const val RELAY_RESULTS = "paycore.master.entitlement.relay.results"
        const val HASH_DRIFT = "paycore.master.entitlement.revision.hash.drift"
        const val SCHEMA_PROMOTIONS = "paycore.master.entitlement.schema.promotions"
        const val PENDING = "paycore.master.entitlement.outbox.pending"
        const val OLDEST_PENDING_AGE = "paycore.master.entitlement.outbox.oldest.pending.age"
        const val QUARANTINED = "paycore.master.entitlement.outbox.quarantined"
        const val UNCONFIRMED = "paycore.master.entitlement.effect.unconfirmed"
        const val TERMS_MISSING = "paycore.master.authorization.terms.missing"
    }
}

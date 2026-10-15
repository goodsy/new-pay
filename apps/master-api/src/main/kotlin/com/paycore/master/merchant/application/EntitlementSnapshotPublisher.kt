package com.paycore.master.merchant.application

import com.paycore.master.merchant.application.required.EntitlementIssueStore
import com.paycore.master.merchant.application.required.EntitlementSourceStore
import com.paycore.master.merchant.application.required.EntitlementTiming
import com.paycore.master.merchant.application.required.MasterIdGenerator
import com.paycore.master.merchant.application.required.MasterOperationalMetrics
import com.paycore.master.merchant.application.required.SnapshotIssueRecord
import com.paycore.master.merchant.application.required.SnapshotPublication
import com.paycore.master.merchant.domain.ENTITLEMENT_SNAPSHOT_SCHEMA_VERSION
import com.paycore.master.merchant.domain.EntitlementSnapshot
import com.paycore.master.merchant.domain.EntitlementSnapshotAssembler
import com.paycore.master.merchant.domain.Merchant
import com.paycore.master.merchant.domain.SnapshotPriority
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * 권리 Snapshot 발행(MASTER-SNAPSHOT-001 명세 §4.6). 호출자가 연 MASTER 로컬 TX 안에서 같은 영속성 컨텍스트로 현재 상태를 읽어 조립하고
 * `entitlement_snapshot_issue`(append-only)와 Outbox(`ENTITLEMENT_SNAPSHOT`)를 함께 쓴다. 외부 호출 없음.
 *
 * - 변경 발행([publishChange]): 새 revision의 첫 발행 — leaseSeq 0, priority HIGH(차단 우선 전파 §3.4-1: 모든 변경을 HIGH로 둔다).
 * - lease 갱신([publishRenewal]): 같은 revision(변경 없으면) leaseSeq+1, priority LOW. 갱신은 언제나 **현재** 상태로 조립한다(R4 — 철회·차단
 *   뒤의 갱신은 차단 상태를 갱신할 뿐 과거 허용을 되살리지 않는다). 같은 revision인데 내용 hash가 다르면 revision 증가 누락 결함이다:
 *   ERROR 로그·지표를 남기고 그대로 발행한다(PAYMENT가 INTEGRITY_CONFLICT로 fail-closed — 명세 §3.5, 자동 보정으로 결함을 숨기지 않는다).
 */
@Component
class EntitlementSnapshotPublisher(
    private val sources: EntitlementSourceStore,
    private val issues: EntitlementIssueStore,
    private val timing: EntitlementTiming,
    private val ids: MasterIdGenerator,
    private val metrics: MasterOperationalMetrics,
) {
    private val log = LoggerFactory.getLogger(EntitlementSnapshotPublisher::class.java)

    fun publishChange(merchant: Merchant, at: Instant, correlationId: String, causationId: String): SnapshotIssueRecord? {
        val source = sources.load(merchant.id) ?: return null
        check(source.merchant.entitlementRevision == merchant.entitlementRevision) { "the stored entitlement revision must be the one just written" }
        val snapshot = EntitlementSnapshotAssembler.assemble(source, at, 0, timing.leaseDuration)
        return publish(snapshot, SnapshotPriority.HIGH, correlationId, causationId)
    }

    fun publishRenewal(merchant: Merchant, latest: SnapshotIssueRecord?, at: Instant, correlationId: String, causationId: String): SnapshotIssueRecord? {
        val source = sources.load(merchant.id) ?: return null
        val sameRevision = latest != null && latest.revision == merchant.entitlementRevision
        check(latest == null || latest.revision <= merchant.entitlementRevision) { "an issued revision is never ahead of the merchant" }
        val snapshot = EntitlementSnapshotAssembler.assemble(source, at, if (sameRevision) checkNotNull(latest).leaseSeq + 1 else 0, timing.leaseDuration)
        if (sameRevision && checkNotNull(latest).contentHash != snapshot.contentHash) {
            log.error(
                "entitlement revision hash drift merchantId={} revision={} correlationId={} (rights changed without a revision increment)",
                merchant.id.value, snapshot.revision, correlationId,
            )
            metrics.revisionHashDrift()
        }
        return publish(snapshot, SnapshotPriority.LOW, correlationId, causationId)
    }

    private fun publish(snapshot: EntitlementSnapshot, priority: SnapshotPriority, correlationId: String, causationId: String): SnapshotIssueRecord {
        val record = SnapshotIssueRecord(
            merchantId = snapshot.merchantId,
            revision = snapshot.revision,
            leaseSeq = snapshot.leaseSeq,
            contentHash = snapshot.contentHash,
            issuedAt = snapshot.issuedAt,
            leaseUntil = snapshot.leaseUntil,
            priority = priority,
            eventId = ids.newUuid(),
            schemaVersion = ENTITLEMENT_SNAPSHOT_SCHEMA_VERSION,
        )
        issues.record(
            SnapshotPublication(
                issue = record,
                eventType = EVENT_TYPE,
                schemaVersion = ENTITLEMENT_SNAPSHOT_SCHEMA_VERSION,
                producer = AdminCommandExecutor.PRODUCER,
                correlationId = correlationId,
                causationId = causationId,
                wirePayload = snapshot.wirePayload(),
            ),
        )
        metrics.snapshotIssued(priority)
        return record
    }

    companion object {
        const val EVENT_TYPE = "EntitlementSnapshot"
        const val EVENT_CATEGORY = "ENTITLEMENT_SNAPSHOT"
    }
}

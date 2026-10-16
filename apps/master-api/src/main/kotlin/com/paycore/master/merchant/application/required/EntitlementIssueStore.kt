package com.paycore.master.merchant.application.required

import com.paycore.master.merchant.domain.MerchantId
import com.paycore.master.merchant.domain.SnapshotPriority
import java.time.Instant

/** 발행 기록(`entitlement_snapshot_issue`, append-only) 한 건. [eventId] = 같은 TX의 Outbox 이벤트. */
data class SnapshotIssueRecord(
    val merchantId: MerchantId,
    val revision: Long,
    val leaseSeq: Long,
    val contentHash: String,
    val issuedAt: Instant,
    val leaseUntil: Instant,
    val priority: SnapshotPriority,
    val eventId: String,
    /** 이 발행의 Snapshot schemaVersion(MASTER-SNAPSHOT-002 §5.2 — 옛 버전 발행 뒤의 갱신은 같은 revision 재발행이 아니라 승격 발행). */
    val schemaVersion: Int,
)

/** 발행 기록 + Outbox 이벤트(`ENTITLEMENT_SNAPSHOT`)를 같은 TX에 넣기 위한 재료. [wirePayload]는 정규 JSON 전송 payload. */
data class SnapshotPublication(
    val issue: SnapshotIssueRecord,
    val eventType: String,
    val schemaVersion: Int,
    val producer: String,
    val correlationId: String,
    val causationId: String,
    val wirePayload: String,
)

interface EntitlementIssueStore {
    /** 그 Merchant의 가장 최근 발행(revision·leaseSeq 최대). */
    fun latest(merchantId: MerchantId): SnapshotIssueRecord?

    fun record(publication: SnapshotPublication)

    /** 가장 최근 발행의 lease가 [threshold] 이전에 끝나는 Merchant(갱신 후보, 잠금 없음 — 갱신 TX가 merchant 잠금 아래 다시 확인한다). */
    fun merchantsDueForRenewal(threshold: Instant, limit: Int): List<MerchantId>
}

package com.paycore.master.merchant.application.required

import com.paycore.master.merchant.application.provided.MerchantPropagationView
import com.paycore.master.merchant.application.provided.PropagationOverview
import com.paycore.master.merchant.domain.EntitlementSource
import com.paycore.master.merchant.domain.MerchantId
import com.paycore.master.merchant.domain.SnapshotPriority
import java.time.Duration
import java.time.Instant

/*
 * 권리 Snapshot 발행·전달 Port(MASTER-SNAPSHOT-001 명세 §4.6–§4.8, §7).
 */

/**
 * 발행·lease·Relay 시간 설정(명세 §8). **운영 기본값을 코드에 두지 않는다** — 구현(adapter.integration 설정 바인딩)은 값이 없으면 기동을
 * 실패시킨다. 시험·로컬 Simulator는 Demo fixture 값을 명시적으로 넣는다(운영값 아님, 허용 §4.2 마지막 문단).
 */
interface EntitlementTiming {
    val leaseDuration: Duration
    val renewBefore: Duration
    val relayBatchSize: Int
    val claimLease: Duration
    val backoffInitial: Duration
    val backoffMax: Duration

    /** 0 이상 1 미만. backoff에 곱해 더하는 무작위 비율의 상한(0이면 결정적). */
    val backoffJitterRatio: Double
}

/** Snapshot 조립 입력 읽기(같은 MASTER TX·같은 JPA 영속성 컨텍스트 — 명세 §4.6). MID가 없는 Merchant는 null(발행 대상 아님, §3.2). */
interface EntitlementSourceStore {
    fun load(merchantId: MerchantId): EntitlementSource?
}

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

/** Relay가 집은 Outbox 한 건(봉투 v1 재료, 명세 §7). */
data class RelayEnvelope(
    val eventId: String,
    val eventType: String,
    val schemaVersion: Int,
    val producer: String,
    val aggregateId: String,
    val aggregateVersion: Long,
    val occurredAt: Instant,
    val attemptCount: Int,
    val payloadJson: String,
)

/** PAYMENT Inbox 내구 커밋 확인(ACK). */
data class SnapshotAck(val consumer: String, val outcome: String, val appliedRevision: Long, val ackedAt: Instant, val originalOutcome: String? = null)

enum class RelayMarkResult {
    /** 이 Relay의 claim으로 상태를 바꿨다. */
    MARKED,

    /** 이미 PUBLISHED였다(다른 인스턴스가 먼저 전달 — ACK만 추가). */
    ALREADY_PUBLISHED,

    /** claim이 만료돼 다른 인스턴스가 다시 집었다(상태를 덮어쓰지 않는다). */
    CLAIM_LOST,
}

/**
 * Outbox Relay 저장(명세 §4.7). claim은 `FOR UPDATE SKIP LOCKED` 한 TX, 결과 반영은 행마다 별도 TX다(전달은 TX 밖).
 */
interface EntitlementRelayStore {
    fun claim(owner: String, now: Instant, claimUntil: Instant, limit: Int): List<RelayEnvelope>

    /** PUBLISHED 표시 + ACK append(이미 PUBLISHED여도 ACK는 추가 — 멱등). */
    fun markPublished(eventId: String, owner: String, ack: SnapshotAck): RelayMarkResult

    fun markRetry(eventId: String, owner: String, nextAttemptAt: Instant, failureCode: String): RelayMarkResult

    /** 계약 오류(4xx) → QUARANTINED(완료 아님 — 경보 대상, A03 "DLQ는 완료 상태가 아니다"). */
    fun markQuarantined(eventId: String, owner: String, failureCode: String): RelayMarkResult
}

/** 전달 결과(HTTP adapter 분류, 명세 §4.7). */
sealed interface DispatchOutcome {
    /** 2xx: PAYMENT가 내구 커밋했다. */
    data class Acknowledged(val consumer: String, val outcome: String, val appliedRevision: Long, val originalOutcome: String? = null) : DispatchOutcome

    /** 5xx·timeout·연결 오류: 같은 eventId로 다시 보낸다(상한 없음 — 차단 Snapshot을 포기하지 않는다). */
    data class Retryable(val code: String) : DispatchOutcome

    /** 4xx(서명·봉투·내용 오류): 계약 오류. 재전송하지 않고 격리한다. */
    data class Rejected(val code: String) : DispatchOutcome
}

/** PAYMENT 내부 Inbox로 전달(DB TX 밖에서만 호출 — 외부 호출 규칙). */
interface EntitlementDispatcher {
    fun dispatch(envelope: RelayEnvelope): DispatchOutcome
}

/**
 * MASTER 운영 지표(명세 §11 Observability, MASTER-DOMAIN-001 review m-7·m-8). label에 MID·merchantId 같은 식별자를 쓰지 않는다
 * (연산 코드·결과 분류만).
 */
interface MasterOperationalMetrics {
    fun adminOutcomeUnknown(operation: String)

    fun adminRetriesExhausted(operation: String)

    fun adminIdentifierCollision(operation: String)

    fun snapshotIssued(priority: SnapshotPriority)

    fun relayResult(result: String)

    /** 같은 revision인데 다시 조립한 내용이 다르다(revision 증가 누락 결함 — PAYMENT는 INTEGRITY_CONFLICT로 막는다). */
    fun revisionHashDrift()

    /** 옛 schemaVersion 발행 뒤 갱신 시점에 revision을 올려 현재 버전으로 다시 발행했다(MASTER-SNAPSHOT-002 §5.2). */
    fun schemaPromotion()
}

/** 전파 조회(CQRS 조회 측, jOOQ adapter.query — 명세 §4.8). */
interface EntitlementPropagationReader {
    fun merchant(merchantId: MerchantId, now: Instant): MerchantPropagationView?

    fun overview(now: Instant): PropagationOverview
}

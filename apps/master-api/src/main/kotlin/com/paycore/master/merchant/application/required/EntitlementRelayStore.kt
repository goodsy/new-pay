package com.paycore.master.merchant.application.required

import java.time.Instant

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

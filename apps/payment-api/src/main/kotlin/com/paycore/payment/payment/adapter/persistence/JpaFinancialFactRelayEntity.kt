package com.paycore.payment.payment.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant

/**
 * financial_fact_relay 행(FIN-FACT-RELAY-001 V32 — Outbox 행당 FINANCIAL 전달 상태). 행은 발견 질의(native INSERT … SELECT)로만 생기고, 이 Entity는 잠가 읽은 뒤
 * 변경 감지로 claim·결과를 바꾼다(`@Version` — 잠금 밖 stale 객체가 최신 행을 덮는 결함의 두 번째 방어). Outbox 식별 열은 updatable=false.
 */
@Entity
@Table(name = "financial_fact_relay")
class JpaFinancialFactRelayEntity protected constructor() {
    @field:Id
    @field:Column(name = "outbox_id", nullable = false, updatable = false)
    var outboxId: Long = 0
        protected set

    @field:Column(name = "event_id", nullable = false, updatable = false, columnDefinition = "char(36)")
    lateinit var eventId: String
        protected set

    @field:Column(name = "status", nullable = false, length = 16)
    lateinit var status: String

    @field:Column(name = "attempts", nullable = false)
    var attempts: Int = 0

    @field:Column(name = "next_attempt_at", nullable = false)
    lateinit var nextAttemptAt: Instant

    @field:Column(name = "claim_owner", length = 64)
    var claimOwner: String? = null

    @field:Column(name = "claim_until")
    var claimUntil: Instant? = null

    @field:Column(name = "last_failure_code", length = 48)
    var lastFailureCode: String? = null

    @field:Column(name = "ack_outcome", length = 16)
    var ackOutcome: String? = null

    @field:Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: Instant
        protected set

    @field:Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant

    @field:Column(name = "delivered_at")
    var deliveredAt: Instant? = null

    @field:Column(name = "quarantined_at")
    var quarantinedAt: Instant? = null

    @field:Version
    @field:Column(name = "version", nullable = false)
    var version: Long = 0
        protected set

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)

    override fun toString(): String = "JpaFinancialFactRelayEntity(outboxId=$outboxId, status=$status, attempts=$attempts, version=$version)"

    companion object {
        const val PENDING = "PENDING"
        const val DELIVERED = "DELIVERED"
        const val QUARANTINED = "QUARANTINED"
    }
}

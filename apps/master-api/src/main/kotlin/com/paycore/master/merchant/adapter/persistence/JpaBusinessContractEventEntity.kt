package com.paycore.master.merchant.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import org.hibernate.Hibernate
import org.hibernate.annotations.Immutable
import java.io.Serializable
import java.time.Instant

/**
 * `business_contract_event` 행(MERCHANT-FLOW-001 §4B.2 — 정지·정지 해제·해지 기록, **INSERT 전용**, BR-BIZ-336). JPA 선택은 [JpaBusinessContractEntity]와 같다.
 * - **생성 열** `terminate_key`(해지일 때만 1 — UNIQUE `uk_bce_terminate`(사업자, 계약, terminate_key): 해지는 계약당 1회, I-6)는 매핑하지 않는다(DB가 계산하는 이중 방어 — §4B.12).
 * - '사유 및 비고'·근거 참조 원문은 이 표에만 — toString에 넣지 않는다.
 */
@Entity
@Immutable
@IdClass(JpaBusinessContractEventEntity.Key::class)
@Table(name = "business_contract_event")
class JpaBusinessContractEventEntity protected constructor() {
    @field:Id
    @field:Column(name = "business_id", nullable = false, updatable = false, length = 64)
    lateinit var businessId: String
        protected set

    @field:Id
    @field:Column(name = "contract_seq", nullable = false, updatable = false)
    var contractSeq: Int = 0
        protected set

    @field:Id
    @field:Column(name = "event_seq", nullable = false, updatable = false)
    var eventSeq: Int = 0
        protected set

    @field:Column(name = "kind", nullable = false, updatable = false, length = 16)
    lateinit var kind: String
        protected set

    @field:Column(name = "reason", nullable = false, updatable = false, length = 200)
    lateinit var reason: String
        protected set

    @field:Column(name = "evidence_ref", updatable = false, length = 128)
    var evidenceRef: String? = null
        protected set

    @field:Column(name = "actor_ref", nullable = false, updatable = false, length = 64)
    lateinit var actorRef: String
        protected set

    @field:Column(name = "occurred_at", nullable = false, updatable = false)
    lateinit var occurredAt: Instant
        protected set

    data class Key(var businessId: String = "", var contractSeq: Int = 0, var eventSeq: Int = 0) : Serializable

    override fun equals(other: Any?): Boolean =
        this === other || (
            other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && businessId == (other as JpaBusinessContractEventEntity).businessId &&
                contractSeq == other.contractSeq && eventSeq == other.eventSeq
            )

    override fun hashCode(): Int = (31 * businessId.hashCode() + contractSeq) * 31 + eventSeq

    override fun toString(): String = "JpaBusinessContractEventEntity(businessId=$businessId, contractSeq=$contractSeq, eventSeq=$eventSeq, kind=$kind)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(businessId: String, contractSeq: Int, eventSeq: Int, kind: String, reason: String, evidenceRef: String?, at: Instant, by: String) =
            JpaBusinessContractEventEntity().also {
                it.businessId = businessId
                it.contractSeq = contractSeq
                it.eventSeq = eventSeq
                it.kind = kind
                it.reason = reason
                it.evidenceRef = evidenceRef
                it.actorRef = by
                it.occurredAt = at
            }
    }
}

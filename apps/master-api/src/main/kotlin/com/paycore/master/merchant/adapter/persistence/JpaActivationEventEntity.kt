package com.paycore.master.merchant.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.Hibernate
import java.io.Serializable
import java.time.Instant

/*
 * MASTER-SNAPSHOT-001 표의 JPA Entity(adapter 전용 — 순수 Domain과 분리, Mapper가 변환). append-only 표는 모든 컬럼 updatable=false이며
 * Store는 persist만 한다(save/merge 금지 — runtime 계정에 UPDATE 권한이 없어 1142). 생성 컬럼(open_*_key)은 매핑하지 않는다.
 */

/** (activation_id, seq) 복합 키. */
@Embeddable
class ActivationEventKey protected constructor() : Serializable {
    @field:Column(name = "activation_id", nullable = false, updatable = false, length = 64)
    lateinit var activationId: String
        protected set

    @field:Column(name = "seq", nullable = false, updatable = false)
    var seq: Int = 0
        protected set

    override fun equals(other: Any?): Boolean = this === other || (other is ActivationEventKey && activationId == other.activationId && seq == other.seq)

    override fun hashCode(): Int = 31 * activationId.hashCode() + seq

    override fun toString(): String = "ActivationEventKey($activationId#$seq)"

    companion object {
        fun of(activationId: String, seq: Int) = ActivationEventKey().also {
            it.activationId = activationId
            it.seq = seq
        }
    }
}

/** payment_method_activation_event 행(append-only 전이 이력). */
@Entity
@Table(name = "payment_method_activation_event")
class JpaActivationEventEntity protected constructor() {
    @field:EmbeddedId
    lateinit var key: ActivationEventKey
        protected set

    @field:Column(name = "from_status", updatable = false, length = 16)
    var fromStatus: String? = null
        protected set

    @field:Column(name = "to_status", nullable = false, updatable = false, length = 16)
    lateinit var toStatus: String
        protected set

    @field:Column(name = "evidence_ref", updatable = false, length = 128)
    var evidenceRef: String? = null
        protected set

    @field:Column(name = "reason", updatable = false, length = 200)
    var reason: String? = null
        protected set

    @field:Column(name = "actor_ref", nullable = false, updatable = false, length = 64)
    lateinit var actorRef: String
        protected set

    @field:Column(name = "request_record_id", nullable = false, updatable = false, length = 64)
    lateinit var requestRecordId: String
        protected set

    @field:Column(name = "occurred_at", nullable = false, updatable = false)
    lateinit var occurredAt: Instant
        protected set

    override fun equals(other: Any?): Boolean =
        this === other || (other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && key == (other as JpaActivationEventEntity).key)

    override fun hashCode(): Int = key.hashCode()

    override fun toString(): String = "JpaActivationEventEntity(key=$key, to=$toStatus)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            key: ActivationEventKey,
            from: String?,
            to: String,
            evidenceRef: String?,
            reason: String?,
            actor: String,
            requestRecordId: String,
            at: Instant,
        ) = JpaActivationEventEntity().also {
            it.key = key
            it.fromStatus = from
            it.toStatus = to
            it.evidenceRef = evidenceRef
            it.reason = reason
            it.actorRef = actor
            it.requestRecordId = requestRecordId
            it.occurredAt = at
        }
    }
}

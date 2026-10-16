package com.paycore.master.merchant.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

/** master_change_history 행(append-only 감사, persist만). detail은 JSON 문자열(adapter 직렬화). */
@Entity
@Table(name = "master_change_history")
class JpaMasterChangeHistoryEntity protected constructor() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "id", nullable = false, updatable = false)
    var id: Long? = null
        protected set

    @field:Column(name = "request_record_id", nullable = false, updatable = false, length = 64)
    lateinit var requestRecordId: String
        protected set

    @field:Column(name = "operation", nullable = false, updatable = false, length = 48)
    lateinit var operation: String
        protected set

    @field:Column(name = "aggregate_type", nullable = false, updatable = false, length = 48)
    lateinit var aggregateType: String
        protected set

    @field:Column(name = "aggregate_id", nullable = false, updatable = false, length = 64)
    lateinit var aggregateId: String
        protected set

    @field:Column(name = "change_type", nullable = false, updatable = false, length = 48)
    lateinit var changeType: String
        protected set

    @field:Column(name = "actor_ref", nullable = false, updatable = false, length = 64)
    lateinit var actorRef: String
        protected set

    @field:Column(name = "occurred_at", nullable = false, updatable = false)
    lateinit var occurredAt: Instant
        protected set

    @field:JdbcTypeCode(SqlTypes.JSON)
    @field:Column(name = "detail", nullable = false, updatable = false)
    lateinit var detail: String
        protected set

    /** 저장 전 id가 없으므로 동일성은 인스턴스 기준(이 Entity는 비교·컬렉션에 쓰지 않는다). */
    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)

    override fun toString(): String = "JpaMasterChangeHistoryEntity(id=$id, aggregateType=$aggregateType, changeType=$changeType)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            requestRecordId: String,
            operation: String,
            aggregateType: String,
            aggregateId: String,
            changeType: String,
            actorRef: String,
            occurredAt: Instant,
            detail: String,
        ) = JpaMasterChangeHistoryEntity().also {
            it.requestRecordId = requestRecordId
            it.operation = operation
            it.aggregateType = aggregateType
            it.aggregateId = aggregateId
            it.changeType = changeType
            it.actorRef = actorRef
            it.occurredAt = occurredAt
            it.detail = detail
        }
    }
}

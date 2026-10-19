package com.paycore.simulator.van.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import java.time.Instant

/** van_inquiry_log 한 건(append-only, `@Immutable`). 조회 1회 = 1행. */
@Entity
@Immutable
@Table(name = "van_inquiry_log")
class JpaVanInquiryLogEntity protected constructor() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "id", nullable = false, updatable = false)
    var id: Long? = null
        protected set

    @field:Column(name = "client_id", nullable = false, updatable = false, length = 64)
    lateinit var clientId: String
        protected set

    @field:Column(name = "environment", nullable = false, updatable = false, length = 4)
    lateinit var environment: String
        protected set

    @field:Column(name = "institution_account", nullable = false, updatable = false, length = 64)
    lateinit var institutionAccount: String
        protected set

    @field:Column(name = "original_request_id", nullable = false, updatable = false, length = 64)
    lateinit var originalRequestId: String
        protected set

    @field:Column(name = "inquiry_id", nullable = false, updatable = false, columnDefinition = "char(29)")
    lateinit var inquiryId: String
        protected set

    @field:Column(name = "request_kind", nullable = false, updatable = false, length = 16)
    lateinit var requestKind: String
        protected set

    @field:Column(name = "result", nullable = false, updatable = false, length = 24)
    lateinit var result: String
        protected set

    @field:Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: Instant
        protected set

    override fun equals(other: Any?): Boolean = this === other || (other is JpaVanInquiryLogEntity && id != null && id == other.id)

    override fun hashCode(): Int = id?.hashCode() ?: 0

    override fun toString(): String = "JpaVanInquiryLogEntity(id=$id, result=$result)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            clientId: String, environment: String, institutionAccount: String, originalRequestId: String, inquiryId: String, requestKind: String, result: String,
            createdAt: Instant,
        ) = JpaVanInquiryLogEntity().also {
            it.clientId = clientId
            it.environment = environment
            it.institutionAccount = institutionAccount
            it.originalRequestId = originalRequestId
            it.inquiryId = inquiryId
            it.requestKind = requestKind
            it.result = result
            it.createdAt = createdAt
        }
    }
}

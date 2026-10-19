package com.paycore.simulator.van.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.Hibernate
import org.hibernate.annotations.Immutable
import java.time.Instant

/** van_evidence_use 행(append-only, `@Immutable`). PK = 증거 참조 — 증거 하나는 요청 ID 하나에만 쓰인다(AUTH-001 §5.3-3). */
@Entity
@Immutable
@Table(name = "van_evidence_use")
class JpaVanEvidenceUseEntity protected constructor() {
    @field:Id
    @field:Column(name = "evidence_ref", nullable = false, updatable = false, length = 128)
    lateinit var evidenceRef: String
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

    @field:Column(name = "operation", nullable = false, updatable = false, length = 16)
    lateinit var operation: String
        protected set

    @field:Column(name = "request_id", nullable = false, updatable = false, columnDefinition = "char(29)")
    lateinit var requestId: String
        protected set

    @field:Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: Instant
        protected set

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
        return evidenceRef == (other as JpaVanEvidenceUseEntity).evidenceRef
    }

    override fun hashCode(): Int = evidenceRef.hashCode()

    override fun toString(): String = "JpaVanEvidenceUseEntity(requestId=$requestId)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(evidenceRef: String, clientId: String, environment: String, institutionAccount: String, operation: String, requestId: String, createdAt: Instant) =
            JpaVanEvidenceUseEntity().also {
                it.evidenceRef = evidenceRef
                it.clientId = clientId
                it.environment = environment
                it.institutionAccount = institutionAccount
                it.operation = operation
                it.requestId = requestId
                it.createdAt = createdAt
            }
    }
}

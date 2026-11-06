package com.paycore.financial.acquiring.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import java.io.Serializable
import java.time.Instant

/** acquisition_transmission_outcome 행(append-only 전송 시도 결과 — 시도당 한 행, `@Immutable`·updatable=false, runtime 권한 SELECT·INSERT). */
@Entity
@Immutable
@IdClass(JpaAcquisitionTransmissionOutcomeEntity.Key::class)
@Table(name = "acquisition_transmission_outcome")
class JpaAcquisitionTransmissionOutcomeEntity protected constructor() {
    @field:Id
    @field:Column(name = "file_id", nullable = false, updatable = false, length = 64)
    lateinit var fileId: String
        protected set

    @field:Id
    @field:Column(name = "attempt_no", nullable = false, updatable = false)
    var attemptNo: Int = 0
        protected set

    @field:Column(name = "outcome", nullable = false, updatable = false, length = 24)
    lateinit var outcome: String
        protected set

    @field:Column(name = "observed", updatable = false, length = 24)
    var observed: String? = null
        protected set

    @field:Column(name = "evidence", updatable = false, length = 32)
    var evidence: String? = null
        protected set

    @field:Column(name = "reason", updatable = false, length = 32)
    var reason: String? = null
        protected set

    @field:Column(name = "resulting_state", nullable = false, updatable = false, length = 24)
    lateinit var resultingState: String
        protected set

    @field:Column(name = "decided_at", nullable = false, updatable = false)
    lateinit var decidedAt: Instant
        protected set

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)

    override fun toString(): String = "JpaAcquisitionTransmissionOutcomeEntity(fileId=$fileId, attemptNo=$attemptNo, outcome=$outcome)"

    data class Key(var fileId: String = "", var attemptNo: Int = 0) : Serializable

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            fileId: String, attemptNo: Int, outcome: String, observed: String?, evidence: String?, reason: String?, resultingState: String, decidedAt: Instant,
        ) =
            JpaAcquisitionTransmissionOutcomeEntity().also {
                it.fileId = fileId
                it.attemptNo = attemptNo
                it.outcome = outcome
                it.observed = observed
                it.evidence = evidence
                it.reason = reason
                it.resultingState = resultingState
                it.decidedAt = decidedAt
            }
    }
}

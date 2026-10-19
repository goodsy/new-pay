package com.paycore.payment.payment.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.Hibernate
import java.io.Serializable

/** payment_termination_evidence 복합 키(payment_id, seq). 값 객체라 equals/hashCode를 두 필드로 정의한다. */
@Embeddable
class TerminationEvidenceKey protected constructor() : Serializable {
    @field:Column(name = "payment_id", nullable = false, updatable = false, length = 64)
    lateinit var paymentId: String
        protected set

    @field:Column(name = "seq", nullable = false, updatable = false)
    var seq: Int = 0
        protected set

    override fun equals(other: Any?): Boolean =
        this === other || (other is TerminationEvidenceKey && paymentId == other.paymentId && seq == other.seq)

    override fun hashCode(): Int = 31 * paymentId.hashCode() + seq

    override fun toString(): String = "TerminationEvidenceKey($paymentId#$seq)"

    companion object {
        private const val serialVersionUID = 1L

        fun of(paymentId: String, seq: Int): TerminationEvidenceKey = TerminationEvidenceKey().also {
            it.paymentId = paymentId
            it.seq = seq
        }
    }
}

/** 종료 근거 한 건(append-only 자식 행, INSERT만). 출처 열(AUTH-001 V11, rev 0.3 m-6)은 넷 다 NULL이면 legacy 행이다. */
@Entity
@Table(name = "payment_termination_evidence")
class JpaPaymentTerminationEvidenceEntity protected constructor() {
    @field:EmbeddedId
    lateinit var key: TerminationEvidenceKey
        protected set

    @field:Column(name = "evidence", nullable = false, updatable = false, length = 48)
    lateinit var evidence: String
        protected set

    @field:Column(name = "source_kind", updatable = false, length = 16)
    var sourceKind: String? = null
        protected set

    @field:Column(name = "source_ref", updatable = false, length = 200)
    var sourceRef: String? = null
        protected set

    @field:Column(name = "recorded_at", updatable = false)
    var recordedAt: java.time.Instant? = null
        protected set

    @field:Column(name = "recorded_by", updatable = false, length = 64)
    var recordedBy: String? = null
        protected set

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
        return key == (other as JpaPaymentTerminationEvidenceEntity).key
    }

    override fun hashCode(): Int = key.hashCode()

    override fun toString(): String = "JpaPaymentTerminationEvidenceEntity($key)"

    companion object {
        fun newRow(
            paymentId: String, seq: Int, evidence: String, sourceKind: String?, sourceRef: String?, recordedAt: java.time.Instant?, recordedBy: String?,
        ): JpaPaymentTerminationEvidenceEntity =
            JpaPaymentTerminationEvidenceEntity().also {
                it.key = TerminationEvidenceKey.of(paymentId, seq)
                it.evidence = evidence
                it.sourceKind = sourceKind
                it.sourceRef = sourceRef
                it.recordedAt = recordedAt
                it.recordedBy = recordedBy
            }
    }
}

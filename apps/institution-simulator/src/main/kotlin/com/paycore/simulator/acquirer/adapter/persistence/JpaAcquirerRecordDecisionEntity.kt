package com.paycore.simulator.acquirer.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import java.io.Serializable
import java.time.Instant

/**
 * acquirer_record_decision 행(레코드 판정 — append-only, `@Immutable` + updatable=false). 복합 키 (receipt_id, record_seq)는 `@IdClass`. 수신 행과 연관
 * (`@ManyToOne`)을 두지 않는다(receipt_id 값만 — 지연 로딩·cascade·N+1 경로 없음). 이중 매입 확정의 DB 방어는 UNIQUE 둘이다: 기관 근거 기준
 * (매입사, 종류, accepted_evidence_entry_id — 리뷰 MJ-1)과 거래 ID 기준(매입사, 종류, accepted_transaction_id) — 둘 다 V10.
 */
@Entity
@Immutable
@IdClass(AcquirerRecordDecisionKey::class)
@Table(name = "acquirer_record_decision")
class JpaAcquirerRecordDecisionEntity protected constructor() {
    @field:Id
    @field:Column(name = "receipt_id", nullable = false, updatable = false)
    var receiptId: Long = 0
        protected set

    @field:Id
    @field:Column(name = "record_seq", nullable = false, updatable = false)
    var recordSeq: Int = 0
        protected set

    @field:Column(name = "acquirer_code", nullable = false, updatable = false, length = 32)
    lateinit var acquirerCode: String
        protected set

    @field:Column(name = "record_type", nullable = false, updatable = false, length = 8)
    lateinit var recordType: String
        protected set

    @field:Column(name = "transaction_id", nullable = false, updatable = false, length = 64)
    lateinit var transactionId: String
        protected set

    @field:Column(name = "provider_transaction_id", nullable = false, updatable = false, length = 64)
    lateinit var providerTransactionId: String
        protected set

    @field:Column(name = "record_amount_minor", nullable = false, updatable = false)
    var recordAmountMinor: Long = 0
        protected set

    @field:Column(name = "netted_cancel_amount_minor", nullable = false, updatable = false)
    var nettedCancelAmountMinor: Long = 0
        protected set

    @field:Column(name = "decision", nullable = false, updatable = false, length = 8)
    lateinit var decision: String
        protected set

    @field:Column(name = "reason_code", updatable = false, length = 32)
    var reasonCode: String? = null
        protected set

    @field:Column(name = "evidence_entry_id", updatable = false, columnDefinition = "char(29)")
    var evidenceEntryId: String? = null
        protected set

    @field:Column(name = "authorization_entry_id", updatable = false, columnDefinition = "char(29)")
    var authorizationEntryId: String? = null
        protected set

    @field:Column(name = "scenario", updatable = false, length = 32)
    var scenario: String? = null
        protected set

    @field:Column(name = "accepted_transaction_id", updatable = false, length = 64)
    var acceptedTransactionId: String? = null
        protected set

    @field:Column(name = "accepted_evidence_entry_id", updatable = false, columnDefinition = "char(29)")
    var acceptedEvidenceEntryId: String? = null
        protected set

    @field:Column(name = "decided_at", nullable = false, updatable = false, columnDefinition = "datetime(3)")
    lateinit var decidedAt: Instant
        protected set

    override fun equals(other: Any?): Boolean =
        this === other || (other is JpaAcquirerRecordDecisionEntity && receiptId == other.receiptId && recordSeq == other.recordSeq)

    override fun hashCode(): Int = javaClass.hashCode()

    override fun toString(): String = "JpaAcquirerRecordDecisionEntity(receiptId=$receiptId, recordSeq=$recordSeq, decision=$decision)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            receiptId: Long, recordSeq: Int, acquirerCode: String, recordType: String, transactionId: String, providerTransactionId: String, recordAmountMinor: Long,
            nettedCancelAmountMinor: Long, decision: String, reasonCode: String?, evidenceEntryId: String?, authorizationEntryId: String?, scenario: String?,
            decidedAt: Instant,
        ) = JpaAcquirerRecordDecisionEntity().apply {
            this.receiptId = receiptId
            this.recordSeq = recordSeq
            this.acquirerCode = acquirerCode
            this.recordType = recordType
            this.transactionId = transactionId
            this.providerTransactionId = providerTransactionId
            this.recordAmountMinor = recordAmountMinor
            this.nettedCancelAmountMinor = nettedCancelAmountMinor
            this.decision = decision
            this.reasonCode = reasonCode
            this.evidenceEntryId = evidenceEntryId
            this.authorizationEntryId = if (decision == ACCEPTED) authorizationEntryId else null
            this.scenario = scenario
            this.acceptedTransactionId = if (decision == ACCEPTED) transactionId else null
            this.acceptedEvidenceEntryId = if (decision == ACCEPTED) evidenceEntryId else null
            this.decidedAt = decidedAt
        }

        private const val ACCEPTED = "ACCEPTED"
    }
}

/** [JpaAcquirerRecordDecisionEntity] 복합 키(`@IdClass` — JPA가 no-arg 생성자와 값 equals를 요구한다). */
class AcquirerRecordDecisionKey() : Serializable {
    var receiptId: Long = 0
    var recordSeq: Int = 0

    constructor(receiptId: Long, recordSeq: Int) : this() {
        this.receiptId = receiptId
        this.recordSeq = recordSeq
    }

    override fun equals(other: Any?): Boolean = other is AcquirerRecordDecisionKey && receiptId == other.receiptId && recordSeq == other.recordSeq

    override fun hashCode(): Int = 31 * receiptId.hashCode() + recordSeq
}

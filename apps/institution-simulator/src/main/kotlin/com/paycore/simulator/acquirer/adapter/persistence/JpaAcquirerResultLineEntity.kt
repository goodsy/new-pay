package com.paycore.simulator.acquirer.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import java.io.Serializable

/**
 * acquirer_result_line 행(결과 파일 게시 계획 한 줄 — append-only, `@Immutable`). 거래 ID·종류·금액은 판정 행(FK (receipt_id, record_seq))에서 읽는다.
 * 결과 상충 시나리오의 줄은 판정과 다른 결과를 가진다(그래서 판정 표와 따로 둔다).
 */
@Entity
@Immutable
@IdClass(AcquirerResultLineKey::class)
@Table(name = "acquirer_result_line")
class JpaAcquirerResultLineEntity protected constructor() {
    @field:Id
    @field:Column(name = "receipt_id", nullable = false, updatable = false)
    var receiptId: Long = 0
        protected set

    @field:Id
    @field:Column(name = "result_seq", nullable = false, updatable = false)
    var resultSeq: Int = 0
        protected set

    @field:Id
    @field:Column(name = "record_seq", nullable = false, updatable = false)
    var recordSeq: Int = 0
        protected set

    @field:Column(name = "result", nullable = false, updatable = false, length = 8)
    lateinit var result: String
        protected set

    @field:Column(name = "reason_code", updatable = false, length = 32)
    var reasonCode: String? = null
        protected set

    override fun equals(other: Any?): Boolean = this === other ||
        (other is JpaAcquirerResultLineEntity && receiptId == other.receiptId && resultSeq == other.resultSeq && recordSeq == other.recordSeq)

    override fun hashCode(): Int = javaClass.hashCode()

    override fun toString(): String = "JpaAcquirerResultLineEntity(receiptId=$receiptId, resultSeq=$resultSeq, recordSeq=$recordSeq, result=$result)"

    companion object {
        fun newRow(receiptId: Long, resultSeq: Int, recordSeq: Int, result: String, reasonCode: String?) = JpaAcquirerResultLineEntity().apply {
            this.receiptId = receiptId
            this.resultSeq = resultSeq
            this.recordSeq = recordSeq
            this.result = result
            this.reasonCode = reasonCode
        }
    }
}

/** [JpaAcquirerResultLineEntity] 복합 키. */
class AcquirerResultLineKey() : Serializable {
    var receiptId: Long = 0
    var resultSeq: Int = 0
    var recordSeq: Int = 0

    override fun equals(other: Any?): Boolean =
        other is AcquirerResultLineKey && receiptId == other.receiptId && resultSeq == other.resultSeq && recordSeq == other.recordSeq

    override fun hashCode(): Int = (31 * receiptId.hashCode() + resultSeq) * 31 + recordSeq
}

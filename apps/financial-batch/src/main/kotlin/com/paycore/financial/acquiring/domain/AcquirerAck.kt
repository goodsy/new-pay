package com.paycore.financial.acquiring.domain

import java.time.Instant

/**
 * 가상 매입사 ACK 한 장(ACK·결과 계약 v1 §5.1 — `A|1|fileId|ackSeq|outcome|reasonCode|receivedSha256|receivedBytes|recordCount|receivedAt`).
 * [receivedSha256]·[receivedBytes] = 기관이 받은 매입 파일 전체의 SHA-256·크기(FINANCIAL이 봉인 hash와 대조 — I-8). [recordCount]는 RECEIVED에서만,
 * [reasonCode]는 RECEIVED가 아닐 때만 있다. RECEIVED는 **매입 확정이 아니다**(수신 축 — INV-12).
 */
data class AcquirerAck(
    val fileId: AcquisitionFileId,
    val ackSeq: Int,
    val outcome: AckOutcome,
    val reasonCode: String?,
    val receivedSha256: String,
    val receivedBytes: Long,
    val recordCount: Int?,
    val receivedAt: Instant,
) {
    init {
        if (ackSeq !in 1..MAX_SEQ) throw AcquisitionContractViolation("ack seq must be within 1..$MAX_SEQ")
        if (!SHA256_HEX.matches(receivedSha256)) throw AcquisitionContractViolation("received hash is a lower-case SHA-256 hex")
        if (receivedBytes < 0) throw AcquisitionContractViolation("received bytes must not be negative")
        if ((outcome == AckOutcome.RECEIVED) != (reasonCode == null)) throw AcquisitionContractViolation("only a non-RECEIVED ack carries a reason code")
        if ((outcome == AckOutcome.RECEIVED) != (recordCount != null)) throw AcquisitionContractViolation("only a RECEIVED ack carries a record count")
        reasonCode?.let { AcquisitionText.token("reasonCode", it) }
        if (recordCount != null && recordCount < 0) throw AcquisitionContractViolation("record count must not be negative")
        if (receivedAt.nano % NANOS_PER_MILLI != 0) throw AcquisitionContractViolation("receivedAt must be truncated to milliseconds")
    }

    companion object {
        const val MAX_SEQ = 99
        private const val NANOS_PER_MILLI = 1_000_000
        private val SHA256_HEX = Regex("^[0-9a-f]{64}$")
    }
}

/** ACK 결과(계약 v1). FILE_REJECTED = 파일 전체 거절(그 파일 레코드 종결 — D-ACQ-004·I-7), RECEIPT_CONFLICT = 같은 파일 ID에 다른 내용이 도착(격리). */
enum class AckOutcome { RECEIVED, FILE_REJECTED, RECEIPT_CONFLICT }

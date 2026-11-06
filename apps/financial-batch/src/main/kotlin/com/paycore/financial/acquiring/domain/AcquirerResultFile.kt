package com.paycore.financial.acquiring.domain

import java.time.Instant

/**
 * 가상 매입사 결과 파일 한 장(ACK·결과 계약 v1 §5.1 — `H|1|resultFileId|fileId|fileSha256|resultSeq|producedAt` + `R|…` 줄). [fileSha256] = 기관이 받은
 * 매입 파일 전체 SHA-256(봉인 hash와 대조 — I-8). 한 수신의 결과가 회차 01..99 여러 파일로 나뉠 수 있다(지연·중복·상충). 줄은 recordSeq 오름차순, 파일 안 중복 없음.
 */
data class AcquirerResultFile(
    val fileId: AcquisitionFileId,
    val fileSha256: String,
    val resultSeq: Int,
    val producedAt: Instant,
    val lines: List<AcquirerResultLine>,
) {
    init {
        if (resultSeq !in 1..AcquirerAck.MAX_SEQ) throw AcquisitionContractViolation("result seq must be within 1..${AcquirerAck.MAX_SEQ}")
        if (!SHA256_HEX.matches(fileSha256)) throw AcquisitionContractViolation("file hash is a lower-case SHA-256 hex")
        if (producedAt.nano % NANOS_PER_MILLI != 0) throw AcquisitionContractViolation("producedAt must be truncated to milliseconds")
        lines.zipWithNext().forEach { (a, b) -> if (a.recordSeq >= b.recordSeq) throw AcquisitionContractViolation("result lines run in strictly ascending record seq") }
    }

    /** 결과 파일 ID(`RES.{fileId}.{nn}`). */
    val resultFileId: String get() = "RES.${fileId.value}.${AcquirerResponseFormat.seqText(resultSeq)}"

    companion object {
        private const val NANOS_PER_MILLI = 1_000_000
        private val SHA256_HEX = Regex("^[0-9a-f]{64}$")
    }
}

/** 결과 줄 하나. [reasonCode]는 REJECTED에서만(가상 사유 코드 — 해석 없이 기록·노출). */
data class AcquirerResultLine(
    val recordSeq: Int,
    val transactionId: String,
    val recordType: AcquisitionRecordType,
    val result: LineResult,
    val reasonCode: String?,
    val recordAmountMinor: Long,
) {
    init {
        if (recordSeq < 1) throw AcquisitionContractViolation("record seq must be at least 1")
        AcquisitionText.id("transactionId", transactionId)
        if ((result == LineResult.REJECTED) != (reasonCode != null)) throw AcquisitionContractViolation("only a REJECTED line carries a reason code")
        reasonCode?.let { AcquisitionText.token("reasonCode", it) }
        if (recordAmountMinor < 1) throw AcquisitionContractViolation("record amount is a positive minor-unit integer")
    }
}

/** 결과 줄의 판정(기관 값). ACCEPTED = 매입 확정, REJECTED = 그 파일에서 종결(재매입은 2인 승인 — D-ACQ-011). */
enum class LineResult { ACCEPTED, REJECTED }

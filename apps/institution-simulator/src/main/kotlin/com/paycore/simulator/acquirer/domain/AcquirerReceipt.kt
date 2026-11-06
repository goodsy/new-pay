package com.paycore.simulator.acquirer.domain

import java.time.Instant

/** 수신 판정(ACK outcome과 같은 이름 — 레코드 결과와 별개, D-ACQ-004). */
enum class ReceiptOutcome { RECEIVED, FILE_REJECTED, RECEIPT_CONFLICT }

/**
 * 가상 매입사 수신 원장 한 행(append-only — 파일 ID·내용 hash당 한 행). [ordinal] = 그 파일 ID의 수신 순번(= ACK 순번 nn): 첫 수신이 1(RECEIVED 또는 FILE_REJECTED),
 * 같은 ID에 다른 내용이 들어올 때마다 +1(RECEIPT_CONFLICT — 레코드를 처리하지 않는다). 같은 ID·같은 내용은 새 행 없이 멱등이다([ReceiptRule]).
 */
data class AcquirerReceipt(
    val acquirerCode: String,
    val fileId: String,
    val ordinal: Int,
    val contentSha256: String,
    val contentBytes: Long,
    val recordCount: Int?,
    val outcome: ReceiptOutcome,
    val reason: FileRejectionReason?,
    val receivedAt: Instant,
) {
    init {
        require(AcquisitionFileRef.parseOrNull(fileId) != null) { "receipt file id must be an acquisition file id" }
        require(ordinal in 1..AcquisitionFileRef.MAX_SEQ) { "receipt ordinal must be 1..${AcquisitionFileRef.MAX_SEQ}" }
        require(Sha256.isHex(contentSha256) && contentBytes >= 0) { "receipt carries the content SHA-256 and size" }
        require((outcome == ReceiptOutcome.RECEIVED) == (reason == null)) { "a reason exists exactly for a refused receipt" }
        require((outcome == ReceiptOutcome.RECEIVED) == (recordCount != null)) { "a record count exists exactly for RECEIVED" }
        require((outcome == ReceiptOutcome.RECEIPT_CONFLICT) == (ordinal > 1)) { "the first receipt of a file id decides it; later different contents conflict" }
        require((outcome == ReceiptOutcome.RECEIPT_CONFLICT) == (reason == FileRejectionReason.CONTENT_MISMATCH)) { "CONTENT_MISMATCH is the conflict reason" }
    }

    val ref: AcquisitionFileRef get() = checkNotNull(AcquisitionFileRef.parseOrNull(fileId))
}

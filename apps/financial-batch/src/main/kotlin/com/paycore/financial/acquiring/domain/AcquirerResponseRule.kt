package com.paycore.financial.acquiring.domain

/**
 * 기관 ACK·결과 파일을 봉인 파일과 대조해 수신 축 전이와 격리를 정한다(ACQUIRING-001 §4.1·§5.1 — 2-F).
 *
 * 사용자 결정·기준선:
 * - D-ACQ-004: 수신(ACK)·결과는 다른 축. RECEIVED는 매입 확정이 아니다. FILE_REJECTED는 그 파일 레코드를 모두 종결한다.
 * - 기준선 §5.4 "같은 ID 다른 내용 격리": ACK `receivedSha256`·결과 `fileSha256`이 봉인 hash와 다르면 격리(I-8).
 *
 * Implementer 해석(사용자 결정 아님):
 * - I-8: 유효한 결과 파일은 ACK가 없어도 수신 근거(RESULT_IMPLIED). hash가 일치하는 ACK·결과는 전송 완료 근거이기도 하다(I-9 ACK_HASH_MATCH — 전송 축이
 *   SEALED·TRANSMISSION_UNKNOWN이면 TRANSMITTED).
 * - I-45: 수신 축의 RECEIPT_CONFLICT는 "기관이 이 파일 ID로 받은 내용이 봉인과 다르다"는 격리 요약이다 — RECEIPT_CONFLICT ACK, hash·크기·건수가 다른 ACK·결과,
 *   서로 모순된 ACK(RECEIVED ↔ FILE_REJECTED)가 만든다. 한 번 격리되면 그 파일의 뒤 응답은 자동 반영하지 않고 격리 기록만 남긴다(운영자 확인 — ADMIN 경로 없음,
 *   응답 바이트는 보존). 결과 줄이 봉인 레코드(순번·거래 ID·종류·금액)와 다르면 그 결과 파일 전체를 격리하고 수신 축은 바꾸지 않는다(hash는 일치 — 계약 위반 기록).
 */
object AcquirerResponseRule {

    fun judgeAck(file: AcquisitionFile, ack: AcquirerAck): ResponseJudgement {
        if (ack.fileId != file.id) throw AcquisitionContractViolation("an ack is judged against its own file")
        transmissionQuarantine(file)?.let { return it }
        if (ack.outcome == AckOutcome.RECEIPT_CONFLICT) return ResponseJudgement.Quarantine(QuarantineReason.RECEIPT_CONFLICT, ReceiptState.RECEIPT_CONFLICT)
        if (ack.receivedSha256 != file.contentSha256 || ack.receivedBytes != file.contentBytes.toLong()) {
            return ResponseJudgement.Quarantine(QuarantineReason.HASH_MISMATCH, ReceiptState.RECEIPT_CONFLICT)
        }
        if (ack.outcome == AckOutcome.RECEIVED && ack.recordCount != file.totals.recordCount) {
            return ResponseJudgement.Quarantine(QuarantineReason.COUNT_MISMATCH, ReceiptState.RECEIPT_CONFLICT)
        }
        if (file.receiptState == ReceiptState.RECEIPT_CONFLICT) return ResponseJudgement.Quarantine(QuarantineReason.AFTER_CONFLICT, ReceiptState.RECEIPT_CONFLICT)
        return when (ack.outcome) {
            AckOutcome.RECEIVED -> when (file.receiptState) {
                ReceiptState.NONE, ReceiptState.RECEIVED -> ResponseJudgement.Accept(ReceiptState.RECEIVED, ResponseEvidence.ACK_HASH_MATCH, null)
                else -> ResponseJudgement.Quarantine(QuarantineReason.ACK_CONFLICT, ReceiptState.RECEIPT_CONFLICT)
            }
            AckOutcome.FILE_REJECTED -> when (file.receiptState) {
                ReceiptState.NONE, ReceiptState.FILE_REJECTED ->
                    ResponseJudgement.Accept(ReceiptState.FILE_REJECTED, ResponseEvidence.ACK_HASH_MATCH, checkNotNull(ack.reasonCode))
                else -> ResponseJudgement.Quarantine(QuarantineReason.ACK_CONFLICT, ReceiptState.RECEIPT_CONFLICT)
            }
            AckOutcome.RECEIPT_CONFLICT -> error("handled above")
        }
    }

    fun judgeResult(file: AcquisitionFile, records: List<AcquisitionRecord>, result: AcquirerResultFile): ResponseJudgement {
        if (result.fileId != file.id) throw AcquisitionContractViolation("a result file is judged against its own file")
        transmissionQuarantine(file)?.let { return it }
        if (result.fileSha256 != file.contentSha256) return ResponseJudgement.Quarantine(QuarantineReason.HASH_MISMATCH, ReceiptState.RECEIPT_CONFLICT)
        if (file.receiptState == ReceiptState.RECEIPT_CONFLICT) return ResponseJudgement.Quarantine(QuarantineReason.AFTER_CONFLICT, ReceiptState.RECEIPT_CONFLICT)
        val bySeq = records.associateBy { it.seq }
        val consistent = result.lines.all { l ->
            val r = bySeq[l.recordSeq]
            r != null && r.transactionId == l.transactionId && r.type == l.recordType && r.recordAmountMinor == l.recordAmountMinor
        }
        if (!consistent) return ResponseJudgement.Quarantine(QuarantineReason.RECORD_MISMATCH, file.receiptState)
        val receipt = if (file.receiptState == ReceiptState.NONE) ReceiptState.RECEIVED else file.receiptState
        return ResponseJudgement.Accept(receipt, ResponseEvidence.RESULT_HASH_MATCH, null)
    }
}

/**
 * (rev 1.1 — 리뷰 m-2) 전송 축이 TRANSMISSION_QUARANTINED(기관 최종명에 다른 바이트 — I-23)인 파일의 응답은 hash가 맞아도 자동 반영하지 않는다(수신 축·전송 축·레코드 결과 불변,
 * 격리 기록만 — 운영자 확인 전 자동 확정 없음).
 */
private fun transmissionQuarantine(file: AcquisitionFile): ResponseJudgement? =
    if (file.transmissionState == TransmissionState.TRANSMISSION_QUARANTINED) ResponseJudgement.Quarantine(QuarantineReason.TRANSMISSION_QUARANTINED, file.receiptState)
    else null

sealed interface ResponseJudgement {
    /** 반영: [receiptAfter] = 수신 축, [evidence] = 전송 완료 근거(hash 일치), [fileRejectionReason] = ACK FILE_REJECTED의 사유(레코드 종결). */
    data class Accept(val receiptAfter: ReceiptState, val evidence: ResponseEvidence, val fileRejectionReason: String?) : ResponseJudgement

    /** 격리: 자동 반영하지 않는다. [receiptAfter] = 격리 뒤 수신 축(RECEIPT_CONFLICT 또는 그대로). */
    data class Quarantine(val reason: QuarantineReason, val receiptAfter: ReceiptState) : ResponseJudgement
}

/** 응답이 전송 완료 근거가 된 이유(I-9 ACK_HASH_MATCH — 결과 파일 hash 일치도 같은 성질의 근거). DB CHECK와 같은 집합. */
enum class ResponseEvidence { ACK_HASH_MATCH, RESULT_HASH_MATCH }

/**
 * 응답 격리 사유(DB CHECK와 같은 집합). FORMAT_INVALID·NAME_MISMATCH·UNKNOWN_FILE·NAME_REUSED는 파일 대조 전(이름·문법·소유) 사유다.
 */
enum class QuarantineReason {
    FORMAT_INVALID,
    NAME_MISMATCH,
    UNKNOWN_FILE,
    NAME_REUSED,
    HASH_MISMATCH,
    COUNT_MISMATCH,
    RECORD_MISMATCH,
    RECEIPT_CONFLICT,
    ACK_CONFLICT,
    AFTER_CONFLICT,

    /** (rev 1.1 — 리뷰 m-2) 전송 축 격리(TRANSMISSION_QUARANTINED) 파일의 응답. */
    TRANSMISSION_QUARANTINED,
}

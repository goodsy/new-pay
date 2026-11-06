package com.paycore.simulator.acquirer.domain

/**
 * 레코드 결과 파일 계약 v1(가상 매입사 → FINANCIAL, golden `acquiring-ack-result-v1.golden.json`). 이름 `RES.{fileId}.{nn}.dat`, 내용:
 * ```
 * H|1|resultFileId|fileId|fileSha256|resultSeq|producedAt
 * R|recordSeq|transactionId|SALE/CANCEL|ACCEPTED/REJECTED|reasonCode|recordAmount      (recordSeq 오름차순)
 * T|lineCount|acceptedCount|rejectedCount|acceptedSaleTotal|acceptedCancelTotal|bodySha256
 * ```
 * fileSha256 = 받은 매입 파일 전체 SHA-256(FINANCIAL 봉인 hash와 대조), producedAt = 계획된 게시 시각(재게시도 같은 바이트), bodySha256 = H·R 줄 SHA-256.
 */
object ResultFileV1 {

    fun render(receipt: AcquirerReceipt, file: PlannedResultFile): ByteArray {
        require(receipt.outcome == ReceiptOutcome.RECEIVED) { "only a RECEIVED file has record results" }
        val ref = receipt.ref
        val lines = buildList {
            add(listOf("H", FORMAT_VERSION, ref.resultFileId(file.seq), receipt.fileId, receipt.contentSha256, AcquisitionFileRef.seq(file.seq), ContractTimestamp.format(file.dueAt)))
            file.lines.forEach { l ->
                add(listOf("R", l.recordSeq.toString(), l.transactionId, l.recordType.name, l.result.name, l.reason.orEmpty(), l.recordAmountMinor.toString()))
            }
        }
        val body = lines.joinToString("") { it.joinToString("|") + "\n" }.toByteArray(Charsets.US_ASCII)
        val accepted = file.lines.filter { it.result == RecordResult.ACCEPTED }
        val trailer = listOf(
            "T", file.lines.size.toString(), accepted.size.toString(), (file.lines.size - accepted.size).toString(),
            accepted.filter { it.recordType == ReceivedRecordType.SALE }.sumOf { it.recordAmountMinor }.toString(),
            accepted.filter { it.recordType == ReceivedRecordType.CANCEL }.sumOf { it.recordAmountMinor }.toString(), Sha256.hex(body),
        ).joinToString("|") + "\n"
        return body + trailer.toByteArray(Charsets.US_ASCII)
    }

    fun fileName(receipt: AcquirerReceipt, seq: Int): String = receipt.ref.resultName(seq)

    private const val FORMAT_VERSION = "1"
}

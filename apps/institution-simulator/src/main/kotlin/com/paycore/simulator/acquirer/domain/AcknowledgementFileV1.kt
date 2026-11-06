package com.paycore.simulator.acquirer.domain

/**
 * ACK 파일 계약 v1(가상 매입사 → FINANCIAL, golden `acquiring-ack-result-v1.golden.json` — harness `test_acquiring_ack_result_golden.py`가 독립 생성).
 * 이름 `ACK.{fileId}.{nn}.dat`(nn = 수신 순번), 내용:
 * ```
 * A|1|fileId|ackSeq|outcome|reasonCode|receivedSha256|receivedBytes|recordCount|receivedAt
 * T|bodySha256
 * ```
 * US-ASCII, LF, 마지막 LF. reasonCode는 RECEIVED에서 빈 값, recordCount는 RECEIVED에서만. bodySha256 = A 줄(LF 포함) SHA-256.
 * 같은 수신 기록은 언제나 같은 바이트다(재시작·재게시 대조 — 게시 시각이 아니라 수신 시각만 싣는다).
 */
object AcknowledgementFileV1 {

    fun render(receipt: AcquirerReceipt): ByteArray {
        val line = listOf(
            "A", FORMAT_VERSION, receipt.fileId, AcquisitionFileRef.seq(receipt.ordinal), receipt.outcome.name, receipt.reason?.name.orEmpty(),
            receipt.contentSha256, receipt.contentBytes.toString(), receipt.recordCount?.toString().orEmpty(), ContractTimestamp.format(receipt.receivedAt),
        ).joinToString("|") + "\n"
        val body = line.toByteArray(Charsets.US_ASCII)
        return body + "T|${Sha256.hex(body)}\n".toByteArray(Charsets.US_ASCII)
    }

    fun fileName(receipt: AcquirerReceipt): String = receipt.ref.ackName(receipt.ordinal)

    private const val FORMAT_VERSION = "1"
}

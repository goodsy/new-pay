package com.paycore.financial.acquiring.domain

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * ACK·결과 파일 계약 v1의 이름·렌더링·엄격 해석(ACQUIRING-001 §5.1 — 가상 규격, golden `acquiring-ack-result-v1.golden.json`이 harness Python 독립 생성 값과
 * 바이트로 맞춘다. 실제 기관 규격은 LIVE Gate). 형식:
 * ```
 * ACK  ACK.{fileId}.{nn}.dat : A|1|fileId|ackSeq|outcome|reasonCode|receivedSha256|receivedBytes|recordCount|receivedAt   T|bodySha256
 * 결과 RES.{fileId}.{nn}.dat : H|1|resultFileId|fileId|fileSha256|resultSeq|producedAt   R|recordSeq|transactionId|type|result|reasonCode|recordAmount
 *                              T|lineCount|acceptedCount|rejectedCount|acceptedSaleTotal|acceptedCancelTotal|bodySha256
 * ```
 * US-ASCII, LF, 마지막 LF, BOM·CR·빈 줄 없음. bodySha256 = 트레일러 앞 줄들(각 LF 포함)의 SHA-256(부분 다운로드 검출). 해석은 다시 렌더링한 바이트와 같아야 받는다
 * (정규형만 — 같은 의미의 다른 바이트를 받지 않는다). 위반은 [AcquisitionContractViolation](메시지에 값을 넣지 않는다).
 */
object AcquirerResponseFormat {
    const val FORMAT_VERSION = "1"
    private const val SEP = "|"
    private const val LF = '\n'
    private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    private val COUNT = Regex("^(0|[1-9][0-9]{0,8})$")
    private val AMOUNT = Regex("^(0|[1-9][0-9]{0,18})$")
    private val SEQ = Regex("^[0-9]{2}$")
    private val SHA256_HEX = Regex("^[0-9a-f]{64}$")
    private val NAME = Regex("^(ACK|RES)\\.(ACQ\\.[A-Z0-9]{1,32}\\.(?:TEST|LIVE)\\.[0-9]{8}\\.[0-9]{3})\\.([0-9]{2})\\.dat$")
    private const val ACK_FIELDS = 10
    private const val RESULT_HEADER_FIELDS = 7
    private const val RESULT_LINE_FIELDS = 7
    private const val RESULT_TRAILER_FIELDS = 7

    /** 두 자리 회차·순번(01..99). */
    fun seqText(seq: Int): String {
        if (seq !in 1..AcquirerAck.MAX_SEQ) throw AcquisitionContractViolation("sequence must be within 1..${AcquirerAck.MAX_SEQ}")
        return seq.toString().padStart(2, '0')
    }

    fun ackFileName(fileId: AcquisitionFileId, ackSeq: Int): String = "ACK.${fileId.value}.${seqText(ackSeq)}.dat"

    fun resultFileName(fileId: AcquisitionFileId, resultSeq: Int): String = "RES.${fileId.value}.${seqText(resultSeq)}.dat"

    /** outbox 최종명 해석. 계약 밖 이름(점 임시명 `.{name}.tmp` 포함)은 null — 받는 쪽은 무시한다. */
    fun parseName(name: String): AcquirerResponseName? {
        val m = NAME.matchEntire(name) ?: return null
        val (kind, fileId, seq) = m.destructured
        val id = runCatching { AcquisitionFileId.parse(fileId) }.getOrNull() ?: return null
        val n = seq.toInt()
        if (n !in 1..AcquirerAck.MAX_SEQ) return null
        return AcquirerResponseName(if (kind == "ACK") AcquirerResponseKind.ACK else AcquirerResponseKind.RESULT, id, n, name)
    }

    fun renderAck(ack: AcquirerAck): ByteArray {
        val line = listOf(
            "A", FORMAT_VERSION, ack.fileId.value, seqText(ack.ackSeq), ack.outcome.name, ack.reasonCode.orEmpty(), ack.receivedSha256,
            ack.receivedBytes.toString(), ack.recordCount?.toString().orEmpty(), TIMESTAMP.format(ack.receivedAt),
        ).joinToString(SEP) + LF
        val body = line.toByteArray(Charsets.US_ASCII)
        return body + ("T" + SEP + AcquisitionFileFormat.sha256(body) + LF).toByteArray(Charsets.US_ASCII)
    }

    fun renderResult(result: AcquirerResultFile): ByteArray {
        val body = buildString {
            append(
                listOf("H", FORMAT_VERSION, result.resultFileId, result.fileId.value, result.fileSha256, seqText(result.resultSeq), TIMESTAMP.format(result.producedAt))
                    .joinToString(SEP),
            ).append(LF)
            result.lines.forEach { l ->
                append(
                    listOf("R", l.recordSeq.toString(), l.transactionId, l.recordType.name, l.result.name, l.reasonCode.orEmpty(), l.recordAmountMinor.toString())
                        .joinToString(SEP),
                ).append(LF)
            }
        }.toByteArray(Charsets.US_ASCII)
        val accepted = result.lines.filter { it.result == LineResult.ACCEPTED }
        val sum = { type: AcquisitionRecordType -> accepted.filter { it.recordType == type }.fold(0L) { a, l -> Math.addExact(a, l.recordAmountMinor) } }
        val trailer = listOf(
            "T", result.lines.size, accepted.size, result.lines.size - accepted.size, sum(AcquisitionRecordType.SALE), sum(AcquisitionRecordType.CANCEL),
            AcquisitionFileFormat.sha256(body),
        ).joinToString(SEP) + LF
        return body + trailer.toByteArray(Charsets.US_ASCII)
    }

    /** ACK 바이트 해석(정규형·트레일러 hash 검증). */
    fun parseAck(content: ByteArray): AcquirerAck {
        val lines = lines(content)
        if (lines.size != 2) throw AcquisitionContractViolation("an ack has one A line and a trailer")
        val f = lines[0].split(SEP)
        if (f.size != ACK_FIELDS || f[0] != "A" || f[1] != FORMAT_VERSION) throw AcquisitionContractViolation("ack line shape")
        val outcome = AckOutcome.entries.firstOrNull { it.name == f[4] } ?: throw AcquisitionContractViolation("ack outcome")
        if (!SHA256_HEX.matches(f[6])) throw AcquisitionContractViolation("ack received hash")
        val ack = AcquirerAck(
            fileId = AcquisitionFileId.parse(f[2]), ackSeq = seq(f[3]), outcome = outcome, reasonCode = f[5].ifEmpty { null }, receivedSha256 = f[6],
            receivedBytes = count(f[7]).toLong(), recordCount = f[8].ifEmpty { null }?.let(::count), receivedAt = instant(f[9]),
        )
        canonical(renderAck(ack), content)
        return ack
    }

    /** 결과 바이트 해석(정규형·트레일러 건수·합계·hash 검증). */
    fun parseResult(content: ByteArray): AcquirerResultFile {
        val lines = lines(content)
        if (lines.size < 2) throw AcquisitionContractViolation("a result file has a header and a trailer")
        val h = lines.first().split(SEP)
        if (h.size != RESULT_HEADER_FIELDS || h[0] != "H" || h[1] != FORMAT_VERSION) throw AcquisitionContractViolation("result header shape")
        if (!SHA256_HEX.matches(h[4])) throw AcquisitionContractViolation("result file hash")
        val records = lines.subList(1, lines.size - 1).map { line ->
            val r = line.split(SEP)
            if (r.size != RESULT_LINE_FIELDS || r[0] != "R") throw AcquisitionContractViolation("result line shape")
            AcquirerResultLine(
                recordSeq = count(r[1]), transactionId = r[2],
                recordType = AcquisitionRecordType.entries.firstOrNull { it.name == r[3] } ?: throw AcquisitionContractViolation("result line record type"),
                result = LineResult.entries.firstOrNull { it.name == r[4] } ?: throw AcquisitionContractViolation("result line result"),
                reasonCode = r[5].ifEmpty { null }, recordAmountMinor = amount(r[6]),
            )
        }
        val result = AcquirerResultFile(AcquisitionFileId.parse(h[3]), h[4], seq(h[5]), instant(h[6]), records)
        if (h[2] != result.resultFileId) throw AcquisitionContractViolation("result file id disagrees with the file id and seq")
        val t = lines.last().split(SEP)
        if (t.size != RESULT_TRAILER_FIELDS || t[0] != "T") throw AcquisitionContractViolation("result trailer shape")
        // 리뷰 n-3: 합계 계산의 Math.addExact 오버플로(ArithmeticException)도 계약 위반으로 — 응답 서비스가 FORMAT_INVALID로 격리한다(독성 파일이 회차마다 실패하지 않게).
        val rendered = try {
            renderResult(result)
        } catch (e: ArithmeticException) {
            throw AcquisitionContractViolation("result totals overflow")
        }
        canonical(rendered, content)
        return result
    }

    private fun lines(content: ByteArray): List<String> {
        if (content.any { it < 0 || (it < 0x20 && it != LF.code.toByte()) || it == 0x7f.toByte() }) {
            throw AcquisitionContractViolation("a response file is printable US-ASCII with LF line ends")
        }
        val text = String(content, Charsets.US_ASCII)
        if (!text.endsWith(LF)) throw AcquisitionContractViolation("the last line must end with LF")
        return text.dropLast(1).split(LF)
    }

    /** 다시 렌더링한 바이트와 같아야 한다 — 트레일러 건수·합계·bodySha256 불일치(부분 다운로드 포함)와 비정규 표기를 함께 거부한다. */
    private fun canonical(rendered: ByteArray, content: ByteArray) {
        if (!rendered.contentEquals(content)) throw AcquisitionContractViolation("trailer counts, totals or body hash do not match, or the file is not in canonical v1 form")
    }

    private fun seq(v: String): Int = if (SEQ.matches(v)) v.toInt().also { if (it !in 1..AcquirerAck.MAX_SEQ) throw AcquisitionContractViolation("seq range") }
    else throw AcquisitionContractViolation("seq")

    private fun count(v: String): Int = if (COUNT.matches(v)) v.toInt() else throw AcquisitionContractViolation("count")

    private fun amount(v: String): Long = if (AMOUNT.matches(v)) v.toLongOrNull() ?: throw AcquisitionContractViolation("amount range") else throw AcquisitionContractViolation("amount")

    private fun instant(v: String): Instant = try {
        Instant.from(TIMESTAMP.parse(v)).also { if (TIMESTAMP.format(it) != v) throw AcquisitionContractViolation("timestamp form") }
    } catch (e: DateTimeParseException) {
        throw AcquisitionContractViolation("timestamp")
    }
}

/** outbox 최종명(`ACK.{fileId}.{nn}.dat`·`RES.{fileId}.{nn}.dat`). */
data class AcquirerResponseName(val kind: AcquirerResponseKind, val fileId: AcquisitionFileId, val seq: Int, val name: String)

enum class AcquirerResponseKind { ACK, RESULT }

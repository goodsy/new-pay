package com.paycore.financial.acquiring.domain

import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.HexFormat

/**
 * 매입 파일 계약 v1의 렌더링·해석(ACQUIRING-001 §5 — 가상 규격, 실제 카드사 규격 추측 아님. golden `acquiring-file-v1.golden.json`이 harness Python 독립 생성 값과
 * 바이트로 맞춘다). 형식:
 * ```
 * H|1|fileId|senderId|acquirerCode|environment|yyyy-MM-dd|vvv|originalFileId|sealedAt|cutoffPolicyVersion
 * D|seq|SALE|CANCEL|transactionId|originalTransactionId|merchantRef|approvalNumber|providerTransactionId|originalProviderTransactionId|
 *   institutionTransactionDate|eventAt|KRW|approvedAmount|nettedCancelAmount|recordAmount|planKind|installmentMonths|cardType|issuerCode|generation|reacquisitionOf
 * T|recordCount|saleCount|saleAmountTotal|cancelCount|cancelAmountTotal|netAmount|bodySha256
 * ```
 * 인코딩 US-ASCII(UTF-8 부분집합, BOM 없음), 줄 끝 LF, 마지막 줄 뒤 LF 하나(I-15). 빈 값 = 빈 문자열. 금액 = 부호 없는 10진 정수(앞 0 없음), 트레일러 netAmount만 음수 가능.
 * 시각 = UTC `yyyy-MM-ddTHH:mm:ss.SSSZ`. bodySha256 = 헤더 줄 + D 줄 바이트(각 LF 포함)의 SHA-256 소문자 hex. 봉인 hash = 파일 전체 바이트 SHA-256.
 */
object AcquisitionFileFormat {
    const val FORMAT_VERSION = "1"
    private const val SEP = "|"
    private const val LF = '\n'
    private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    private val AMOUNT = Regex("^(0|[1-9][0-9]{0,18})$")
    private val SIGNED_AMOUNT = Regex("^(0|-?[1-9][0-9]{0,18})$")
    private val COUNT = Regex("^(0|[1-9][0-9]{0,8})$")
    private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

    fun render(header: AcquisitionFileHeader, records: List<AcquisitionRecord>): RenderedAcquisitionFile {
        records.forEachIndexed { i, r -> if (r.seq != i + 1) throw AcquisitionContractViolation("record seq must run 1..n in file order") }
        val body = buildString {
            append(headerLine(header)).append(LF)
            records.forEach { append(recordLine(it)).append(LF) }
        }.toByteArray(Charsets.US_ASCII)
        val totals = AcquisitionTotals.of(records)
        val trailer = listOf(
            "T", totals.recordCount, totals.saleCount, totals.saleAmountMinor, totals.cancelCount, totals.cancelAmountMinor, totals.netAmountMinor, sha256(body),
        ).joinToString(SEP) + LF
        val content = body + trailer.toByteArray(Charsets.US_ASCII)
        return RenderedAcquisitionFile(header, records, totals, content, sha256(content))
    }

    /** 바이트를 엄격히 해석하고 트레일러(건수·합계·bodySha256)를 검증한다. 어긋나면 [AcquisitionContractViolation]. */
    fun parse(content: ByteArray): RenderedAcquisitionFile {
        if (content.any { it < 0 || (it < 0x20 && it != LF.code.toByte()) || it == 0x7f.toByte() }) {
            throw AcquisitionContractViolation("an acquisition file is printable US-ASCII with LF line ends")
        }
        val text = String(content, Charsets.US_ASCII)
        if (!text.endsWith(LF)) throw AcquisitionContractViolation("the last line must end with LF")
        val lines = text.dropLast(1).split(LF)
        if (lines.size < 2) throw AcquisitionContractViolation("a file has a header and a trailer")
        val header = parseHeader(lines.first())
        val records = lines.subList(1, lines.size - 1).map(::parseRecord)
        val rendered = render(header, records)
        val trailer = lines.last().split(SEP)
        val expected = String(rendered.content, Charsets.US_ASCII).dropLast(1).split(LF).last().split(SEP)
        if (trailer.size != expected.size || trailer.first() != "T") throw AcquisitionContractViolation("trailer shape")
        trailer.drop(1).dropLast(1).forEach { if (!SIGNED_AMOUNT.matches(it)) throw AcquisitionContractViolation("trailer number") }
        if (!SHA256_HEX.matches(trailer.last())) throw AcquisitionContractViolation("trailer body hash")
        if (trailer != expected) throw AcquisitionContractViolation("trailer counts, totals or body hash do not match the records")
        if (!rendered.content.contentEquals(content)) throw AcquisitionContractViolation("the file is not in the canonical v1 form")
        return rendered
    }

    fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun headerLine(h: AcquisitionFileHeader) = listOf(
        "H", FORMAT_VERSION, h.fileId.value, h.senderId, h.fileId.acquirerCode, h.fileId.environment.name, h.fileId.businessDate.toString(),
        h.fileId.versionText, h.originalFileId?.value.orEmpty(), TIMESTAMP.format(h.sealedAt), h.cutoffPolicyVersion,
    ).joinToString(SEP)

    private fun recordLine(r: AcquisitionRecord) = listOf(
        "D", r.seq.toString(), r.type.name, r.transactionId, r.originalTransactionId.orEmpty(), r.merchantRef, r.approvalNumber, r.providerTransactionId,
        r.originalProviderTransactionId.orEmpty(), r.institutionTransactionDate.toString(), TIMESTAMP.format(r.eventAt), r.currency,
        r.approvedAmountMinor.toString(), r.nettedCancelAmountMinor.toString(), r.recordAmountMinor.toString(), r.planKind, r.installmentMonths?.toString().orEmpty(),
        r.cardType, r.issuerCode, r.generation.toString(), r.reacquisitionOf.orEmpty(),
    ).joinToString(SEP)

    private fun parseHeader(line: String): AcquisitionFileHeader {
        val f = line.split(SEP)
        if (f.size != HEADER_FIELDS || f[0] != "H" || f[1] != FORMAT_VERSION) throw AcquisitionContractViolation("header shape")
        val id = AcquisitionFileId.parse(f[2])
        if (f[4] != id.acquirerCode || f[5] != id.environment.name || f[6] != id.businessDate.toString() || f[7] != id.versionText) {
            throw AcquisitionContractViolation("header fields disagree with the file id")
        }
        return AcquisitionFileHeader(id, f[3], instant(f[9]), f[10], f[8].ifEmpty { null }?.let(AcquisitionFileId::parse))
    }

    private fun parseRecord(line: String): AcquisitionRecord {
        val f = line.split(SEP)
        if (f.size != RECORD_FIELDS || f[0] != "D") throw AcquisitionContractViolation("record shape")
        val type = AcquisitionRecordType.entries.firstOrNull { it.name == f[2] } ?: throw AcquisitionContractViolation("record type")
        return AcquisitionRecord(
            seq = count(f[1]), type = type, transactionId = f[3], originalTransactionId = f[4].ifEmpty { null }, merchantRef = f[5], approvalNumber = f[6],
            providerTransactionId = f[7], originalProviderTransactionId = f[8].ifEmpty { null }, institutionTransactionDate = date(f[9]), eventAt = instant(f[10]),
            currency = f[11], approvedAmountMinor = amount(f[12]), nettedCancelAmountMinor = amount(f[13]), recordAmountMinor = amount(f[14]), planKind = f[15],
            installmentMonths = f[16].ifEmpty { null }?.let(::count), cardType = f[17], issuerCode = f[18], generation = count(f[19]),
            reacquisitionOf = f[20].ifEmpty { null },
        )
    }

    private fun amount(v: String): Long = if (AMOUNT.matches(v)) v.toLongOrNull() ?: throw AcquisitionContractViolation("amount range") else throw AcquisitionContractViolation("amount")

    private fun count(v: String): Int = if (COUNT.matches(v)) v.toInt() else throw AcquisitionContractViolation("count")

    private fun date(v: String): LocalDate = try {
        LocalDate.parse(v)
    } catch (e: DateTimeParseException) {
        throw AcquisitionContractViolation("date")
    }

    private fun instant(v: String): Instant = try {
        Instant.from(TIMESTAMP.parse(v)).also { if (TIMESTAMP.format(it) != v) throw AcquisitionContractViolation("timestamp form") }
    } catch (e: DateTimeParseException) {
        throw AcquisitionContractViolation("timestamp")
    }

    private const val HEADER_FIELDS = 11
    private const val RECORD_FIELDS = 21
}

/** 렌더링(또는 검증된 해석) 결과: [content] = 파일 바이트, [sha256] = 봉인 hash(파일 전체 SHA-256 소문자 hex). */
class RenderedAcquisitionFile(
    val header: AcquisitionFileHeader,
    val records: List<AcquisitionRecord>,
    val totals: AcquisitionTotals,
    val content: ByteArray,
    val sha256: String,
) {
    override fun toString(): String = "RenderedAcquisitionFile(fileId=${header.fileId}, records=${records.size}, sha256=$sha256)"
}

/** 트레일러 합계(오버플로는 실패 — Math.addExact). netAmount = saleAmount − cancelAmount. */
data class AcquisitionTotals(
    val recordCount: Int,
    val saleCount: Int,
    val saleAmountMinor: Long,
    val cancelCount: Int,
    val cancelAmountMinor: Long,
) {
    val netAmountMinor: Long get() = Math.subtractExact(saleAmountMinor, cancelAmountMinor)

    companion object {
        fun of(records: List<AcquisitionRecord>): AcquisitionTotals {
            val sales = records.filter { it.type == AcquisitionRecordType.SALE }
            val cancels = records.filter { it.type == AcquisitionRecordType.CANCEL }
            return AcquisitionTotals(
                recordCount = records.size,
                saleCount = sales.size,
                saleAmountMinor = sales.fold(0L) { acc, r -> Math.addExact(acc, r.recordAmountMinor) },
                cancelCount = cancels.size,
                cancelAmountMinor = cancels.fold(0L) { acc, r -> Math.addExact(acc, r.recordAmountMinor) },
            )
        }
    }
}

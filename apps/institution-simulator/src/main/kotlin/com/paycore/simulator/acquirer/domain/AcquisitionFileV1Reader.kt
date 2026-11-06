package com.paycore.simulator.acquirer.domain

import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * 매입 파일 계약 v1 읽기(가상 매입사 쪽 — FINANCIAL 렌더러와 코드를 공유하지 않고 golden `acquiring-file-v1.golden.json`으로만 맞춘다, ACQUIRING-001 §5·§7).
 * 받는 쪽은 정규형만 받는다: US-ASCII 인쇄 문자 + LF, 마지막 줄 뒤 LF, BOM·CR·빈 줄 없음, `H`(11) · `D`(21)×n · `T`(8). 판정 순서(앞의 것이 이긴다):
 * FORMAT_INVALID → RECORD_INVALID → BODY_HASH_MISMATCH → TOTALS_MISMATCH → FILE_NAME_MISMATCH → ACQUIRER_MISMATCH → ENVIRONMENT_NOT_SUPPORTED.
 * 크기 상한(FILE_TOO_LARGE)은 바이트를 다 읽기 전에 호출자가 판정한다.
 */
object AcquisitionFileV1Reader {

    sealed interface Outcome {
        data class Accepted(val file: ReceivedAcquisitionFile) : Outcome

        data class Rejected(val reason: FileRejectionReason) : Outcome
    }

    /**
     * @param nameRef 업로드 최종명(`{id}.dat`)에서 읽은 파일 ID
     * @param acquirerCode 업로드한 계정의 매입사(chroot)
     */
    fun read(nameRef: AcquisitionFileRef, acquirerCode: String, content: ByteArray): Outcome = try {
        val file = parse(content)
        when {
            file.header.ref != nameRef -> Outcome.Rejected(FileRejectionReason.FILE_NAME_MISMATCH)
            file.header.ref.acquirerCode != acquirerCode -> Outcome.Rejected(FileRejectionReason.ACQUIRER_MISMATCH)
            file.header.ref.environment != TEST -> Outcome.Rejected(FileRejectionReason.ENVIRONMENT_NOT_SUPPORTED)
            else -> Outcome.Accepted(file)
        }
    } catch (e: Refused) {
        Outcome.Rejected(e.reason)
    }

    /** 형식·레코드 모양·hash·합계만 본다(이름·매입사·환경 대조 전). 위반은 [Refused]. */
    private fun parse(content: ByteArray): ReceivedAcquisitionFile {
        if (content.isEmpty() || content.last() != LF || content.any { b -> b != LF && (b < PRINTABLE_FIRST || b > PRINTABLE_LAST) }) format()
        val text = String(content, Charsets.US_ASCII)
        val lines = text.substring(0, text.length - 1).split('\n')
        if (lines.size < 2 || lines.any { it.isEmpty() }) format()
        val header = header(fields(lines.first(), "H", HEADER_FIELDS))
        val records = lines.subList(1, lines.size - 1).mapIndexed { i, line -> record(i + 1, fields(line, "D", RECORD_FIELDS)) }
        val trailer = trailer(fields(lines.last(), "T", TRAILER_FIELDS))
        val bodyLength = content.size - lines.last().length - 1
        if (Sha256.hex(content.copyOf(bodyLength)) != trailer.bodySha256) throw Refused(FileRejectionReason.BODY_HASH_MISMATCH)
        checkTotals(records, trailer)
        return ReceivedAcquisitionFile(header, records, trailer, Sha256.hex(content), content.size)
    }

    private fun header(f: List<String>): ReceivedHeader {
        if (f[1] != FORMAT_VERSION) format()
        val ref = AcquisitionFileRef.parseOrNull(f[2]) ?: format()
        if (f[4] != ref.acquirerCode || f[5] != ref.environment || date(f[6]) != ref.businessDate || f[7] != ref.value.substringAfterLast('.')) format()
        val original = f[8].ifEmpty { null }?.let { AcquisitionFileRef.parseOrNull(it) ?: format() }
        val originalConsistent = if (ref.version == 1) {
            original == null
        } else {
            original != null && original.acquirerCode == ref.acquirerCode && original.environment == ref.environment &&
                original.businessDate == ref.businessDate && original.version < ref.version
        }
        if (!originalConsistent) format()
        return ReceivedHeader(ref, id(f[3]), original?.value, instant(f[9]), id(f[10]))
    }

    private fun record(expectedSeq: Int, f: List<String>): ReceivedRecord {
        if (f[1] != expectedSeq.toString()) format()
        val type = when (f[2]) {
            "SALE" -> ReceivedRecordType.SALE
            "CANCEL" -> ReceivedRecordType.CANCEL
            else -> format()
        }
        val months = f[16].ifEmpty { null }?.let { if (MONTHS.matches(it)) it.toInt() else format() }
        val record = ReceivedRecord(
            seq = expectedSeq, type = type, transactionId = id(f[3]), originalTransactionId = optionalId(f[4]), merchantRef = id(f[5]), approvalNumber = id(f[6]),
            providerTransactionId = id(f[7]), originalProviderTransactionId = optionalId(f[8]), institutionTransactionDate = date(f[9]), eventAt = instant(f[10]),
            currency = code(f[11]), approvedAmountMinor = amount(f[12]), nettedCancelAmountMinor = amount(f[13]), recordAmountMinor = amount(f[14]),
            planKind = token(f[15]), installmentMonths = months, cardType = token(f[17]), issuerCode = code(f[18]),
            generation = if (UNSIGNED.matches(f[19]) && f[19].length <= MAX_GENERATION_DIGITS) f[19].toInt() else format(), reacquisitionOf = optionalId(f[20]),
        )
        if (!shapeValid(record)) throw Refused(FileRejectionReason.RECORD_INVALID)
        return record
    }

    private fun shapeValid(r: ReceivedRecord): Boolean {
        val amounts = r.approvedAmountMinor >= 1 && r.recordAmountMinor >= 1 && r.nettedCancelAmountMinor >= 0
        val plan = (r.planKind == LUMP_SUM && r.installmentMonths == null) || (r.planKind == INSTALLMENT && r.installmentMonths != null && r.installmentMonths in 2..99)
        val typed = when (r.type) {
            ReceivedRecordType.SALE -> r.originalTransactionId == null && r.originalProviderTransactionId == null &&
                r.recordAmountMinor == r.approvedAmountMinor - r.nettedCancelAmountMinor
            ReceivedRecordType.CANCEL -> r.originalTransactionId != null && r.originalProviderTransactionId != null && r.nettedCancelAmountMinor == 0L &&
                r.recordAmountMinor <= r.approvedAmountMinor
        }
        val generation = (r.generation == 0) == (r.reacquisitionOf == null)
        return r.currency == KRW && amounts && plan && typed && generation
    }

    private fun trailer(f: List<String>): ReceivedTrailer {
        val net = f[6].let { if (SIGNED_AMOUNT.matches(it)) it.toLong() else format() }
        if (!Sha256.isHex(f[7])) format()
        return ReceivedTrailer(count(f[1]), count(f[2]), amount(f[3]), count(f[4]), amount(f[5]), net, f[7])
    }

    private fun checkTotals(records: List<ReceivedRecord>, t: ReceivedTrailer) {
        val sales = records.filter { it.type == ReceivedRecordType.SALE }
        val cancels = records.filter { it.type == ReceivedRecordType.CANCEL }
        val ok = try {
            val saleTotal = sales.fold(0L) { acc, r -> Math.addExact(acc, r.recordAmountMinor) }
            val cancelTotal = cancels.fold(0L) { acc, r -> Math.addExact(acc, r.recordAmountMinor) }
            t.recordCount == records.size && t.saleCount == sales.size && t.cancelCount == cancels.size && t.saleAmountTotal == saleTotal &&
                t.cancelAmountTotal == cancelTotal && t.netAmount == Math.subtractExact(saleTotal, cancelTotal)
        } catch (e: ArithmeticException) {
            false
        }
        if (!ok) throw Refused(FileRejectionReason.TOTALS_MISMATCH)
    }

    private fun fields(line: String, kind: String, count: Int): List<String> {
        val f = line.split('|')
        if (f.size != count || f[0] != kind) format()
        return f
    }

    private fun id(v: String): String = if (ID.matches(v)) v else format()

    private fun optionalId(v: String): String? = v.ifEmpty { null }?.let(::id)

    private fun code(v: String): String = if (CODE.matches(v)) v else format()

    private fun token(v: String): String = if (TOKEN.matches(v)) v else format()

    private fun count(v: String): Int = if (UNSIGNED.matches(v) && v.length <= MAX_COUNT_DIGITS) v.toInt() else format()

    /** 부호 없는 10진 정수(앞 0 없음). 0 허용 여부는 레코드 모양 판정(RECORD_INVALID)이 본다. */
    private fun amount(v: String): Long = if (UNSIGNED.matches(v) && v.length <= MAX_AMOUNT_DIGITS) v.toLong() else format()

    private fun date(v: String): LocalDate = if (DATE.matches(v)) parseOrFormat { LocalDate.parse(v) } else format()

    private fun instant(v: String): Instant = if (TIMESTAMP.matches(v)) parseOrFormat { Instant.parse(v) } else format()

    private inline fun <T> parseOrFormat(block: () -> T): T = try {
        block()
    } catch (e: DateTimeParseException) {
        format()
    }

    private fun format(): Nothing = throw Refused(FileRejectionReason.FORMAT_INVALID)

    private class Refused(val reason: FileRejectionReason) : RuntimeException(reason.name, null, false, false)

    private const val LF: Byte = 0x0A
    private const val PRINTABLE_FIRST: Byte = 0x20
    private const val PRINTABLE_LAST: Byte = 0x7E
    private const val FORMAT_VERSION = "1"
    private const val HEADER_FIELDS = 11
    private const val RECORD_FIELDS = 21
    private const val TRAILER_FIELDS = 8
    private const val MAX_AMOUNT_DIGITS = 18
    private const val MAX_COUNT_DIGITS = 9
    private const val MAX_GENERATION_DIGITS = 4
    private const val TEST = "TEST"
    private const val KRW = "KRW"
    private const val LUMP_SUM = "LUMP_SUM"
    private const val INSTALLMENT = "INSTALLMENT"
    private val ID = Regex("^[A-Za-z0-9._:#-]{1,64}$")
    private val CODE = Regex("^[A-Z0-9]{1,32}$")
    private val TOKEN = Regex("^[A-Z0-9_]{1,32}$")
    private val UNSIGNED = Regex("^(0|[1-9][0-9]*)$")
    private val SIGNED_AMOUNT = Regex("^(0|-?[1-9][0-9]{0,17})$")
    private val MONTHS = Regex("^[1-9][0-9]?$")
    private val DATE = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")
    private val TIMESTAMP = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z$")
}

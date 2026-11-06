package com.paycore.simulator.acquirer.domain

import com.paycore.simulator.acquirer.domain.AcquirerFixture.cancel
import com.paycore.simulator.acquirer.domain.AcquirerFixture.file
import com.paycore.simulator.acquirer.domain.AcquirerFixture.ref
import com.paycore.simulator.acquirer.domain.AcquirerFixture.sale
import com.paycore.simulator.acquirer.domain.AcquirerFixture.sha256
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * 매입 파일 v1 읽기 판정(ACQUIRING-001 §5 — 가상 매입사 수신 검증). 정규형이 아니면 파일 전체 거절이고 사유는 판정 순서의 첫 위반이다:
 * FORMAT_INVALID → RECORD_INVALID → BODY_HASH_MISMATCH → TOTALS_MISMATCH → FILE_NAME_MISMATCH → ACQUIRER_MISMATCH → ENVIRONMENT_NOT_SUPPORTED.
 */
class AcquisitionFileV1ReaderSpec : FunSpec({

    val id = "ACQ.SIMACQA.TEST.20261105.001"
    val valid = file(id, listOf(sale(1, "t-a", 20_000, 5_000), sale(2, "t-b", 30_000)))

    fun reasonOf(bytes: ByteArray, fileId: String = id, acquirer: String = "SIMACQA"): FileRejectionReason? =
        (AcquisitionFileV1Reader.read(ref(fileId), acquirer, bytes) as? AcquisitionFileV1Reader.Outcome.Rejected)?.reason

    fun String.ascii() = toByteArray(Charsets.US_ASCII)

    /** body를 바꾼 뒤 트레일러 bodySha256만 다시 맞춘다(합계는 그대로 — TOTALS 판정을 따로 본다). */
    fun rehash(text: String): ByteArray {
        val lines = text.removeSuffix("\n").split("\n")
        val body = lines.dropLast(1).joinToString("") { "$it\n" }
        val t = lines.last().split("|").toMutableList()
        t[7] = sha256(body.ascii())
        return (body + t.joinToString("|") + "\n").ascii()
    }

    val text = String(valid, Charsets.US_ASCII)

    test("R-1 a canonical file is accepted with its seal hash (whole-file SHA-256) and the trailer totals") {
        val outcome = AcquisitionFileV1Reader.read(ref(id), "SIMACQA", valid)
        outcome.shouldBeInstanceOf<AcquisitionFileV1Reader.Outcome.Accepted>()
        outcome.file.contentSha256 shouldBe sha256(valid)
        outcome.file.trailer shouldBe ReceivedTrailer(2, 2, 45_000, 0, 0, 45_000, outcome.file.trailer.bodySha256)
        outcome.file.records.map { it.recordAmountMinor } shouldBe listOf(15_000L, 30_000L)
    }

    test("R-2 encoding and structure violations are FORMAT_INVALID (CRLF, BOM, non-ASCII, missing final LF, empty line, field count, sequence, header)") {
        mapOf(
            "crlf" to text.replace("\n", "\r\n").ascii(),
            "bom" to byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + valid,
            "non-ascii" to text.replace("demo-mid-001", "demo-mid-00é").toByteArray(Charsets.UTF_8),
            "no final LF" to text.removeSuffix("\n").ascii(),
            "empty line" to text.replaceFirst("\n", "\n\n").ascii(),
            "record field count" to rehash(text.replaceFirst("|SIMISSA|0|", "|SIMISSA|0||")),
            "sequence gap" to rehash(text.replace("\nD|2|", "\nD|3|")),
            "header format version" to rehash(text.replaceFirst("H|1|", "H|2|")),
            "header acquirer differs from the file id" to rehash(text.replaceFirst("|PAYCORE|SIMACQA|", "|PAYCORE|SIMACQB|")),
            "version 001 with an original file" to rehash(text.replaceFirst("|001||", "|001|ACQ.SIMACQA.TEST.20261105.001|")),
            "amount with a leading zero" to rehash(text.replace("|30000|0|30000|", "|030000|0|30000|")),
            "timestamp without milliseconds" to rehash(text.replaceFirst("2026-11-05T01:00:00.000Z", "2026-11-05T01:00:00Z")),
            "unknown record type" to rehash(text.replaceFirst("|SALE|", "|REFUND|")),
        ).mapValues { (_, bytes) -> reasonOf(bytes) } shouldBe mapOf(
            "crlf" to FileRejectionReason.FORMAT_INVALID, "bom" to FileRejectionReason.FORMAT_INVALID, "non-ascii" to FileRejectionReason.FORMAT_INVALID,
            "no final LF" to FileRejectionReason.FORMAT_INVALID, "empty line" to FileRejectionReason.FORMAT_INVALID,
            "record field count" to FileRejectionReason.FORMAT_INVALID, "sequence gap" to FileRejectionReason.FORMAT_INVALID,
            "header format version" to FileRejectionReason.FORMAT_INVALID, "header acquirer differs from the file id" to FileRejectionReason.FORMAT_INVALID,
            "version 001 with an original file" to FileRejectionReason.FORMAT_INVALID, "amount with a leading zero" to FileRejectionReason.FORMAT_INVALID,
            "timestamp without milliseconds" to FileRejectionReason.FORMAT_INVALID, "unknown record type" to FileRejectionReason.FORMAT_INVALID,
        )
    }

    test("R-3 record shape violations are RECORD_INVALID (SALE net ≠ approved − netted, netted CANCEL, non-KRW, months without installment, generation)") {
        val withCancel = String(file(id, listOf(sale(1, "t-a", 20_000), cancel(2, "c-a", "t-a", 20_000, 5_000))), Charsets.US_ASCII)
        mapOf(
            "sale net" to rehash(text.replaceFirst("|20000|5000|15000|", "|20000|5000|16000|")),
            "netted cancel" to rehash(withCancel.replace("|20000|0|5000|", "|20000|1|5000|")),
            "currency" to rehash(text.replaceFirst("|KRW|", "|USD|")),
            "months on lump sum" to rehash(text.replaceFirst("|LUMP_SUM||", "|LUMP_SUM|3|")),
            "reacquisition reference without generation" to rehash(text.replaceFirst("|SIMISSA|0|\n", "|SIMISSA|0|ACQ.SIMACQA.TEST.20261104.001#1\n")),
        ).mapValues { (_, bytes) -> reasonOf(bytes) }.values.toSet() shouldBe setOf(FileRejectionReason.RECORD_INVALID)
    }

    test("R-4 a changed body under the original trailer is BODY_HASH_MISMATCH; a re-hashed body with wrong totals is TOTALS_MISMATCH") {
        reasonOf(text.replaceFirst("|20000|5000|15000|", "|20000|4000|16000|").ascii()) shouldBe FileRejectionReason.BODY_HASH_MISMATCH
        reasonOf(rehash(text.replaceFirst("|20000|5000|15000|", "|20000|4000|16000|"))) shouldBe FileRejectionReason.TOTALS_MISMATCH
        val trailer = text.removeSuffix("\n").substringAfterLast("\n")
        reasonOf(text.replace(trailer, trailer.replaceFirst("T|2|2|45000|0|0|45000|", "T|2|2|45000|0|0|45001|")).ascii()) shouldBe FileRejectionReason.TOTALS_MISMATCH
    }

    test("R-5 a valid file under another name, from another acquirer's account or for LIVE is refused with that reason") {
        reasonOf(valid, fileId = "ACQ.SIMACQA.TEST.20261105.002") shouldBe FileRejectionReason.FILE_NAME_MISMATCH
        reasonOf(valid, acquirer = "SIMACQB") shouldBe FileRejectionReason.ACQUIRER_MISMATCH
        val live = "ACQ.SIMACQA.LIVE.20261105.001"
        reasonOf(file(live, listOf(sale(1, "t-a", 20_000))), fileId = live) shouldBe FileRejectionReason.ENVIRONMENT_NOT_SUPPORTED
    }

    test("R-6 file names: final {id}.dat and per-attempt temporary .{id}.dat.{n}.part map to the same file id; legacy/malformed temp names and impossible dates do not") {
        AcquisitionFileRef.fromFinalName("$id.dat") shouldBe ref(id)
        AcquisitionFileRef.fromTempName(".$id.dat.1.part") shouldBe ref(id)
        AcquisitionFileRef.fromTempName(".$id.dat.999999.part") shouldBe ref(id)
        AcquisitionFileRef.finalNameOfTemp(".$id.dat.42.part") shouldBe "$id.dat"
        AcquisitionFileRef.tempName(ref(id), 7) shouldBe ".$id.dat.7.part"
        // Coordinator 결정 (9): 옛 공용 임시명·앞 0·7자리·0·숫자 아님은 임시명이 아니다.
        listOf(".$id.dat.part", ".$id.dat.01.part", ".$id.dat.1234567.part", ".$id.dat.0.part", ".$id.dat.x.part", "$id.dat.1.part")
            .map { AcquisitionFileRef.fromTempName(it) } shouldBe List(6) { null }
        listOf(".$id.dat", "$id.dat.part", "$id.txt", "ACQ.SIMACQA.TEST.20261131.001.dat", "ACQ.SIMACQA.TEST.20261105.000.dat", "ACQ.simacqa.TEST.20261105.001.dat")
            .map { AcquisitionFileRef.fromFinalName(it) } shouldBe List(6) { null }
        ref(id).ackName(2) shouldBe "ACK.$id.02.dat"
        ref(id).resultName(11) shouldBe "RES.$id.11.dat"
    }
})

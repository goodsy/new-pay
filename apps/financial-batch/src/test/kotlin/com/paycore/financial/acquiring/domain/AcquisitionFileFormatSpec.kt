package com.paycore.financial.acquiring.domain

import com.paycore.financial.acquiring.domain.AcquisitionFixture.CUTOFF
import com.paycore.financial.acquiring.domain.AcquisitionFixture.cancellationOf
import com.paycore.financial.acquiring.domain.AcquisitionFixture.header
import com.paycore.financial.acquiring.domain.AcquisitionFixture.included
import com.paycore.financial.acquiring.domain.AcquisitionFixture.sale
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

/**
 * 매입 파일 계약 v1 렌더링·해석·봉인(AQ-F1..F6 — ACQUIRING-001 §5, Implementer 해석 I-11·I-15). 바이트 자체의 계약값은 golden(AcquisitionFileGoldenSpec)이 고정한다.
 */
class AcquisitionFileFormatSpec : BehaviorSpec({

    Given("AQ-F1 a sealed file with two SALE members (one netted)") {
        val a = sale("ptx_a", 10_000, approvedAt = Instant.parse("2026-11-05T03:00:00.000Z"))
        val bBase = sale("ptx_b", 20_000, approvedAt = Instant.parse("2026-11-05T01:00:00.000Z"))
        val b = bBase.copy(cancellations = listOf(cancellationOf(bBase, 5_000)))
        val (file, rendered) = AcquisitionFile.seal(header(), CUTOFF, listOf(included(a), included(b)))
        Then("members are numbered by (approvedAt, transactionId), totals match and the seal hash is SHA-256 of the whole bytes") {
            rendered.records.map { it.transactionId to it.seq } shouldBe listOf("ptx_b" to 1, "ptx_a" to 2)
            rendered.totals shouldBe AcquisitionTotals(recordCount = 2, saleCount = 2, saleAmountMinor = 25_000, cancelCount = 0, cancelAmountMinor = 0)
            file.contentSha256 shouldBe AcquisitionFileFormat.sha256(rendered.content)
            listOf(file.transmissionState, file.receiptState, file.resultState) shouldBe listOf(TransmissionState.SEALED, ReceiptState.NONE, ResultState.AWAITING)
        }
        Then("the bytes are US-ASCII lines ending in LF (no CR, no BOM) and parse back to the same header and records") {
            val text = String(rendered.content, Charsets.US_ASCII)
            (text.endsWith("\n") && !text.contains('\r') && rendered.content.first() == 'H'.code.toByte()) shouldBe true
            text.lines().dropLast(1).map { it.first() } shouldBe listOf('H', 'D', 'D', 'T')
            val parsed = AcquisitionFileFormat.parse(rendered.content)
            (parsed.header == rendered.header && parsed.records == rendered.records && parsed.sha256 == rendered.sha256) shouldBe true
        }
        Then("the stored bytes match the stored header and records; any other bytes do not (restart integrity — I-11)") {
            file.matches(rendered.records, rendered.content, rendered.sha256) shouldBe true
            val tampered = rendered.content.copyOf().also { it[it.size - 2] = if (it[it.size - 2] == '0'.code.toByte()) '1'.code.toByte() else '0'.code.toByte() }
            file.matches(rendered.records, tampered, rendered.sha256) shouldBe false
            file.matches(rendered.records.map { if (it.seq == 1) it.copy(merchantRef = "other-mid") else it }, rendered.content, rendered.sha256) shouldBe false
            // 리뷰 NIT: 바이트 행의 hash 열도 대조한다.
            file.matches(rendered.records, rendered.content, "0".repeat(64)) shouldBe false
        }
    }

    Given("AQ-F2 an empty business day") {
        val (file, rendered) = AcquisitionFile.seal(header(), CUTOFF, emptyList())
        Then("the file is a header and a zero trailer (an explicit 'nothing submitted' — Implementer I-3)") {
            String(rendered.content, Charsets.US_ASCII).lines().dropLast(1).size shouldBe 2
            file.totals shouldBe AcquisitionTotals(0, 0, 0, 0, 0)
            AcquisitionFileFormat.parse(rendered.content).records shouldBe emptyList()
        }
    }

    Given("AQ-F3 malformed or altered files") {
        val (_, rendered) = AcquisitionFile.seal(header(), CUTOFF, listOf(included(sale())))
        val text = String(rendered.content, Charsets.US_ASCII)
        Then("CRLF, a changed amount (trailer/body hash mismatch), a missing final LF and non-ASCII bytes are refused") {
            listOf(
                text.replace("\n", "\r\n").toByteArray(Charsets.US_ASCII),
                text.replace("|10000|0|10000|", "|10000|0|9999|").toByteArray(Charsets.US_ASCII),
                text.dropLast(1).toByteArray(Charsets.US_ASCII),
                text.replace("PAYCORE", "PAYCORÉ").toByteArray(Charsets.UTF_8),
            ).forEach { bytes -> shouldThrow<AcquisitionContractViolation> { AcquisitionFileFormat.parse(bytes) } }
        }
    }

    Given("AQ-F4 record invariants") {
        val record = included(sale()).toRecord(1)
        Then("a SALE amount must equal approved − netted, a CANCEL needs its original, KRW only, no field delimiter, generation ↔ re-acquisition reference") {
            shouldThrow<AcquisitionContractViolation> { record.copy(recordAmountMinor = 9_999) }
            shouldThrow<AcquisitionContractViolation> { record.copy(type = AcquisitionRecordType.CANCEL, nettedCancelAmountMinor = 0) }
            shouldThrow<AcquisitionContractViolation> { record.copy(currency = "USD") }
            shouldThrow<AcquisitionContractViolation> { record.copy(merchantRef = "mid|x") }
            shouldThrow<AcquisitionContractViolation> { record.copy(generation = 1) }
            shouldThrow<AcquisitionContractViolation> { record.copy(eventAt = record.eventAt.plusNanos(1)) }
        }
    }

    Given("AQ-F5 seal guards") {
        val s = sale()
        Then("a member twice, a member of another lane and a member approved after the cutoff are refused") {
            shouldThrow<AcquisitionContractViolation> { AcquisitionFile.seal(header(), CUTOFF, listOf(included(s), included(s))) }
            shouldThrow<AcquisitionContractViolation> { AcquisitionFile.seal(header(), CUTOFF, listOf(included(sale(acquirerCode = "SIMACQB")))) }
            shouldThrow<AcquisitionContractViolation> { AcquisitionFile.seal(header(), CUTOFF.minusSeconds(3600 * 24), listOf(included(s))) }
        }
    }

    Given("AQ-F6 file ids") {
        val id = AcquisitionFileId("SIMACQA", AcquisitionEnvironment.TEST, AcquisitionFixture.BUSINESS_DATE, 1)
        Then("the id and file name follow ACQ.{acquirer}.{env}.{yyyyMMdd}.{vvv}, round-trip, and reject bad codes and versions") {
            listOf(id.value, id.fileName) shouldBe listOf("ACQ.SIMACQA.TEST.20261105.001", "ACQ.SIMACQA.TEST.20261105.001.dat")
            AcquisitionFileId.parse(id.value) shouldBe id
            shouldThrow<AcquisitionContractViolation> { AcquisitionFileId("SIM-ACQ", AcquisitionEnvironment.TEST, AcquisitionFixture.BUSINESS_DATE, 1) }
            shouldThrow<AcquisitionContractViolation> { AcquisitionFileId("SIMACQA", AcquisitionEnvironment.TEST, AcquisitionFixture.BUSINESS_DATE, 1000) }
            shouldThrow<AcquisitionContractViolation> { AcquisitionFileId.parse("ACQ.SIMACQA.TEST.20261332.001") }
        }
    }
})

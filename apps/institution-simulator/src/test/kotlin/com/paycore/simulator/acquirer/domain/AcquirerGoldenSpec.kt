package com.paycore.simulator.acquirer.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate

/**
 * 계약 golden(ACQUIRING-001 §5·§7). 가상 매입사는 FINANCIAL과 코드를 공유하지 않고 harness가 Python으로 독립 생성한 golden으로만 맞춘다:
 * - `acquiring-file-v1.golden.json`(FINANCIAL이 봉인하는 매입 파일 — financial-batch 사본과 같은 바이트, harness test_acquiring_file_golden.py가 둘 다 대조):
 *   가상 매입사 읽기가 golden 바이트를 그대로 받아 같은 헤더·레코드·트레일러·hash로 읽는다.
 * - `acquiring-ack-result-v1.golden.json`(가상 매입사가 게시하는 ACK·결과 — harness test_acquiring_ack_result_golden.py): 렌더러가 같은 이름·바이트를 만든다.
 */
class AcquirerGoldenSpec : FunSpec({

    val mapper = JsonMapper.builder().build()
    fun load(name: String): JsonNode = mapper.readTree(requireNotNull(AcquirerGoldenSpec::class.java.getResourceAsStream("/contracts/$name")) { "golden $name missing" })
    val fileGolden = load("acquiring-file-v1.golden.json")
    val outGolden = load("acquiring-ack-result-v1.golden.json")

    fun JsonNode.text(field: String): String? = get(field).let { if (it == null || it.isNull) null else it.asString() }

    fun record(r: JsonNode) = ReceivedRecord(
        seq = r.get("seq").asInt(), type = ReceivedRecordType.valueOf(r.get("type").asString()), transactionId = r.get("transactionId").asString(),
        originalTransactionId = r.text("originalTransactionId"), merchantRef = r.get("merchantRef").asString(), approvalNumber = r.get("approvalNumber").asString(),
        providerTransactionId = r.get("providerTransactionId").asString(), originalProviderTransactionId = r.text("originalProviderTransactionId"),
        institutionTransactionDate = LocalDate.parse(r.get("institutionTransactionDate").asString()), eventAt = Instant.parse(r.get("eventAt").asString()),
        currency = r.get("currency").asString(), approvedAmountMinor = r.get("approvedAmountMinor").asLong(),
        nettedCancelAmountMinor = r.get("nettedCancelAmountMinor").asLong(), recordAmountMinor = r.get("recordAmountMinor").asLong(),
        planKind = r.get("planKind").asString(), installmentMonths = r.get("installmentMonths").let { if (it.isNull) null else it.asInt() },
        cardType = r.get("cardType").asString(), issuerCode = r.get("issuerCode").asString(), generation = r.get("generation").asInt(),
        reacquisitionOf = r.text("reacquisitionOf"),
    )

    test("AG-1 the simulator reader accepts every FINANCIAL file v1 golden vector as is (header, records, trailer, seal hash)") {
        val vectors = fileGolden.get("vectors").toList()
        vectors.size shouldBe 3
        vectors.forEach { v ->
            val h = v.get("header")
            val ref = AcquirerFixture.ref(h.get("fileId").asString())
            val content = v.get("expected").get("content").asString().toByteArray(Charsets.US_ASCII)
            val outcome = AcquisitionFileV1Reader.read(ref, "SIMACQA", content)
            outcome.shouldBeInstanceOf<AcquisitionFileV1Reader.Outcome.Accepted>()
            val f = outcome.file
            f.header shouldBe ReceivedHeader(ref, h.get("senderId").asString(), h.text("originalFileId"), Instant.parse(h.get("sealedAt").asString()),
                h.get("cutoffPolicyVersion").asString())
            f.records shouldBe v.get("records").toList().map(::record)
            mapOf("contentSha256" to f.contentSha256, "bodySha256" to f.trailer.bodySha256, "bytes" to f.contentBytes.toString()) shouldBe mapOf(
                "contentSha256" to v.get("expected").get("contentSha256").asString(), "bodySha256" to v.get("expected").get("bodySha256").asString(),
                "bytes" to v.get("expected").get("contentBytes").asInt().toString(),
            )
        }
    }

    test("AG-2 the test-only file writer (AcquirerFixture.file) reproduces the golden bytes — integration specs build valid v1 files with it") {
        fileGolden.get("vectors").forEach { v ->
            val h = v.get("header")
            val bytes = AcquirerFixture.file(h.get("fileId").asString(), v.get("records").toList().map(::record), h.get("sealedAt").asString(), h.text("originalFileId"),
                h.get("senderId").asString(), h.get("cutoffPolicyVersion").asString())
            String(bytes, Charsets.US_ASCII) shouldBe v.get("expected").get("content").asString()
        }
    }

    test("AG-3 the ACK renderer produces the golden file name and bytes for every ACK vector (RECEIVED, FILE_REJECTED, RECEIPT_CONFLICT, empty day)") {
        val acks = outGolden.get("acks").toList()
        acks.size shouldBe 4
        acks.forEach { a ->
            val receipt = AcquirerReceipt(
                "SIMACQA", a.get("fileId").asString(), a.get("ackSeq").asInt(), a.get("receivedSha256").asString(), a.get("receivedBytes").asLong(),
                a.get("recordCount").let { if (it.isNull) null else it.asInt() }, ReceiptOutcome.valueOf(a.get("outcome").asString()),
                a.text("reasonCode")?.let(FileRejectionReason::valueOf), Instant.parse(a.get("receivedAt").asString()),
            )
            (AcknowledgementFileV1.fileName(receipt) to String(AcknowledgementFileV1.render(receipt), Charsets.US_ASCII)) shouldBe
                (a.get("expected").get("fileName").asString() to a.get("expected").get("content").asString())
        }
    }

    test("AG-4 the result renderer produces the golden file name and bytes for every result vector (mixed, delayed second file, empty day, CANCEL)") {
        val results = outGolden.get("results").toList()
        results.size shouldBe 4
        results.forEach { r ->
            val receipt = AcquirerReceipt("SIMACQA", r.get("fileId").asString(), 1, r.get("fileSha256").asString(), 1, 0, ReceiptOutcome.RECEIVED, null,
                Instant.parse("2026-11-01T00:00:00.000Z"))
            val lines = r.get("lines").toList().map { l ->
                PlannedResultLine(l.get("recordSeq").asInt(), l.get("transactionId").asString(), ReceivedRecordType.valueOf(l.get("recordType").asString()),
                    RecordResult.valueOf(l.get("result").asString()), l.text("reasonCode"), l.get("recordAmountMinor").asLong())
            }
            val planned = PlannedResultFile(r.get("resultSeq").asInt(), Instant.parse(r.get("producedAt").asString()), lines)
            (ResultFileV1.fileName(receipt, planned.seq) to String(ResultFileV1.render(receipt, planned), Charsets.US_ASCII)) shouldBe
                (r.get("expected").get("fileName").asString() to r.get("expected").get("content").asString())
        }
    }
})

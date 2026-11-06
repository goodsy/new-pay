package com.paycore.financial.acquiring.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

/**
 * ACK·결과 계약 v1 golden(`contracts/acquiring-ack-result-v1.golden.json` — harness `test_acquiring_ack_result_golden.py`가 Python 표준 라이브러리로 독립 생성,
 * simulator 사본과 같은 바이트). 각 vector는 **서로 독립된 형식 예시**다(한 수신 흐름이 아니다 — 리뷰 n-5): FINANCIAL 파서가 vector 하나하나를 받고, 같은 값에서
 * 같은 이름·바이트를 다시 만든다.
 */
class AcquirerResponseGoldenSpec : FunSpec({

    val golden: JsonNode = JsonMapper.builder().build().readTree(
        requireNotNull(AcquirerResponseGoldenSpec::class.java.getResourceAsStream("/contracts/acquiring-ack-result-v1.golden.json")) { "golden missing" },
    )

    fun JsonNode.textOrNull(field: String): String? = get(field).let { if (it == null || it.isNull) null else it.asString() }

    fun ack(v: JsonNode) = AcquirerAck(
        fileId = AcquisitionFileId.parse(v.get("fileId").asString()), ackSeq = v.get("ackSeq").asInt(), outcome = AckOutcome.valueOf(v.get("outcome").asString()),
        reasonCode = v.textOrNull("reasonCode"), receivedSha256 = v.get("receivedSha256").asString(), receivedBytes = v.get("receivedBytes").asLong(),
        recordCount = v.get("recordCount").let { if (it.isNull) null else it.asInt() }, receivedAt = Instant.parse(v.get("receivedAt").asString()),
    )

    fun result(v: JsonNode) = AcquirerResultFile(
        fileId = AcquisitionFileId.parse(v.get("fileId").asString()), fileSha256 = v.get("fileSha256").asString(), resultSeq = v.get("resultSeq").asInt(),
        producedAt = Instant.parse(v.get("producedAt").asString()),
        lines = v.get("lines").toList().map { l ->
            AcquirerResultLine(
                recordSeq = l.get("recordSeq").asInt(), transactionId = l.get("transactionId").asString(),
                recordType = AcquisitionRecordType.valueOf(l.get("recordType").asString()), result = LineResult.valueOf(l.get("result").asString()),
                reasonCode = l.textOrNull("reasonCode"), recordAmountMinor = l.get("recordAmountMinor").asLong(),
            )
        },
    )

    test("AR-G1 each ACK vector parses to its fields and renders back to the same name, bytes, hash and size") {
        val acks = golden.get("acks").toList()
        acks.size shouldBe 4
        acks.forEach { v ->
            val expected = v.get("expected")
            val bytes = expected.get("content").asString().toByteArray(Charsets.US_ASCII)
            val parsed = AcquirerResponseFormat.parseAck(bytes)
            parsed shouldBe ack(v)
            val rendered = AcquirerResponseFormat.renderAck(parsed)
            listOf(AcquirerResponseFormat.ackFileName(parsed.fileId, parsed.ackSeq), String(rendered, Charsets.US_ASCII), AcquisitionFileFormat.sha256(rendered), rendered.size) shouldBe
                listOf(expected.get("fileName").asString(), expected.get("content").asString(), expected.get("contentSha256").asString(), expected.get("contentBytes").asInt())
            AcquirerResponseFormat.parseName(expected.get("fileName").asString()) shouldBe
                AcquirerResponseName(AcquirerResponseKind.ACK, parsed.fileId, parsed.ackSeq, expected.get("fileName").asString())
        }
    }

    test("AR-G2 each result vector parses to its fields and renders back to the same name, result file id, bytes, hash and size (empty day included)") {
        val results = golden.get("results").toList()
        results.size shouldBe 4
        results.forEach { v ->
            val expected = v.get("expected")
            val parsed = AcquirerResponseFormat.parseResult(expected.get("content").asString().toByteArray(Charsets.US_ASCII))
            parsed shouldBe result(v)
            val rendered = AcquirerResponseFormat.renderResult(parsed)
            listOf(
                AcquirerResponseFormat.resultFileName(parsed.fileId, parsed.resultSeq), parsed.resultFileId, String(rendered, Charsets.US_ASCII),
                AcquisitionFileFormat.sha256(rendered), rendered.size,
            ) shouldBe listOf(
                expected.get("fileName").asString(), expected.get("resultFileId").asString(), expected.get("content").asString(),
                expected.get("contentSha256").asString(), expected.get("contentBytes").asInt(),
            )
        }
    }

    test("AR-G3 the ACK received hashes are the sealed hashes of the acquisition file golden vectors (cross-contract consistency, not one flow)") {
        val files = JsonMapper.builder().build().readTree(
            requireNotNull(AcquirerResponseGoldenSpec::class.java.getResourceAsStream("/contracts/acquiring-file-v1.golden.json")) { "file golden missing" },
        ).get("vectors").toList().associate { it.get("header").get("fileId").asString() to it.get("expected").get("contentSha256").asString() }
        golden.get("acks").toList().filter { it.get("outcome").asString() != "RECEIPT_CONFLICT" }.forEach { v ->
            files[v.get("fileId").asString()] shouldBe v.get("receivedSha256").asString()
        }
    }
})

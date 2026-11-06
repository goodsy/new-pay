package com.paycore.financial.acquiring.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate

/**
 * 매입 파일 계약 v1 golden(`contracts/acquiring-file-v1.golden.json` — harness `test_acquiring_file_golden.py`가 Python 표준 라이브러리로 독립 생성).
 * FINANCIAL 렌더러가 같은 입력에서 golden과 **같은 바이트·hash**를 만들고, 그 바이트를 해석하면 같은 헤더·레코드로 돌아온다.
 */
class AcquisitionFileGoldenSpec : FunSpec({

    val golden: JsonNode = JsonMapper.builder().build().readTree(
        requireNotNull(AcquisitionFileGoldenSpec::class.java.getResourceAsStream("/contracts/acquiring-file-v1.golden.json")) { "golden missing" },
    )

    fun JsonNode.textOrNull(field: String): String? = get(field).let { if (it == null || it.isNull) null else it.asString() }

    fun header(h: JsonNode) = AcquisitionFileHeader(
        AcquisitionFileId.parse(h.get("fileId").asString()), h.get("senderId").asString(), Instant.parse(h.get("sealedAt").asString()),
        h.get("cutoffPolicyVersion").asString(), h.textOrNull("originalFileId")?.let(AcquisitionFileId::parse),
    )

    fun record(r: JsonNode) = AcquisitionRecord(
        seq = r.get("seq").asInt(), type = AcquisitionRecordType.valueOf(r.get("type").asString()), transactionId = r.get("transactionId").asString(),
        originalTransactionId = r.textOrNull("originalTransactionId"), merchantRef = r.get("merchantRef").asString(), approvalNumber = r.get("approvalNumber").asString(),
        providerTransactionId = r.get("providerTransactionId").asString(), originalProviderTransactionId = r.textOrNull("originalProviderTransactionId"),
        institutionTransactionDate = LocalDate.parse(r.get("institutionTransactionDate").asString()), eventAt = Instant.parse(r.get("eventAt").asString()),
        currency = r.get("currency").asString(), approvedAmountMinor = r.get("approvedAmountMinor").asLong(),
        nettedCancelAmountMinor = r.get("nettedCancelAmountMinor").asLong(), recordAmountMinor = r.get("recordAmountMinor").asLong(),
        planKind = r.get("planKind").asString(), installmentMonths = r.get("installmentMonths").let { if (it.isNull) null else it.asInt() },
        cardType = r.get("cardType").asString(), issuerCode = r.get("issuerCode").asString(), generation = r.get("generation").asInt(),
        reacquisitionOf = r.textOrNull("reacquisitionOf"),
    )

    test("AQ-G1 every golden vector renders to the same bytes, body hash, seal hash and size") {
        val vectors = golden.get("vectors").toList()
        vectors.size shouldBe 3
        vectors.forEach { v ->
            val rendered = AcquisitionFileFormat.render(header(v.get("header")), v.get("records").toList().map(::record))
            val expected = v.get("expected")
            mapOf(
                "content" to String(rendered.content, Charsets.US_ASCII),
                "contentSha256" to rendered.sha256,
                "contentBytes" to rendered.content.size.toString(),
            ) shouldBe mapOf(
                "content" to expected.get("content").asString(),
                "contentSha256" to expected.get("contentSha256").asString(),
                "contentBytes" to expected.get("contentBytes").asInt().toString(),
            )
        }
    }

    test("AQ-G2 the golden bytes parse back to the golden header and records (the reader accepts exactly the canonical form)") {
        golden.get("vectors").forEach { v ->
            val parsed = AcquisitionFileFormat.parse(v.get("expected").get("content").asString().toByteArray(Charsets.US_ASCII))
            (parsed.header == header(v.get("header")) && parsed.records == v.get("records").toList().map(::record)) shouldBe true
        }
    }
})

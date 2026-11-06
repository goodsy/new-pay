package com.paycore.simulator.acquirer.domain

import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.HexFormat

/**
 * 가상 매입사 시험 Fixture(Test Fixture Policy — 핵심 규칙은 명시 Fixture, Fixture Monkey 미사용). 매입 파일 v1 바이트는 시험 전용 작성기 [file]가 만든다
 * (운영 코드에는 v1 렌더러가 없다 — 가상 매입사는 읽기만 한다. 작성기가 golden과 같은 바이트를 만드는지는 AcquirerGoldenSpec이 확인한다).
 * 금액은 업무 의미대로 파생한다: SALE 레코드 금액 = 승인액 − 상계, CANCEL은 상계 0·원거래 참조.
 */
object AcquirerFixture {
    val BUSINESS_DATE: LocalDate = LocalDate.parse("2026-11-05")
    const val APPROVAL = "30000001"

    fun sale(
        seq: Int, txn: String, approved: Long, netted: Long = 0, ptx: String = "VAN-T-$txn", approval: String = APPROVAL, date: LocalDate = BUSINESS_DATE,
        generation: Int = 0, reacquisitionOf: String? = null,
    ) = ReceivedRecord(
        seq = seq, type = ReceivedRecordType.SALE, transactionId = txn, originalTransactionId = null, merchantRef = "demo-mid-001", approvalNumber = approval,
        providerTransactionId = ptx, originalProviderTransactionId = null, institutionTransactionDate = date, eventAt = Instant.parse("2026-11-05T01:00:00.000Z"),
        currency = "KRW", approvedAmountMinor = approved, nettedCancelAmountMinor = netted, recordAmountMinor = approved - netted, planKind = "LUMP_SUM",
        installmentMonths = null, cardType = "CREDIT", issuerCode = "SIMISSA", generation = generation, reacquisitionOf = reacquisitionOf,
    )

    fun cancel(seq: Int, txn: String, original: String, originalApproved: Long, amount: Long, ptx: String = "VAN-C-$txn", originalPtx: String = "VAN-T-$original") =
        ReceivedRecord(
            seq = seq, type = ReceivedRecordType.CANCEL, transactionId = txn, originalTransactionId = original, merchantRef = "demo-mid-001",
            approvalNumber = APPROVAL, providerTransactionId = ptx, originalProviderTransactionId = originalPtx, institutionTransactionDate = BUSINESS_DATE,
            eventAt = Instant.parse("2026-11-06T05:06:07.890Z"), currency = "KRW", approvedAmountMinor = originalApproved, nettedCancelAmountMinor = 0,
            recordAmountMinor = amount, planKind = "LUMP_SUM", installmentMonths = null, cardType = "CHECK", issuerCode = "SIMISSA", generation = 0, reacquisitionOf = null,
        )

    fun ref(fileId: String): AcquisitionFileRef = checkNotNull(AcquisitionFileRef.parseOrNull(fileId))

    /** 매입 파일 v1 바이트(시험 작성기 — 파일 계약 v1: H 11·D 21·T 8, US-ASCII LF, bodySha256 = H+D 줄 SHA-256). */
    fun file(
        fileId: String, records: List<ReceivedRecord>, sealedAt: String = "2026-11-05T14:05:00.000Z", originalFileId: String? = null,
        senderId: String = "PAYCORE", cutoffPolicyVersion: String = "acq-cutoff-demo-1",
    ): ByteArray {
        val r = ref(fileId)
        val lines = mutableListOf(
            listOf("H", "1", fileId, senderId, r.acquirerCode, r.environment, r.businessDate.toString(), fileId.substringAfterLast('.'), originalFileId.orEmpty(), sealedAt,
                cutoffPolicyVersion).joinToString("|"),
        )
        records.forEach { d ->
            lines += listOf(
                "D", d.seq, d.type, d.transactionId, d.originalTransactionId.orEmpty(), d.merchantRef, d.approvalNumber, d.providerTransactionId,
                d.originalProviderTransactionId.orEmpty(), d.institutionTransactionDate, d.eventAt.toString().let(::millis), d.currency, d.approvedAmountMinor,
                d.nettedCancelAmountMinor, d.recordAmountMinor, d.planKind, d.installmentMonths?.toString().orEmpty(), d.cardType, d.issuerCode, d.generation,
                d.reacquisitionOf.orEmpty(),
            ).joinToString("|")
        }
        val body = lines.joinToString("") { "$it\n" }.toByteArray(Charsets.US_ASCII)
        val sales = records.filter { it.type == ReceivedRecordType.SALE }
        val cancels = records.filter { it.type == ReceivedRecordType.CANCEL }
        val saleTotal = sales.sumOf { it.recordAmountMinor }
        val cancelTotal = cancels.sumOf { it.recordAmountMinor }
        val trailer = listOf("T", records.size, sales.size, saleTotal, cancels.size, cancelTotal, saleTotal - cancelTotal, sha256(body)).joinToString("|") + "\n"
        return body + trailer.toByteArray(Charsets.US_ASCII)
    }

    fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** Instant.toString()은 0 밀리초를 생략한다 — 계약 표기(.SSS) 보정. */
    private fun millis(s: String): String = if (Regex("\\.[0-9]{3}Z$").containsMatchIn(s)) s else s.removeSuffix("Z") + ".000Z"
}

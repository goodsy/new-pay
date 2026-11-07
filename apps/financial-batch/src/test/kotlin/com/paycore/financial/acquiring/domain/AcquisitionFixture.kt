package com.paycore.financial.acquiring.domain

import java.time.Instant
import java.time.LocalDate

/**
 * 매입 Named Fixture(Test Fixture Policy — 핵심 규칙은 업무 조건이 드러나는 명시 Fixture, Fixture Monkey 미사용). 취소는 원승인 후보에서 파생한다
 * ([cancellationOf] — 통화가 원승인 값 그대로라 정상 Fixture가 불변식을 만족). 의도된 위반은 이름·`copy`로 드러낸다. 금액·코드는 Demo 값(운영값 아님).
 */
object AcquisitionFixture {
    val BUSINESS_DATE: LocalDate = LocalDate.parse("2026-11-05")

    /** Demo 마감 23:00 Asia/Seoul = 14:00Z. */
    val CUTOFF: Instant = Instant.parse("2026-11-05T14:00:00Z")

    fun sale(
        transactionId: String = "ptx_auth_1",
        amountMinor: Long = 10_000,
        approvedAt: Instant = Instant.parse("2026-11-05T01:02:03.456Z"),
        acquirerCode: String = "SIMACQA",
        environment: AcquisitionEnvironment = AcquisitionEnvironment.TEST,
        currency: String = "KRW",
        held: Boolean = false,
        cancellations: List<KnownCancellation> = emptyList(),
        paymentHeld: Boolean = held || cancellations.any { it.held },
        approvalNumber: String = "30000001",
    ) = SaleCandidate(
        transactionId = transactionId, paymentId = "pay_$transactionId", environment = environment, merchantAccountId = "demo-mid-001", acquirerCode = acquirerCode,
        amountMinor = amountMinor, currency = currency, planKind = "LUMP_SUM", installmentMonths = null, cardType = "CREDIT", issuerCode = "SIMISSA",
        approvalNumber = approvalNumber, providerTransactionId = "VAN-T-$transactionId", institutionTransactionDate = BUSINESS_DATE, approvedAt = approvedAt,
        held = held, paymentHeld = paymentHeld, cancellations = cancellations,
    )

    fun cancellationOf(sale: SaleCandidate, amountMinor: Long, n: Int = 1, held: Boolean = false) =
        KnownCancellation("${sale.transactionId}_c$n", amountMinor, sale.currency, held)

    fun header(
        fileId: AcquisitionFileId = AcquisitionFileId("SIMACQA", AcquisitionEnvironment.TEST, BUSINESS_DATE, 1),
        sealedAt: Instant = Instant.parse("2026-11-05T14:05:00.000Z"),
    ) = AcquisitionFileHeader(fileId, "PAYCORE", sealedAt, "acq-cutoff-demo-1", null)

    fun included(sale: SaleCandidate): CandidateDecision.Include =
        AcquisitionCandidateRule.decide(sale, CUTOFF) as CandidateDecision.Include

    /**
     * (2-F) 원승인 [original]이 이미 membership(파일 [originalFile] 순번 [originalSeq]의 SALE, 상계액 [nettedMinor])인 뒤 받은 전송 후 취소(금액 [amountMinor]).
     * 원 기관 거래번호·MID·통화는 원승인 값 그대로다(정상 Fixture가 불변식을 만족). 취소 시각은 다음 업무일 마감 전.
     */
    fun cancelAfterMembership(
        original: SaleCandidate = sale(),
        amountMinor: Long = 3_000,
        nettedMinor: Long = 0,
        alreadyCancelledMinor: Long = 0,
        n: Int = 1,
        held: Boolean = false,
        originalFile: AcquisitionFileId = AcquisitionFileId("SIMACQA", AcquisitionEnvironment.TEST, BUSINESS_DATE, 1),
        originalSeq: Int = 1,
        cancelledAt: Instant = Instant.parse("2026-11-06T02:03:04.567Z"),
    ): CancelCandidate {
        val originalSale = CandidateDecision.Include(original, emptyList(), nettedMinor).toRecord(originalSeq)
        return CancelCandidate(
            transactionId = "${original.transactionId}_post$n", original = original, originalSaleFileId = originalFile, originalSale = originalSale,
            alreadyCancelledMinor = alreadyCancelledMinor, merchantAccountId = original.merchantAccountId, amountMinor = amountMinor, currency = original.currency,
            providerTransactionId = "VAN-C-${original.transactionId}-$n", originalProviderTransactionId = original.providerTransactionId,
            institutionTransactionDate = BUSINESS_DATE.plusDays(1), cancelledAt = cancelledAt, held = held,
        )
    }

    /**
     * (ACQ-ADMIN-001 B2 — L-6) 원승인 [original]이 재매입돼 가장 높은 세대 SALE이 세대 [generation](원 SALE 상계액 [nettedMinor] = 실행 시점까지 판정 끝난 취소 합,
     * I-13)인 뒤 받은 취소. 재매입 레코드는 같은 업무일 다음 버전 파일(버전 = 세대 + 1)의 순번 1이고 바로 앞 버전 레코드 #1을 가리킨다(I-13 — 정상 Fixture가 불변식을 만족).
     */
    fun cancelAfterReacquisition(
        original: SaleCandidate = sale(),
        amountMinor: Long = 2_000,
        nettedMinor: Long = 0,
        alreadyCancelledMinor: Long = 0,
        generation: Int = 1,
    ): CancelCandidate {
        val base = cancelAfterMembership(original, amountMinor, nettedMinor, alreadyCancelledMinor)
        val previous = AcquisitionFileId("SIMACQA", AcquisitionEnvironment.TEST, BUSINESS_DATE, generation)
        return base.copy(
            originalSaleFileId = AcquisitionFileId("SIMACQA", AcquisitionEnvironment.TEST, BUSINESS_DATE, generation + 1),
            originalSale = base.originalSale.copy(generation = generation, reacquisitionOf = "${previous.value}#1"),
        )
    }

    /** 다음 업무일(2026-11-06) Demo 마감 = 14:00Z. */
    val NEXT_CUTOFF: Instant = Instant.parse("2026-11-06T14:00:00Z")
}

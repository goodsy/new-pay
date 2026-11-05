package com.paycore.financial.paymentfact.domain

import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicLong

/**
 * 결제 사실 Named Fixture(Test Fixture Policy — 핵심 규칙은 업무 조건이 드러나는 명시 Fixture, Fixture Monkey 미사용).
 * 취소는 원승인에서 파생한다([cancelOf]): 원승인 ID·금액·통화·기관 거래번호가 독립 random이 아니라 원승인 값 그대로라 정상 Fixture가 Domain Invariant를 만족한다.
 * 의도적으로 어긋난 값은 이름에 드러낸다([cancelWithoutOriginal]·`copy(...)`로 바꾼 필드).
 */
object PaymentFactFixture {
    private val seq = AtomicLong()

    fun nextPaymentId(): String = "pay_fx_" + seq.incrementAndGet()

    fun identity(paymentId: String = nextPaymentId(), environment: FactEnvironment = FactEnvironment.TEST) = PaymentFactIdentity(
        environment = environment, merchantAccountId = "demo-mid-001", merchantSiteId = "demo-site-001", merchantOrderId = "order-$paymentId", paymentId = paymentId,
    )

    /** 일시불 신용 승인 1건(Demo 금액 — 운영값 아님). */
    fun authorized(
        paymentId: String = nextPaymentId(),
        amountMinor: Long = 10_000,
        currency: String = "KRW",
        transactionId: String = "ptx_auth_$paymentId",
    ) = AuthorizedPaymentFact(
        identity = identity(paymentId), authorizationId = "pau_$paymentId", transactionId = transactionId, money = FactMoney(amountMinor, currency),
        plan = FactPlan(FactPlan.LUMP_SUM, null), cardType = "CREDIT", issuerCode = "SIMISSA", acquirerCode = "SIMACQA", approvalNumber = "30000001",
        providerTransactionId = "VAN-T-$paymentId", institutionTransactionDate = LocalDate.parse("2026-11-05"), institutionBusinessDate = null,
        approvedAt = Instant.parse("2026-11-05T01:02:03.456Z"), policyAssignmentVersion = "pa:demo-policy-1", entitlementVersion = "es1:demo-merchant-001:3",
    )

    /**
     * 원승인 [original]의 [returnSequence]번째 반환(취소 금액 [amountMinor], 누적 [cumulativeMinor]). 원승인 ID·통화·승인액·기관 원거래번호는 원승인 값 그대로다.
     */
    fun cancelOf(
        original: AuthorizedPaymentFact,
        amountMinor: Long,
        returnSequence: Int = 1,
        cumulativeMinor: Long = amountMinor,
        transactionId: String = "ptx_cancel_${original.identity.paymentId}_$returnSequence",
    ) = CancelledPaymentFact(
        identity = original.identity, authorizationId = original.authorizationId, originalTransactionId = original.transactionId,
        cancellationId = "pcn_${original.identity.paymentId}_$returnSequence", transactionId = transactionId, money = FactMoney(amountMinor, original.money.currency),
        scope = if (cumulativeMinor >= original.money.minor) "FULL" else "PARTIAL", authorizedAmountMinor = original.money.minor, returnSequence = returnSequence,
        cumulativeReturnedMinor = cumulativeMinor, returnStatusAfter = if (cumulativeMinor >= original.money.minor) "FULLY_RETURNED" else "PARTIALLY_RETURNED",
        cancelApprovalNumber = "4000000$returnSequence", providerTransactionId = "VAN-C-${original.identity.paymentId}-$returnSequence",
        originalProviderTransactionId = original.providerTransactionId, institutionTransactionDate = LocalDate.parse("2026-11-05"),
        institutionBusinessDate = LocalDate.parse("2026-11-05"), cancelledAt = Instant.parse("2026-11-05T02:03:04.567Z"),
        policyAssignmentVersion = original.policyAssignmentVersion, entitlementVersion = original.entitlementVersion, initiatorKind = "MERCHANT",
        originalPlan = original.plan, originalCardType = original.cardType,
    )

    /** 의도: 원승인 투영이 FINANCIAL에 아직 없는 취소(원승인 사실을 FINANCIAL에 넣지 않은 채 만든 원승인 모양에서 파생 — D-ACQ-002 Hold 입력). */
    fun cancelWithoutOriginal(paymentId: String = nextPaymentId(), amountMinor: Long = 3_000): Pair<AuthorizedPaymentFact, CancelledPaymentFact> {
        val unseenOriginal = authorized(paymentId)
        return unseenOriginal to cancelOf(unseenOriginal, amountMinor)
    }
}

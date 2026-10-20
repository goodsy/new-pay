package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.required.AuthorizationStatusRow
import com.paycore.payment.payment.application.required.CancellationHistoryRow
import com.paycore.payment.payment.application.required.PaymentStatusRow
import com.paycore.payment.payment.application.required.ReturnBalanceRow
import com.paycore.payment.payment.domain.ApprovedTerms
import com.paycore.payment.payment.domain.AuthorizationId
import com.paycore.payment.payment.domain.AuthorizationStatus
import com.paycore.payment.payment.domain.CancellationId
import com.paycore.payment.payment.domain.CancellationStatus
import com.paycore.payment.payment.domain.CardType
import com.paycore.payment.payment.domain.CurrencyCode
import com.paycore.payment.payment.domain.MerchantCancelId
import com.paycore.payment.payment.domain.MerchantOrderId
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.PaymentId
import com.paycore.payment.payment.domain.PaymentPlan
import com.paycore.payment.payment.domain.PaymentStatus
import com.paycore.payment.payment.domain.TerminationEvidence
import java.time.Instant
import java.time.LocalDate

/**
 * 결제 상태 조회 행 Named Fixture(CP-QUERY-001 S1, src/test 전용 — 순수 view 변환 시험용). 값은 업무 의미가 드러나게 고정하고(난수 없음), 각 상태는 쓰기 경로가 남기는 행 모양 그대로다
 * (Payment AUTHORIZING ⇔ Authorization OPEN 등 — AC1 §5.2). 일부러 어긋난 행은 이름이 `inconsistent…`로 시작하는 Invalid Fixture다(CQ-07 검출 시험용).
 */
object PaymentStatusRowFixture {
    val KRW = CurrencyCode("KRW")
    val CREATED_AT: Instant = Instant.parse("2026-10-20T01:02:03.456Z")
    val APPROVED_AT: Instant = Instant.parse("2026-10-20T01:03:00.000Z")
    val AUTH_ID = AuthorizationId("pau_01DEMOAUTHORIZATION000000")

    fun won(amount: Long) = Money(amount, KRW)

    val TERMS = ApprovedTerms(won(10_000), PaymentPlan.LumpSum, CardType.CREDIT, "12345678", "SIMPTX-DEMO-1", LocalDate.parse("2026-10-20"), null)

    fun row(
        status: PaymentStatus,
        authorization: AuthorizationStatusRow? = null,
        balance: ReturnBalanceRow? = null,
        cancellations: List<CancellationHistoryRow> = emptyList(),
        activeHold: Boolean = false,
    ) = PaymentStatusRow(
        paymentId = PaymentId("pay_01DEMOPAYMENT0000000000000"),
        merchantOrderId = MerchantOrderId("order-0001"),
        money = won(10_000),
        status = status,
        createdAt = CREATED_AT,
        activeHold = activeHold,
        authorization = authorization,
        balance = balance,
        cancellations = cancellations,
    )

    fun authorization(status: AuthorizationStatus, failure: TerminationEvidence? = null, approved: Boolean = status == AuthorizationStatus.AUTHORIZED) =
        AuthorizationStatusRow(AUTH_ID, status, failure, if (approved) TERMS else null, if (approved) APPROVED_AT else null)

    fun requested() = row(PaymentStatus.REQUESTED)

    fun expired() = row(PaymentStatus.EXPIRED)

    fun inProgress() = row(PaymentStatus.AUTHORIZING, authorization(AuthorizationStatus.OPEN))

    fun unknown(activeHold: Boolean = false) = row(PaymentStatus.UNKNOWN, authorization(AuthorizationStatus.UNKNOWN), activeHold = activeHold)

    fun failed(evidence: TerminationEvidence) = row(PaymentStatus.FAILED, authorization(AuthorizationStatus.FAILED, evidence))

    /** 승인됨, 취소 시도 없음(return_balance 지연 개설 전 — CC-02). */
    fun authorized(activeHold: Boolean = false) = row(PaymentStatus.AUTHORIZED, authorization(AuthorizationStatus.AUTHORIZED), activeHold = activeHold)

    fun cancellation(
        seq: Int,
        status: CancellationStatus,
        amount: Long,
        byOperator: Boolean = false,
        failure: TerminationEvidence? = null,
        openedAt: Instant = CREATED_AT.plusSeconds(600L + seq),
    ) = CancellationHistoryRow(
        cancellationId = CancellationId("pcn_01DEMOCANCEL00000000000$seq"),
        byOperator = byOperator,
        merchantCancelId = if (byOperator) null else MerchantCancelId("c-$seq"),
        status = status,
        money = won(amount),
        failureEvidence = failure,
        openedAt = openedAt,
        cancelTransactionId = if (status == CancellationStatus.COMPLETED) "ptx_01DEMOCANCELTX0000000000$seq" else null,
        cancelApprovalNumber = if (status == CancellationStatus.COMPLETED) "9000000$seq" else null,
        confirmedAt = if (status == CancellationStatus.COMPLETED) openedAt.plusSeconds(1) else null,
    )

    /** 승인 10,000: 가맹점 3,000 완료, 운영자 보정 1,000 완료, 가맹점 2,000 거절, 가맹점 2,000 진행 중(UNKNOWN). */
    fun authorizedWithHistory(): PaymentStatusRow = row(
        PaymentStatus.AUTHORIZED, authorization(AuthorizationStatus.AUTHORIZED),
        balance = ReturnBalanceRow(authorizedMinor = 10_000, completedMinor = 4_000, reservedMinor = 2_000, forcedMinor = 0),
        cancellations = listOf(
            cancellation(1, CancellationStatus.COMPLETED, 3_000),
            cancellation(2, CancellationStatus.COMPLETED, 1_000, byOperator = true),
            cancellation(3, CancellationStatus.FAILED, 2_000, failure = TerminationEvidence.INSTITUTION_CONFIRMED_DECLINE),
            cancellation(4, CancellationStatus.UNKNOWN, 2_000),
        ),
    )

    /**
     * 승인 10,000 전액 반환(완료 10,000) 뒤, 앞서 거절된 4,000 취소의 늦은 성공이 FORCED 4,000(초과)으로 기록된 Payment(D-CANCEL-006 — hold 활성).
     * 반환 가능액은 domain 식 max(0, …)로 0이다(F-1).
     */
    fun authorizedWithForcedExcess(): PaymentStatusRow = row(
        PaymentStatus.AUTHORIZED, authorization(AuthorizationStatus.AUTHORIZED),
        balance = ReturnBalanceRow(authorizedMinor = 10_000, completedMinor = 10_000, reservedMinor = 0, forcedMinor = 4_000),
        cancellations = listOf(
            cancellation(1, CancellationStatus.FAILED, 4_000, failure = TerminationEvidence.INSTITUTION_CONFIRMED_DECLINE),
            cancellation(2, CancellationStatus.COMPLETED, 10_000),
        ),
        activeHold = true,
    )

    /**
     * 승인 10,000, 거절된 4,000 취소의 늦은 성공이 FORCED 4,000으로 기록됐지만 승인액 안(초과 없음 — D-CANCEL-006, hold 활성). 반환 가능액은 FORCED를 뺀 권위 값 6,000이다(D-CPQ-005 —
     * 다른 필드에서 FORCED 금액을 추론할 수 있는 것은 허용된 추론).
     */
    fun authorizedWithForcedWithinBalance(): PaymentStatusRow = row(
        PaymentStatus.AUTHORIZED, authorization(AuthorizationStatus.AUTHORIZED),
        balance = ReturnBalanceRow(authorizedMinor = 10_000, completedMinor = 0, reservedMinor = 0, forcedMinor = 4_000),
        cancellations = listOf(cancellation(1, CancellationStatus.FAILED, 4_000, failure = TerminationEvidence.INSTITUTION_CONFIRMED_DECLINE)),
        activeHold = true,
    )

    /** P1(D-CANCEL-011): 가맹점에 FAILED로 알려진 Payment에 RECON이 늦은 승인 거래를 기록했고 운영자 보정 취소로 환불했다. */
    fun p1WithLateApprovalAndOperatorCancel(): PaymentStatusRow = row(
        PaymentStatus.FAILED,
        authorization(AuthorizationStatus.FAILED, TerminationEvidence.INSTITUTION_CONFIRMED_DECLINE, approved = true),
        balance = ReturnBalanceRow(authorizedMinor = 10_000, completedMinor = 10_000, reservedMinor = 0, forcedMinor = 0),
        cancellations = listOf(cancellation(1, CancellationStatus.COMPLETED, 10_000, byOperator = true)),
        activeHold = true,
    )

    /** Invalid: Payment AUTHORIZED인데 Authorization은 OPEN(한 스냅샷에서는 결함). */
    fun inconsistentAuthorizedWithOpenAuthorization() = row(PaymentStatus.AUTHORIZED, authorization(AuthorizationStatus.OPEN))

    /** Invalid: 반환 잔액의 완료 합계가 COMPLETED 취소 합과 다르다. */
    fun inconsistentCompletedTotal() = row(
        PaymentStatus.AUTHORIZED, authorization(AuthorizationStatus.AUTHORIZED),
        balance = ReturnBalanceRow(10_000, completedMinor = 5_000, reservedMinor = 0, forcedMinor = 0),
        cancellations = listOf(cancellation(1, CancellationStatus.COMPLETED, 3_000)),
    )

    /** Invalid: 진행 중 승인(AUTHORIZING)인 Payment에 취소 행이 있다. */
    fun inconsistentCancellationBeforeAuthorization() = row(
        PaymentStatus.AUTHORIZING, authorization(AuthorizationStatus.OPEN),
        cancellations = listOf(cancellation(1, CancellationStatus.OPEN, 1_000)),
    )
}

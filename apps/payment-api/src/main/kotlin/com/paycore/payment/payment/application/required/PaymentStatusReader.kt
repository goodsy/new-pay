package com.paycore.payment.payment.application.required

import com.paycore.payment.payment.domain.ApprovedTerms
import com.paycore.payment.payment.domain.AuthorizationId
import com.paycore.payment.payment.domain.AuthorizationStatus
import com.paycore.payment.payment.domain.CancellationId
import com.paycore.payment.payment.domain.CancellationStatus
import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.MerchantAccountId
import com.paycore.payment.payment.domain.MerchantCancelId
import com.paycore.payment.payment.domain.MerchantOrderId
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.PaymentId
import com.paycore.payment.payment.domain.PaymentStatus
import com.paycore.payment.payment.domain.TerminationEvidence
import java.time.Instant

/** Payment의 승인(있으면). [approved]·[approvedAt]은 승인 거래(AUTHORIZATION)가 있을 때만. */
data class AuthorizationStatusRow(
    val authorizationId: AuthorizationId,
    val status: AuthorizationStatus,
    val failureEvidence: TerminationEvidence?,
    val approved: ApprovedTerms?,
    val approvedAt: Instant?,
)

/** 반환 잔액 합계 열(`return_balance` — 첫 취소에서 지연 개설, 없으면 null). */
data class ReturnBalanceRow(val authorizedMinor: Long, val completedMinor: Long, val reservedMinor: Long, val forcedMinor: Long)

/** 취소 operation 한 건(+ 있으면 CANCEL 거래). [byOperator]면 [merchantCancelId]는 null. */
data class CancellationHistoryRow(
    val cancellationId: CancellationId,
    val byOperator: Boolean,
    val merchantCancelId: MerchantCancelId?,
    val status: CancellationStatus,
    val money: Money,
    val failureEvidence: TerminationEvidence?,
    val openedAt: Instant,
    val cancelTransactionId: String?,
    val cancelApprovalNumber: String?,
    val confirmedAt: Instant?,
)

/** 결제 상태 조회 행(비권위 — 한 읽기 스냅샷). [activeHold]는 그 Payment 자신의 해제되지 않은 사고 보류가 있는지. */
data class PaymentStatusRow(
    val paymentId: PaymentId,
    val merchantOrderId: MerchantOrderId,
    val money: Money,
    val status: PaymentStatus,
    val createdAt: Instant,
    val activeHold: Boolean,
    val authorization: AuthorizationStatusRow?,
    val balance: ReturnBalanceRow?,
    val cancellations: List<CancellationHistoryRow>,
)

/**
 * 결제 상태 조회 Port(CP-QUERY-001 §6.1 — jOOQ CQRS, 쓰기·잠금 없음, 비권위). 범위(environment·MID)는 **필수 인자**이며 구현은 SQL 조건으로 강제한다 — 범위 밖 행은 없음(null)과
 * 구별되지 않는다(CQ-02). 명령 판정에 쓰지 않는다(사용처는 조회 서비스뿐 — ArchUnit CQ-06).
 */
interface PaymentStatusReader {
    fun find(environment: Environment, merchantAccountId: MerchantAccountId, paymentId: PaymentId): PaymentStatusRow?
}

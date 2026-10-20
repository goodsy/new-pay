package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.provided.AuthorizationView
import com.paycore.payment.payment.application.provided.AuthorizationViewStatus
import com.paycore.payment.payment.application.provided.CancellationHistoryItem
import com.paycore.payment.payment.application.provided.CancellationInitiator
import com.paycore.payment.payment.application.provided.PaymentStatusInconsistent
import com.paycore.payment.payment.application.provided.PaymentStatusView
import com.paycore.payment.payment.application.provided.PaymentViewStatus
import com.paycore.payment.payment.application.provided.ReturnSummaryView
import com.paycore.payment.payment.application.required.AuthorizationStatusRow
import com.paycore.payment.payment.application.required.CancellationHistoryRow
import com.paycore.payment.payment.application.required.PaymentStatusRow
import com.paycore.payment.payment.domain.ApprovedTerms
import com.paycore.payment.payment.domain.AuthorizationStatus
import com.paycore.payment.payment.domain.CancellationStatus
import com.paycore.payment.payment.domain.PaymentStatus
import com.paycore.payment.payment.domain.ReturnBalance

/**
 * 결제 상태 조회 행 → 공개 view(CP-QUERY-001 §5.1·§4.4). 비권위 표시값이다.
 * - 상태: AC1 §5.1 Payment 요약 상태를 confirm 어휘로(AUTHORIZING = IN_PROGRESS, UNKNOWN 그대로 — CQ-12). FAILED 분류는 confirm과 같은 함수.
 * - 승인 terms·반환 요약·취소 이력은 AUTHORIZED일 때만(D-CPQ-002 — P1의 늦은 승인·운영자 취소는 숨긴다). 운영자 취소는 개시자 종류만.
 * - 반환 가능액·반환 상태는 domain 식([ReturnBalance.cancellableMinor] — F-1, 0 이상).
 * - CQ-07: 한 스냅샷에서 생길 수 없는 조합이면 view를 만들지 않는다([PaymentStatusInconsistent] — 규칙 이름만).
 */
internal object PaymentStatusViews {

    fun of(row: PaymentStatusRow): PaymentStatusView {
        val auth = row.authorization
        requireSummaryConsistent(row.status, auth)
        row.cancellations.forEach(::requireCancellationPairs)
        if (row.status != PaymentStatus.AUTHORIZED && row.status != PaymentStatus.FAILED && (row.cancellations.isNotEmpty() || row.balance != null)) {
            inconsistent("CANCELLATION_ON_UNCANCELLABLE")
        }
        val status = when (row.status) {
            PaymentStatus.REQUESTED -> PaymentViewStatus.REQUESTED
            PaymentStatus.AUTHORIZING -> PaymentViewStatus.IN_PROGRESS
            PaymentStatus.UNKNOWN -> PaymentViewStatus.UNKNOWN
            PaymentStatus.AUTHORIZED -> PaymentViewStatus.AUTHORIZED
            PaymentStatus.FAILED -> PaymentViewStatus.FAILED
            PaymentStatus.EXPIRED -> PaymentViewStatus.EXPIRED
        }
        val authorization = auth?.let { authorizationView(row, it) }
        val authorized = row.status == PaymentStatus.AUTHORIZED
        return PaymentStatusView(
            paymentId = row.paymentId,
            merchantOrderId = row.merchantOrderId,
            money = row.money,
            createdAt = row.createdAt,
            status = status,
            failureCategory = authorization?.failureCategory,
            onHold = row.activeHold,
            authorization = authorization,
            returnSummary = if (authorized) returnSummary(row, checkNotNull(auth?.approved)) else null,
            cancellations = if (authorized) row.cancellations.sortedWith(compareBy({ it.openedAt }, { it.cancellationId.value })).map(::historyItem) else emptyList(),
        )
    }

    /** AC1 §5.2 요약 일치: Payment 상태와 Authorization 상태는 같은 TX에서 함께 바뀐다. */
    private fun requireSummaryConsistent(status: PaymentStatus, auth: AuthorizationStatusRow?) {
        val consistent = when (status) {
            PaymentStatus.REQUESTED, PaymentStatus.EXPIRED -> auth == null
            PaymentStatus.AUTHORIZING -> auth?.status == AuthorizationStatus.OPEN
            PaymentStatus.UNKNOWN -> auth?.status == AuthorizationStatus.UNKNOWN
            PaymentStatus.AUTHORIZED -> auth?.status == AuthorizationStatus.AUTHORIZED && auth.approved != null && auth.approvedAt != null
            PaymentStatus.FAILED -> auth?.status == AuthorizationStatus.FAILED && auth.failureEvidence != null
        }
        if (!consistent) inconsistent("PAYMENT_AUTHORIZATION_SUMMARY")
    }

    private fun authorizationView(row: PaymentStatusRow, auth: AuthorizationStatusRow): AuthorizationView = when (auth.status) {
        AuthorizationStatus.AUTHORIZED -> AuthorizationView(row.paymentId, auth.authorizationId, AuthorizationViewStatus.AUTHORIZED, auth.approved, auth.approvedAt, null)
        // P1(D-CANCEL-011)의 늦은 승인 거래가 있어도 FAILED는 상태·분류만(D-CPQ-002).
        AuthorizationStatus.FAILED -> AuthorizationView(
            row.paymentId, auth.authorizationId, AuthorizationViewStatus.FAILED, null, null, AuthorizationEvents.failureCategory(checkNotNull(auth.failureEvidence)),
        )
        AuthorizationStatus.OPEN -> AuthorizationView(row.paymentId, auth.authorizationId, AuthorizationViewStatus.IN_PROGRESS, null, null, null)
        AuthorizationStatus.UNKNOWN -> AuthorizationView(row.paymentId, auth.authorizationId, AuthorizationViewStatus.UNKNOWN, null, null, null)
    }

    /**
     * 반환 요약. 잔액 행이 없으면(첫 취소 전 — CC-02) 취소도 없어야 하고 승인액 전부가 반환 가능하다. 있으면 합계 열 = 취소 이력 합(개시자 무관):
     * 완료 = COMPLETED 합, 진행 중 = OPEN·UNKNOWN 합(FORCED는 FAILED 취소에 붙으므로 두 합에 들지 않는다 — D-CANCEL-006).
     */
    private fun returnSummary(row: PaymentStatusRow, terms: ApprovedTerms): ReturnSummaryView {
        if (row.cancellations.any { it.money.currency != terms.money.currency }) inconsistent("CANCELLATION_CURRENCY")
        val balance = row.balance
        if (balance == null) {
            if (row.cancellations.isNotEmpty()) inconsistent("RETURN_BALANCE_MISSING")
            val authorized = terms.money.amountMinor
            return ReturnSummaryView(authorized, 0, 0, ReturnBalance.cancellableMinor(authorized, 0, 0, 0), ReturnBalance.returnStatus(authorized, 0))
        }
        if (balance.authorizedMinor != terms.money.amountMinor) inconsistent("RETURN_BALANCE_AUTHORIZED")
        val completed = row.cancellations.filter { it.status == CancellationStatus.COMPLETED }.sumOf { it.money.amountMinor }
        val inFlight = row.cancellations.filter { it.status == CancellationStatus.OPEN || it.status == CancellationStatus.UNKNOWN }.sumOf { it.money.amountMinor }
        if (balance.completedMinor != completed) inconsistent("RETURN_BALANCE_COMPLETED")
        if (balance.reservedMinor != inFlight) inconsistent("RETURN_BALANCE_RESERVED")
        return ReturnSummaryView(
            authorizedMinor = balance.authorizedMinor,
            cancelledMinor = balance.completedMinor,
            inProgressMinor = balance.reservedMinor,
            cancellableMinor = ReturnBalance.cancellableMinor(balance.authorizedMinor, balance.completedMinor, balance.reservedMinor, balance.forcedMinor),
            returnStatus = ReturnBalance.returnStatus(balance.authorizedMinor, balance.completedMinor),
        )
    }

    /**
     * 취소 행 하나의 짝 규칙(검토 #1 n-4 — view init의 결함 예외가 아니라 이름 있는 CQ-07 규칙): COMPLETED ⇔ CANCEL 거래·완료 시각 있음(V17 생성 열 UNIQUE·TX2 원자성),
     * FAILED ⇔ 종료 근거 있음(V15 CHECK), 가맹점 취소 ⇔ merchantCancelId 있음(V15 CHECK). 숨겨지는 행(P1)도 같은 스냅샷이므로 모두 검사한다.
     */
    private fun requireCancellationPairs(c: CancellationHistoryRow) {
        val completed = c.status == CancellationStatus.COMPLETED
        if (completed != (c.cancelTransactionId != null) || completed != (c.confirmedAt != null)) inconsistent("CANCELLATION_TRANSACTION")
        if ((c.status == CancellationStatus.FAILED) != (c.failureEvidence != null)) inconsistent("CANCELLATION_FAILURE_EVIDENCE")
        if (c.byOperator == (c.merchantCancelId != null)) inconsistent("CANCELLATION_INITIATOR_KEY")
    }

    private fun historyItem(c: CancellationHistoryRow): CancellationHistoryItem = CancellationHistoryItem(
        cancellationId = c.cancellationId,
        initiator = if (c.byOperator) CancellationInitiator.OPERATOR else CancellationInitiator.MERCHANT,
        merchantCancelId = c.merchantCancelId,
        status = CancellationViews.status(c.status),
        money = c.money,
        requestedAt = c.openedAt,
        cancelTransactionId = c.cancelTransactionId,
        cancelApprovalNumber = c.cancelApprovalNumber,
        cancelledAt = c.confirmedAt,
        failureCategory = c.failureEvidence?.let(CancellationViews::failureCategory),
    )

    private fun inconsistent(rule: String): Nothing = throw PaymentStatusInconsistent(rule)
}

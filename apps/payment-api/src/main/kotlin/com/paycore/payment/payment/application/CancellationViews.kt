package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.provided.CancelFailureCategory
import com.paycore.payment.payment.application.provided.CancellationView
import com.paycore.payment.payment.application.provided.CancellationViewStatus
import com.paycore.payment.payment.application.required.CancellationViewReader
import com.paycore.payment.payment.application.required.CancellationViewRow
import com.paycore.payment.payment.domain.CancellationId
import com.paycore.payment.payment.domain.CancellationStatus
import com.paycore.payment.payment.domain.ReturnBalance
import com.paycore.payment.payment.domain.TerminationEvidence

/** 취소 조회 행 → 응답 view(CANCEL-001 §4.1 — 상태·실패 분류·반환 후 잔액). 비권위 표시값이다. */
internal object CancellationViews {

    fun of(reader: CancellationViewReader, id: CancellationId): CancellationView = of(reader.find(id) ?: error("a started cancellation is stored"))

    fun of(row: CancellationViewRow): CancellationView = CancellationView(
        paymentId = row.paymentId,
        cancellationId = row.cancellationId,
        status = status(row.status),
        money = row.money,
        cancelTransactionId = row.cancelTransactionId,
        cancelApprovalNumber = row.cancelApprovalNumber,
        cancelledAt = row.confirmedAt,
        failureCategory = row.failureEvidence?.let(::failureCategory),
        // CP-QUERY-001 F-1: domain 식(max(0, …)) 하나만 쓴다 — FORCED 초과가 있어도 음수가 아니다.
        cancellableAmountMinor = ReturnBalance.cancellableMinor(row.authorizedMinor, row.completedMinor, row.reservedMinor, row.forcedMinor),
        returnStatus = ReturnBalance.returnStatus(row.authorizedMinor, row.completedMinor),
    )

    /** 취소 상태 → 공개 상태(CC §8). GetPayment 취소 이력도 이 함수를 쓴다(CP-QUERY-001 CQ-15). */
    fun status(status: CancellationStatus): CancellationViewStatus = when (status) {
        CancellationStatus.OPEN -> CancellationViewStatus.IN_PROGRESS
        CancellationStatus.UNKNOWN -> CancellationViewStatus.UNKNOWN
        CancellationStatus.COMPLETED -> CancellationViewStatus.COMPLETED
        CancellationStatus.FAILED -> CancellationViewStatus.FAILED
    }

    /** FAILED 취소 근거 → 고정 분류(CC §8). */
    fun failureCategory(evidence: TerminationEvidence): CancelFailureCategory = when (evidence) {
        TerminationEvidence.INSTITUTION_CONFIRMED_DECLINE -> CancelFailureCategory.DECLINED
        TerminationEvidence.INSTITUTION_CONFIRMED_NO_EFFECT -> CancelFailureCategory.NO_EFFECT
        TerminationEvidence.PRE_TRANSMISSION_FAILURE_CONFIRMED -> CancelFailureCategory.NOT_SENT
        else -> error("a cancellation fails only with a decline, no-effect or pre-transmission evidence")
    }
}

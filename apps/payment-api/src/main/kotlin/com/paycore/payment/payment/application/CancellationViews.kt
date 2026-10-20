package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.provided.CancelFailureCategory
import com.paycore.payment.payment.application.provided.CancellationView
import com.paycore.payment.payment.application.provided.CancellationViewStatus
import com.paycore.payment.payment.application.required.CancellationViewReader
import com.paycore.payment.payment.application.required.CancellationViewRow
import com.paycore.payment.payment.domain.CancellationId
import com.paycore.payment.payment.domain.CancellationStatus
import com.paycore.payment.payment.domain.ReturnStatus
import com.paycore.payment.payment.domain.TerminationEvidence

/** 취소 조회 행 → 응답 view(CANCEL-001 §4.1 — 상태·실패 분류·반환 후 잔액). 비권위 표시값이다. */
internal object CancellationViews {

    fun of(reader: CancellationViewReader, id: CancellationId): CancellationView = of(reader.find(id) ?: error("a started cancellation is stored"))

    fun of(row: CancellationViewRow): CancellationView {
        val status = when (row.status) {
            CancellationStatus.OPEN -> CancellationViewStatus.IN_PROGRESS
            CancellationStatus.UNKNOWN -> CancellationViewStatus.UNKNOWN
            CancellationStatus.COMPLETED -> CancellationViewStatus.COMPLETED
            CancellationStatus.FAILED -> CancellationViewStatus.FAILED
        }
        val failure = row.failureEvidence?.let {
            when (it) {
                TerminationEvidence.INSTITUTION_CONFIRMED_DECLINE -> CancelFailureCategory.DECLINED
                TerminationEvidence.INSTITUTION_CONFIRMED_NO_EFFECT -> CancelFailureCategory.NO_EFFECT
                TerminationEvidence.PRE_TRANSMISSION_FAILURE_CONFIRMED -> CancelFailureCategory.NOT_SENT
                else -> error("a cancellation fails only with a decline, no-effect or pre-transmission evidence")
            }
        }
        val cancellable = row.authorizedMinor - row.completedMinor - row.reservedMinor - row.forcedMinor
        val returnStatus = when {
            row.completedMinor == row.authorizedMinor -> ReturnStatus.FULLY_CANCELLED
            row.completedMinor > 0 -> ReturnStatus.PARTIALLY_CANCELLED
            else -> ReturnStatus.NONE
        }
        return CancellationView(
            paymentId = row.paymentId,
            cancellationId = row.cancellationId,
            status = status,
            money = row.money,
            cancelTransactionId = row.cancelTransactionId,
            cancelApprovalNumber = row.cancelApprovalNumber,
            cancelledAt = row.confirmedAt,
            failureCategory = failure,
            cancellableAmountMinor = cancellable,
            returnStatus = returnStatus,
        )
    }
}

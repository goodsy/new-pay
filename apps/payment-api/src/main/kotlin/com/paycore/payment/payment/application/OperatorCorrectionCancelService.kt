package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.provided.OperatorCancelTargetQuery
import com.paycore.payment.payment.application.provided.OperatorCancelTargetView
import com.paycore.payment.payment.application.provided.OperatorCorrectionCancelCommand
import com.paycore.payment.payment.application.provided.OperatorCorrectionCancelResult
import com.paycore.payment.payment.application.provided.OperatorCorrectionCancelUseCase
import com.paycore.payment.payment.application.required.CancellationViewReader
import com.paycore.payment.payment.domain.PaymentId
import org.springframework.stereotype.Service

/**
 * 운영자 보정 취소 조정자(CANCEL-001 §5.3 — TX를 열지 않는다, **진입점 없음** D-CANCEL1-001·ArchUnit m-5). TX1은 가맹점 경로와 같은 단계 서비스([CancelStartService])이고 멱등 기록 대신
 * approvalId 업무 키로 재요청을 판정한다(CN-10). (S6) 새 시작이면 가맹점 취소와 같은 전송·반영을 탄다. 승인 대상 조회([OperatorCancelTargetQuery])는 비권위 읽기다.
 */
@Service
class OperatorCorrectionCancelService(
    private val start: CancelStartService,
    private val dispatcher: CancellationDispatchService,
    private val views: CancellationViewReader,
) : OperatorCorrectionCancelUseCase, OperatorCancelTargetQuery {

    override fun cancel(command: OperatorCorrectionCancelCommand): OperatorCorrectionCancelResult = when (val o = start.startOperator(command)) {
        is OperatorCancelStartOutcome.Started -> {
            if (!o.existing) dispatcher.dispatch(o.cancellationId, EventCausation(command.correlationId.take(MAX_CORRELATION), o.cancellationId.value))
            OperatorCorrectionCancelResult.Accepted(CancellationViews.of(views, o.cancellationId), o.existing)
        }
        is OperatorCancelStartOutcome.Rejected -> OperatorCorrectionCancelResult.Rejected(o.code)
        OperatorCancelStartOutcome.NotFound -> OperatorCorrectionCancelResult.NotFound
    }

    override fun find(paymentId: String): OperatorCancelTargetView? {
        val id = runCatching { PaymentId(paymentId) }.getOrNull() ?: return null
        val row = views.findTarget(id) ?: return null
        val authorized = row.authorizedMoney
        return OperatorCancelTargetView(
            paymentId = row.paymentId,
            cancellable = authorized != null,
            currency = row.currency,
            cancellableMinor = authorized?.let { it.amountMinor - row.completedMinor - row.reservedMinor - row.forcedMinor } ?: 0,
            balanceRevision = row.balanceRevision,
        )
    }

    private companion object {
        const val MAX_CORRELATION = 128
    }
}

package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.provided.OperatorCancelTargetQuery
import com.paycore.payment.payment.application.provided.OperatorCancelTargetView
import com.paycore.payment.payment.application.provided.OperatorCorrectionCancelCommand
import com.paycore.payment.payment.application.provided.OperatorCorrectionCancelResult
import com.paycore.payment.payment.application.provided.OperatorCorrectionCancelUseCase
import com.paycore.payment.payment.application.provided.OperatorQueryFailure
import com.paycore.payment.payment.application.required.CancellationViewReader
import com.paycore.payment.payment.application.required.PersistenceFailure
import com.paycore.payment.payment.application.required.QueryTimedOut
import com.paycore.payment.payment.domain.PaymentId
import com.paycore.payment.payment.domain.ReturnBalance
import org.springframework.stereotype.Service

/**
 * 운영자 보정 취소 조정자(CANCEL-001 §5.3 — TX를 열지 않는다). 진입점은 서명 인증된 ADMIN 채널 adapter뿐이다(ADMIN-PAYMENT-OPS-001 §4.3·§4.8 — `AdminOperatorCancellationController`·
 * `OperatorCancelTargetController` 정확 FQN, ArchUnit). TX1은 가맹점 경로와 같은 단계 서비스([CancelStartService])이고 approvalId별 결정 기록으로 재요청을 판정한다(D-OPS-005). 새 시작이면
 * 가맹점 취소와 같은 전송·반영을 TX 밖에서 탄다. 승인 대상 조회([OperatorCancelTargetQuery])는 비권위 읽기다.
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
        is OperatorCancelStartOutcome.Rejected -> OperatorCorrectionCancelResult.Rejected(o.code, o.replayed)
        OperatorCancelStartOutcome.NotFound -> OperatorCorrectionCancelResult.NotFound
    }

    override fun find(paymentId: String): OperatorCancelTargetView? {
        val id = runCatching { PaymentId(paymentId) }.getOrNull() ?: return null
        val row = try {
            views.findTarget(id)
        } catch (e: QueryTimedOut) {
            throw OperatorQueryFailure.TimedOut(e)
        } catch (e: PersistenceFailure) {
            throw OperatorQueryFailure.Unavailable(e)
        } ?: return null
        val authorized = row.authorizedMoney
        return OperatorCancelTargetView(
            paymentId = row.paymentId,
            paymentStatus = row.paymentStatus,
            cancellable = authorized != null,
            currency = row.currency,
            authorizedMinor = authorized?.amountMinor,
            // F-1(ADMIN-PAYMENT-OPS-001, D-CPQ-005): domain 식 max(0, 승인 − 완료 − 예약 − FORCED) — FORCED 초과에서도 음수가 아니다.
            cancellableMinor = authorized?.let { ReturnBalance.cancellableMinor(it.amountMinor, row.completedMinor, row.reservedMinor, row.forcedMinor) } ?: 0,
            balanceRevision = row.balanceRevision,
            onHold = row.onHold,
            inFlightCancel = row.inFlightCancel,
        )
    }

    private companion object {
        const val MAX_CORRELATION = 128
    }
}

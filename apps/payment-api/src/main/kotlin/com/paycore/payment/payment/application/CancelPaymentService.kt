package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.provided.CancelOutcome
import com.paycore.payment.payment.application.provided.CancelPaymentCommand
import com.paycore.payment.payment.application.provided.CancelPaymentResult
import com.paycore.payment.payment.application.provided.CancelPaymentUseCase
import com.paycore.payment.payment.application.required.CancellationViewReader
import org.springframework.stereotype.Service

/**
 * 가맹점 취소 조정자(CANCEL-001 §4.2 — **TX를 열지 않는다**): TX1([CancelStartService]) → 새 시작이면 전송([CancellationDispatchService] — TXd·TX 밖 호출·TX2) → 취소 현재 상태 조회. 같은 키 재생·다른 키의 기존
 * 취소는 기관을 다시 부르지 않는다. 전송·반영이 이 요청 안에서 끝나지 않으면 202로 답하고 회복 Worker가 정리한다.
 */
@Service
class CancelPaymentService(
    private val start: CancelStartService,
    private val dispatcher: CancellationDispatchService,
    private val views: CancellationViewReader,
) : CancelPaymentUseCase {

    override fun cancel(command: CancelPaymentCommand): CancelPaymentResult = when (val outcome = start.start(command)) {
        is CancelStartOutcome.Accepted -> {
            // 새 시작만 보낸다 — 같은 키 재생·같은 merchantCancelId의 기존 취소는 기관을 다시 부르지 않는다(CC-05).
            if (!outcome.replayed && outcome.outcome == CancelOutcome.ACCEPTED) {
                dispatcher.dispatch(outcome.cancellationId, EventCausation(command.correlationId, outcome.cancellationId.value))
            }
            CancelPaymentResult.Accepted(CancellationViews.of(views, outcome.cancellationId), outcome.replayed)
        }
        is CancelStartOutcome.Rejected -> CancelPaymentResult.Rejected(outcome.rejection, outcome.paymentId, outcome.replayed)
        CancelStartOutcome.NotFound -> CancelPaymentResult.NotFound
        CancelStartOutcome.IdempotencyKeyReused -> CancelPaymentResult.IdempotencyKeyReused
    }
}

package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.provided.AuthorizationView
import com.paycore.payment.payment.application.provided.AuthorizationViewStatus
import com.paycore.payment.payment.application.provided.ConfirmOutcome
import com.paycore.payment.payment.application.provided.ConfirmPaymentCommand
import com.paycore.payment.payment.application.provided.ConfirmPaymentResult
import com.paycore.payment.payment.application.provided.ConfirmPaymentUseCase
import com.paycore.payment.payment.application.required.AuthorizationViewReader
import com.paycore.payment.payment.domain.AuthorizationId
import com.paycore.payment.payment.domain.AuthorizationStatus
import org.springframework.stereotype.Service

/**
 * confirm 조정자(AUTH-001 §4.2 — **TX를 열지 않는다**): TX1([ConfirmStartService]) → 새 시작이면 전송([AuthorizationDispatchService] — TXd·TX 밖 호출·TX2) → 승인 현재 상태 조회.
 * 같은 키 재생·다른 키 재사용은 기관을 다시 부르지 않는다(시도 §5). 전송·반영이 이 요청 안에서 끝나지 않으면(UNKNOWN·TX2 실패·commit 불명확) 202로 답하고 회복 Worker가 정리한다.
 */
@Service
class ConfirmPaymentService(
    private val start: ConfirmStartService,
    private val dispatcher: AuthorizationDispatchService,
    private val views: AuthorizationViewReader,
) : ConfirmPaymentUseCase {

    override fun confirm(command: ConfirmPaymentCommand): ConfirmPaymentResult = when (val outcome = start.start(command)) {
        is ConfirmStartOutcome.Accepted -> {
            if (!outcome.replayed && outcome.code == ConfirmOutcome.ACCEPTED) {
                dispatcher.dispatch(outcome.authorizationId, EventCausation(command.correlationId, outcome.authorizationId.value))
            }
            ConfirmPaymentResult.Accepted(view(outcome.authorizationId), outcome.replayed)
        }
        is ConfirmStartOutcome.Rejected -> ConfirmPaymentResult.Rejected(outcome.rejection, outcome.paymentId, outcome.detail, outcome.replayed)
        ConfirmStartOutcome.NotFound -> ConfirmPaymentResult.NotFound
        ConfirmStartOutcome.IdempotencyKeyReused -> ConfirmPaymentResult.IdempotencyKeyReused
    }

    private fun view(authorizationId: AuthorizationId): AuthorizationView {
        val row = views.find(authorizationId) ?: error("an accepted confirm refers to a stored authorization")
        return when (row.status) {
            AuthorizationStatus.AUTHORIZED -> AuthorizationView(row.paymentId, row.authorizationId, AuthorizationViewStatus.AUTHORIZED, row.approved, row.approvedAt, null)
            AuthorizationStatus.FAILED -> AuthorizationView(
                row.paymentId, row.authorizationId, AuthorizationViewStatus.FAILED, null, null, AuthorizationEvents.failureCategory(checkNotNull(row.failureEvidence)),
            )
            AuthorizationStatus.OPEN -> AuthorizationView(row.paymentId, row.authorizationId, AuthorizationViewStatus.IN_PROGRESS, null, null, null)
            AuthorizationStatus.UNKNOWN -> AuthorizationView(row.paymentId, row.authorizationId, AuthorizationViewStatus.UNKNOWN, null, null, null)
        }
    }
}

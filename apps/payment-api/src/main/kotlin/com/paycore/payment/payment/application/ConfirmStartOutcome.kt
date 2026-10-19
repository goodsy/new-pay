package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.provided.ConfirmOutcome
import com.paycore.payment.payment.application.provided.ConfirmRejection
import com.paycore.payment.payment.domain.AuthorizationId
import com.paycore.payment.payment.domain.PaymentId

/** confirm TX1 결과(커밋된 저장 결과 또는 저장 없는 판정). [ConfirmStartService]가 돌려주고 confirm 조정자가 전송·응답을 정한다. */
sealed interface ConfirmStartOutcome {
    /** ACCEPTED(새 시작 — 전송 대상) 또는 진행 중 승인 재사용. [replayed]면 같은 키의 저장 결과 재생. */
    data class Accepted(val paymentId: PaymentId, val authorizationId: AuthorizationId, val code: ConfirmOutcome, val replayed: Boolean) : ConfirmStartOutcome

    data class Rejected(val rejection: ConfirmRejection, val paymentId: PaymentId, val detail: String?, val replayed: Boolean) : ConfirmStartOutcome

    /** Payment 없음·다른 MID/환경·허용 판정 NotFoundOrForbidden(404 — 저장 없음). */
    data object NotFound : ConfirmStartOutcome

    /** 같은 키·다른 내용(409). */
    data object IdempotencyKeyReused : ConfirmStartOutcome
}

package com.paycore.payment.payment.application.provided

/** 결제창 취소 Use Case(AUTHN-001 §6.1 — 취소 TX: payment → authentication_session → checkout_session). */
interface CancelCheckoutUseCase {
    fun cancel(command: CancelCheckoutCommand): CancelCheckoutResult
}

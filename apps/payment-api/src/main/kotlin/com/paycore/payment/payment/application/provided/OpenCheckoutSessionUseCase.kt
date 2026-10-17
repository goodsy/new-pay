package com.paycore.payment.payment.application.provided

/** 결제창 토큰 교환 Use Case(AUTHN-001 §4.1 TX-X — payment(X) → checkout_session(X) → checkout_token(X)). 토큰은 1회만 교환된다(C1). */
interface OpenCheckoutSessionUseCase {
    fun open(command: OpenCheckoutSessionCommand): OpenCheckoutSessionResult
}

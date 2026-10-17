package com.paycore.payment.payment.application.provided

/** 결제창 토큰 발급 업무 거절(공개 code는 web이 고정 문구로 만든다, AUTHN-001 §5.3). [CHECKOUT_UNAVAILABLE]만 재시도 가능하다. */
enum class CheckoutTokenRejection {
    INVALID_RETURN_URL,
    PAYMENT_NOT_FOUND,
    CHECKOUT_NOT_SUPPORTED,
    RETURN_URL_NOT_ALLOWED,
    ORDER_ON_HOLD,
    PAYMENT_TERMINATED,
    PAYMENT_AUTHORIZATION_STARTED,
    CHECKOUT_UNAVAILABLE,
}

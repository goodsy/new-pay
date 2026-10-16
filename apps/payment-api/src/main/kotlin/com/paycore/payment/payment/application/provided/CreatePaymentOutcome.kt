package com.paycore.payment.payment.application.provided

/** 성공 결과 종류(CP-CONTRACT-001 §5 outcome). */
enum class CreatePaymentOutcome {
    /** 이 요청이 새 Payment를 만들었다. */
    CREATED,

    /** 다른 키·같은 주문·같은 고정 입력의 활성 시도를 재사용했다. */
    EXISTING_ACTIVE_PAYMENT,
}

package com.paycore.financial.paymentfact.domain

/** Inbox에 저장하는 처리 결과. DUPLICATE는 저장하지 않는 응답 값이다(수신 Use Case 결과). */
enum class PaymentFactInboxOutcome { APPLIED, HELD, UNSUPPORTED }

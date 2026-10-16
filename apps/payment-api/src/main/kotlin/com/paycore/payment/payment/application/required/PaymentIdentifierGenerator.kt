package com.paycore.payment.payment.application.required

import com.paycore.payment.payment.application.provided.NewPaymentIdentifiers

/**
 * 서버 생성 식별자(CP-API-001 명세 §3.4): paymentId `pay_` + ULID 26자, paymentKey `pk_` + 256bit base64url, 멱등 기록 ID·eventId UUID v4.
 * 매 호출이 새 값 묶음을 만든다.
 */
interface PaymentIdentifierGenerator {
    fun newIdentifiers(): NewPaymentIdentifiers
}

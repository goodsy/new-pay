package com.paycore.payment.payment.application.provided

import com.paycore.payment.payment.domain.IdempotencyKey
import com.paycore.payment.payment.domain.OrderIntent
import com.paycore.payment.payment.domain.OrderKey
import com.paycore.payment.payment.domain.PaymentId
import com.paycore.payment.payment.domain.PaymentKey
import com.paycore.payment.payment.domain.PaymentMethod
import java.time.Instant

/** 서버가 생성해 넘기는 새 식별자(생성기·형식은 CP-API-001). 새 Payment를 만들지 않는 경로에서는 쓰이지 않는다. */
data class NewPaymentIdentifiers(
    val paymentId: PaymentId,
    val paymentKey: PaymentKey,
    val idempotencyRecordId: IdempotencyRecordId,
    val eventId: EventId,
)

/**
 * 검증된 CreatePayment 입력. [orderKey]의 MID는 인증 MID(body MID와 같음을 호출자가 확인), environment는 Credential에서 온다.
 * [requestedAt]은 서버 clock(ms로 잘라 저장한다). [orderName]은 표시용(고정 입력 아님, 이벤트 payload 제외).
 */
data class CreatePaymentCommand(
    val orderKey: OrderKey,
    val orderIntent: OrderIntent,
    val paymentMethod: PaymentMethod,
    val orderName: String?,
    val idempotencyKey: IdempotencyKey,
    val requestedAt: Instant,
    val correlationId: String,
    val newIdentifiers: NewPaymentIdentifiers,
    /** 요청한 인증 주체(Credential keyId, D-CPA-001). 새 Payment의 생성 행위자로 남는다(Auditability). */
    val actor: CredentialKeyId,
) {
    init {
        require(correlationId.length in 1..MAX_CORRELATION_ID && correlationId.all { it in '!'..'~' }) {
            "correlationId must be 1..$MAX_CORRELATION_ID printable ASCII characters"
        }
        // orderName 규칙(CP-API-001 명세 §4.4): code point 1..100(영속 VARCHAR(100) utf8mb4와 같은 단위), 짝 없는 서로게이트·제어문자 거절.
        // web adapter가 같은 규칙으로 먼저 400을 만든다. 여기는 최후 방어선이다.
        require(orderName == null || OrderNameRule.isValid(orderName)) { "orderName violates the order name rule" }
    }

    private companion object {
        const val MAX_CORRELATION_ID = 128
    }
}

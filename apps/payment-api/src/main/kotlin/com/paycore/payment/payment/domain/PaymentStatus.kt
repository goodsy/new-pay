package com.paycore.payment.payment.domain

import com.paycore.payment.payment.domain.PaymentStatus.EXPIRED
import com.paycore.payment.payment.domain.PaymentStatus.FAILED

/**
 * Payment 고객 시도 상태(확정 문서 §2). 활성 = REQUESTED·AUTHORIZING·UNKNOWN(REQUESTED 포함 주문당 하나).
 * 취소/환불 상태는 별도 요약이며 이 생명주기를 바꾸지 않는다(AUTHORIZED는 반환 후에도 성공 이력).
 */
enum class PaymentStatus { REQUESTED, AUTHORIZING, UNKNOWN, AUTHORIZED, FAILED, EXPIRED }

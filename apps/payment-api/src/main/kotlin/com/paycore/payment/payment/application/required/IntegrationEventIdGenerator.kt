package com.paycore.payment.payment.application.required

import com.paycore.payment.payment.application.provided.EventId

/**
 * 통합 이벤트 ID 생성(UUID v4, CP-API-001 §3.4와 같은 형식). 생성 요청에 미리 만든 식별자 묶음([PaymentIdentifierGenerator])이 없는 경로 — 만료 이벤트
 * (PaymentExpired, AUTHN-001 §7.5.7)처럼 TX 안에서 필요할 때만 만드는 이벤트 — 가 쓴다.
 */
interface IntegrationEventIdGenerator {
    fun newEventId(): EventId
}

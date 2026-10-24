package com.paycore.payment.payment.application.required

import com.paycore.payment.payment.domain.MerchantWebhookEventId

/** 저장된 가맹점 이벤트(보낼 바이트 그대로 + 저장 당시 SHA-256 소문자 hex — n-4). */
class StoredMerchantWebhookEvent(val eventId: MerchantWebhookEventId, val eventType: String, val body: ByteArray, val bodySha256: String) {
    override fun toString(): String = "StoredMerchantWebhookEvent(eventId=${eventId.value}, eventType=$eventType)"
}

/** 가맹점 이벤트 읽기 Port(전송 직전 — 자기 읽기 전용 TX). */
interface MerchantWebhookEventReader {
    fun find(id: MerchantWebhookEventId): StoredMerchantWebhookEvent?
}

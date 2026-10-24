package com.paycore.payment.payment.application.required

/** 서명 헤더 Port(D-WH-003 — HMAC 계산은 adapter: application은 JCE를 쓰지 않는다, F-7). [timestampSeconds]는 주입 시계의 Unix 초. */
interface MerchantWebhookSigner {
    fun signatureHeader(timestampSeconds: Long, body: ByteArray, keys: List<WebhookSigningKey>): String
}

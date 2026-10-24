package com.paycore.payment.payment.domain

/**
 * 가맹점 Webhook 서버 생성 식별자(WEBHOOK-001 WH-05): 접두 + ULID 26자(Crockford base32 — I·L·O·U 없음). 공개 이벤트 ID는 Outbox `event_id`(내부)와 다르다.
 * 예외 메시지에 값을 넣지 않는다.
 */
private val ULID_BODY = Regex("^[0-9A-HJKMNP-TV-Z]{26}$")

private fun requireServerId(value: String, prefix: String, field: String) {
    require(value.startsWith(prefix) && ULID_BODY.matches(value.substring(prefix.length))) { "$field must be '$prefix' followed by a 26-character ULID" }
}

/** 가맹점 이벤트 ID(`whe_` — 재시도·재전송 내내 같다). */
@JvmInline
value class MerchantWebhookEventId(val value: String) {
    init {
        requireServerId(value, "whe_", "webhookEventId")
    }
}

/** 전달 ID(`whd_`). v1은 이벤트당 전달 하나((MID, 환경)당 endpoint 하나 — D-WH-004). */
@JvmInline
value class MerchantWebhookDeliveryId(val value: String) {
    init {
        requireServerId(value, "whd_", "webhookDeliveryId")
    }
}

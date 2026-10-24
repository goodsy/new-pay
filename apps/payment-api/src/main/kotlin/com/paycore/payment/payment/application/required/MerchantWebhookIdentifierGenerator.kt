package com.paycore.payment.payment.application.required

import com.paycore.payment.payment.domain.MerchantWebhookDeliveryId
import com.paycore.payment.payment.domain.MerchantWebhookEventId
import java.time.Instant

/** 가맹점 Webhook 식별자 생성 Port(`whe_`·`whd_` + ULID — WH-05). */
interface MerchantWebhookIdentifierGenerator {
    fun eventId(at: Instant): MerchantWebhookEventId

    fun deliveryId(at: Instant): MerchantWebhookDeliveryId
}

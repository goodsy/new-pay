package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.required.MerchantWebhookIdentifierGenerator
import com.paycore.payment.payment.domain.MerchantWebhookDeliveryId
import com.paycore.payment.payment.domain.MerchantWebhookEventId
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.time.Instant

/** 가맹점 Webhook 식별자(JDK SecureRandom ULID — 시각 48bit + 난수 80bit, 다른 생성기와 같은 규칙, WH-05). */
@Component
class SecureRandomMerchantWebhookIdentifierGenerator : MerchantWebhookIdentifierGenerator {
    private val random = SecureRandom()

    override fun eventId(at: Instant): MerchantWebhookEventId = MerchantWebhookEventId("whe_" + ulid(at))

    override fun deliveryId(at: Instant): MerchantWebhookDeliveryId = MerchantWebhookDeliveryId("whd_" + ulid(at))

    private fun ulid(at: Instant): String {
        val out = CharArray(LENGTH)
        var t = at.toEpochMilli()
        for (i in TIME_CHARS - 1 downTo 0) {
            out[i] = ALPHABET[(t and MASK).toInt()]
            t = t ushr BITS
        }
        for (i in TIME_CHARS until LENGTH) out[i] = ALPHABET[random.nextInt(ALPHABET.length)]
        return String(out)
    }

    private companion object {
        const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        const val LENGTH = 26
        const val TIME_CHARS = 10
        const val BITS = 5
        const val MASK = 0x1FL
    }
}

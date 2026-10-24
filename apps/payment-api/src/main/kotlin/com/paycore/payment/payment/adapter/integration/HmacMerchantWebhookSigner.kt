package com.paycore.payment.payment.adapter.integration

import com.paycore.payment.payment.application.required.MerchantWebhookSigner
import com.paycore.payment.payment.application.required.WebhookSigningKey
import org.springframework.stereotype.Component
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 가맹점 Webhook 서명(WEBHOOK-001 §21.0, D-WH-003, WH-19·WH-20, 검토 m-8). 헤더 = `t=<Unix 초>` 다음 활성 키 순서대로 `,kid=<id>,v1=<64 소문자 hex>`(최대 2쌍, 공백 없음).
 * HMAC-SHA256 키 = 비밀 문자열 전체의 UTF-8 바이트([WebhookSigningKey.secret]), 서명 대상 = t의 ASCII 숫자 + `.` + 본문 바이트 그대로. 비밀·서명 값을 로그·예외에 넣지 않는다.
 */
@Component
class HmacMerchantWebhookSigner : MerchantWebhookSigner {

    override fun signatureHeader(timestampSeconds: Long, body: ByteArray, keys: List<WebhookSigningKey>): String {
        require(timestampSeconds >= 0) { "signature timestamp must not be negative" }
        require(keys.size in 1..MAX_KEYS) { "1..$MAX_KEYS active signing keys" }
        keys.forEach { require(KEY_ID.matches(it.keyId)) { "signing key id must be 1..40 characters of [A-Za-z0-9_-]" } }
        val prefix = "$timestampSeconds.".toByteArray(Charsets.US_ASCII)
        return buildString {
            append("t=").append(timestampSeconds)
            keys.forEach { k ->
                val mac = Mac.getInstance(ALGORITHM).apply { init(SecretKeySpec(k.secret, ALGORITHM)) }
                mac.update(prefix)
                append(",kid=").append(k.keyId).append(",v1=").append(HexFormat.of().formatHex(mac.doFinal(body)))
            }
        }
    }

    private companion object {
        const val ALGORITHM = "HmacSHA256"
        const val MAX_KEYS = 2
        val KEY_ID = Regex("^[A-Za-z0-9_-]{1,40}$")
    }
}

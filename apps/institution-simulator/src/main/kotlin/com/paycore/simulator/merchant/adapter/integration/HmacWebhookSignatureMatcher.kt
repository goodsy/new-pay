package com.paycore.simulator.merchant.adapter.integration

import com.paycore.simulator.merchant.application.required.WebhookSignatureMatcher
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * [WebhookSignatureMatcher] JCE 구현(WEBHOOK-001 §21.0, WH-19·WH-20, 검토 m-8): 기대 HMAC-SHA256을 계산해 받은 서명(소문자 hex — 문법은 domain이 먼저 확인)의 바이트와
 * `MessageDigest.isEqual`(상수 시간)로 비교한다. 비밀·서명 값을 로그·예외에 넣지 않는다.
 */
@Component
class HmacWebhookSignatureMatcher : WebhookSignatureMatcher {

    override fun matches(secret: ByteArray, timestampSeconds: Long, body: ByteArray, v1Hex: String): Boolean {
        val presented = try {
            HexFormat.of().parseHex(v1Hex)
        } catch (e: IllegalArgumentException) {
            return false
        }
        val mac = Mac.getInstance(ALGORITHM).apply { init(SecretKeySpec(secret, ALGORITHM)) }
        mac.update("$timestampSeconds.".toByteArray(Charsets.US_ASCII))
        return MessageDigest.isEqual(mac.doFinal(body), presented)
    }

    private companion object {
        const val ALGORITHM = "HmacSHA256"
    }
}

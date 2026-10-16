package com.paycore.payment.entitlement.adapter.web

import java.security.MessageDigest
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** 봉투 서명 검증(TX 밖, 명세 §5.1). 헤더 `PayCore-Signature: keyId=<id>,v1=<hex HMAC-SHA256(key, 본문 바이트)>`. 비교는 상수 시간. */
object EntitlementSignature {
    const val HEADER = "PayCore-Signature"
    private val FORMAT = Regex("^keyId=([A-Za-z0-9._-]{1,32}),v1=([0-9a-f]{64})$")

    fun verify(settings: EntitlementInboxSettings, header: String?, body: ByteArray): Boolean {
        val match = header?.let { FORMAT.matchEntire(it) } ?: return false
        val key = settings.key(match.groupValues[1]) ?: return false
        val expected = hmac(key, body)
        return MessageDigest.isEqual(expected, HexFormat.of().parseHex(match.groupValues[2]))
    }

    fun hmac(key: ByteArray, body: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(body)
}

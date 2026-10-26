package com.paycore.admin.support

import java.nio.ByteBuffer
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** 시험 쪽 독립 TOTP 계산(RFC 6238 SHA-1·6자리·30초) — 운영 검증기를 쓰지 않는다. `otpauth` URI의 base32 시크릿을 풀어 코드를 만든다. */
object TestTotp {
    fun secretOf(otpauthUri: String): ByteArray = decodeBase32(Regex("[?&]secret=([A-Z2-7]+)").find(otpauthUri)!!.groupValues[1])

    fun code(secret: ByteArray, at: Instant = Instant.now()): String {
        val step = at.epochSecond / 30
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(secret, "HmacSHA1")) }.doFinal(ByteBuffer.allocate(8).putLong(step).array())
        val o = mac.last().toInt() and 0x0F
        val bin = ((mac[o].toInt() and 0x7F) shl 24) or ((mac[o + 1].toInt() and 0xFF) shl 16) or ((mac[o + 2].toInt() and 0xFF) shl 8) or (mac[o + 3].toInt() and 0xFF)
        return (bin % 1_000_000).toString().padStart(6, '0')
    }

    fun decodeBase32(text: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in text) {
            buffer = (buffer shl 5) or alphabet.indexOf(c)
            bits += 5
            if (bits >= 8) {
                out.write((buffer shr (bits - 8)) and 0xFF)
                bits -= 8
            }
        }
        return out.toByteArray()
    }
}

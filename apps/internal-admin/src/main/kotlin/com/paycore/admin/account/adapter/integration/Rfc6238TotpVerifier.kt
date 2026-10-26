package com.paycore.admin.account.adapter.integration

import com.paycore.admin.account.application.required.TotpVerifier
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * RFC 6238 TOTP(SHA-1·6자리·30초, RFC 4226 동적 절단) 검증 — Spring Security 내장 수단이 없어 자체 구현(F-12, D-04).
 * 비교는 상수 시간([MessageDigest.isEqual]), 창 안의 모든 단계를 끝까지 계산한다(맞는 단계를 찾아도 멈추지 않음 — 시간차 축소).
 * 시험: AccountCryptoAdaptersSpec(RFC golden — harness가 Python hmac으로 독립 확인).
 */
class Rfc6238TotpVerifier : TotpVerifier {

    override fun stepAt(at: Instant): Long = Math.floorDiv(at.epochSecond, PERIOD_SECONDS)

    override fun matchingStep(secret: ByteArray, code: String, at: Instant, windowSteps: Int): Long? {
        require(windowSteps in 0..MAX_WINDOW) { "TOTP window must be 0-$MAX_WINDOW steps" }
        if (!CODE.matches(code)) return null
        val expected = code.toByteArray(Charsets.US_ASCII)
        val current = stepAt(at)
        var matched: Long? = null
        for (step in (current - windowSteps)..(current + windowSteps)) {
            if (step < 0) continue
            if (MessageDigest.isEqual(codeAt(secret, step).toByteArray(Charsets.US_ASCII), expected) && matched == null) matched = step
        }
        return matched
    }

    private fun codeAt(secret: ByteArray, step: Long): String {
        val mac = Mac.getInstance(ALGORITHM).apply { init(SecretKeySpec(secret, ALGORITHM)) }.doFinal(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(step).array())
        val offset = mac.last().toInt() and 0x0F
        val binary = ((mac[offset].toInt() and 0x7F) shl 24) or ((mac[offset + 1].toInt() and 0xFF) shl 16) or
            ((mac[offset + 2].toInt() and 0xFF) shl 8) or (mac[offset + 3].toInt() and 0xFF)
        return (binary % MODULUS).toString().padStart(DIGITS, '0')
    }

    private companion object {
        const val ALGORITHM = "HmacSHA1"
        const val PERIOD_SECONDS = 30L
        const val DIGITS = 6
        const val MODULUS = 1_000_000
        const val MAX_WINDOW = 3
        val CODE = Regex("^[0-9]{6}$")
    }
}

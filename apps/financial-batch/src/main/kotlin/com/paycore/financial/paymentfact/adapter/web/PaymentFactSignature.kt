package com.paycore.financial.paymentfact.adapter.web

import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * PAYMENT → FINANCIAL 결제 사실 채널 서명 v1(FIN-FACT-RELAY-001 §4.1 — 계약은 golden `payment-fact-relay-v1.golden.json`, 두 앱 코드 공유 없음).
 *
 * - 헤더 `PayCore-Fact-Signature: t=<unix 초>,kid=<keyId>,v1=<64 소문자 hex>` — 전체 문법 일치만(공백·순서 변경·추가 필드·대문자 hex 거부).
 * - canonical = `paycore.payment-fact.v1` LF `POST` LF `/internal/v1/payment-facts` LF t(헤더 숫자 그대로) LF hex(SHA-256(본문)). UTF-8, 끝 개행 없음.
 * - v1 = hex(HMAC-SHA256(Base64 디코드 키, canonical)). 비교는 상수 시간([MessageDigest.isEqual]).
 * - 판정 순서: 문법 → 시각 창(경계 포함) → kid → 서명. 거절 사유는 응답에서 구별하지 않는다(모두 401).
 * nonce는 없다: 같은 본문 재생은 Inbox가 eventId·effect key로 멱등 처리한다(DUPLICATE). 시각 창은 오래된 서명 본문의 재사용 기간을 줄인다.
 */
object PaymentFactSignature {
    const val HEADER = "PayCore-Fact-Signature"
    const val CANONICAL_TAG = "paycore.payment-fact.v1"
    const val METHOD = "POST"
    const val PATH = "/internal/v1/payment-facts"
    private val FORMAT = Regex("^t=([0-9]{1,10}),kid=([A-Za-z0-9._-]{1,32}),v1=([0-9a-f]{64})$")
    private val HEX: HexFormat = HexFormat.of()

    fun canonical(t: String, body: ByteArray): String =
        listOf(CANONICAL_TAG, METHOD, PATH, t, HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(body))).joinToString("\n")

    fun verify(header: String?, body: ByteArray, nowEpochSecond: Long, window: Duration, keyOf: (String) -> ByteArray?): Verification {
        val match = header?.let { FORMAT.matchEntire(it) } ?: return Verification.Rejected(Rejection.MALFORMED)
        val (t, kid, presented) = match.destructured
        if (abs(nowEpochSecond - t.toLong()) > window.seconds) return Verification.Rejected(Rejection.STALE)
        val key = keyOf(kid) ?: return Verification.Rejected(Rejection.UNKNOWN_KID)
        val expected = mac(key, canonical(t, body))
        if (!MessageDigest.isEqual(expected, HEX.parseHex(presented))) return Verification.Rejected(Rejection.SIGNATURE)
        return Verification.Accepted(kid)
    }

    private fun mac(key: ByteArray, canonical: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(canonical.toByteArray(Charsets.UTF_8))

    sealed interface Verification {
        data class Accepted(val keyId: String) : Verification

        data class Rejected(val reason: Rejection) : Verification
    }

    enum class Rejection { MALFORMED, STALE, UNKNOWN_KID, SIGNATURE }
}

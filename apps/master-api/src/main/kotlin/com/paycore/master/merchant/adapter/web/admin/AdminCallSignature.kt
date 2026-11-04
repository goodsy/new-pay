package com.paycore.master.merchant.adapter.web.admin

import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * ADMIN → MASTER 내부 호출 서명 v1 검증(MERCHANT-OPS-001 §3 — ADMIN-PAYMENT-OPS-001 §4.2·D-OPS-001과 **같은 계약**, golden `admin-call-signature-v1.golden.json`을
 * 세 앱이 같은 바이트로 갖는다. 코드 공유 없음 — 앱마다 구현하고 golden으로 맞춘다).
 *
 * - 헤더 `PayCore-Admin-Signature: t=<unix 초>,kid=<keyId>,v1=<64 소문자 hex>` — 전체 문법 일치만.
 * - canonical = `paycore.admin-call.v1` LF METHOD LF 정규화 경로 LF t LF hex(SHA-256(본문)). UTF-8, 끝 개행 없음, GET 본문 = 빈 바이트.
 * - v1 = hex(HMAC-SHA256(Base64 디코드 키, canonical)), 상수 시간 비교. 판정 순서: 문법 → 시각 창(경계 포함) → kid → 서명.
 * nonce는 없다(이 채널은 읽기 전용 — 재전송해도 부작용이 없다).
 */
object AdminCallSignature {
    const val HEADER = "PayCore-Admin-Signature"
    const val CANONICAL_TAG = "paycore.admin-call.v1"
    private val FORMAT = Regex("^t=([0-9]{1,10}),kid=([A-Za-z0-9._-]{1,32}),v1=([0-9a-f]{64})$")
    private val HEX: HexFormat = HexFormat.of()

    fun canonical(method: String, path: String, t: String, body: ByteArray): String =
        listOf(CANONICAL_TAG, method, path, t, HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(body))).joinToString("\n")

    fun v1(key: ByteArray, method: String, path: String, t: String, body: ByteArray): String = HEX.formatHex(mac(key, canonical(method, path, t, body)))

    @Suppress("LongParameterList")
    fun verify(
        header: String?,
        method: String,
        path: String,
        body: ByteArray,
        nowEpochSecond: Long,
        window: Duration,
        keyOf: (String) -> ByteArray?,
    ): AdminCallVerification {
        val match = header?.let { FORMAT.matchEntire(it) } ?: return AdminCallVerification.Rejected(AdminCallRejection.MALFORMED)
        val (t, kid, presented) = match.destructured
        if (abs(nowEpochSecond - t.toLong()) > window.seconds) return AdminCallVerification.Rejected(AdminCallRejection.STALE)
        val key = keyOf(kid) ?: return AdminCallVerification.Rejected(AdminCallRejection.UNKNOWN_KID)
        val expected = mac(key, canonical(method, path, t, body))
        if (!MessageDigest.isEqual(expected, HEX.parseHex(presented))) return AdminCallVerification.Rejected(AdminCallRejection.SIGNATURE)
        return AdminCallVerification.Accepted(kid)
    }

    private fun mac(key: ByteArray, canonical: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(canonical.toByteArray(Charsets.UTF_8))
}

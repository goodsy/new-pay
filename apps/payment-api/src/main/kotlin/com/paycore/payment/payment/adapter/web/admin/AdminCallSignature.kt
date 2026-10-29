package com.paycore.payment.payment.adapter.web.admin

import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * ADMIN → PAYMENT 내부 호출 서명 v1(ADMIN-PAYMENT-OPS-001 §4.2, D-OPS-001 — 계약은 golden ② `admin-call-signature-v1.golden.json`, 두 앱 코드 공유 없음).
 *
 * - 헤더 `PayCore-Admin-Signature: t=<unix 초>,kid=<keyId>,v1=<64 소문자 hex>` — 전체 문법 일치만(공백·순서 변경·추가 필드·대문자 hex 거부).
 * - canonical = `paycore.admin-call.v1` LF METHOD LF 정규화 경로 LF t(헤더 숫자 그대로) LF hex(SHA-256(본문)). UTF-8, 끝 개행 없음, GET 본문 = 빈 바이트.
 * - v1 = hex(HMAC-SHA256(Base64 디코드 키, canonical)). 비교는 상수 시간([MessageDigest.isEqual]).
 * - 판정 순서(이 함수): 문법 → 시각 창(경계 포함) → kid → 서명. 크기·query는 진입 필터가 먼저 본다.
 * nonce는 없다(명령은 approvalId 결정 기록으로 멱등 — D-OPS-005, 조회는 부작용 없음).
 */
object AdminCallSignature {
    const val HEADER = "PayCore-Admin-Signature"
    const val CANONICAL_TAG = "paycore.admin-call.v1"
    private val FORMAT = Regex("^t=([0-9]{1,10}),kid=([A-Za-z0-9._-]{1,32}),v1=([0-9a-f]{64})$")
    private val HEX: HexFormat = HexFormat.of()

    fun canonical(method: String, path: String, t: String, body: ByteArray): String =
        listOf(CANONICAL_TAG, method, path, t, HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(body))).joinToString("\n")

    fun v1(key: ByteArray, method: String, path: String, t: String, body: ByteArray): String = HEX.formatHex(mac(key, canonical(method, path, t, body)))

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

package com.paycore.admin.paymentops.adapter.integration

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * ADMIN → PAYMENT 호출 서명(ADMIN-PAYMENT-OPS-001 §4.2, D-OPS-001 — 계약 golden ② `signVectors`와 바이트 대조, PAYMENT 코드 공유 없음).
 * canonical = `paycore.admin-call.v1` LF METHOD LF 정규화 경로 LF t LF 소문자 hex(SHA-256(본문)) — UTF-8, 끝 개행 없음, GET 본문 = 빈 바이트.
 * 헤더 `PayCore-Admin-Signature: t=<unix 초>,kid=<keyId>,v1=<HMAC-SHA256(key, canonical) 소문자 hex>`. 키 = 설정 비밀의 Base64 디코드 바이트(I-39). 응답 서명 없음.
 */
class AdminCallSigner(private val keyId: String, key: ByteArray) {
    private val key = key.copyOf()

    fun canonical(method: String, path: String, epochSecond: Long, body: ByteArray): String {
        require(method == "GET" || method == "POST") { "method" }
        require(path.startsWith("/") && path.none { it == '?' || it == '#' || it.code <= SPACE }) { "normalized path only (no query)" }
        return listOf(TAG, method, path, epochSecond.toString(), hex(MessageDigest.getInstance("SHA-256").digest(body))).joinToString("\n")
    }

    fun header(method: String, path: String, epochSecond: Long, body: ByteArray): String {
        val mac = Mac.getInstance(ALGORITHM).apply { init(SecretKeySpec(key, ALGORITHM)) }
        val v1 = hex(mac.doFinal(canonical(method, path, epochSecond, body).toByteArray(Charsets.UTF_8)))
        return "t=$epochSecond,kid=$keyId,v1=$v1"
    }

    override fun toString(): String = "AdminCallSigner(kid=$keyId)"

    companion object {
        const val HEADER = "PayCore-Admin-Signature"
        const val TAG = "paycore.admin-call.v1"
        private const val ALGORITHM = "HmacSHA256"
        private const val SPACE = 0x20

        private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

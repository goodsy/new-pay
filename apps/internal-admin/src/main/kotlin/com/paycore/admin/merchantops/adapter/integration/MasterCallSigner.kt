package com.paycore.admin.merchantops.adapter.integration

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * ADMIN → MASTER 호출 서명(MERCHANT-OPS-001 §3 — ADMIN-PAYMENT-OPS-001 §4.2와 같은 계약 v1, golden ② `signVectors`와 바이트 대조). paymentops의 서명기와 코드를 공유하지 않는다
 * (모듈 경계 — 계약은 golden이 맞춘다). 키는 MASTER 전용 키 공간(`paycore.admin.master-ops` — PAYMENT ops 키와 다르면 기동, 같으면 거부).
 * canonical = `paycore.admin-call.v1` LF METHOD LF 정규화 경로 LF t LF 소문자 hex(SHA-256(본문)). 헤더 `PayCore-Admin-Signature: t=<초>,kid=<keyId>,v1=<hex>`.
 */
class MasterCallSigner(private val keyId: String, key: ByteArray) {
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

    override fun toString(): String = "MasterCallSigner(kid=$keyId)"

    companion object {
        const val HEADER = "PayCore-Admin-Signature"
        const val TAG = "paycore.admin-call.v1"
        private const val ALGORITHM = "HmacSHA256"
        private const val SPACE = 0x20

        private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

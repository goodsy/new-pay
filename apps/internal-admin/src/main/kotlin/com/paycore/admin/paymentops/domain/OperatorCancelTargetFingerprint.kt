package com.paycore.admin.paymentops.domain

import java.security.MessageDigest

/**
 * 운영자 보정 취소 승인 대상 지문 `oct1:`(ADMIN-PAYMENT-OPS-001 §4.19 ①, CANCEL-CONTRACT CC-13, D-ADMIN-007) — **ADMIN 독립 구현**. PAYMENT
 * `OperatorCancelTarget.fingerprint()`와 코드를 공유하지 않고 golden(`operator-cancel-target-oct1.golden.json`, harness가 Python 표준 라이브러리로 생성)으로만 맞춘다.
 *
 * canonical = [ELEMENTS] 11개를 순서대로 `<UTF-8 바이트 수>:<값>` 으로 구분자 없이 이어 붙인 UTF-8 바이트(길이 접두 = 문자 수가 아니라 바이트 수).
 * 지문 = `oct1:` + 소문자 hex(SHA-256(canonical)). 사유 문구는 넣지 않는다. Unicode 정규화를 하지 않는다(받은 코드 포인트 그대로 — §4.18).
 * **UTF-16 유효성 선검사**: 짝 없는 surrogate가 있으면 거부한다(JVM UTF-8 인코더는 그것을 `?`로 조용히 바꿔 다른 입력과 같은 지문을 만든다).
 */
object OperatorCancelTargetFingerprint {
    const val DOMAIN_TAG = "paycore.operator-cancel-target"
    const val VERSION = "1"
    const val PREFIX = "oct1:"

    /** 요소 순서(golden `elements`와 같아야 한다 — 시험이 대조). */
    val ELEMENTS: List<String> = listOf(
        "domainTag", "version", "paymentId", "amountMinor", "currency", "balanceRevision", "approvalId", "requestedBy", "approvedBy", "purposeKind", "purposeReference",
    )

    val PURPOSE_KINDS: Set<String> = setOf("INCIDENT_OR_RECON_CORRECTION", "MERCHANT_TERMINATED_OBLIGATION")

    /** 지문 재료(승인 TX1 — 요청 고정 값 + 승인 시점 재조회 revision + 승인자). toString에 근거 문구가 없다. */
    data class Input(
        val paymentId: String,
        val amountMinor: Long,
        val currency: String,
        val balanceRevision: Long,
        val approvalId: String,
        val requestedBy: String,
        val approvedBy: String,
        val purposeKind: String,
        val purposeReference: String,
    ) {
        override fun toString(): String = "OperatorCancelTargetFingerprint.Input(paymentId=$paymentId, approvalId=$approvalId)"
    }

    fun canonical(input: Input): ByteArray {
        require(input.amountMinor > 0) { "amount must be positive" }
        require(input.balanceRevision >= 0) { "balance revision must not be negative" }
        require(input.purposeKind in PURPOSE_KINDS) { "unknown purpose kind" }
        val parts = listOf(
            DOMAIN_TAG, VERSION, input.paymentId, input.amountMinor.toString(), input.currency, input.balanceRevision.toString(), input.approvalId, input.requestedBy,
            input.approvedBy, input.purposeKind, input.purposeReference,
        )
        parts.forEach { part ->
            require(part.isNotEmpty()) { "an element is empty" }
            require(isWellFormedUtf16(part)) { "an element is not well-formed UTF-16 (lone surrogate)" }
        }
        val text = buildString { parts.forEach { append(it.toByteArray(Charsets.UTF_8).size).append(':').append(it) } }
        return text.toByteArray(Charsets.UTF_8)
    }

    fun of(input: Input): String = PREFIX + MessageDigest.getInstance("SHA-256").digest(canonical(input)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun isWellFormedUtf16(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                Character.isHighSurrogate(c) -> {
                    if (i + 1 >= s.length || !Character.isLowSurrogate(s[i + 1])) return false
                    i += 2
                }
                Character.isLowSurrogate(c) -> return false
                else -> i++
            }
        }
        return true
    }
}

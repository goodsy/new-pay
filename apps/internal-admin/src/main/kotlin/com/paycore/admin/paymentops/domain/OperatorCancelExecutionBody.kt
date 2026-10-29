package com.paycore.admin.paymentops.domain

import java.time.Instant

/**
 * PAYMENT 실행 POST 본문(ADMIN-PAYMENT-OPS-001 §4.3 — 계약 golden ③ `requestExamples`와 **바이트 동일**). 승인 TX1이 한 번 만들어 실행 행에 저장하고, 재전송·기한 확인은
 * 그 바이트를 그대로 보낸다(재생성 없음 — I-16). 필드 순서 고정, 구분 공백 없음, 문자열은 JSON 이스케이프(`"`·`\`·제어 문자, 비ASCII는 그대로 UTF-8 — Python
 * `json.dumps(ensure_ascii=False, separators=(",", ":"))`와 같다). 시각은 `yyyy-MM-ddTHH:mm:ss.SSSZ`(ms 3자리). toString에 사유·근거가 없다.
 */
data class OperatorCancelExecutionBody(
    val approvalId: String,
    val paymentId: String,
    val amountMinor: Long,
    val currency: String,
    val requestedBy: String,
    val approvedBy: String,
    val reason: String,
    val purposeKind: String,
    val purposeReference: String,
    val approvedTargetFingerprint: String,
    val approvedAt: Instant,
    val executeBefore: Instant,
    val correlationId: String,
) {
    init {
        require(APPROVAL_ID.matches(approvalId)) { "approval id format" }
        require(PAYMENT_ID.matches(paymentId)) { "payment id format" }
        require(amountMinor > 0) { "amount must be positive" }
        require(CURRENCY.matches(currency)) { "currency format" }
        require(OPERATOR_ID.matches(requestedBy) && OPERATOR_ID.matches(approvedBy)) { "operator id format" }
        require(purposeKind in OperatorCancelTargetFingerprint.PURPOSE_KINDS) { "purpose kind" }
        require(FINGERPRINT.matches(approvedTargetFingerprint)) { "fingerprint format" }
        require(executeBefore.isAfter(approvedAt)) { "deadline after approval" }
        require(approvedAt.nano % NANOS_PER_MILLI == 0 && executeBefore.nano % NANOS_PER_MILLI == 0) { "millisecond precision" }
        require(CORRELATION.matches(correlationId)) { "correlation id format" }
    }

    fun toJson(): String = buildString {
        append("{\"approvalId\":").str(approvalId).append(",\"paymentId\":").str(paymentId).append(",\"amountMinor\":").append(amountMinor)
        append(",\"currency\":").str(currency).append(",\"requestedBy\":").str(requestedBy).append(",\"approvedBy\":").str(approvedBy)
        append(",\"reason\":").str(reason).append(",\"purpose\":{\"kind\":").str(purposeKind).append(",\"reference\":").str(purposeReference).append('}')
        append(",\"approvedTargetFingerprint\":").str(approvedTargetFingerprint).append(",\"approvedAt\":").str(millis(approvedAt))
        append(",\"executeBefore\":").str(millis(executeBefore)).append(",\"correlationId\":").str(correlationId).append('}')
    }

    fun toBytes(): ByteArray = toJson().toByteArray(Charsets.UTF_8)

    override fun toString(): String = "OperatorCancelExecutionBody(approvalId=$approvalId, paymentId=$paymentId)"

    companion object {
        private val APPROVAL_ID = Regex("^apr_[0-9A-HJKMNP-TV-Z]{26}$")
        private val PAYMENT_ID = Regex("^pay_[0-9A-HJKMNP-TV-Z]{26}$")
        private val OPERATOR_ID = Regex("^adm_[0-9A-HJKMNP-TV-Z]{26}$")
        private val CURRENCY = Regex("^[A-Z]{3}$")
        private val FINGERPRINT = Regex("^oct1:[0-9a-f]{64}$")
        private val CORRELATION = Regex("^[!-~]{1,128}$")
        private const val NANOS_PER_MILLI = 1_000_000

        /** `2026-10-27T01:02:03.456Z`(초·ms 항상 표시). */
        fun millis(at: Instant): String {
            val text = at.toString()
            val base = text.removeSuffix("Z")
            val dot = base.indexOf('.')
            val withSeconds = if (base.count { it == ':' } == 1) "$base:00" else base
            val (head, frac) = if (dot < 0) withSeconds to "" else withSeconds.substringBefore('.') to withSeconds.substringAfter('.')
            return head + "." + (frac + "000").take(3) + "Z"
        }

        private fun StringBuilder.str(value: String): StringBuilder {
            append('"')
            value.forEach { c ->
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
                }
            }
            return append('"')
        }
    }
}

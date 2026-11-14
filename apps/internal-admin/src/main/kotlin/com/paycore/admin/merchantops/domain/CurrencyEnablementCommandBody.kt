package com.paycore.admin.merchantops.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 통화 활성화 승인 명령 본문과 `mop1` 지문(MERCHANT-OPS-002 B3-2 §9.14·§9.26 — D-MOPS-009 ②, MASTER `MerchantApprovalCommandParser.currencyEnablement`과 코드 공유 없이 같은 계약,
 * 독립 벡터 `CurrencyEnablementCommandBodySpec` = MASTER 시험 벡터). 순수 계산(승인 TX1 안에서 한 번 만든다 — 실행·재전송은 저장한 이 바이트 그대로, 재생성 없음).
 * - [effectiveFrom]: 문자열 `AT_EXECUTION`(MASTER가 잠금 뒤 시각으로 확정 — 승인 시각이 아니다) 또는 KST 00:00의 정규 `…Z` ms 시각([startOfKstDay]).
 * - [effectiveTo]: 없음 또는 KST 00:00 정규 시각(C-7 배타 끝). 없으면 본문에 키가 없고 `mop1` 끝 줄은 빈 줄.
 * - 지문 줄: `mop1`, `EnableCurrency`, approvalId, requestedBy, approvedBy, approvedAt ms, executeBefore ms, evidenceRef, merchantAccountId, currency, effectiveFrom, effectiveTo(없으면 "").
 * - 통화는 KRW만(C-41 — MASTER parser와 같은 목록 복제). 근거 참조 = Domain 기관 근거(C-36). toString에 근거 참조가 없다.
 */
data class CurrencyEnablementCommandBody(
    val approvalId: String,
    val requestedBy: String,
    val approvedBy: String,
    val approvedAt: Instant,
    val executeBefore: Instant,
    val evidenceRef: String,
    val merchantAccountId: String,
    val currency: String,
    val effectiveFrom: String,
    val effectiveTo: String?,
) {
    init {
        ApprovalBodyRules.common(approvalId, requestedBy, approvedBy, approvedAt, executeBefore, evidenceRef)
        require(Regex("^mid_${ApprovalBodyRules.ULID}$").matches(merchantAccountId)) { "merchant account id format" }
        require(currency in CURRENCIES) { "currency (C-41 — KRW only)" }
        require(effectiveFrom == AT_EXECUTION || isKstMidnight(effectiveFrom)) { "effectiveFrom: AT_EXECUTION or a KST-midnight instant (C-48)" }
        require(effectiveTo == null || isKstMidnight(effectiveTo)) { "effectiveTo: a KST-midnight instant (C-48)" }
    }

    private val fields: List<String> get() = listOf(merchantAccountId, currency, effectiveFrom, effectiveTo ?: "")

    fun fingerprint(): String = ApprovalBodyRules.fingerprint(OPERATION, approvalId, requestedBy, approvedBy, approvedAt, executeBefore, evidenceRef, fields)

    fun json(): ByteArray = ApprovalBodyRules.json(
        approvalId, requestedBy, approvedBy, approvedAt, executeBefore, evidenceRef, fingerprint(), OPERATION,
        listOf("merchantAccountId" to merchantAccountId, "currency" to currency, "effectiveFrom" to effectiveFrom) + listOfNotNull(effectiveTo?.let { "effectiveTo" to it }),
    )

    override fun toString(): String = "CurrencyEnablementCommandBody(approvalId=$approvalId, merchantAccountId=$merchantAccountId, effectiveFrom=$effectiveFrom)"

    companion object {
        const val OPERATION = "EnableCurrency"
        const val AT_EXECUTION = "AT_EXECUTION"
        val CURRENCIES = setOf("KRW")
        private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
        private val INSTANT = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z$")
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

        /** KST 날짜 그날 00:00(Asia/Seoul)의 UTC 정규 `…Z` ms 문자열(C-7 — ADMIN 요청 날짜 → MASTER 본문 시각). */
        fun startOfKstDay(date: LocalDate): String = TIME.format(date.atStartOfDay(SEOUL).toInstant())

        private fun isKstMidnight(value: String): Boolean = INSTANT.matches(value) && runCatching {
            val at = Instant.parse(value)
            TIME.format(at) == value && at.atZone(SEOUL).toLocalDate().atStartOfDay(SEOUL).toInstant() == at
        }.getOrDefault(false)
    }
}

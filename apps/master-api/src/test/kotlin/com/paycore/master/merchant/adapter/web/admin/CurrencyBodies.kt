package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.merchant.adapter.web.admin.MerchantApprovalBodies.Approval
import com.paycore.master.merchant.adapter.web.admin.MerchantOperationsBodies.raw
import com.paycore.master.merchant.adapter.web.admin.MerchantOperationsBodies.str
import com.paycore.master.merchant.application.provided.AdminOperation

/**
 * MERCHANT-OPS-002 B3-2 `currency-enablements` 본문 Named Fixture(src/test 전용 — D-MOPS-009 ②, §9.14·§9.26). 근거 공통 9 + `operation` = `EnableCurrency`(C-39) +
 * 명령 필드 §9.14 순서(`merchantAccountId`, `currency`, `effectiveFrom`, `effectiveTo`(선택 — 없으면 필드 없음, `mop1` 끝 줄은 빈 줄)).
 * `effectiveFrom`은 문자열 `AT_EXECUTION` 또는 KST 00:00의 `…Z` ms 시각이다. 시각 값은 시험 clock t0(2026-10-09T02:00Z = 11:00 KST) 기준 명시 값.
 */
object CurrencyBodies {
    const val CURRENCY_ENABLEMENTS = "/internal/v1/admin/merchant-operations/currency-enablements"

    const val AT_EXECUTION = "AT_EXECUTION"

    /** t0(2026-10-09 11:00 KST) 기준 내일 00:00 KST. */
    const val TOMORROW_KST = "2026-10-09T15:00:00.000Z"

    /** t0 기준 모레 00:00 KST(종료일 — 배타 끝 C-7). */
    const val DAY_AFTER_KST = "2026-10-10T15:00:00.000Z"

    /** 2인 통화 활성화 본문 필드(§9.14 순서). [effectiveTo] null이면 필드를 넣지 않는다. */
    fun fields(
        mid: String,
        effectiveFrom: String = AT_EXECUTION,
        effectiveTo: String? = null,
        a: Approval = Approval(),
        currency: String = "KRW",
        fingerprint: String? = null,
    ): List<Pair<String, String>> {
        val op = AdminOperation.ENABLE_CURRENCY
        val fp = fingerprint ?: a.fingerprint(op, listOf(mid, currency, effectiveFrom, effectiveTo ?: ""))
        return (
            listOf(
                "idempotencyKey" to a.idempotencyKey, "correlationId" to a.correlationId, "approvalId" to a.approvalId, "requestedBy" to a.requestedBy,
                "approvedBy" to a.approvedBy, "approvedAt" to a.approvedAt, "executeBefore" to a.executeBefore, "evidenceRef" to a.evidenceRef, "approvalFingerprint" to fp,
                "operation" to op.code, "merchantAccountId" to mid, "currency" to currency, "effectiveFrom" to effectiveFrom,
            ) + listOfNotNull(effectiveTo?.let { "effectiveTo" to it })
            ).map { (k, v) -> k to str(v) }
    }

    @Suppress("LongParameterList")
    fun body(
        mid: String,
        effectiveFrom: String = AT_EXECUTION,
        effectiveTo: String? = null,
        a: Approval = Approval(),
        currency: String = "KRW",
        fingerprint: String? = null,
    ): String = raw(fields(mid, effectiveFrom, effectiveTo, a, currency, fingerprint))
}

package com.paycore.admin.merchantops.domain

import java.time.Instant

/**
 * 가맹점 코드 변경 승인 명령 본문과 `mop1` 지문(MERCHANT-CODE-001 MC-1b §3.7.2 — MASTER `MerchantApprovalCommandParser.merchantCodeChange`와 코드 공유 없이 같은 계약,
 * 독립 벡터 `MerchantCodeChangeCommandBodySpec` = MASTER 시험 벡터). 순수 계산(승인 TX1 안에서 한 번 만든다 — 실행·재전송은 저장한 바이트 그대로).
 * - 지문 줄: `mop1`, `ChangeMerchantCode`, approvalId, requestedBy, approvedBy, approvedAt ms, executeBefore ms, evidenceRef, merchantId, currentCode, newCode, reasonCode.
 * - 본문: 근거 공통 9 + `operation`(B3 관례 — 기한 확인 본문 판별) + 명령 필드 4. 등록자 필드 없음(Q-MC-10). toString에 근거 참조가 없다.
 */
data class MerchantCodeChangeCommandBody(
    val approvalId: String,
    val requestedBy: String,
    val approvedBy: String,
    val approvedAt: Instant,
    val executeBefore: Instant,
    val evidenceRef: String,
    val merchantId: String,
    val currentCode: String,
    val newCode: String,
    val reasonCode: String,
) {
    init {
        ApprovalBodyRules.common(approvalId, requestedBy, approvedBy, approvedAt, executeBefore, evidenceRef)
        require(Regex("^mer_${ApprovalBodyRules.ULID}$").matches(merchantId)) { "merchant id format" }
        require(CODE.matches(currentCode) && CODE.matches(newCode) && currentCode != newCode) { "merchant codes (§3.1, different)" }
        require(reasonCode in REASONS) { "reason code (C-12)" }
    }

    private val fields: List<String> get() = listOf(merchantId, currentCode, newCode, reasonCode)

    fun fingerprint(): String = ApprovalBodyRules.fingerprint(OPERATION, approvalId, requestedBy, approvedBy, approvedAt, executeBefore, evidenceRef, fields)

    fun json(): ByteArray = ApprovalBodyRules.json(
        approvalId, requestedBy, approvedBy, approvedAt, executeBefore, evidenceRef, fingerprint(), OPERATION,
        listOf("merchantId" to merchantId, "currentCode" to currentCode, "newCode" to newCode, "reasonCode" to reasonCode),
    )

    override fun toString(): String = "MerchantCodeChangeCommandBody(approvalId=$approvalId, merchantId=$merchantId)"

    companion object {
        const val OPERATION = "ChangeMerchantCode"
        private val CODE = Regex("^[a-z][a-z0-9]{5,13}$")
        private val REASONS = setOf("TYPO_CORRECTION", "MERCHANT_REQUEST", "OTHER_POLICY")
    }
}

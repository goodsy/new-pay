package com.paycore.admin.approval.domain

import java.time.Instant

/**
 * 운영자 보정 취소 요청의 고정 값(ADMIN-PAYMENT-OPS-001 §4.9·§4.11 `admin_operator_cancel_command` — INSERT-only, I-16). 승인 요청과 같은 TX에서 한 번 쓰고 바꾸지 않는다.
 * [pinnedTarget]이 승인 TX1 비교의 권위 값이다(같은 Payment). 금액 > 0, 통화 3자리 대문자, 근거 1~200·사유 1~500(UTF-16 단위 — DB 열 길이 안). 근거·사유의
 * 문자 집합·앞뒤 공백·카드 번호 패턴 규칙(§4.18, I-17·I-18)은 요청 입력 검사(S5)가 맡는다. toString에 사유·근거가 없다(§6 비노출).
 */
data class OperatorCancelRequestSnapshot(
    val approvalId: String,
    val paymentId: String,
    val amountMinor: Long,
    val currency: String,
    val purposeKind: OperatorCancelPurposeKind,
    val purposeReference: String,
    val reason: String,
    val pinnedTarget: PinnedPaymentTarget,
    val createdAt: Instant,
) {
    init {
        require(ID.matches(approvalId)) { "approval id format" }
        require(pinnedTarget.paymentId == paymentId) { "the pinned target is the commanded Payment" }
        require(amountMinor > 0) { "amount must be positive" }
        require(CURRENCY.matches(currency)) { "currency format" }
        require(purposeReference.length in 1..MAX_REFERENCE) { "purpose reference length 1..$MAX_REFERENCE" }
        require(reason.length in 1..MAX_REASON) { "reason length 1..$MAX_REASON" }
    }

    override fun toString(): String = "OperatorCancelRequestSnapshot(approvalId=$approvalId, paymentId=$paymentId)"

    companion object {
        const val MAX_REFERENCE = 200
        const val MAX_REASON = 500
        private val ID = Regex("^apr_[0-9A-HJKMNP-TV-Z]{26}$")
        private val CURRENCY = Regex("^[A-Z]{3}$")

        @Suppress("LongParameterList")
        fun of(
            approvalId: String, paymentId: String, amountMinor: Long, currency: String, purposeKind: OperatorCancelPurposeKind, purposeReference: String, reason: String,
            pinnedTarget: PinnedPaymentTarget, createdAt: Instant,
        ) = OperatorCancelRequestSnapshot(approvalId, paymentId, amountMinor, currency, purposeKind, purposeReference, reason, pinnedTarget, createdAt)
    }
}

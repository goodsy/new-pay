package com.paycore.payment.payment.domain

import java.security.MessageDigest
import java.util.HexFormat

/**
 * 운영자 보정 취소에서 2인 승인이 승인한 대상(D-ADMIN-007 "승인한 입력 버전 = 실행 입력", review m-3·m-C): Payment·취소 Money·반환 잔액 revision과 승인
 * 주체(approvalId·요청자·승인자)·목적 종류·근거 참조. 사유 문구는 넣지 않는다(표시 정보). ADMIN이 승인 요청 때 이 값의 [fingerprint]를 고정하고, PAYMENT의
 * [CancelStart]는 실행 시점 값으로 다시 계산해 다르면 거절한다(잔액이 그 사이 바뀌었거나, 금액·승인 주체·목적이 다르면 stale). 잔액이 아직 없으면 revision 0.
 */
data class OperatorCancelTarget(
    val paymentId: PaymentId,
    val money: Money,
    val balanceRevision: Long,
    val approvalId: OperatorApprovalId,
    val requestedBy: OperatorId,
    val approvedBy: OperatorId,
    val purpose: OperatorCancelPurpose,
) {
    init {
        require(balanceRevision >= 0) { "balance revision must not be negative" }
    }

    /** "oct1:" + lowercase hex(SHA-256(길이 접두 canonical)) — CP계약 §7·CC-06과 같은 방식. */
    fun fingerprint(): String {
        val purposeType = when (purpose) {
            is OperatorCancelPurpose.IncidentOrReconCorrection -> "INCIDENT_OR_RECON_CORRECTION"
            is OperatorCancelPurpose.MerchantTerminatedObligation -> "MERCHANT_TERMINATED_OBLIGATION"
        }
        val canonical = listOf(
            DOMAIN_TAG, "1", paymentId.value, money.amountMinor.toString(), money.currency.value, balanceRevision.toString(),
            approvalId.value, requestedBy.value, approvedBy.value, purposeType, purpose.reference,
        ).joinToString("") { "${Utf8Text.encode(it).size}:$it" }
        return "oct1:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Utf8Text.encode(canonical)))
    }

    companion object {
        private const val DOMAIN_TAG = "paycore.operator-cancel-target"

        /** 실행 시점 대상: 운영자 입력의 승인 주체·목적 + 대상 Payment·금액·잔액 revision. */
        fun of(paymentId: PaymentId, money: Money, balanceRevision: Long, operator: CancelInitiator.Operator) =
            OperatorCancelTarget(paymentId, money, balanceRevision, operator.approvalId, operator.requestedBy, operator.approvedBy, operator.purpose)
    }
}

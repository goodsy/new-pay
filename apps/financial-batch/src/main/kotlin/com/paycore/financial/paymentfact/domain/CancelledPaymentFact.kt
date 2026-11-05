package com.paycore.financial.paymentfact.domain

import java.time.Instant
import java.time.LocalDate

/**
 * PaymentCancelled v1 사실(PAYMENT 확정 CANCEL 거래 1건 — CANCEL-001 §4.10, CANCEL-CONTRACT-001 §9.3). [originalTransactionId]가 원승인 거래를 가리킨다.
 * 취소 기록·상계(전송 전/후) 판단은 FINANCIAL 매입 작업이 한다(CANCEL-CONTRACT-001, D-ACQ-002) — 이 사실은 그 입력이다.
 * [authorizedAmountMinor]·[cumulativeReturnedMinor]는 PAYMENT가 취소 확정 시점에 본 값이며 FINANCIAL 정합성 검사에 쓴다.
 */
data class CancelledPaymentFact(
    override val identity: PaymentFactIdentity,
    override val authorizationId: String,
    val originalTransactionId: String,
    val cancellationId: String,
    override val transactionId: String,
    override val money: FactMoney,
    val scope: String,
    val authorizedAmountMinor: Long,
    val returnSequence: Int,
    val cumulativeReturnedMinor: Long,
    val returnStatusAfter: String,
    val cancelApprovalNumber: String?,
    val providerTransactionId: String,
    val originalProviderTransactionId: String,
    val institutionTransactionDate: LocalDate,
    val institutionBusinessDate: LocalDate?,
    val cancelledAt: Instant,
    override val policyAssignmentVersion: String,
    override val entitlementVersion: String,
    val initiatorKind: String,
    val originalPlan: FactPlan,
    val originalCardType: String,
) : PaymentFact {
    override val kind: PaymentFactKind get() = PaymentFactKind.CANCELLATION

    init {
        FactText.requireId("authorizationId", authorizationId)
        FactText.requireId("originalTransactionId", originalTransactionId)
        FactText.requireId("cancellationId", cancellationId)
        FactText.requireId("transactionId", transactionId)
        if (transactionId == originalTransactionId) throw PaymentFactContractViolation("a cancel transaction is not its own original")
        FactText.requireOneOf("scope", scope, SCOPES)
        if (authorizedAmountMinor < 1) throw PaymentFactContractViolation("authorizedAmount must be a positive minor-unit integer")
        if (returnSequence < 1) throw PaymentFactContractViolation("returnSequence must be at least 1")
        if (cumulativeReturnedMinor < money.minor) throw PaymentFactContractViolation("cumulativeReturnedAmount must include this cancel amount")
        FactText.require("returnStatusAfter", returnStatusAfter, RETURN_STATUS_MAX)
        cancelApprovalNumber?.let { FactText.require("cancelApprovalNumber", it, AuthorizedPaymentFact.CODE_MAX) }
        FactText.requireId("providerTransactionId", providerTransactionId)
        FactText.requireId("originalProviderTransactionId", originalProviderTransactionId)
        FactText.require("policyAssignmentVersion", policyAssignmentVersion, AuthorizedPaymentFact.POLICY_VERSION_MAX)
        FactText.require("entitlementVersion", entitlementVersion, AuthorizedPaymentFact.ENTITLEMENT_VERSION_MAX)
        FactText.requireOneOf("initiatorKind", initiatorKind, INITIATORS)
        FactText.requireOneOf("originalCardType", originalCardType, AuthorizedPaymentFact.CARD_TYPES)
    }

    companion object {
        val SCOPES = setOf("FULL", "PARTIAL")
        val INITIATORS = setOf("MERCHANT", "OPERATOR")
        const val RETURN_STATUS_MAX = 24
    }
}

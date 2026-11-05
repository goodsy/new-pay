package com.paycore.financial.paymentfact.domain

import java.time.Instant
import java.time.LocalDate

/**
 * PaymentAuthorized v1 사실(PAYMENT 확정 승인 거래 1건 — AUTH-001 §4.10, OutboxStore `PaymentAuthorizedV1Payload`). 매입 대상 판단의 입력이다
 * (D-ACQ-002 "매입 대상은 PAYMENT 승인 확정 건만" — 이 사실이 그 확정의 FINANCIAL 쪽 증거). 기관 영업일은 기관이 준 값 그대로(의미는 Q-CALENDAR).
 */
data class AuthorizedPaymentFact(
    override val identity: PaymentFactIdentity,
    override val authorizationId: String,
    override val transactionId: String,
    override val money: FactMoney,
    val plan: FactPlan,
    val cardType: String,
    val issuerCode: String,
    val acquirerCode: String,
    val approvalNumber: String,
    val providerTransactionId: String,
    val institutionTransactionDate: LocalDate,
    val institutionBusinessDate: LocalDate?,
    val approvedAt: Instant,
    override val policyAssignmentVersion: String,
    override val entitlementVersion: String,
) : PaymentFact {
    override val kind: PaymentFactKind get() = PaymentFactKind.AUTHORIZATION

    init {
        FactText.requireId("authorizationId", authorizationId)
        FactText.requireId("transactionId", transactionId)
        FactText.requireOneOf("cardType", cardType, CARD_TYPES)
        FactText.require("issuerCode", issuerCode, CODE_MAX)
        FactText.require("acquirerCode", acquirerCode, CODE_MAX)
        FactText.require("approvalNumber", approvalNumber, CODE_MAX)
        FactText.requireId("providerTransactionId", providerTransactionId)
        FactText.require("policyAssignmentVersion", policyAssignmentVersion, POLICY_VERSION_MAX)
        FactText.require("entitlementVersion", entitlementVersion, ENTITLEMENT_VERSION_MAX)
    }

    companion object {
        val CARD_TYPES = setOf("CREDIT", "CHECK")
        const val CODE_MAX = 32
        const val POLICY_VERSION_MAX = 160
        const val ENTITLEMENT_VERSION_MAX = 128
    }
}

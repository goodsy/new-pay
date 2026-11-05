package com.paycore.financial.paymentfact.adapter.persistence

import com.paycore.financial.paymentfact.domain.AuthorizedPaymentFact
import com.paycore.financial.paymentfact.domain.CancelledPaymentFact
import com.paycore.financial.paymentfact.domain.FactEnvironment
import com.paycore.financial.paymentfact.domain.FactMoney
import com.paycore.financial.paymentfact.domain.FactPlan
import com.paycore.financial.paymentfact.domain.PaymentFactIdentity
import java.time.Instant

/**
 * 순수 domain 사실 ↔ JPA 행. 행 → domain은 domain 생성자를 다시 거친다(저장된 행이 존재할 수 없는 사실이면 거부 — 예: CHECK를 우회해 들어온 값).
 */
internal object PaymentFactPersistenceMapper {

    fun toRow(fact: AuthorizedPaymentFact, sourceEventId: String, receivedAt: Instant): JpaAuthorizedPaymentFactEntity {
        val i = fact.identity
        return JpaAuthorizedPaymentFactEntity.blank().fill(
            transactionId = fact.transactionId, paymentId = i.paymentId, authorizationId = fact.authorizationId, environment = i.environment.name,
            merchantAccountId = i.merchantAccountId, merchantSiteId = i.merchantSiteId, merchantOrderId = i.merchantOrderId, amountMinor = fact.money.minor,
            currency = fact.money.currency, planKind = fact.plan.kind, installmentMonths = fact.plan.installmentMonths, cardType = fact.cardType,
            issuerCode = fact.issuerCode, acquirerCode = fact.acquirerCode, approvalNumber = fact.approvalNumber, providerTransactionId = fact.providerTransactionId,
            institutionTransactionDate = fact.institutionTransactionDate, institutionBusinessDate = fact.institutionBusinessDate, approvedAt = fact.approvedAt,
            policyAssignmentVersion = fact.policyAssignmentVersion, entitlementVersion = fact.entitlementVersion, sourceEventId = sourceEventId,
            effectKey = fact.effectKey, receivedAt = receivedAt,
        )
    }

    fun toRow(fact: CancelledPaymentFact, originalKnownAtReceipt: Boolean, sourceEventId: String, receivedAt: Instant): JpaCancelledPaymentFactEntity {
        val i = fact.identity
        return JpaCancelledPaymentFactEntity.blank().fill(
            transactionId = fact.transactionId, originalTransactionId = fact.originalTransactionId, paymentId = i.paymentId, authorizationId = fact.authorizationId,
            cancellationId = fact.cancellationId, environment = i.environment.name, merchantAccountId = i.merchantAccountId, merchantSiteId = i.merchantSiteId,
            merchantOrderId = i.merchantOrderId, amountMinor = fact.money.minor, currency = fact.money.currency, scope = fact.scope,
            authorizedAmountMinor = fact.authorizedAmountMinor, returnSequence = fact.returnSequence, cumulativeReturnedMinor = fact.cumulativeReturnedMinor,
            returnStatusAfter = fact.returnStatusAfter, cancelApprovalNumber = fact.cancelApprovalNumber, providerTransactionId = fact.providerTransactionId,
            originalProviderTransactionId = fact.originalProviderTransactionId, institutionTransactionDate = fact.institutionTransactionDate,
            institutionBusinessDate = fact.institutionBusinessDate, cancelledAt = fact.cancelledAt, policyAssignmentVersion = fact.policyAssignmentVersion,
            entitlementVersion = fact.entitlementVersion, initiatorKind = fact.initiatorKind, originalPlanKind = fact.originalPlan.kind,
            originalInstallmentMonths = fact.originalPlan.installmentMonths, originalCardType = fact.originalCardType, originalKnownAtReceipt = originalKnownAtReceipt,
            sourceEventId = sourceEventId, effectKey = fact.effectKey, receivedAt = receivedAt,
        )
    }

    fun toDomain(row: JpaAuthorizedPaymentFactEntity): AuthorizedPaymentFact = AuthorizedPaymentFact(
        identity = PaymentFactIdentity(FactEnvironment.valueOf(row.environment), row.merchantAccountId, row.merchantSiteId, row.merchantOrderId, row.paymentId),
        authorizationId = row.authorizationId, transactionId = row.transactionId, money = FactMoney(row.amountMinor, row.currency),
        plan = FactPlan(row.planKind, row.installmentMonths), cardType = row.cardType, issuerCode = row.issuerCode, acquirerCode = row.acquirerCode,
        approvalNumber = row.approvalNumber, providerTransactionId = row.providerTransactionId, institutionTransactionDate = row.institutionTransactionDate,
        institutionBusinessDate = row.institutionBusinessDate, approvedAt = row.approvedAt, policyAssignmentVersion = row.policyAssignmentVersion,
        entitlementVersion = row.entitlementVersion,
    )

    fun toDomain(row: JpaCancelledPaymentFactEntity): CancelledPaymentFact = CancelledPaymentFact(
        identity = PaymentFactIdentity(FactEnvironment.valueOf(row.environment), row.merchantAccountId, row.merchantSiteId, row.merchantOrderId, row.paymentId),
        authorizationId = row.authorizationId, originalTransactionId = row.originalTransactionId, cancellationId = row.cancellationId,
        transactionId = row.transactionId, money = FactMoney(row.amountMinor, row.currency), scope = row.scope, authorizedAmountMinor = row.authorizedAmountMinor,
        returnSequence = row.returnSequence, cumulativeReturnedMinor = row.cumulativeReturnedMinor, returnStatusAfter = row.returnStatusAfter,
        cancelApprovalNumber = row.cancelApprovalNumber, providerTransactionId = row.providerTransactionId,
        originalProviderTransactionId = row.originalProviderTransactionId, institutionTransactionDate = row.institutionTransactionDate,
        institutionBusinessDate = row.institutionBusinessDate, cancelledAt = row.cancelledAt, policyAssignmentVersion = row.policyAssignmentVersion,
        entitlementVersion = row.entitlementVersion, initiatorKind = row.initiatorKind, originalPlan = FactPlan(row.originalPlanKind, row.originalInstallmentMonths),
        originalCardType = row.originalCardType,
    )

    fun toOwner(row: JpaPaymentFactStreamEntity): PaymentFactIdentity =
        PaymentFactIdentity(FactEnvironment.valueOf(row.environment), row.merchantAccountId, row.merchantSiteId, row.merchantOrderId, row.paymentId)
}

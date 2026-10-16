package com.paycore.master.merchant.adapter.persistence

import com.paycore.master.merchant.domain.AcquirerCode
import com.paycore.master.merchant.domain.ActivationStatus
import com.paycore.master.merchant.domain.ActorRef
import com.paycore.master.merchant.domain.CardRouteActivation
import com.paycore.master.merchant.domain.CardRouteActivationId
import com.paycore.master.merchant.domain.ContractServiceAgreementId
import com.paycore.master.merchant.domain.CurrencyCode
import com.paycore.master.merchant.domain.DecisionReason
import com.paycore.master.merchant.domain.EffectivePeriod
import com.paycore.master.merchant.domain.EvidenceRef
import com.paycore.master.merchant.domain.InstitutionMerchantRef
import com.paycore.master.merchant.domain.LimitScope
import com.paycore.master.merchant.domain.LimitScopeKind
import com.paycore.master.merchant.domain.LimitValue
import com.paycore.master.merchant.domain.MerchantAccountId
import com.paycore.master.merchant.domain.MerchantId
import com.paycore.master.merchant.domain.PaymentMethod
import com.paycore.master.merchant.domain.PolicyAssignment
import com.paycore.master.merchant.domain.PolicyAssignmentId
import com.paycore.master.merchant.domain.PolicyCode
import com.paycore.master.merchant.domain.PolicyContentStatus
import com.paycore.master.merchant.domain.PolicyKind
import com.paycore.master.merchant.domain.PolicyVersion
import com.paycore.master.merchant.domain.PolicyVersionId
import com.paycore.master.merchant.domain.RouteRef
import com.paycore.master.merchant.domain.TransactionLimitPolicy
import com.paycore.master.merchant.domain.TransactionLimitPolicyId

/** Domain ↔ 행 변환. 복원은 domain의 공식 복원 경로를 거친다(저장 값이 규칙상 불가능하면 예외 — Seoul 자정 등). */
internal object AuthorizationTermsMapper {
    fun toLimit(row: JpaTransactionLimitPolicyEntity): TransactionLimitPolicy {
        val scope = when (LimitScopeKind.valueOf(row.scopeKind)) {
            LimitScopeKind.CSA_DEFAULT -> LimitScope.CsaDefault(ContractServiceAgreementId(checkNotNull(row.agreementId)))
            LimitScopeKind.MID_OVERRIDE -> LimitScope.MidOverride(MerchantAccountId(checkNotNull(row.merchantAccountId)))
        }
        return TransactionLimitPolicy.reconstitute(
            TransactionLimitPolicyId(row.policyId), MerchantId(row.merchantId), scope, CurrencyCode(row.currencyCode),
            limitOf(row.perTxKind, row.perTxAmount), limitOf(row.dailyKind, row.dailyAmount), EffectivePeriod(row.effectiveFrom, row.effectiveTo),
            DecisionReason(row.reason), row.createdAt, ActorRef(row.createdBy), row.endedAt, row.endedBy?.let(::ActorRef),
        )
    }

    fun limitColumns(v: LimitValue): Pair<String, Long?> = when (v) {
        LimitValue.Unlimited -> "UNLIMITED" to null
        is LimitValue.Limited -> "LIMITED" to v.amountMinor
    }

    private fun limitOf(kind: String, amount: Long?): LimitValue = when (kind) {
        "UNLIMITED" -> LimitValue.Unlimited.also { check(amount == null) { "an unlimited value has no amount" } }
        "LIMITED" -> LimitValue.Limited(checkNotNull(amount) { "a limited value has an amount" })
        else -> error("unknown limit kind")
    }

    fun toVersion(row: JpaPolicyVersionEntity) = PolicyVersion.reconstitute(
        PolicyVersionId(row.policyVersionId), PolicyKind.valueOf(row.policyKind), PolicyCode(row.policyCode), row.versionNo,
        PolicyContentStatus.valueOf(row.contentStatus), row.contentHash, DecisionReason(row.reason), row.registeredAt, ActorRef(row.registeredBy),
    )

    fun toAssignment(row: JpaPolicyAssignmentEntity) = PolicyAssignment.reconstitute(
        PolicyAssignmentId(row.assignmentId), MerchantId(row.merchantId), ContractServiceAgreementId(row.agreementId), MerchantAccountId(row.merchantAccountId),
        CurrencyCode(row.currencyCode), PolicyKind.valueOf(row.policyKind), PolicyVersionId(row.policyVersionId),
        EffectivePeriod(row.effectiveFrom, row.effectiveTo), DecisionReason(row.reason), row.createdAt, ActorRef(row.createdBy), row.endedAt,
        row.endedBy?.let(::ActorRef),
    )

    fun toRoute(row: JpaCardRouteActivationEntity) = CardRouteActivation.reconstitute(
        CardRouteActivationId(row.activationId), MerchantAccountId(row.merchantAccountId), PaymentMethod.valueOf(row.paymentMethod),
        AcquirerCode(row.acquirerCode), RouteRef(row.routeRef), InstitutionMerchantRef(row.institutionMerchantRef), row.registeredAt,
        ActorRef(row.registeredBy), ActivationStatus.valueOf(row.status), row.evidenceRef?.let(::EvidenceRef), row.statusChangedAt,
    )
}

package com.paycore.payment.payment.adapter.integration

import com.paycore.payment.entitlement.application.provided.EntitlementEvaluationFailure
import com.paycore.payment.entitlement.application.provided.LimitBound
import com.paycore.payment.entitlement.application.provided.LimitPolicyLookup
import com.paycore.payment.payment.application.provided.StoreDiagnostic
import com.paycore.payment.payment.application.required.LimitPolicyHistoryPort
import com.paycore.payment.payment.application.required.PersistenceFailure
import com.paycore.payment.payment.domain.CurrencyCode
import com.paycore.payment.payment.domain.LimitAmount
import com.paycore.payment.payment.domain.PaymentAuthorization
import com.paycore.payment.payment.domain.TransactionLimitPolicy
import org.springframework.stereotype.Component

/** [LimitPolicyHistoryPort] 운영 구현(AU-29) — entitlement 공개 조회([LimitPolicyLookup])로 승인에 고정된 revision의 정책 값을 domain 정책으로 옮긴다. 통화가 다르면 null(결함 방어). */
@Component
class EntitlementLimitPolicyHistoryAdapter(private val lookup: LimitPolicyLookup) : LimitPolicyHistoryPort {
    override fun policyOf(authorization: PaymentAuthorization): TransactionLimitPolicy? {
        val mid = authorization.orderKey.merchantAccountId
        val terms = try {
            lookup.find(authorization.admission.entitlementVersion, mid.value, authorization.limitPolicyVersion)
        } catch (e: EntitlementEvaluationFailure) {
            val diagnostic = if (e.vendorCode == null && e.sqlState == null) null else StoreDiagnostic(e.vendorCode, e.sqlState)
            throw when (e) {
                is EntitlementEvaluationFailure.Deadlock -> PersistenceFailure.Deadlock(e, diagnostic)
                is EntitlementEvaluationFailure.LockTimeout -> PersistenceFailure.LockTimeout(e, diagnostic)
                is EntitlementEvaluationFailure.Unavailable -> PersistenceFailure.Unavailable(e, diagnostic)
            }
        } ?: return null
        if (terms.currency != authorization.money.currency.value) return null
        return TransactionLimitPolicy(terms.policyId, mid, CurrencyCode(terms.currency), terms.perTransaction.toAmount(), terms.daily.toAmount())
    }

    private fun LimitBound.toAmount(): LimitAmount = when (this) {
        LimitBound.Unlimited -> LimitAmount.Unlimited
        is LimitBound.Limited -> LimitAmount.Limited(amountMinor)
    }
}

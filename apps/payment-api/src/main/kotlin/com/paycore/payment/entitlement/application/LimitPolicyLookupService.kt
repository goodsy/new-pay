package com.paycore.payment.entitlement.application

import com.paycore.payment.entitlement.application.provided.EntitlementEvaluationFailure
import com.paycore.payment.entitlement.application.provided.LimitBound
import com.paycore.payment.entitlement.application.provided.LimitPolicyLookup
import com.paycore.payment.entitlement.application.provided.LimitTerms
import com.paycore.payment.entitlement.application.required.EntitlementPersistenceFailure
import com.paycore.payment.entitlement.application.required.EntitlementProjectionStore
import com.paycore.payment.entitlement.domain.LimitBoundEntry
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/** [LimitPolicyLookup] 구현: 고정 revision의 불변 Snapshot 내용에서 MID의 한도 정책 행을 찾는다(head를 잡지 않는다 — 과거 revision은 바뀌지 않는다). */
@Service
class LimitPolicyLookupService(private val store: EntitlementProjectionStore) : LimitPolicyLookup {

    @Transactional(propagation = Propagation.MANDATORY)
    override fun find(entitlementVersion: String, mid: String, policyId: String): LimitTerms.Resolved? {
        val parts = entitlementVersion.split(':')
        if (parts.size != VERSION_PARTS || parts[0] != PREFIX) return null
        val revision = parts[2].toLongOrNull() ?: return null
        val content = try {
            store.findSnapshot(parts[1], revision)
        } catch (e: EntitlementPersistenceFailure.Deadlock) {
            throw EntitlementEvaluationFailure.Deadlock(e, e.vendorCode, e.sqlState)
        } catch (e: EntitlementPersistenceFailure.LockTimeout) {
            throw EntitlementEvaluationFailure.LockTimeout(e, e.vendorCode, e.sqlState)
        } catch (e: EntitlementPersistenceFailure.Unavailable) {
            throw EntitlementEvaluationFailure.Unavailable(e, e.vendorCode, e.sqlState)
        } ?: return null
        val policy = content.accounts.firstOrNull { it.mid == mid }?.terms?.limitPolicies?.firstOrNull { it.policyId == policyId } ?: return null
        return LimitTerms.Resolved(policy.policyId, policy.scope.name, policy.currency, policy.perTransaction.toBound(), policy.daily.toBound())
    }

    private fun LimitBoundEntry.toBound(): LimitBound = when (this) {
        LimitBoundEntry.Unlimited -> LimitBound.Unlimited
        is LimitBoundEntry.Limited -> LimitBound.Limited(amountMinor)
    }

    private companion object {
        const val PREFIX = "es1"
        const val VERSION_PARTS = 3
    }
}

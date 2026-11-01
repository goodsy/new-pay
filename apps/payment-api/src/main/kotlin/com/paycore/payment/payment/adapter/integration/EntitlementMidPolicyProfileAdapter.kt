package com.paycore.payment.payment.adapter.integration

import com.paycore.payment.entitlement.application.provided.EntitlementEvaluationFailure
import com.paycore.payment.entitlement.application.provided.MidPolicyContentStatus
import com.paycore.payment.entitlement.application.provided.MidPolicyProfileQuery
import com.paycore.payment.payment.application.provided.StoreDiagnostic
import com.paycore.payment.payment.application.required.MidPolicyContents
import com.paycore.payment.payment.application.required.MidPolicyProfilePort
import com.paycore.payment.payment.application.required.PersistenceFailure
import com.paycore.payment.payment.domain.MerchantAccountId
import org.springframework.stereotype.Component

/**
 * [MidPolicyProfilePort]의 운영 구현(DEMO-SEED-001 §4.5, R-16): entitlement 공개 조회([MidPolicyProfileQuery], provided만)를 호출자 PAYMENT 로컬 TX에서 부르고
 * payment 모양으로 옮긴다. 읽기 전용이라 웹 컨텍스트에 있어도 쓰기가 없다. 캐시 없음.
 */
@Component
class EntitlementMidPolicyProfileAdapter(private val query: MidPolicyProfileQuery) : MidPolicyProfilePort {

    override fun find(mid: MerchantAccountId): MidPolicyContents? {
        val profile = try {
            query.find(mid.value)
        } catch (e: EntitlementEvaluationFailure.Deadlock) {
            throw PersistenceFailure.Deadlock(e, diagnosticOf(e))
        } catch (e: EntitlementEvaluationFailure.LockTimeout) {
            throw PersistenceFailure.LockTimeout(e, diagnosticOf(e))
        } catch (e: EntitlementEvaluationFailure.Unavailable) {
            throw PersistenceFailure.Unavailable(e, diagnosticOf(e))
        } ?: return null
        val demo = profile.policyContentStatuses.count { it == MidPolicyContentStatus.DEMO }
        return MidPolicyContents(demoCount = demo, otherCount = profile.policyContentStatuses.size - demo)
    }

    private fun diagnosticOf(e: EntitlementEvaluationFailure): StoreDiagnostic? =
        if (e.vendorCode == null && e.sqlState == null) null else StoreDiagnostic(vendorCode = e.vendorCode, sqlState = e.sqlState)
}

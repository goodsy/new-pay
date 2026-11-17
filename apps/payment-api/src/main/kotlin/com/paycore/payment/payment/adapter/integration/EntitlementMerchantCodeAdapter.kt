package com.paycore.payment.payment.adapter.integration

import com.paycore.payment.entitlement.application.provided.EntitlementEvaluationFailure
import com.paycore.payment.entitlement.application.provided.MerchantCodeQuery
import com.paycore.payment.payment.application.provided.StoreDiagnostic
import com.paycore.payment.payment.application.required.MerchantCodePort
import com.paycore.payment.payment.application.required.PersistenceFailure
import com.paycore.payment.payment.domain.MerchantAccountId
import org.springframework.stereotype.Component

/**
 * [MerchantCodePort]의 운영 구현(MERCHANT-CODE-001 MC-4): entitlement 모듈의 공개 조회([MerchantCodeQuery], provided만)를 부르고 실패를 payment 저장소 실패 분류로 옮긴다
 * ([EntitlementSiteWebHostAdapter]와 같은 방식). 캐시 없음(ArchitectureSpec 캐시 금지 규칙 대상).
 */
@Component
class EntitlementMerchantCodeAdapter(private val query: MerchantCodeQuery) : MerchantCodePort {

    override fun merchantCodeOf(merchantAccountId: MerchantAccountId): String? =
        try {
            query.merchantCodeOf(merchantAccountId.value)
        } catch (e: EntitlementEvaluationFailure.Deadlock) {
            throw PersistenceFailure.Deadlock(e, diagnosticOf(e))
        } catch (e: EntitlementEvaluationFailure.LockTimeout) {
            throw PersistenceFailure.LockTimeout(e, diagnosticOf(e))
        } catch (e: EntitlementEvaluationFailure.Unavailable) {
            throw PersistenceFailure.Unavailable(e, diagnosticOf(e))
        }

    private fun diagnosticOf(e: EntitlementEvaluationFailure): StoreDiagnostic? =
        if (e.vendorCode == null && e.sqlState == null) null else StoreDiagnostic(vendorCode = e.vendorCode, sqlState = e.sqlState)
}

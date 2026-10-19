package com.paycore.payment.payment.adapter.integration

import com.paycore.payment.entitlement.application.provided.EntitlementEvaluationFailure
import com.paycore.payment.entitlement.application.provided.EntitlementHeadLock
import com.paycore.payment.payment.application.provided.StoreDiagnostic
import com.paycore.payment.payment.application.required.EntitlementHeadLockPort
import com.paycore.payment.payment.application.required.PersistenceFailure
import com.paycore.payment.payment.domain.MerchantAccountId
import org.springframework.stereotype.Component

/** [EntitlementHeadLockPort] 운영 구현 — entitlement 모듈 공개 연산([EntitlementHeadLock], provided만)으로 잠근다. 저장소 일시 실패는 payment [PersistenceFailure]로 옮긴다. */
@Component
class EntitlementHeadLockAdapter(private val lock: EntitlementHeadLock) : EntitlementHeadLockPort {
    override fun lockForShare(mid: MerchantAccountId) {
        try {
            lock.lockHeadForShare(mid.value)
        } catch (e: EntitlementEvaluationFailure.Deadlock) {
            throw PersistenceFailure.Deadlock(e, diagnosticOf(e))
        } catch (e: EntitlementEvaluationFailure.LockTimeout) {
            throw PersistenceFailure.LockTimeout(e, diagnosticOf(e))
        } catch (e: EntitlementEvaluationFailure.Unavailable) {
            throw PersistenceFailure.Unavailable(e, diagnosticOf(e))
        }
    }

    private fun diagnosticOf(e: EntitlementEvaluationFailure): StoreDiagnostic? =
        if (e.vendorCode == null && e.sqlState == null) null else StoreDiagnostic(vendorCode = e.vendorCode, sqlState = e.sqlState)
}

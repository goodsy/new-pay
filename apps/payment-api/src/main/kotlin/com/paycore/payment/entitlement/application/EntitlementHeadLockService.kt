package com.paycore.payment.entitlement.application

import com.paycore.payment.entitlement.application.provided.EntitlementEvaluationFailure
import com.paycore.payment.entitlement.application.provided.EntitlementHeadLock
import com.paycore.payment.entitlement.application.required.EntitlementPersistenceFailure
import com.paycore.payment.entitlement.application.required.EntitlementProjectionStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/** [EntitlementHeadLock] 구현: MID 소유 Merchant(비잠금) → head `FOR SHARE`. 쓰기·판정 없음. */
@Service
class EntitlementHeadLockService(private val store: EntitlementProjectionStore) : EntitlementHeadLock {

    @Transactional(propagation = Propagation.MANDATORY)
    override fun lockHeadForShare(mid: String) {
        try {
            store.findMidOwner(mid)?.let { store.lockHeadForShare(it) }
        } catch (e: EntitlementPersistenceFailure.Deadlock) {
            throw EntitlementEvaluationFailure.Deadlock(e, e.vendorCode, e.sqlState)
        } catch (e: EntitlementPersistenceFailure.LockTimeout) {
            throw EntitlementEvaluationFailure.LockTimeout(e, e.vendorCode, e.sqlState)
        } catch (e: EntitlementPersistenceFailure.Unavailable) {
            throw EntitlementEvaluationFailure.Unavailable(e, e.vendorCode, e.sqlState)
        }
    }
}

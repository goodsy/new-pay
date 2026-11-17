package com.paycore.payment.entitlement.application

import com.paycore.payment.entitlement.application.provided.EntitlementEvaluationFailure
import com.paycore.payment.entitlement.application.provided.MerchantCodeQuery
import com.paycore.payment.entitlement.application.required.EntitlementPersistenceFailure
import com.paycore.payment.entitlement.application.required.EntitlementProjectionStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 가맹점 코드 조회(MERCHANT-CODE-001 MC-4). MID 소속(비잠금) → head 비잠금 읽기 → head에 저장된 v4 코드. 판정을 하지 않으므로 head를 잠그지 않는다(Inbox 적용과
 * 직렬화할 필요 없음 — 코드는 MID 발급 = 계약 승인 뒤 불변). 막힌 head(INTEGRITY_CONFLICT 등)여도 마지막으로 적용한 내용의 코드다(표시값 — 판정은 막힘을 그대로 따른다).
 * 로그·지표 없음(코드 값은 label·로그에 넣지 않는다 — 명세 §9 Observability).
 */
@Service
class MerchantCodeService(private val store: EntitlementProjectionStore) : MerchantCodeQuery {

    @Transactional(propagation = Propagation.REQUIRED, readOnly = true)
    override fun merchantCodeOf(mid: String): String? =
        try {
            store.findMidOwner(mid)?.let { store.findHead(it) }?.merchantCode
        } catch (e: EntitlementPersistenceFailure.Deadlock) {
            throw EntitlementEvaluationFailure.Deadlock(e, e.vendorCode, e.sqlState)
        } catch (e: EntitlementPersistenceFailure.LockTimeout) {
            throw EntitlementEvaluationFailure.LockTimeout(e, e.vendorCode, e.sqlState)
        } catch (e: EntitlementPersistenceFailure.Unavailable) {
            throw EntitlementEvaluationFailure.Unavailable(e, e.vendorCode, e.sqlState)
        }
}

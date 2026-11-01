package com.paycore.payment.entitlement.application

import com.paycore.payment.entitlement.application.provided.EntitlementEvaluationFailure
import com.paycore.payment.entitlement.application.provided.MidPolicyContentStatus
import com.paycore.payment.entitlement.application.provided.MidPolicyProfile
import com.paycore.payment.entitlement.application.provided.MidPolicyProfileQuery
import com.paycore.payment.entitlement.application.required.EntitlementPersistenceFailure
import com.paycore.payment.entitlement.application.required.EntitlementProjectionStore
import com.paycore.payment.entitlement.domain.PolicyContentStatus
import com.paycore.payment.entitlement.domain.isSupportedSnapshotSchema
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * [MidPolicyProfileQuery] 구현(DEMO-SEED-001 §4.5). [SiteWebHostService]와 같은 읽기 순서: 호출자 TX 참여(MANDATORY) → MID 소속(비잠금) → head `FOR SHARE`
 * → 그 revision 내용(불변 행). 막힌 head·미지원 schema·내용 없음은 "그 MID의 최신 Snapshot 없음"(null — fail-closed)으로 본다.
 */
@Service
class MidPolicyProfileService(private val store: EntitlementProjectionStore) : MidPolicyProfileQuery {

    @Transactional(propagation = Propagation.MANDATORY)
    override fun find(mid: String): MidPolicyProfile? = try {
        read(mid)
    } catch (e: EntitlementPersistenceFailure.Deadlock) {
        throw EntitlementEvaluationFailure.Deadlock(e, e.vendorCode, e.sqlState)
    } catch (e: EntitlementPersistenceFailure.LockTimeout) {
        throw EntitlementEvaluationFailure.LockTimeout(e, e.vendorCode, e.sqlState)
    } catch (e: EntitlementPersistenceFailure.Unavailable) {
        throw EntitlementEvaluationFailure.Unavailable(e, e.vendorCode, e.sqlState)
    }

    private fun read(mid: String): MidPolicyProfile? {
        val merchantId = store.findMidOwner(mid) ?: return null
        val head = store.lockHeadForShare(merchantId) ?: return null
        if (head.isEmpty || head.blocked != null || !isSupportedSnapshotSchema(head.schemaVersion)) return null
        val content = store.findSnapshot(merchantId, head.revision) ?: return null
        val account = content.accounts.singleOrNull { it.mid == mid } ?: return null
        val statuses = account.terms?.policyAssignments.orEmpty().map {
            when (it.contentStatus) {
                PolicyContentStatus.UNDEFINED -> MidPolicyContentStatus.UNDEFINED
                PolicyContentStatus.DEMO -> MidPolicyContentStatus.DEMO
            }
        }
        return MidPolicyProfile(mid, head.revision, statuses)
    }
}

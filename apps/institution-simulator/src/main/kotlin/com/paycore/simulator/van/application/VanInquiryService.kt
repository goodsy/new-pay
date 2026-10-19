package com.paycore.simulator.van.application

import com.paycore.simulator.van.application.provided.InquireAtVanUseCase
import com.paycore.simulator.van.application.provided.VanInquiryRequest
import com.paycore.simulator.van.application.provided.VanInquiryResult
import com.paycore.simulator.van.application.required.DuplicateVanRecord
import com.paycore.simulator.van.application.required.VanClock
import com.paycore.simulator.van.application.required.VanIdGenerator
import com.paycore.simulator.van.application.required.VanInquiryLogStore
import com.paycore.simulator.van.application.required.VanLedgerStore
import com.paycore.simulator.van.application.required.VanPendingDecisionStore
import com.paycore.simulator.van.application.required.VanProfiles
import com.paycore.simulator.van.domain.VanBehaviour
import com.paycore.simulator.van.domain.VanInquiry
import com.paycore.simulator.van.domain.VanInquiryJudgement
import com.paycore.simulator.van.domain.VanNamespace
import com.paycore.simulator.van.domain.VanProfile
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * 가상 VAN 조회 Use Case(AUTH-001 §5.4). 원 요청 ID 참조만 쓰고 새 기관 요청 ID를 만들지 않는다. 무효과 종결 표지·예약 확정은 원장 UNIQUE(namespace)에서 결정 INSERT와
 * 경합하며(진 쪽은 새 TX에서 이긴 쪽 기록으로 응답 — C30, rev 0.3 H-3), 조회 기록은 같은 TX에 append한다.
 */
@Service
class VanInquiryService(
    transactionManager: PlatformTransactionManager,
    private val ledger: VanLedgerStore,
    private val pendings: VanPendingDecisionStore,
    private val logs: VanInquiryLogStore,
    private val profiles: VanProfiles,
    private val ids: VanIdGenerator,
    private val clock: VanClock,
) : InquireAtVanUseCase {

    private val tx = TransactionTemplate(transactionManager)

    override fun inquire(clientId: String, inquiry: VanInquiryRequest): VanInquiryResult {
        val profile = profiles.profileOf(inquiry.institutionAccount) ?: return VanInquiryResult.CloseWithoutRecord
        if (inquiry.environment != TEST) return VanInquiryResult.CloseWithoutRecord
        val namespace = VanNamespace(clientId, inquiry.environment, inquiry.institutionAccount, VanNamespace.AUTHORIZE, inquiry.originalRequestId)
        repeat(MAX_ATTEMPTS) {
            try {
                return checkNotNull(tx.execute { judge(namespace, inquiry, profile) })
            } catch (e: DuplicateVanRecord) {
                // 동시 기록에 졌다 — 새 TX에서 이긴 쪽 기록으로 다시 판정한다.
            }
        }
        return VanInquiryResult.CloseWithoutRecord
    }

    private fun judge(namespace: VanNamespace, inquiry: VanInquiryRequest, profile: VanProfile): VanInquiryResult {
        val existing = ledger.find(namespace)
        val pending = if (existing == null) pendings.find(namespace) else null
        val behaviour = VanBehaviour.ofCard(existing?.cardRef ?: pending?.cardRef)
        val at = clock.now()
        val result = when (val j = VanInquiry.judge(namespace, existing, pending, inquiry.closeIfAbsent, profile, logs.count(namespace), behaviour, ids.next(at), at)) {
            is VanInquiryJudgement.Found -> VanInquiryResult.Found(j.entry)
            is VanInquiryJudgement.NoEffectConfirmed -> VanInquiryResult.NoEffectConfirmed(j.entry.entryId)
            is VanInquiryJudgement.Close -> {
                ledger.insert(j.entry)
                VanInquiryResult.NoEffectConfirmed(j.entry.entryId)
            }
            is VanInquiryJudgement.StillPending -> VanInquiryResult.Pending(j.pending.pendingId)
            is VanInquiryJudgement.ResolvePending -> {
                ledger.insert(j.entry)
                pendings.markResolved(j.pending, at)
                VanInquiryResult.Found(j.entry)
            }
            VanInquiryJudgement.NotFound -> VanInquiryResult.NotFound
            VanInquiryJudgement.Unavailable -> VanInquiryResult.Unavailable
        }
        logs.append(namespace, inquiry.inquiryId, inquiry.closeIfAbsent, resultName(result), at)
        return result
    }

    private fun resultName(r: VanInquiryResult): String = when (r) {
        is VanInquiryResult.Found -> "FOUND"
        is VanInquiryResult.Pending -> "PENDING"
        is VanInquiryResult.NoEffectConfirmed -> "NO_EFFECT_CONFIRMED"
        VanInquiryResult.NotFound -> "NOT_FOUND"
        VanInquiryResult.Unavailable -> "UNAVAILABLE"
        VanInquiryResult.CloseWithoutRecord -> error("not logged")
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val TEST = "TEST"
    }
}

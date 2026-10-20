package com.paycore.simulator.van.application

import com.paycore.simulator.van.application.provided.InquireCancelAtVanUseCase
import com.paycore.simulator.van.application.provided.VanCancelInquiryRequest
import com.paycore.simulator.van.application.provided.VanCancelInquiryResult
import com.paycore.simulator.van.application.required.DuplicateVanRecord
import com.paycore.simulator.van.application.required.VanCancelLedgerStore
import com.paycore.simulator.van.application.required.VanClock
import com.paycore.simulator.van.application.required.VanIdGenerator
import com.paycore.simulator.van.application.required.VanInquiryLogStore
import com.paycore.simulator.van.application.required.VanProfiles
import com.paycore.simulator.van.domain.VanCancelInquiry
import com.paycore.simulator.van.domain.VanCancelInquiryJudgement
import com.paycore.simulator.van.domain.VanNamespace
import com.paycore.simulator.van.domain.VanProfile
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

/**
 * 가상 VAN 취소 조회 Use Case(CANCEL-001 §6.5). 원 취소 요청 ID 참조만 쓰고 새 기관 요청 ID를 만들지 않는다. 무효과 종결 표지는 취소 원장 UNIQUE(namespace)에서 취소 결정
 * INSERT와 경합하며(진 쪽은 새 TX에서 이긴 쪽 기록으로 응답 — K16), 조회 기록(operation CANCEL)은 같은 TX에 append한다.
 */
@Service
class VanCancelInquiryService(
    transactionManager: PlatformTransactionManager,
    private val cancels: VanCancelLedgerStore,
    private val logs: VanInquiryLogStore,
    private val profiles: VanProfiles,
    private val ids: VanIdGenerator,
    private val clock: VanClock,
) : InquireCancelAtVanUseCase {

    // 검토 #2 n-3: 격리 수준 명시 고정(REPEATABLE READ) — 취소 판정과 같은 가정.
    private val tx = TransactionTemplate(transactionManager).apply { isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ }

    override fun inquire(clientId: String, inquiry: VanCancelInquiryRequest): VanCancelInquiryResult {
        val profile = profiles.profileOf(inquiry.institutionAccount) ?: return VanCancelInquiryResult.CloseWithoutRecord
        if (inquiry.environment != TEST) return VanCancelInquiryResult.CloseWithoutRecord
        val namespace = VanNamespace(clientId, inquiry.environment, inquiry.institutionAccount, VanNamespace.CANCEL, inquiry.originalRequestId)
        repeat(MAX_ATTEMPTS) {
            try {
                return checkNotNull(tx.execute { judge(namespace, inquiry, profile) })
            } catch (e: DuplicateVanRecord) {
                // 동시 기록에 졌다 — 새 TX에서 이긴 쪽 기록으로 다시 판정한다.
            }
        }
        return VanCancelInquiryResult.CloseWithoutRecord
    }

    private fun judge(namespace: VanNamespace, inquiry: VanCancelInquiryRequest, profile: VanProfile): VanCancelInquiryResult {
        val at = clock.now()
        val result = when (val j = VanCancelInquiry.judge(namespace, cancels.find(namespace), inquiry.closeIfAbsent, profile, logs.count(namespace), ids.next(at), at)) {
            is VanCancelInquiryJudgement.Found -> VanCancelInquiryResult.Found(j.entry)
            is VanCancelInquiryJudgement.NoEffectConfirmed -> VanCancelInquiryResult.NoEffectConfirmed(j.entry.entryId)
            is VanCancelInquiryJudgement.Close -> {
                cancels.insert(j.entry)
                VanCancelInquiryResult.NoEffectConfirmed(j.entry.entryId)
            }
            VanCancelInquiryJudgement.NotFound -> VanCancelInquiryResult.NotFound
            VanCancelInquiryJudgement.Unavailable -> VanCancelInquiryResult.Unavailable
        }
        logs.append(namespace, inquiry.inquiryId, inquiry.closeIfAbsent, resultName(result), at)
        return result
    }

    private fun resultName(r: VanCancelInquiryResult): String = when (r) {
        is VanCancelInquiryResult.Found -> "FOUND"
        is VanCancelInquiryResult.NoEffectConfirmed -> "NO_EFFECT_CONFIRMED"
        VanCancelInquiryResult.NotFound -> "NOT_FOUND"
        VanCancelInquiryResult.Unavailable -> "UNAVAILABLE"
        VanCancelInquiryResult.CloseWithoutRecord -> error("not logged")
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val TEST = "TEST"
    }
}

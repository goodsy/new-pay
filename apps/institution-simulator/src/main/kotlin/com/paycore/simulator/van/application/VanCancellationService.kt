package com.paycore.simulator.van.application

import com.paycore.simulator.van.application.provided.CancelAtVanUseCase
import com.paycore.simulator.van.application.provided.VanCancelResult
import com.paycore.simulator.van.application.required.DuplicateVanRecord
import com.paycore.simulator.van.application.required.VanCancelLedgerStore
import com.paycore.simulator.van.application.required.VanClock
import com.paycore.simulator.van.application.required.VanIdGenerator
import com.paycore.simulator.van.application.required.VanLedgerStore
import com.paycore.simulator.van.application.required.VanProfiles
import com.paycore.simulator.van.domain.VanCancelBehaviour
import com.paycore.simulator.van.domain.VanCancelJudgement
import com.paycore.simulator.van.domain.VanCancelRequest
import com.paycore.simulator.van.domain.VanCancellation
import com.paycore.simulator.van.domain.VanNamespace
import com.paycore.simulator.van.domain.VanProfile
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

/**
 * 가상 VAN 취소 Use Case(CANCEL-001 §6.3). 한 로컬 TX에서 취소 원장·원 승인 원장·승인된 취소 n·Σ(한 SELECT — m-6)를 읽고 [VanCancellation]으로 판정한 뒤 결정을 INSERT하고
 * 커밋한다 — 커밋 뒤에만 전송 계층이 응답한다. 같은 namespace의 동시 기록(취소 결정 vs 무효과 종결 — K16)과 같은 원 승인의 같은 승인 순번(K15)은 UNIQUE가 하나로 만들고,
 * 진 쪽은 새 TX에서 이긴 쪽 기록으로 다시 판정한다(최대 3회, 소진 시 기록 없이 연결 종료 — AU n-2와 같다).
 */
@Service
class VanCancellationService(
    transactionManager: PlatformTransactionManager,
    private val cancels: VanCancelLedgerStore,
    private val ledger: VanLedgerStore,
    private val profiles: VanProfiles,
    private val ids: VanIdGenerator,
    private val clock: VanClock,
) : CancelAtVanUseCase {

    // 검토 #2 n-3: 격리 수준 명시 고정(REPEATABLE READ — MySQL 기본과 같지만 설정 변경에 기대지 않는다). n·Σ 한 SELECT·1062 뒤 새 TX 재판정이 이 가정 위에 있다.
    private val tx = TransactionTemplate(transactionManager).apply { isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ }

    override fun cancel(clientId: String, request: VanCancelRequest): VanCancelResult {
        val profile = profiles.profileOf(request.institutionAccount) ?: return VanCancelResult.CloseWithoutRecord
        repeat(MAX_ATTEMPTS) {
            try {
                return checkNotNull(tx.execute { decide(clientId, request, profile) })
            } catch (e: DuplicateVanRecord) {
                // 동시 기록에 졌다 — 새 TX에서 이긴 쪽 기록으로 다시 판정한다.
            }
        }
        return VanCancelResult.CloseWithoutRecord
    }

    private fun decide(clientId: String, request: VanCancelRequest, profile: VanProfile): VanCancelResult {
        val existing = cancels.find(VanNamespace.of(clientId, request))
        val original = if (existing == null) {
            ledger.find(VanNamespace(clientId, request.environment, request.institutionAccount, VanNamespace.AUTHORIZE, request.originalRequestId))
        } else {
            null
        }
        val approved = cancels.approvedTotals(clientId, request.environment, request.institutionAccount, request.originalRequestId)
        val at = clock.now()
        return when (val j = VanCancellation.judge(clientId, request, existing, original, approved, profile, ids.next(at), at)) {
            is VanCancelJudgement.Replay -> VanCancelResult.Decided(j.entry, VanCancelBehaviour.NORMAL)
            VanCancelJudgement.Conflict -> VanCancelResult.Conflict
            VanCancelJudgement.RejectedClosed -> VanCancelResult.RejectedClosed
            VanCancelJudgement.Refused, VanCancelJudgement.DropWithoutRecord -> VanCancelResult.CloseWithoutRecord
            is VanCancelJudgement.Decide -> {
                cancels.insert(j.entry)
                VanCancelResult.Decided(j.entry, j.behaviour)
            }
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}

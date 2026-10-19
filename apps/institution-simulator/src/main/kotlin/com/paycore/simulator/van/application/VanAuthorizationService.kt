package com.paycore.simulator.van.application

import com.paycore.simulator.authentication.application.provided.AuthenticationEvidenceLookup
import com.paycore.simulator.authentication.application.provided.AuthenticationEvidenceView
import com.paycore.simulator.van.application.provided.AuthorizeAtVanUseCase
import com.paycore.simulator.van.application.provided.VanAuthorizationResult
import com.paycore.simulator.van.application.required.DuplicateVanRecord
import com.paycore.simulator.van.application.required.VanClock
import com.paycore.simulator.van.application.required.VanEvidenceUseStore
import com.paycore.simulator.van.application.required.VanIdGenerator
import com.paycore.simulator.van.application.required.VanLedgerStore
import com.paycore.simulator.van.application.required.VanPendingDecisionStore
import com.paycore.simulator.van.application.required.VanProfiles
import com.paycore.simulator.van.domain.VanAuthRequest
import com.paycore.simulator.van.domain.VanAuthorization
import com.paycore.simulator.van.domain.VanBehaviour
import com.paycore.simulator.van.domain.VanEvidence
import com.paycore.simulator.van.domain.VanJudgement
import com.paycore.simulator.van.domain.VanNamespace
import com.paycore.simulator.van.domain.VanPlan
import com.paycore.simulator.van.domain.VanPlanKind
import com.paycore.simulator.van.domain.VanProfile
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * 가상 VAN 승인 Use Case(AUTH-001 §5.3). 한 로컬 TX에서 원장·예약·증거 사용을 읽고 [VanAuthorization]으로 판정한 뒤 결과를 기록하고 커밋한다 — 커밋 뒤에만 전송 계층이
 * 응답한다(거절도 같다, m-7). 같은 namespace·같은 증거의 동시 기록은 UNIQUE가 직렬화하고, 진 쪽은 새 TX에서 이긴 쪽의 기록을 읽어 다시 판정한다(종결 표지면 REJECTED_CLOSED,
 * 결정이면 재응답 — C30). 인증 증거는 인증 모듈 provided 조회로만 읽는다(AU-13).
 */
@Service
class VanAuthorizationService(
    transactionManager: PlatformTransactionManager,
    private val ledger: VanLedgerStore,
    private val pendings: VanPendingDecisionStore,
    private val evidenceUses: VanEvidenceUseStore,
    private val evidences: AuthenticationEvidenceLookup,
    private val profiles: VanProfiles,
    private val ids: VanIdGenerator,
    private val clock: VanClock,
) : AuthorizeAtVanUseCase {

    private val tx = TransactionTemplate(transactionManager)

    override fun authorize(clientId: String, request: VanAuthRequest): VanAuthorizationResult {
        val profile = profiles.profileOf(request.institutionAccount) ?: return VanAuthorizationResult.CloseWithoutRecord
        repeat(MAX_ATTEMPTS) {
            try {
                return checkNotNull(tx.execute { decide(clientId, request, profile) })
            } catch (e: DuplicateVanRecord) {
                // 동시 기록에 졌다 — 새 TX에서 이긴 쪽 기록으로 다시 판정한다.
            }
        }
        // 검토 #2 n-2: 재판정 상한 — 기록 없이 연결을 닫는다(PG는 OutcomeUnknown → 조회로 확인).
        return VanAuthorizationResult.CloseWithoutRecord
    }

    private fun decide(clientId: String, request: VanAuthRequest, profile: VanProfile): VanAuthorizationResult {
        val namespace = VanNamespace.of(clientId, request)
        val existing = ledger.find(namespace)
        val pending = if (existing == null) pendings.find(namespace) else null
        val evidenceRef = request.authentication.evidenceRef
        val evidence = evidences.find(evidenceRef)?.let(::toVan)
        val usedBy = evidenceUses.namespaceOf(evidenceRef)
        val at = clock.now()
        return when (val j = VanAuthorization.judge(clientId, request, existing, pending, evidence, usedBy != null && usedBy != namespace, profile, ids.next(at), at)) {
            is VanJudgement.Replay -> VanAuthorizationResult.Decided(j.entry, VanBehaviour.NORMAL)
            VanJudgement.Conflict -> VanAuthorizationResult.Conflict
            VanJudgement.RejectedClosed -> VanAuthorizationResult.RejectedClosed
            VanJudgement.Refused, VanJudgement.DropWithoutRecord -> VanAuthorizationResult.CloseWithoutRecord
            is VanJudgement.Decide -> {
                ledger.insert(j.entry)
                if (j.consumesEvidence) evidenceUses.insert(evidenceRef, namespace, at)
                VanAuthorizationResult.Decided(j.entry, j.behaviour)
            }
            is VanJudgement.Hold -> {
                pendings.insert(j.pending)
                evidenceUses.insert(evidenceRef, namespace, at)
                VanAuthorizationResult.Pending(j.pending.pendingId)
            }
            is VanJudgement.StillPending -> VanAuthorizationResult.Pending(j.pending.pendingId)
            is VanJudgement.ResolvePending -> {
                ledger.insert(j.entry)
                pendings.markResolved(j.pending, at)
                VanAuthorizationResult.Decided(j.entry, VanBehaviour.NORMAL)
            }
        }
    }

    private fun toVan(v: AuthenticationEvidenceView): VanEvidence? {
        val plan = runCatching { VanPlan(VanPlanKind.valueOf(v.planKind), v.planMonths) }.getOrNull() ?: return null
        return VanEvidence(
            evidenceRef = v.evidenceRef, clientId = v.clientId, authenticated = v.authenticated, environment = v.environment, merchantRef = v.merchantRef,
            amountMinor = v.amountMinor, currency = v.currency, plan = plan, cardRef = v.cardRef, cardType = v.cardType, issuerCode = v.issuerCode,
            acquirerCode = v.acquirerCode, providerCode = v.providerCode, validUntil = v.validUntil,
        )
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}

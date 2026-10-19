package com.paycore.simulator.van.domain

import java.time.Instant

enum class VanPendingState { PENDING, RESOLVED }

/**
 * PENDING 시나리오의 예약 결정(AUTH-001 §5.5 `van_pending_decision`). 기관이 "처리 중"이라고 답한 요청이다 — 확정 사실이 아니며 무효과 종결 대상도 아니다(rev 0.3 H-3).
 * [resolveAfter]부터 같은 요청·조회가 결정을 확정한다: 결정은 원장에 새 DECISION 행으로 INSERT되고(같은 UNIQUE(namespace) — 종결 표지와 경합하면 하나만 생긴다) 예약은
 * RESOLVED가 된다. 예약 승인 조건은 요청·증거에서 그대로 온다(이 시나리오는 정상 승인).
 */
class VanPendingDecision private constructor(
    val pendingId: String,
    val namespace: VanNamespace,
    val payloadHash: String,
    val evidenceRef: String,
    val cardRef: String?,
    val amountMinor: Long,
    val currency: String,
    val plan: VanPlan,
    val cardType: String,
    val resolveAfter: Instant,
    val createdAt: Instant,
) {
    var state: VanPendingState = VanPendingState.PENDING
        private set

    var resolvedEntryId: String? = null
        private set

    fun isDue(at: Instant): Boolean = !at.isBefore(resolveAfter)

    /** 예약을 결정으로 확정한다(새 원장 DECISION 행 — 호출자가 같은 TX에서 INSERT한다). */
    fun resolve(ids: VanDecisionIds, at: Instant): VanLedgerEntry {
        check(state == VanPendingState.PENDING) { "a pending reservation resolves once" }
        require(isDue(at)) { "the reservation is not due" }
        val entry = VanLedgerEntry.approved(
            namespace, ids.decisionId, payloadHash, evidenceRef, cardRef,
            VanApproval(ids.approvalNumber, ids.providerTransactionId, amountMinor, currency, plan, cardType, VanDates.seoulDate(at), VanDates.seoulDate(at)), at,
        )
        state = VanPendingState.RESOLVED
        resolvedEntryId = entry.entryId
        return entry
    }

    override fun toString(): String = "VanPendingDecision(pendingId=$pendingId, state=$state, resolveAfter=$resolveAfter)"

    companion object {
        fun hold(pendingId: String, namespace: VanNamespace, request: VanAuthRequest, cardRef: String?, cardType: String, resolveAfter: Instant, at: Instant) =
            VanPendingDecision(
                pendingId, namespace, request.payloadHash(), request.authentication.evidenceRef, cardRef, request.amountMinor, request.currency, request.plan, cardType,
                resolveAfter, at,
            )

        /** 영속 복원(Mapper 전용). */
        @Suppress("LongParameterList")
        fun reconstitute(
            pendingId: String, namespace: VanNamespace, payloadHash: String, evidenceRef: String, cardRef: String?, amountMinor: Long, currency: String, plan: VanPlan,
            cardType: String, resolveAfter: Instant, createdAt: Instant, state: VanPendingState, resolvedEntryId: String?,
        ): VanPendingDecision {
            require((state == VanPendingState.RESOLVED) == (resolvedEntryId != null)) { "a resolved reservation names its decision" }
            return VanPendingDecision(pendingId, namespace, payloadHash, evidenceRef, cardRef, amountMinor, currency, plan, cardType, resolveAfter, createdAt).also {
                it.state = state
                it.resolvedEntryId = resolvedEntryId
            }
        }
    }
}

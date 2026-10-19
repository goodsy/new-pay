package com.paycore.simulator.van.domain

import java.time.Instant

/**
 * 가상 VAN 원 요청 ID 조회(AUTH-001 §5.4, 순수 함수). 판정 순서:
 * 1. 요청 ID의 ULID 시각이 조회 보존기간보다 오래면 UNAVAILABLE(원장 행이 있어도 — "없음"으로 오해하지 않게, rev 0.3 m-7). 종결도 하지 않는다.
 * 2. (Demo) 첫 조회 UNAVAILABLE 카드.
 * 3. 원장 결정 → FOUND, 종결 표지 → NO_EFFECT_CONFIRMED(같은 closureId).
 * 4. PENDING 예약: 기한 전 PENDING(closeIfAbsent여도 종결하지 않음 — H-3), 기한이면 결정 확정 → FOUND.
 * 5. closeIfAbsent이고 Capability `confirmsNoEffect` 프로파일(A)이며 요청 ID 시각이 `closableAfter`보다 오래됐으면 종결 표지 INSERT → NO_EFFECT_CONFIRMED.
 *    요청 ID 시각을 읽을 수 없으면 종결하지 않는다.
 * 6. 그 밖 NOT_FOUND.
 */
object VanInquiry {

    @Suppress("LongParameterList", "ReturnCount")
    fun judge(
        namespace: VanNamespace,
        existing: VanLedgerEntry?,
        pending: VanPendingDecision?,
        closeIfAbsent: Boolean,
        profile: VanProfile,
        previousInquiries: Long,
        behaviour: VanBehaviour,
        ids: VanDecisionIds,
        at: Instant,
    ): VanInquiryJudgement {
        val requestedAt = VanUlid.timeOf(namespace.requestId)
        if (requestedAt != null && requestedAt.plus(profile.inquiryRetention).isBefore(at)) return VanInquiryJudgement.Unavailable
        if (behaviour == VanBehaviour.INQUIRY_UNAVAILABLE_ONCE && previousInquiries == 0L) return VanInquiryJudgement.Unavailable
        if (existing != null) {
            require(existing.namespace == namespace) { "the ledger row belongs to another namespace" }
            return when (existing.kind) {
                VanEntryKind.DECISION -> VanInquiryJudgement.Found(existing)
                VanEntryKind.CLOSURE -> VanInquiryJudgement.NoEffectConfirmed(existing)
            }
        }
        if (pending != null && pending.state == VanPendingState.PENDING) {
            require(pending.namespace == namespace) { "the reservation belongs to another namespace" }
            return if (pending.isDue(at)) VanInquiryJudgement.ResolvePending(pending, pending.resolve(ids, at)) else VanInquiryJudgement.StillPending(pending)
        }
        val closable = closeIfAbsent && profile.confirmsNoEffect && requestedAt != null && !requestedAt.plus(profile.closableAfter).isAfter(at)
        return if (closable) VanInquiryJudgement.Close(VanLedgerEntry.closure(namespace, ids.closureId, null, at)) else VanInquiryJudgement.NotFound
    }
}

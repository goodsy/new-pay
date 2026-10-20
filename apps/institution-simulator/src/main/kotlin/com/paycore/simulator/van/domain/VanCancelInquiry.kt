package com.paycore.simulator.van.domain

import java.time.Instant

/**
 * 가상 VAN 취소 조회(CANCEL-001 §6.5 — AU §5.4 규칙을 취소 namespace와 프로파일 **취소** Capability에 적용, 순수 함수):
 * 1. 취소 요청 ID 시각이 `cancel.inquiryRetention`보다 오래면 UNAVAILABLE(행이 있어도, 종결도 하지 않는다).
 * 2. (Demo) 첫 조회 UNAVAILABLE 카드(기록된 취소의 원 승인 카드).
 * 3. 결정 → FOUND, 종결 표지 → NO_EFFECT_CONFIRMED.
 * 4. closeIfAbsent이고 `cancel.confirmsNoEffect`이며 요청 ID 시각 + `cancel.closableAfter` ≤ 시각이면 종결 표지 INSERT → NO_EFFECT_CONFIRMED.
 * 5. 그 밖 NOT_FOUND.
 */
object VanCancelInquiry {

    @Suppress("LongParameterList", "ReturnCount")
    fun judge(
        namespace: VanNamespace,
        existing: VanCancelEntry?,
        closeIfAbsent: Boolean,
        profile: VanProfile,
        previousInquiries: Long,
        ids: VanDecisionIds,
        at: Instant,
    ): VanCancelInquiryJudgement {
        require(namespace.operation == VanNamespace.CANCEL) { "a cancel inquiry reads the CANCEL namespace" }
        val cancel = profile.cancel
        val requestedAt = VanUlid.timeOf(namespace.requestId)
        if (requestedAt != null && requestedAt.plus(cancel.inquiryRetention).isBefore(at)) return VanCancelInquiryJudgement.Unavailable
        if (existing != null) {
            require(existing.namespace == namespace) { "the cancel ledger row belongs to another namespace" }
            if (VanCancelBehaviour.ofCard(existing.cardRef) == VanCancelBehaviour.INQUIRY_UNAVAILABLE_ONCE && previousInquiries == 0L) {
                return VanCancelInquiryJudgement.Unavailable
            }
            return when (existing.kind) {
                VanEntryKind.DECISION -> VanCancelInquiryJudgement.Found(existing)
                VanEntryKind.CLOSURE -> VanCancelInquiryJudgement.NoEffectConfirmed(existing)
            }
        }
        val closable = closeIfAbsent && cancel.confirmsNoEffect && requestedAt != null && !requestedAt.plus(cancel.closableAfter).isAfter(at)
        return if (closable) VanCancelInquiryJudgement.Close(VanCancelEntry.closure(namespace, ids.closureId, at)) else VanCancelInquiryJudgement.NotFound
    }
}

package com.paycore.master.merchant.domain

import java.time.Instant

/**
 * 통화 활성화 기록(D-MD-018): (MID, 통화, 기간 [from, to), 근거). CreatePayment CURRENCY_NOT_ENABLED 판정의 근거다(승인 시작의 (CSA, MID, 통화)
 * 정책 Assignment 요구는 AUTH-001). 기간 규칙(해석 — D-MD-005 "거래 당시 근거를 바꾸지 않는다" 유추, Coordinator 채택 2026-10-13):
 * 소급 시작·소급 종료 금지(from·to ≥ 명령 시각), 미래 종료 예약 허용, 같은 (MID, 통화) 기간 중첩 금지, 종료는 열린 기록에만 한 번.
 */
class CurrencyEnablement private constructor(
    val id: CurrencyEnablementId,
    val merchantAccountId: MerchantAccountId,
    val unit: CurrencyUnit,
    period: EffectivePeriod,
    val evidenceRef: EvidenceRef,
    val createdAt: Instant,
    val createdBy: ActorRef,
    endedAt: Instant?,
    endedBy: ActorRef?,
) {
    var period: EffectivePeriod = period
        private set

    var endedAt: Instant? = endedAt
        private set

    var endedBy: ActorRef? = endedBy
        private set

    init {
        require(!period.from.isBefore(createdAt)) { "a stored enablement cannot start before it was recorded" }
        require((endedAt == null) == (endedBy == null)) { "end time and actor are recorded together" }
    }

    /** 열린(종료 시각 미정) 기록에 종료 시각을 정한다. [effectiveTo] ≥ [at](소급 종료 금지), > from. */
    fun end(effectiveTo: Instant, at: Instant, by: ActorRef) {
        if (period.to != null) throw MasterRuleViolation(MasterRejection.CURRENCY_ENABLEMENT_ALREADY_ENDED)
        if (effectiveTo.isBefore(at)) throw MasterRuleViolation(MasterRejection.CURRENCY_ENABLEMENT_RETROACTIVE)
        val ended = EffectivePeriod.ofMillis(period.from, effectiveTo)
        period = ended
        endedAt = at
        endedBy = by
    }

    override fun toString(): String = "CurrencyEnablement(id=${id.value}, currency=${unit.code.value}, period=$period)"

    companion object {
        /**
         * 새 활성화. [existing]은 같은 MID의 기존 기록 전체(호출자가 merchant 잠금 아래 읽는다). 시작은 [at] 이후(소급 금지), 같은 통화 기록과
         * 기간이 겹치면 거절한다.
         */
        @Suppress("LongParameterList")
        fun enable(
            id: CurrencyEnablementId,
            account: MerchantAccount,
            unit: CurrencyUnit,
            period: EffectivePeriod,
            existing: List<CurrencyEnablement>,
            evidence: EvidenceRef,
            at: Instant,
            by: ActorRef,
        ): CurrencyEnablement {
            require(existing.all { it.merchantAccountId == account.id }) { "existing enablements must belong to the MID" }
            account.requireActive()
            if (period.from.isBefore(at)) throw MasterRuleViolation(MasterRejection.CURRENCY_ENABLEMENT_RETROACTIVE)
            existing.firstOrNull { it.unit.code == unit.code && it.period.overlaps(period) }?.let {
                throw MasterRuleViolation(MasterRejection.CURRENCY_ENABLEMENT_OVERLAP, it.id.value)
            }
            return CurrencyEnablement(id, account.id, unit, period, evidence, at, by, null, null)
        }

        @Suppress("LongParameterList")
        fun reconstitute(
            id: CurrencyEnablementId,
            merchantAccountId: MerchantAccountId,
            unit: CurrencyUnit,
            period: EffectivePeriod,
            evidenceRef: EvidenceRef,
            createdAt: Instant,
            createdBy: ActorRef,
            endedAt: Instant?,
            endedBy: ActorRef?,
        ) = CurrencyEnablement(id, merchantAccountId, unit, period, evidenceRef, createdAt, createdBy, endedAt, endedBy)
    }
}

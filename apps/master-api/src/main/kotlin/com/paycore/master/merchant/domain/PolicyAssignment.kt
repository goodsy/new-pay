package com.paycore.master.merchant.domain

import java.time.Instant

/**
 * (CSA, MID, 통화, 종류) 정책 Assignment(D-MD-018, 명세 §3.3). CSA는 **MID의 발급 CSA와 같아야 한다**(도출 — Coordinator 확인). 대상 정책 버전은
 * 불변, 바꾸려면 종료 + 새 Assignment. 기간: 소급 금지, 같은 (MID, 통화, 종류) 중첩 금지, 종료 한 번(D-MS2-002 자정 제한은 한도 정책에만).
 */
class PolicyAssignment private constructor(
    val id: PolicyAssignmentId,
    val merchantId: MerchantId,
    val agreementId: ContractServiceAgreementId,
    val merchantAccountId: MerchantAccountId,
    val currency: CurrencyCode,
    val kind: PolicyKind,
    val policyVersionId: PolicyVersionId,
    period: EffectivePeriod,
    val reason: DecisionReason,
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
        require(!period.from.isBefore(createdAt)) { "a stored assignment cannot start before it was recorded" }
        require((endedAt == null) == (endedBy == null)) { "end time and actor are recorded together" }
    }

    fun end(effectiveTo: Instant, at: Instant, by: ActorRef) {
        if (period.to != null) throw MasterRuleViolation(MasterRejection.POLICY_ASSIGNMENT_ALREADY_ENDED)
        if (effectiveTo.isBefore(at)) throw MasterRuleViolation(MasterRejection.POLICY_ASSIGNMENT_RETROACTIVE)
        period = EffectivePeriod.ofMillis(period.from, effectiveTo)
        endedAt = at
        endedBy = by
    }

    override fun toString(): String = "PolicyAssignment(id=${id.value}, kind=$kind, currency=${currency.value}, period=$period)"

    companion object {
        /** [existing]은 같은 MID의 기존 Assignment 전체(merchant 잠금 아래). */
        @Suppress("LongParameterList")
        fun assign(
            id: PolicyAssignmentId,
            account: MerchantAccount,
            agreementId: ContractServiceAgreementId,
            currency: CurrencyUnit,
            version: PolicyVersion,
            period: EffectivePeriod,
            existing: List<PolicyAssignment>,
            reason: DecisionReason,
            at: Instant,
            by: ActorRef,
        ): PolicyAssignment {
            require(existing.all { it.merchantAccountId == account.id }) { "existing assignments must belong to the MID" }
            account.requireActive()
            if (agreementId != account.issuingBasis.agreementId) throw MasterRuleViolation(MasterRejection.POLICY_ASSIGNMENT_AGREEMENT_MISMATCH)
            if (period.from.isBefore(at)) throw MasterRuleViolation(MasterRejection.POLICY_ASSIGNMENT_RETROACTIVE)
            existing.firstOrNull { it.currency == currency.code && it.kind == version.kind && it.period.overlaps(period) }?.let {
                throw MasterRuleViolation(MasterRejection.POLICY_ASSIGNMENT_OVERLAP, it.id.value)
            }
            return PolicyAssignment(
                id, account.merchantId, agreementId, account.id, currency.code, version.kind, version.id, period, reason, at, by, null, null,
            )
        }

        /** 공백 없는 교체: [old]를 [switchAt]에 끝내고 같은 (MID, 통화, 종류)에 [version]을 [switchAt]부터 건다. */
        @Suppress("LongParameterList")
        fun replace(
            old: PolicyAssignment,
            newId: PolicyAssignmentId,
            account: MerchantAccount,
            version: PolicyVersion,
            switchAt: Instant,
            existing: List<PolicyAssignment>,
            currency: CurrencyUnit,
            reason: DecisionReason,
            at: Instant,
            by: ActorRef,
        ): PolicyAssignment {
            require(old.merchantAccountId == account.id && old.currency == currency.code) { "a replacement keeps the MID and currency" }
            if (old.kind != version.kind) throw MasterRuleViolation(MasterRejection.POLICY_KIND_MISMATCH)
            old.end(switchAt, at, by)
            return assign(newId, account, old.agreementId, currency, version, EffectivePeriod(switchAt), existing, reason, at, by)
        }

        @Suppress("LongParameterList")
        fun reconstitute(
            id: PolicyAssignmentId,
            merchantId: MerchantId,
            agreementId: ContractServiceAgreementId,
            merchantAccountId: MerchantAccountId,
            currency: CurrencyCode,
            kind: PolicyKind,
            policyVersionId: PolicyVersionId,
            period: EffectivePeriod,
            reason: DecisionReason,
            createdAt: Instant,
            createdBy: ActorRef,
            endedAt: Instant?,
            endedBy: ActorRef?,
        ) = PolicyAssignment(
            id, merchantId, agreementId, merchantAccountId, currency, kind, policyVersionId, period, reason, createdAt, createdBy, endedAt, endedBy,
        )
    }
}

package com.paycore.master.merchant.domain

import java.time.Instant

/** Revision 승인 사실(append-only 확정 사실, D-MD-004). */
data class RevisionApproval(val approvedAt: Instant, val approvedBy: ActorRef)

/** 계약 Revision. 내용(Scope·효력 기간)은 등록 후 불변이며 변경은 새 Revision이다(D-MD-004). 승인은 별도 사실로 한 번만 붙는다. */
class ContractRevision private constructor(
    val revisionNo: Int,
    val scope: ContractScope,
    val effectivePeriod: EffectivePeriod,
    val registeredAt: Instant,
    val registeredBy: ActorRef,
    approval: RevisionApproval?,
) {
    var approval: RevisionApproval? = approval
        private set

    init {
        require(revisionNo >= 1) { "revisionNo starts at 1" }
    }

    val isApproved: Boolean get() = approval != null

    internal fun approve(at: Instant, by: ActorRef): RevisionApproval {
        if (approval != null) throw MasterRuleViolation(MasterRejection.REVISION_ALREADY_APPROVED)
        return RevisionApproval(at, by).also { approval = it }
    }

    companion object {
        internal fun register(no: Int, scope: ContractScope, period: EffectivePeriod, at: Instant, by: ActorRef) =
            ContractRevision(no, scope, period, at, by, null)

        fun reconstitute(
            revisionNo: Int,
            scope: ContractScope,
            effectivePeriod: EffectivePeriod,
            registeredAt: Instant,
            registeredBy: ActorRef,
            approval: RevisionApproval?,
        ) = ContractRevision(revisionNo, scope, effectivePeriod, registeredAt, registeredBy, approval)
    }
}

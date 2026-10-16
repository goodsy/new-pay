package com.paycore.master.merchant.domain

import java.time.Instant

/** MID 발급 근거(불변으로 MID에 남는다 — 초기 "발급 시점의 계약 서비스와 근거 Revision을 보존"). */
data class IssuingBasis(val contractId: ContractId, val agreementId: ContractServiceAgreementId, val revisionNo: Int) {
    init {
        require(revisionNo >= 1) { "revisionNo starts at 1" }
    }
}

/**
 * 계약 Aggregate(D-MD-001·D-MD-004): 당사자 Business, 대상 Merchant(모든 Revision 공통 — D-MD-013), Revision(append-only, 승인 사실 포함),
 * 서비스 계약(CSA). 철회·종료·재활성화는 MASTER-LIFECYCLE-001(전이 메서드 없음).
 */
class Contract private constructor(
    val id: ContractId,
    val businessId: BusinessId,
    val merchantId: MerchantId,
    revisions: List<ContractRevision>,
    agreements: List<ContractServiceAgreement>,
    val createdAt: Instant,
    val createdBy: ActorRef,
) {
    private val revisionList = revisions.sortedBy { it.revisionNo }.toMutableList()
    private val agreementList = agreements.toMutableList()

    init {
        require(revisionList.isNotEmpty()) { "a contract has at least one revision" }
        require(revisionList.map { it.revisionNo } == (1..revisionList.size).toList()) { "revision numbers are consecutive from 1" }
        require(revisionList.all { it.scope.merchantId == merchantId }) { "all revisions of a contract target its merchant (D-MD-013)" }
        require(agreementList.map { it.serviceKind }.toSet().size == agreementList.size) { "one agreement per service kind" }
    }

    val revisions: List<ContractRevision> get() = revisionList.toList()
    val agreements: List<ContractServiceAgreement> get() = agreementList.toList()

    fun revision(no: Int): ContractRevision? = revisionList.firstOrNull { it.revisionNo == no }

    fun agreement(id: ContractServiceAgreementId): ContractServiceAgreement? = agreementList.firstOrNull { it.id == id }

    /**
     * 시각 [at]에 적용되는 Revision(D-MD-011): [at]을 효력 기간에 포함하는 **승인된** Revision 중 번호가 가장 큰 것. 이전 Revision은 기간이
     * 남아 있어도 대체된다. 없으면 null.
     */
    fun applicableRevisionAt(at: Instant): ContractRevision? =
        revisionList.filter { it.isApproved && it.effectivePeriod.contains(at) }.maxByOrNull { it.revisionNo }

    /**
     * 새 Revision(Scope 변경 포함, D-MD-004). 대상 Merchant는 바꿀 수 없다 — 다른 Merchant는 새 계약(D-MD-013).
     * Scope 검증은 [ContractScope.requireValid]로 호출자가 먼저 한다.
     */
    fun registerRevision(scope: ContractScope, period: EffectivePeriod, at: Instant, by: ActorRef): ContractRevision {
        if (scope.merchantId != merchantId) throw MasterRuleViolation(MasterRejection.CONTRACT_MERCHANT_MISMATCH)
        return ContractRevision.register(revisionList.size + 1, scope, period, at, by).also { revisionList += it }
    }

    /** 승인 사실 기록. 효력 기간이 이미 끝난 Revision의 승인은 거절한다(D-MD-014). */
    fun approve(revisionNo: Int, at: Instant, by: ActorRef): RevisionApproval {
        val revision = revision(revisionNo) ?: throw MasterRuleViolation(MasterRejection.REVISION_NOT_FOUND)
        if (revision.effectivePeriod.hasEndedAt(at)) throw MasterRuleViolation(MasterRejection.REVISION_ENDED)
        return revision.approve(at, by)
    }

    fun addAgreement(id: ContractServiceAgreementId, kind: ServiceKind, at: Instant, by: ActorRef): ContractServiceAgreement {
        agreementList.firstOrNull { it.serviceKind == kind }?.let {
            throw MasterRuleViolation(MasterRejection.SERVICE_KIND_DUPLICATE, it.id.value)
        }
        return ContractServiceAgreement(id, kind, at, by).also { agreementList += it }
    }

    /**
     * MID 발급 근거 확인(초기 셋째 문단, A10 INV-17, 구조 경계 §1, D-MD-012·D-MD-015): CSA가 이 계약 소속, 인용한 Revision이 승인됐고 끝나지
     * 않았으며 다음 중 하나여야 한다. 아니면 REVISION_NOT_APPLICABLE(fail-closed).
     * - 발급 시각 [now]에 적용되는 Revision(D-MD-011)이 **있으면** 그것만(D-MD-012 — 대체된 Revision·미래 Revision 인용 거절).
     * - **없으면**(효력 시작 전 선발급, D-MD-015) 아직 시작 전인 승인 Revision 중 **자기 시작 시각에 적용될** Revision만(= 시작 시각을 포함하는
     *   승인 Revision 중 번호 최대가 자기 자신). 발급 ≠ 개통·결제 허용: 효력 전에는 결제가 계속 차단된다(판정은 Snapshot·PAYMENT).
     * 연결할 Site는 그 Revision의 Scope 안이어야 한다.
     */
    fun issuingBasis(agreementId: ContractServiceAgreementId, revisionNo: Int, site: MerchantSite, now: Instant): IssuingBasis {
        if (agreement(agreementId) == null) throw MasterRuleViolation(MasterRejection.AGREEMENT_NOT_FOUND)
        val revision = revision(revisionNo) ?: throw MasterRuleViolation(MasterRejection.REVISION_NOT_FOUND)
        if (!revision.isApproved) throw MasterRuleViolation(MasterRejection.REVISION_NOT_APPROVED)
        if (revision.effectivePeriod.hasEndedAt(now)) throw MasterRuleViolation(MasterRejection.REVISION_ENDED)
        val applicableNow = applicableRevisionAt(now)
        val citable = if (applicableNow != null) {
            applicableNow.revisionNo == revisionNo
        } else {
            val start = revision.effectivePeriod.from
            start.isAfter(now) && applicableRevisionAt(start)?.revisionNo == revisionNo
        }
        if (!citable) throw MasterRuleViolation(MasterRejection.REVISION_NOT_APPLICABLE)
        if (!revision.scope.includes(site)) throw MasterRuleViolation(MasterRejection.SITE_OUTSIDE_CONTRACT_SCOPE)
        return IssuingBasis(id, agreementId, revisionNo)
    }

    override fun toString(): String = "Contract(id=${id.value}, revisions=${revisionList.size}, agreements=${agreementList.size})"

    companion object {
        /** 계약과 첫 Revision을 등록한다. 대상 Merchant = 첫 Scope의 Merchant(D-MD-013). Scope 검증은 [ContractScope.requireValid]로 먼저 한다. */
        fun register(
            id: ContractId,
            party: Business,
            firstScope: ContractScope,
            firstPeriod: EffectivePeriod,
            at: Instant,
            by: ActorRef,
        ): Contract {
            party.requireActive()
            return Contract(
                id, party.id, firstScope.merchantId, listOf(ContractRevision.register(1, firstScope, firstPeriod, at, by)), emptyList(), at, by,
            )
        }

        @Suppress("LongParameterList")
        fun reconstitute(
            id: ContractId,
            businessId: BusinessId,
            merchantId: MerchantId,
            revisions: List<ContractRevision>,
            agreements: List<ContractServiceAgreement>,
            createdAt: Instant,
            createdBy: ActorRef,
        ) = Contract(id, businessId, merchantId, revisions, agreements, createdAt, createdBy)
    }
}

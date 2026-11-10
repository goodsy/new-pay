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
     * 효력 시작이 판정 시각 [at]의 Asia/Seoul 날짜 00:00보다 앞이면 [MasterRejection.CONTRACT_START_RETROACTIVE](D-MOPS-007 ② — Revision 1과 같은 규칙,
     * MERCHANT-OPS-002 §9.2). 판정 순서: 대상 Merchant 불일치가 먼저.
     */
    fun registerRevision(scope: ContractScope, period: EffectivePeriod, at: Instant, by: ActorRef): ContractRevision {
        if (scope.merchantId != merchantId) throw MasterRuleViolation(MasterRejection.CONTRACT_MERCHANT_MISMATCH)
        if (period.from.isBefore(SeoulMidnight.startOfDay(at))) throw MasterRuleViolation(MasterRejection.CONTRACT_START_RETROACTIVE)
        return ContractRevision.register(revisionList.size + 1, scope, period, at, by).also { revisionList += it }
    }

    /** 승인 사실 기록. 효력 기간이 이미 끝난 Revision의 승인은 거절한다(D-MD-014). */
    fun approve(revisionNo: Int, at: Instant, by: ActorRef): RevisionApproval {
        val revision = revision(revisionNo) ?: throw MasterRuleViolation(MasterRejection.REVISION_NOT_FOUND)
        if (revision.effectivePeriod.hasEndedAt(at)) throw MasterRuleViolation(MasterRejection.REVISION_ENDED)
        return revision.approve(at, by)
    }

    /**
     * 승인 근거가 있는 경로의 승인(MERCHANT-OPS-002 §9.3 판정 5 — D-MOPS-008 ③, C-27·C-31). 판정 순서: Revision 없음 → 끝난 Revision(D-MD-014) →
     * **Revision 등록자 = 승인자**([MasterRejection.APPROVER_IS_REVISION_REGISTRANT]) → 이미 승인됨. Revision 1의 등록자는 계약 등록자다.
     * 근거 없는 [approve](Demo seed 경로 — D-MOPS-008 ②·D-DEMO-005)는 등록자 검사를 하지 않는다. 어느 경로인지는 Application이 어느 메서드를 부르는지로 정한다 —
     * Domain은 행위자 이름을 모른다(하드코딩 없음, 정규형 문자열 비교만).
     */
    fun approveWithEvidence(revisionNo: Int, at: Instant, approver: ActorRef): RevisionApproval {
        val revision = revision(revisionNo) ?: throw MasterRuleViolation(MasterRejection.REVISION_NOT_FOUND)
        if (revision.effectivePeriod.hasEndedAt(at)) throw MasterRuleViolation(MasterRejection.REVISION_ENDED)
        if (revision.registeredBy == approver) throw MasterRuleViolation(MasterRejection.APPROVER_IS_REVISION_REGISTRANT)
        return revision.approve(at, approver)
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
        /**
         * 계약과 첫 Revision을 등록한다. 대상 Merchant = 첫 Scope의 Merchant(D-MD-013). Scope 검증은 [ContractScope.requireValid]로 먼저 한다.
         * 효력 시작이 판정 시각 [at]의 **Asia/Seoul 날짜 00:00보다 앞**이면 소급 등록이라 거절한다([MasterRejection.CONTRACT_START_RETROACTIVE] — D-MOPS-006 ⑤,
         * MERCHANT-OPS-002 §3.4). 같은 KST 날짜 안의 앞선 시각은 허용한다. [registerRevision]에도 같은 규칙(D-MOPS-007 ②).
         */
        fun register(
            id: ContractId,
            party: Business,
            firstScope: ContractScope,
            firstPeriod: EffectivePeriod,
            at: Instant,
            by: ActorRef,
        ): Contract {
            party.requireActive()
            if (firstPeriod.from.isBefore(SeoulMidnight.startOfDay(at))) throw MasterRuleViolation(MasterRejection.CONTRACT_START_RETROACTIVE)
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

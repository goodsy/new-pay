package com.paycore.master.merchant.domain

import java.time.Instant

/**
 * 효력 기간 [from, to)(UTC 시각, D-MD-005·D-MD-004). [to]가 없으면 종료 예정 없음.
 * 저장 정밀도는 ms(DATETIME(3))다. 명령 경계는 [ofMillis]로 ms로 자른 기간만 쓴다(MySQL은 소수 초를 **반올림**하므로 ms 미만 값을 그대로
 * 넘기면 저장 값·fingerprint가 입력과 달라진다).
 */
data class EffectivePeriod(val from: Instant, val to: Instant? = null) {
    init {
        require(to == null || to.isAfter(from)) { "effective period must end after it starts" }
    }

    /** ms 정밀도인가(저장 값과 같은가). */
    val isMillisPrecise: Boolean get() = from.nano % NANOS_PER_MILLI == 0 && (to == null || to.nano % NANOS_PER_MILLI == 0)

    fun contains(at: Instant): Boolean = !at.isBefore(from) && (to == null || at.isBefore(to))

    /** 이미 끝났다(종료 시각이 [at] 이전이거나 같다). */
    fun hasEndedAt(at: Instant): Boolean = to != null && !at.isBefore(to)

    fun overlaps(other: EffectivePeriod): Boolean =
        (other.to == null || from.isBefore(other.to)) && (to == null || other.from.isBefore(to))

    companion object {
        private const val NANOS_PER_MILLI = 1_000_000

        /**
         * 명령 입력을 저장 정밀도(ms, 버림)로 자른 기간. 잘라서 비게 되면(예: 0.1ms~0.4ms) 업무 거절 [MasterRejection.EFFECTIVE_PERIOD_EMPTY]
         * (DB에서 CHECK 위반이나 반올림으로 다른 기간이 저장되는 대신).
         */
        fun ofMillis(from: Instant, to: Instant?): EffectivePeriod {
            val f = from.truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
            val t = to?.truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
            if (t != null && !t.isAfter(f)) throw MasterRuleViolation(MasterRejection.EFFECTIVE_PERIOD_EMPTY)
            return EffectivePeriod(f, t)
        }
    }
}

/**
 * 계약 Scope(초기: Merchant 또는 MerchantSite, D-MD-004: MERCHANT 또는 SITES(1개 이상)). GID 상속 없음.
 * SITES의 Site는 모두 [merchantId] 소속이다(D-MD-010: 한 Scope = 한 Merchant). 한 계약의 모든 Revision은 같은 Merchant 대상이다(D-MD-013).
 */
sealed interface ContractScope {
    val merchantId: MerchantId

    fun includes(site: MerchantSite): Boolean

    data class MerchantWide(override val merchantId: MerchantId) : ContractScope {
        override fun includes(site: MerchantSite): Boolean = site.merchantId == merchantId
    }

    data class Sites(override val merchantId: MerchantId, val siteIds: Set<MerchantSiteId>) : ContractScope {
        init {
            require(siteIds.isNotEmpty()) { "a SITES scope lists at least one site" }
        }

        override fun includes(site: MerchantSite): Boolean = site.merchantId == merchantId && site.id in siteIds
    }

    companion object {
        /**
         * Scope 검증: Scope Merchant는 계약 당사자 Business 소속이고 활성, SITES의 Site는 모두 그 Merchant 소속이고 활성이다.
         * [sites]는 Scope가 나열한 Site들(MERCHANT Scope면 비어 있다).
         */
        fun requireValid(scope: ContractScope, party: BusinessId, merchant: Merchant, sites: List<MerchantSite>) {
            require(merchant.id == scope.merchantId) { "scope merchant mismatch" }
            if (merchant.businessId != party) throw MasterRuleViolation(MasterRejection.SCOPE_MERCHANT_OF_OTHER_BUSINESS)
            merchant.requireActive()
            when (scope) {
                is MerchantWide -> require(sites.isEmpty()) { "a MERCHANT scope lists no sites" }
                is Sites -> {
                    require(sites.map { it.id }.toSet() == scope.siteIds) { "scope sites must be provided" }
                    sites.forEach { site ->
                        if (site.merchantId != merchant.id) throw MasterRuleViolation(MasterRejection.SCOPE_SITE_OF_OTHER_MERCHANT)
                        site.requireActive()
                    }
                }
            }
        }
    }
}

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

/** 서비스 종류(D-MD-002: CSA 1건 = 서비스 종류 1개). 초기 범위는 국내 신용카드 PG 결제 서비스(초기 §출시 범위). */
enum class ServiceKind { CARD_PG_PAYMENT }

/** 서비스 계약(CSA, D-MD-001). Contract 1:N. MID 발급 근거·정책 Assignment 기준. */
data class ContractServiceAgreement(
    val id: ContractServiceAgreementId,
    val serviceKind: ServiceKind,
    val addedAt: Instant,
    val addedBy: ActorRef,
)

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

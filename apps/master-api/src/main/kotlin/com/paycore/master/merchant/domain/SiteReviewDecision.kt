package com.paycore.master.merchant.domain

import java.time.Instant

/**
 * MerchantSite PG 심사 상태(D-MS-002 (a), 2026-10-13 사용자 확정): PENDING/APPROVED/REJECTED/SUSPENDED. 신규 Site는 PENDING(결정 기록 없음),
 * 결제 생성은 APPROVED만. D-MD-016의 Site 적용 확인(기관 개통)과 별개다.
 */
enum class SiteReviewStatus { PENDING, APPROVED, REJECTED, SUSPENDED }

/** 심사 결정 기록(append-only: Site, 순번, 결정, 사유, 근거 참조(선택), 행위자, 시각). 현재 상태 = 마지막 결정. */
data class SiteReviewDecision(
    val merchantSiteId: MerchantSiteId,
    val merchantId: MerchantId,
    val seq: Int,
    val decision: SiteReviewStatus,
    val reason: DecisionReason,
    val evidenceRef: EvidenceRef?,
    val decidedAt: Instant,
    val decidedBy: ActorRef,
) {
    init {
        require(seq >= 1) { "decision sequence starts at 1" }
        require(decision != SiteReviewStatus.PENDING) { "PENDING is the initial state, not a decision" }
    }
}

/**
 * 심사 전이: PENDING → APPROVED|REJECTED, APPROVED → SUSPENDED, SUSPENDED → APPROVED|REJECTED. **REJECTED는 종결이며 재신청은 새 Site로 한다
 * (D-MS-005, Q-MS-05 답변 2026-10-13).** 같은 상태로의 결정은 거절한다. APPROVED 결정은 활성 Site에만(D-MS-002 (a) 상태 집합).
 */
object SiteReviewRules {
    private val ALLOWED: Map<SiteReviewStatus, Set<SiteReviewStatus>> = mapOf(
        SiteReviewStatus.PENDING to setOf(SiteReviewStatus.APPROVED, SiteReviewStatus.REJECTED),
        SiteReviewStatus.APPROVED to setOf(SiteReviewStatus.SUSPENDED),
        SiteReviewStatus.SUSPENDED to setOf(SiteReviewStatus.APPROVED, SiteReviewStatus.REJECTED),
        SiteReviewStatus.REJECTED to emptySet(),
    )

    /** [history](순번 순)의 현재 상태. 기록이 없으면 PENDING. */
    fun currentStatus(history: List<SiteReviewDecision>): SiteReviewStatus = history.maxByOrNull { it.seq }?.decision ?: SiteReviewStatus.PENDING

    @Suppress("LongParameterList")
    fun decide(
        site: MerchantSite,
        history: List<SiteReviewDecision>,
        decision: SiteReviewStatus,
        reason: DecisionReason,
        evidence: EvidenceRef?,
        at: Instant,
        by: ActorRef,
    ): SiteReviewDecision {
        require(history.all { it.merchantSiteId == site.id }) { "history must belong to the site" }
        require(history.map { it.seq } == (1..history.size).toList()) { "decision sequence is consecutive from 1" }
        val current = currentStatus(history)
        if (decision !in ALLOWED.getValue(current)) throw MasterRuleViolation(MasterRejection.SITE_REVIEW_TRANSITION_NOT_ALLOWED)
        if (decision == SiteReviewStatus.APPROVED) site.requireActive()
        return SiteReviewDecision(site.id, site.merchantId, history.size + 1, decision, reason, evidence, at, by)
    }

    /**
     * 근거 경로 심사 승인(MERCHANT-OPS-002 §9.27 — D-MOPS-009 ③, D-MOPS-010 ②): Site 등록자([MerchantSite.createdBy])는 그 Site의 2인 심사 승인자가 될 수 없다 —
     * PENDING → APPROVED와 SUSPENDED → APPROVED 재승인 모두(재승인 포함은 사용자 확정). 중지 행위자는 대상이 아니다. 순서: **등록자** → 전이(`SITE_REVIEW_TRANSITION_NOT_ALLOWED`)
     * → `SITE_NOT_ACTIVE`([decide] 순서 그대로). 근거 없는 [decide](seed APPROVED·1인 REJECTED·SUSPENDED)는 검사하지 않는다 — Site 등록자도 1인 거절은 할 수 있다.
     */
    @Suppress("LongParameterList")
    fun approveWithEvidence(
        site: MerchantSite,
        history: List<SiteReviewDecision>,
        reason: DecisionReason,
        evidence: EvidenceRef,
        at: Instant,
        approver: ActorRef,
    ): SiteReviewDecision {
        if (site.createdBy == approver) throw MasterRuleViolation(MasterRejection.APPROVER_IS_SITE_REGISTRANT)
        return decide(site, history, SiteReviewStatus.APPROVED, reason, evidence, at, approver)
    }
}

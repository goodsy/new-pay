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
}

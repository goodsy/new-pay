package com.paycore.master.merchant.domain

import java.time.Instant

/**
 * Site 심사 Named Fixture(MERCHANT-OPS-002 §8.4 B3-1F — src/test 전용). 등록자([createdBy])·중지 행위자([suspendedBy])가 업무 조건이므로 이름 있는 인자로 드러낸다.
 * 이력은 공식 전이([SiteReviewRules.decide] — seed·1인 경로)로만 만든다. B5 Site 중지가 아직 없으므로 SUSPENDED 이력도 `decide(SUSPENDED)`로 만든다(기존 B3-1 시험과 같은 방식).
 */
object SiteReviewFixtures {
    /** 이전 심사 승인자(seed 경로 행위자를 흉내 낸 시험 값 — Domain은 이름을 모른다). */
    val EARLIER_APPROVER = ActorRef("system:fixture-earlier-approver")

    /** [createdBy]가 등록한 활성 Site(심사 결정 없음 = PENDING). */
    fun pending(createdBy: ActorRef, at: Instant = EntitlementFixture.t0): MerchantSite =
        MerchantSite.register(MasterFixture.siteId(), MasterFixture.merchant(), MasterFixture.webChannel(), at, createdBy)

    /** [site]의 이력: APPROVED(seq 1, [EARLIER_APPROVER]) → SUSPENDED(seq 2, [suspendedBy]). 현재 상태 SUSPENDED. */
    fun suspendedHistory(site: MerchantSite, suspendedBy: ActorRef, at: Instant = EntitlementFixture.t0): List<SiteReviewDecision> {
        val approved = SiteReviewRules.decide(site, emptyList(), SiteReviewStatus.APPROVED, EntitlementFixture.reason, null, at, EARLIER_APPROVER)
        val suspended = SiteReviewRules.decide(site, listOf(approved), SiteReviewStatus.SUSPENDED, EntitlementFixture.reason, null, at, suspendedBy)
        return listOf(approved, suspended)
    }
}

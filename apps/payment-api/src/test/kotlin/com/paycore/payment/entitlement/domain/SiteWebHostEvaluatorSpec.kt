package com.paycore.payment.entitlement.domain

import com.paycore.payment.entitlement.domain.SnapshotFixture.entitledWithChannels
import com.paycore.payment.entitlement.domain.SnapshotFixture.entitledWithTerms
import com.paycore.payment.entitlement.domain.SnapshotFixture.headOf
import com.paycore.payment.entitlement.domain.SnapshotFixture.ids
import com.paycore.payment.entitlement.domain.SnapshotFixture.leaseAt
import com.paycore.payment.entitlement.domain.SnapshotFixture.mapSite
import com.paycore.payment.entitlement.domain.SnapshotFixture.skew
import com.paycore.payment.entitlement.domain.SnapshotFixture.t0
import com.paycore.payment.entitlement.domain.SnapshotFixture.unsupported
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

/**
 * Site WEB host 판정(AUTHN-001 §9.4, D-AUTHN-002 fail-closed): PAYMENT 로컬 Snapshot만으로 결제창 반환 URL host를 정한다. 판정 불가(없음·막힘·만료·v1/v2
 * head)는 Unavailable, host가 없는 Site(APP·종료·Snapshot에 없음)는 NoWebHost. MID–Site Assignment 상태는 보지 않는다(같은 Merchant의 host — 검토 n-6, AN-11).
 */
class SiteWebHostEvaluatorSpec : FunSpec({

    val at = t0.plusSeconds(5)

    fun evaluate(content: EntitlementContent?, head: EntitlementHead?, mid: String, siteId: String, evaluatedAt: Instant = at, now: Instant = evaluatedAt) =
        SiteWebHostEvaluator.evaluate(head, content, mid, siteId, evaluatedAt, now, skew)

    test("a v3 head with an ACTIVE WEB site gives its host, the snapshot version and validUntil = lease end - skew") {
        val ids = ids()
        val content = entitledWithChannels(ids, revision = 4)
        evaluate(content, headOf(content), ids.mid, ids.siteId) shouldBe
            SiteWebHostDecision.WebHost(SnapshotFixture.WEB_HOST, "es1:${ids.merchantId}:4", leaseAt().leaseUntil.minus(skew))
    }

    test("no host: APP site, terminated site, site not in the snapshot (fail-closed — another merchant may re-register a terminated host)") {
        val ids = ids()
        val content = entitledWithChannels(ids)
        evaluate(content, headOf(content), ids.mid, ids.otherSiteId) shouldBe SiteWebHostDecision.NoWebHost(NoWebHostReason.APP_CHANNEL)
        val terminated = content.mapSite(ids.siteId) { it.copy(lifecycle = Lifecycle.TERMINATED) }
        evaluate(terminated, headOf(terminated), ids.mid, ids.siteId) shouldBe SiteWebHostDecision.NoWebHost(NoWebHostReason.SITE_TERMINATED)
        evaluate(content, headOf(content), ids.mid, "site_unknown") shouldBe SiteWebHostDecision.NoWebHost(NoWebHostReason.SITE_UNKNOWN)
    }

    test("unavailable: no head, MID not in the snapshot, blocked or unsupported head, lease expired, v1/v2 head (host not propagated)") {
        val ids = ids()
        val content = entitledWithChannels(ids)
        val head = headOf(content)
        evaluate(null, null, ids.mid, ids.siteId) shouldBe SiteWebHostDecision.Unavailable(SiteWebHostUnavailableReason.SNAPSHOT_MISSING)
        evaluate(content, EntitlementHead.empty(ids.merchantId), ids.mid, ids.siteId) shouldBe SiteWebHostDecision.Unavailable(SiteWebHostUnavailableReason.SNAPSHOT_MISSING)
        evaluate(content, head, "mid_other", ids.siteId) shouldBe SiteWebHostDecision.Unavailable(SiteWebHostUnavailableReason.SNAPSHOT_MISSING)
        val blocked = checkNotNull(SnapshotApplicationRule.decide(head, unsupported(ids.merchantId, 2)).newHead)
        evaluate(content, blocked, ids.mid, ids.siteId) shouldBe SiteWebHostDecision.Unavailable(SiteWebHostUnavailableReason.VERSION_UNSUPPORTED)
        val leaseEnd = leaseAt().leaseUntil.minus(skew)
        evaluate(content, head, ids.mid, ids.siteId, evaluatedAt = leaseEnd) shouldBe SiteWebHostDecision.Unavailable(SiteWebHostUnavailableReason.SNAPSHOT_EXPIRED)
        evaluate(content, head, ids.mid, ids.siteId, evaluatedAt = at, now = leaseEnd) shouldBe SiteWebHostDecision.Unavailable(SiteWebHostUnavailableReason.SNAPSHOT_EXPIRED)
        val v2 = entitledWithTerms(ids)
        evaluate(v2, headOf(v2), ids.mid, ids.siteId) shouldBe SiteWebHostDecision.Unavailable(SiteWebHostUnavailableReason.HOST_NOT_PROPAGATED)
        val v1 = SnapshotFixture.entitled(ids)
        evaluate(v1, headOf(v1), ids.mid, ids.siteId) shouldBe SiteWebHostDecision.Unavailable(SiteWebHostUnavailableReason.HOST_NOT_PROPAGATED)
    }

    test("the MID–Site assignment status is not consulted (same merchant's host — review n-6)") {
        val ids = ids()
        val content = SnapshotFixture.run { entitledWithChannels(ids).mapAccount { it.copy(assignments = emptyList()) } }
        evaluate(content, headOf(content), ids.mid, ids.siteId) shouldBe
            SiteWebHostDecision.WebHost(SnapshotFixture.WEB_HOST, "es1:${ids.merchantId}:1", leaseAt().leaseUntil.minus(skew))
    }
})

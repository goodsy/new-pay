package com.paycore.payment.entitlement.domain

import java.time.Duration
import java.time.Instant

/** host가 없는 이유(결제창 반환 URL 거절 — D-AUTHN-002 fail-closed). */
enum class NoWebHostReason { APP_CHANNEL, SITE_TERMINATED, SITE_UNKNOWN }

/** 판정 불가(fail-closed). HOST_NOT_PROPAGATED = head가 v1·v2(Site 채널 미전파 — 다음 승격 발행 뒤 해소). */
enum class SiteWebHostUnavailableReason { SNAPSHOT_MISSING, SNAPSHOT_EXPIRED, VERSION_UNSUPPORTED, HOST_NOT_PROPAGATED }

sealed interface SiteWebHostDecision {
    /** [validUntil] = 권리 lease 끝 − skew(> 평가 시각). [snapshotVersion]은 토큰에 기록하는 판정 근거. */
    data class WebHost(val host: String, val snapshotVersion: String, val validUntil: Instant) : SiteWebHostDecision

    data class NoWebHost(val reason: NoWebHostReason) : SiteWebHostDecision

    data class Unavailable(val reason: SiteWebHostUnavailableReason) : SiteWebHostDecision
}

/**
 * Site WEB host 판정(AUTHN-001 §9.4). PAYMENT 로컬 권리 투영만으로 정한다(MASTER 동기 조회 없음). 권리 판정 U1~U3([EntitlementEvaluator])과 같은 head
 * 규칙에 v1·v2 head(HOST_NOT_PROPAGATED)를 더한다. Site는 그 MID가 속한 Merchant의 Snapshot에서 찾고, ACTIVE WEB Site만 host가 있다 — 종료 Site의 host는
 * 다른 Merchant가 다시 등록할 수 있어 근거로 쓰지 않는다(D-MD-008에서 도출). MID–Site Assignment 상태는 보지 않는다(같은 Merchant의 host — 검토 n-6,
 * AN-11; Assignment·권리 재평가는 confirm의 권위 판정). 잠금 대기로 now ≥ validUntil이면 SNAPSHOT_EXPIRED.
 */
object SiteWebHostEvaluator {
    @Suppress("LongParameterList", "ReturnCount")
    fun evaluate(
        head: EntitlementHead?,
        content: EntitlementContent?,
        mid: String,
        siteId: String,
        evaluatedAt: Instant,
        now: Instant,
        skew: Duration,
    ): SiteWebHostDecision {
        require(!skew.isNegative) { "clock skew margin must not be negative" }
        if (head == null || head.isEmpty) return unavailable(SiteWebHostUnavailableReason.SNAPSHOT_MISSING)
        if (head.blocked != null || !isSupportedSnapshotSchema(head.schemaVersion)) return unavailable(SiteWebHostUnavailableReason.VERSION_UNSUPPORTED)
        val validUntil = checkNotNull(head.lease).leaseUntil.minus(skew)
        if (!evaluatedAt.isBefore(validUntil) || !now.isBefore(validUntil)) return unavailable(SiteWebHostUnavailableReason.SNAPSHOT_EXPIRED)
        if (checkNotNull(head.schemaVersion) < CHANNEL_SNAPSHOT_SCHEMA_VERSION) return unavailable(SiteWebHostUnavailableReason.HOST_NOT_PROPAGATED)
        if (content == null || content.account(mid) == null) return unavailable(SiteWebHostUnavailableReason.SNAPSHOT_MISSING)
        val site = content.sites.firstOrNull { it.siteId == siteId } ?: return SiteWebHostDecision.NoWebHost(NoWebHostReason.SITE_UNKNOWN)
        if (site.lifecycle != Lifecycle.ACTIVE) return SiteWebHostDecision.NoWebHost(NoWebHostReason.SITE_TERMINATED)
        return when (val channel = checkNotNull(site.channel) { "a v3 site carries its channel" }) {
            is SiteChannelEntry.Web -> SiteWebHostDecision.WebHost(channel.host, content.snapshotVersion, validUntil)
            SiteChannelEntry.App -> SiteWebHostDecision.NoWebHost(NoWebHostReason.APP_CHANNEL)
        }
    }

    private fun unavailable(reason: SiteWebHostUnavailableReason) = SiteWebHostDecision.Unavailable(reason)
}

package com.paycore.payment.entitlement.application.provided

import java.time.Instant

/**
 * 결제창 반환 URL host 판정 조회(AUTHN-001 §9.4, D-AUTHN-002). **호출자의 PAYMENT 로컬 TX(토큰 발급 TX-I)에 참여한다**(MANDATORY). 로컬 권리 투영만
 * 읽는다(head `FOR SHARE`, 쓰기·외부 호출·MASTER 조회·캐시 없음). host만 판정한다 — MID 차단·계약 효력 재평가는 confirm의 몫(AN-11).
 */
interface SiteWebHostQuery {
    /** @throws EntitlementEvaluationFailure 저장소 일시 실패(판정 결과가 아니다 — 호출자 TX는 rollback). */
    fun resolve(request: SiteWebHostRequest, evaluatedAt: Instant): SiteWebHostQueryResult
}

data class SiteWebHostRequest(val mid: String, val siteId: String)

sealed interface SiteWebHostQueryResult {
    data class WebHost(val host: String, val snapshotVersion: String, val validUntil: Instant) : SiteWebHostQueryResult

    data class NoWebHost(val reason: SiteWebHostAbsence) : SiteWebHostQueryResult

    data class Unavailable(val reason: SiteWebHostUnavailability) : SiteWebHostQueryResult
}

enum class SiteWebHostAbsence { APP_CHANNEL, SITE_TERMINATED, SITE_UNKNOWN }

enum class SiteWebHostUnavailability { SNAPSHOT_MISSING, SNAPSHOT_EXPIRED, VERSION_UNSUPPORTED, HOST_NOT_PROPAGATED }

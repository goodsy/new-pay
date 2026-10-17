package com.paycore.payment.payment.application.required

import com.paycore.payment.payment.domain.MerchantAccountId
import com.paycore.payment.payment.domain.MerchantSiteId
import java.time.Instant

/**
 * 결제창 반환 URL host Port(AUTHN-001 §9.4, D-AUTHN-002). 구현 규칙은 [AuthorizationAdmissionPort]와 같다: PAYMENT DB 로컬 권리 투영만, 호출자 TX(토큰 발급
 * TX-I) 안에서, 판정 불가는 예외가 아니라 [SiteWebHostResult.Unavailable](fail-closed), 저장소 일시 실패는 [PersistenceFailure].
 */
interface SiteWebHostPort {
    fun resolve(authenticatedMid: MerchantAccountId, merchantSiteId: MerchantSiteId, evaluatedAt: Instant): SiteWebHostResult
}

sealed interface SiteWebHostResult {
    /** 반환 URL host는 [host]와 문자열 완전 일치여야 한다(§8.2). [snapshotVersion]은 토큰에 기록한다. */
    data class WebHost(val host: String, val snapshotVersion: String, val validUntil: Instant) : SiteWebHostResult

    /** host 없음 → 422 RETURN_URL_NOT_ALLOWED. */
    data class NoWebHost(val reason: NoWebHostCause) : SiteWebHostResult

    /** 판정 불가 → 503 CHECKOUT_UNAVAILABLE. */
    data class Unavailable(val reason: SiteWebHostUnavailable) : SiteWebHostResult
}

enum class NoWebHostCause { APP_CHANNEL, SITE_TERMINATED, SITE_UNKNOWN }

enum class SiteWebHostUnavailable { SNAPSHOT_MISSING, SNAPSHOT_EXPIRED, VERSION_UNSUPPORTED, HOST_NOT_PROPAGATED }

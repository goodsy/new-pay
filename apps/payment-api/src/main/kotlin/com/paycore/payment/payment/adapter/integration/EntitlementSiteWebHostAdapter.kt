package com.paycore.payment.payment.adapter.integration

import com.paycore.payment.entitlement.application.provided.EntitlementEvaluationFailure
import com.paycore.payment.entitlement.application.provided.SiteWebHostAbsence
import com.paycore.payment.entitlement.application.provided.SiteWebHostQuery
import com.paycore.payment.entitlement.application.provided.SiteWebHostQueryResult
import com.paycore.payment.entitlement.application.provided.SiteWebHostRequest
import com.paycore.payment.entitlement.application.provided.SiteWebHostUnavailability
import com.paycore.payment.payment.application.provided.StoreDiagnostic
import com.paycore.payment.payment.application.required.NoWebHostCause
import com.paycore.payment.payment.application.required.PersistenceFailure
import com.paycore.payment.payment.application.required.SiteWebHostPort
import com.paycore.payment.payment.application.required.SiteWebHostResult
import com.paycore.payment.payment.application.required.SiteWebHostUnavailable
import com.paycore.payment.payment.domain.MerchantAccountId
import com.paycore.payment.payment.domain.MerchantSiteId
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * [SiteWebHostPort]의 운영 구현(AUTHN-001 §9.4): entitlement 모듈의 공개 조회([SiteWebHostQuery], provided만)를 같은 PAYMENT 로컬 TX에서 부르고 payment
 * 모양으로 옮긴다. 판정 규칙은 entitlement domain에만 있다. 캐시 없음(ArchitectureSpec 캐시 금지 규칙 대상).
 */
@Component
class EntitlementSiteWebHostAdapter(private val query: SiteWebHostQuery) : SiteWebHostPort {

    override fun resolve(authenticatedMid: MerchantAccountId, merchantSiteId: MerchantSiteId, evaluatedAt: Instant): SiteWebHostResult =
        when (val r = resolveOrClassify(SiteWebHostRequest(authenticatedMid.value, merchantSiteId.value), evaluatedAt)) {
            is SiteWebHostQueryResult.WebHost -> SiteWebHostResult.WebHost(r.host, r.snapshotVersion, r.validUntil)
            is SiteWebHostQueryResult.NoWebHost -> SiteWebHostResult.NoWebHost(
                when (r.reason) {
                    SiteWebHostAbsence.APP_CHANNEL -> NoWebHostCause.APP_CHANNEL
                    SiteWebHostAbsence.SITE_TERMINATED -> NoWebHostCause.SITE_TERMINATED
                    SiteWebHostAbsence.SITE_UNKNOWN -> NoWebHostCause.SITE_UNKNOWN
                },
            )
            is SiteWebHostQueryResult.Unavailable -> SiteWebHostResult.Unavailable(
                when (r.reason) {
                    SiteWebHostUnavailability.SNAPSHOT_MISSING -> SiteWebHostUnavailable.SNAPSHOT_MISSING
                    SiteWebHostUnavailability.SNAPSHOT_EXPIRED -> SiteWebHostUnavailable.SNAPSHOT_EXPIRED
                    SiteWebHostUnavailability.VERSION_UNSUPPORTED -> SiteWebHostUnavailable.VERSION_UNSUPPORTED
                    SiteWebHostUnavailability.HOST_NOT_PROPAGATED -> SiteWebHostUnavailable.HOST_NOT_PROPAGATED
                },
            )
        }

    private fun resolveOrClassify(request: SiteWebHostRequest, evaluatedAt: Instant): SiteWebHostQueryResult =
        try {
            query.resolve(request, evaluatedAt)
        } catch (e: EntitlementEvaluationFailure.Deadlock) {
            throw PersistenceFailure.Deadlock(e, diagnosticOf(e))
        } catch (e: EntitlementEvaluationFailure.LockTimeout) {
            throw PersistenceFailure.LockTimeout(e, diagnosticOf(e))
        } catch (e: EntitlementEvaluationFailure.Unavailable) {
            throw PersistenceFailure.Unavailable(e, diagnosticOf(e))
        }

    private fun diagnosticOf(e: EntitlementEvaluationFailure): StoreDiagnostic? =
        if (e.vendorCode == null && e.sqlState == null) null else StoreDiagnostic(vendorCode = e.vendorCode, sqlState = e.sqlState)
}

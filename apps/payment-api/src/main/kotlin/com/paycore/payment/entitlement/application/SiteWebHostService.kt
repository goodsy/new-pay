package com.paycore.payment.entitlement.application

import com.paycore.payment.entitlement.application.provided.EntitlementEvaluationFailure
import com.paycore.payment.entitlement.application.provided.SiteWebHostAbsence
import com.paycore.payment.entitlement.application.provided.SiteWebHostQuery
import com.paycore.payment.entitlement.application.provided.SiteWebHostQueryResult
import com.paycore.payment.entitlement.application.provided.SiteWebHostRequest
import com.paycore.payment.entitlement.application.provided.SiteWebHostUnavailability
import com.paycore.payment.entitlement.application.required.EntitlementClock
import com.paycore.payment.entitlement.application.required.EntitlementPersistenceFailure
import com.paycore.payment.entitlement.application.required.EntitlementProjectionStore
import com.paycore.payment.entitlement.application.required.EntitlementTiming
import com.paycore.payment.entitlement.domain.NoWebHostReason
import com.paycore.payment.entitlement.domain.SiteWebHostDecision
import com.paycore.payment.entitlement.domain.SiteWebHostEvaluator
import com.paycore.payment.entitlement.domain.SiteWebHostUnavailableReason
import com.paycore.payment.entitlement.domain.isSupportedSnapshotSchema
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Site WEB host 판정(AUTHN-001 §9.4). [AuthorizationTermsService]와 같은 읽기: 호출자 TX에 참여(MANDATORY)해 MID 소속(비잠금) → head `FOR SHARE` →
 * revision별 내용(불변 행) → domain [SiteWebHostEvaluator]. 쓰기·외부 호출·캐시 없음. 로그에는 결과·reason·MID만(host·URL 없음).
 */
@Service
class SiteWebHostService(
    private val store: EntitlementProjectionStore,
    private val clock: EntitlementClock,
    private val timing: EntitlementTiming,
) : SiteWebHostQuery {

    private val log = LoggerFactory.getLogger(SiteWebHostService::class.java)

    @Transactional(propagation = Propagation.MANDATORY)
    override fun resolve(request: SiteWebHostRequest, evaluatedAt: Instant): SiteWebHostQueryResult =
        try {
            publish(evaluateLocally(request, evaluatedAt), request)
        } catch (e: EntitlementPersistenceFailure.Deadlock) {
            throw EntitlementEvaluationFailure.Deadlock(e, e.vendorCode, e.sqlState)
        } catch (e: EntitlementPersistenceFailure.LockTimeout) {
            throw EntitlementEvaluationFailure.LockTimeout(e, e.vendorCode, e.sqlState)
        } catch (e: EntitlementPersistenceFailure.Unavailable) {
            throw EntitlementEvaluationFailure.Unavailable(e, e.vendorCode, e.sqlState)
        }

    private fun evaluateLocally(request: SiteWebHostRequest, evaluatedAt: Instant): SiteWebHostDecision {
        val merchantId = store.findMidOwner(request.mid)
        val head = merchantId?.let { store.lockHeadForShare(it) }
        val usable = head != null && !head.isEmpty && head.blocked == null && isSupportedSnapshotSchema(head.schemaVersion)
        val content = if (usable) store.findSnapshot(checkNotNull(merchantId), checkNotNull(head).revision) else null
        return SiteWebHostEvaluator.evaluate(head, content, request.mid, request.siteId, evaluatedAt, clock.now(), timing.clockSkewMargin)
    }

    private fun publish(decision: SiteWebHostDecision, request: SiteWebHostRequest): SiteWebHostQueryResult = when (decision) {
        is SiteWebHostDecision.WebHost -> SiteWebHostQueryResult.WebHost(decision.host, decision.snapshotVersion, decision.validUntil)
        is SiteWebHostDecision.NoWebHost -> {
            log.info(
                "site web host absent correlationId={} reason={} mid={}",
                EntitlementLogValues.correlationId(), decision.reason, EntitlementLogValues.safe(request.mid),
            )
            SiteWebHostQueryResult.NoWebHost(
                when (decision.reason) {
                    NoWebHostReason.APP_CHANNEL -> SiteWebHostAbsence.APP_CHANNEL
                    NoWebHostReason.SITE_TERMINATED -> SiteWebHostAbsence.SITE_TERMINATED
                    NoWebHostReason.SITE_UNKNOWN -> SiteWebHostAbsence.SITE_UNKNOWN
                },
            )
        }
        is SiteWebHostDecision.Unavailable -> {
            log.warn(
                "site web host unavailable correlationId={} reason={} mid={}",
                EntitlementLogValues.correlationId(), decision.reason, EntitlementLogValues.safe(request.mid),
            )
            SiteWebHostQueryResult.Unavailable(
                when (decision.reason) {
                    SiteWebHostUnavailableReason.SNAPSHOT_MISSING -> SiteWebHostUnavailability.SNAPSHOT_MISSING
                    SiteWebHostUnavailableReason.SNAPSHOT_EXPIRED -> SiteWebHostUnavailability.SNAPSHOT_EXPIRED
                    SiteWebHostUnavailableReason.VERSION_UNSUPPORTED -> SiteWebHostUnavailability.VERSION_UNSUPPORTED
                    SiteWebHostUnavailableReason.HOST_NOT_PROPAGATED -> SiteWebHostUnavailability.HOST_NOT_PROPAGATED
                },
            )
        }
    }
}

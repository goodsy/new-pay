package com.paycore.payment.entitlement.application

import com.paycore.payment.entitlement.application.provided.AuthorizationTermsQuery
import com.paycore.payment.entitlement.application.provided.AuthorizationTermsRefusal
import com.paycore.payment.entitlement.application.provided.AuthorizationTermsRequest
import com.paycore.payment.entitlement.application.provided.AuthorizationTermsResult
import com.paycore.payment.entitlement.application.provided.AuthorizationTermsUnavailable
import com.paycore.payment.entitlement.application.provided.EntitlementEvaluationFailure
import com.paycore.payment.entitlement.application.provided.LimitBound
import com.paycore.payment.entitlement.application.provided.LimitTerms
import com.paycore.payment.entitlement.application.provided.PolicyTerms
import com.paycore.payment.entitlement.application.provided.RouteTerms
import com.paycore.payment.entitlement.application.required.EntitlementClock
import com.paycore.payment.entitlement.application.required.EntitlementMetrics
import com.paycore.payment.entitlement.application.required.EntitlementPersistenceFailure
import com.paycore.payment.entitlement.application.required.EntitlementProjectionStore
import com.paycore.payment.entitlement.application.required.EntitlementTiming
import com.paycore.payment.entitlement.domain.AuthorizationTermsDecision
import com.paycore.payment.entitlement.domain.AuthorizationTermsEvaluator
import com.paycore.payment.entitlement.domain.AuthorizationTermsQuestion
import com.paycore.payment.entitlement.domain.LimitBoundEntry
import com.paycore.payment.entitlement.domain.LimitPolicyChoice
import com.paycore.payment.entitlement.domain.PolicyAssignmentEntry
import com.paycore.payment.entitlement.domain.TermsRefusalReason
import com.paycore.payment.entitlement.domain.TermsUnavailableReason
import com.paycore.payment.entitlement.domain.isSupportedSnapshotSchema
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * 승인 시작 허용 판정(MASTER-SNAPSHOT-002 명세 §6). [EntitlementEvaluationService]와 같은 읽기: 호출자 TX에 참여(MANDATORY)해 MID 소속(비잠금) →
 * head `FOR SHARE`(Inbox 적용 FOR UPDATE와 직렬화) → revision별 내용(불변 행) → domain [AuthorizationTermsEvaluator]. 쓰기·외부 호출·캐시 없음.
 * 로그에는 결과·reason·MID만 남긴다 — 기관 계약계정·근거 참조 원문은 남기지 않는다(D-MS2-005).
 */
@Service
class AuthorizationTermsService(
    private val store: EntitlementProjectionStore,
    private val clock: EntitlementClock,
    private val timing: EntitlementTiming,
    private val metrics: EntitlementMetrics,
) : AuthorizationTermsQuery {

    private val log = LoggerFactory.getLogger(AuthorizationTermsService::class.java)

    @Transactional(propagation = Propagation.MANDATORY)
    override fun evaluate(request: AuthorizationTermsRequest, evaluatedAt: Instant): AuthorizationTermsResult =
        try {
            publish(evaluateLocally(request, evaluatedAt), request)
        } catch (e: EntitlementPersistenceFailure.Deadlock) {
            throw EntitlementEvaluationFailure.Deadlock(e, e.vendorCode, e.sqlState)
        } catch (e: EntitlementPersistenceFailure.LockTimeout) {
            throw EntitlementEvaluationFailure.LockTimeout(e, e.vendorCode, e.sqlState)
        } catch (e: EntitlementPersistenceFailure.Unavailable) {
            throw EntitlementEvaluationFailure.Unavailable(e, e.vendorCode, e.sqlState)
        }

    private fun evaluateLocally(request: AuthorizationTermsRequest, evaluatedAt: Instant): AuthorizationTermsDecision {
        val question = AuthorizationTermsQuestion(request.mid, request.siteId, request.paymentMethod, request.currency, request.acquirerCode)
        val merchantId = store.findMidOwner(request.mid)
        val head = merchantId?.let { store.lockHeadForShare(it) }
        val usable = head != null && !head.isEmpty && head.blocked == null && isSupportedSnapshotSchema(head.schemaVersion)
        val content = if (usable) store.findSnapshot(checkNotNull(merchantId), checkNotNull(head).revision) else null
        return AuthorizationTermsEvaluator.evaluate(head, content, question, evaluatedAt, clock.now(), timing.clockSkewMargin)
    }

    private fun publish(decision: AuthorizationTermsDecision, request: AuthorizationTermsRequest): AuthorizationTermsResult = when (decision) {
        is AuthorizationTermsDecision.Admitted -> {
            val limit = when (val l = decision.limit) {
                is LimitPolicyChoice.Resolved -> LimitTerms.Resolved(l.policy.policyId, l.policy.scope.name, l.policy.currency, l.policy.perTransaction.toBound(), l.policy.daily.toBound())
                LimitPolicyChoice.Missing -> LimitTerms.Missing
                LimitPolicyChoice.Ambiguous -> LimitTerms.Ambiguous
            }
            metrics.authorizationTerms("ADMITTED", limit::class.simpleName?.uppercase())
            AuthorizationTermsResult.Admitted(
                entitlementVersion = decision.entitlementVersion,
                route = RouteTerms(decision.route.activationId, decision.route.acquirerCode, decision.route.routeRef, decision.route.institutionMerchantRef),
                feeSchedule = decision.feeSchedule.toTerms(),
                settlementPolicy = decision.settlementPolicy.toTerms(),
                limit = limit,
                validUntil = decision.validUntil,
                evaluatedAt = decision.evaluatedAt,
            )
        }
        is AuthorizationTermsDecision.NotFoundOrForbidden -> {
            metrics.authorizationTerms("NOT_FOUND_OR_FORBIDDEN", null)
            log.info(
                "authorization terms refused correlationId={} result=NOT_FOUND_OR_FORBIDDEN detail={} mid={}",
                EntitlementLogValues.correlationId(), decision.detail, EntitlementLogValues.safe(request.mid),
            )
            AuthorizationTermsResult.NotFoundOrForbidden
        }
        is AuthorizationTermsDecision.Refused -> {
            metrics.authorizationTerms("REFUSED", decision.reason.name)
            log.info(
                "authorization terms refused correlationId={} result=REFUSED reason={} detail={} mid={}",
                EntitlementLogValues.correlationId(), decision.reason, decision.detail, EntitlementLogValues.safe(request.mid),
            )
            AuthorizationTermsResult.Refused(
                when (decision.reason) {
                    TermsRefusalReason.MERCHANT_SUSPENDED -> AuthorizationTermsRefusal.MERCHANT_SUSPENDED
                    TermsRefusalReason.CONTRACT_NOT_EFFECTIVE -> AuthorizationTermsRefusal.CONTRACT_NOT_EFFECTIVE
                    TermsRefusalReason.METHOD_NOT_ENABLED -> AuthorizationTermsRefusal.METHOD_NOT_ENABLED
                    TermsRefusalReason.CURRENCY_NOT_ENABLED -> AuthorizationTermsRefusal.CURRENCY_NOT_ENABLED
                    TermsRefusalReason.CARD_COMPANY_NOT_ENABLED -> AuthorizationTermsRefusal.CARD_COMPANY_NOT_ENABLED
                },
            )
        }
        is AuthorizationTermsDecision.Unavailable -> {
            metrics.authorizationTerms("UNAVAILABLE", decision.reason.name)
            log.warn(
                "authorization terms unavailable correlationId={} reason={} mid={}",
                EntitlementLogValues.correlationId(), decision.reason, EntitlementLogValues.safe(request.mid),
            )
            AuthorizationTermsResult.Unavailable(
                when (decision.reason) {
                    TermsUnavailableReason.SNAPSHOT_MISSING -> AuthorizationTermsUnavailable.SNAPSHOT_MISSING
                    TermsUnavailableReason.SNAPSHOT_EXPIRED -> AuthorizationTermsUnavailable.SNAPSHOT_EXPIRED
                    TermsUnavailableReason.VERSION_UNSUPPORTED -> AuthorizationTermsUnavailable.VERSION_UNSUPPORTED
                    TermsUnavailableReason.TERMS_NOT_PROPAGATED -> AuthorizationTermsUnavailable.TERMS_NOT_PROPAGATED
                    TermsUnavailableReason.POLICY_ASSIGNMENT_MISSING -> AuthorizationTermsUnavailable.POLICY_ASSIGNMENT_MISSING
                    TermsUnavailableReason.POLICY_ASSIGNMENT_AMBIGUOUS -> AuthorizationTermsUnavailable.POLICY_ASSIGNMENT_AMBIGUOUS
                    TermsUnavailableReason.ROUTE_AMBIGUOUS -> AuthorizationTermsUnavailable.ROUTE_AMBIGUOUS
                },
            )
        }
    }

    private fun PolicyAssignmentEntry.toTerms() = PolicyTerms(assignmentId, policyVersionId, policyCode, versionNo, contentStatus.name)

    private fun LimitBoundEntry.toBound(): LimitBound = when (this) {
        LimitBoundEntry.Unlimited -> LimitBound.Unlimited
        is LimitBoundEntry.Limited -> LimitBound.Limited(amountMinor)
    }
}

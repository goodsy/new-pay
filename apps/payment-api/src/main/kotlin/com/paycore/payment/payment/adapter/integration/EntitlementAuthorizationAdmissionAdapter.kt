package com.paycore.payment.payment.adapter.integration

import com.paycore.payment.entitlement.application.provided.AuthorizationTermsQuery
import com.paycore.payment.entitlement.application.provided.AuthorizationTermsRefusal
import com.paycore.payment.entitlement.application.provided.AuthorizationTermsRequest
import com.paycore.payment.entitlement.application.provided.AuthorizationTermsResult
import com.paycore.payment.entitlement.application.provided.AuthorizationTermsUnavailable
import com.paycore.payment.entitlement.application.provided.EntitlementEvaluationFailure
import com.paycore.payment.entitlement.application.provided.LimitBound
import com.paycore.payment.entitlement.application.provided.LimitTerms
import com.paycore.payment.entitlement.application.provided.PolicyTerms
import com.paycore.payment.payment.application.provided.StoreDiagnostic
import com.paycore.payment.payment.application.required.AdmissionRefusalReason
import com.paycore.payment.payment.application.required.AdmissionResult
import com.paycore.payment.payment.application.required.AdmissionUnavailableReason
import com.paycore.payment.payment.application.required.AdmittedRoute
import com.paycore.payment.payment.application.required.AuthorizationAdmissionPort
import com.paycore.payment.payment.application.required.PersistenceFailure
import com.paycore.payment.payment.domain.CurrencyCode
import com.paycore.payment.payment.domain.LimitAmount
import com.paycore.payment.payment.domain.LimitPolicyResolution
import com.paycore.payment.payment.domain.MerchantAccountId
import com.paycore.payment.payment.domain.MerchantSiteId
import com.paycore.payment.payment.domain.PaymentMethod
import com.paycore.payment.payment.domain.TransactionLimitPolicies
import com.paycore.payment.payment.domain.TransactionLimitPolicy
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * [AuthorizationAdmissionPort]의 운영 구현(MASTER-SNAPSHOT-002 명세 §6.3). entitlement 모듈의 공개 조회([AuthorizationTermsQuery], provided만 —
 * Modulith 공개 범위)를 같은 PAYMENT 로컬 TX에서 부르고 payment domain 모양으로 옮긴다. 판정 규칙은 entitlement domain에만 있다.
 *
 * - 한도: 출처(CSA 기본/MID Override)와 무관하게 범위는 MID + 통화다(D-AUTH-006). 옮긴 정책을 domain [TransactionLimitPolicies.resolve]로 다시
 *   걸러 MID·통화 불일치를 결함이 아니라 Missing으로 막는다(이중 방어).
 * - 정책 Assignment: 수수료·정산 두 참조를 정규 문자열 `pa1:fee=…;stl=…`로 고정한다(domain 타입 변경 없음).
 */
@Component
class EntitlementAuthorizationAdmissionAdapter(private val query: AuthorizationTermsQuery) : AuthorizationAdmissionPort {

    override fun evaluate(
        authenticatedMid: MerchantAccountId,
        merchantSiteId: MerchantSiteId,
        currency: CurrencyCode,
        paymentMethod: PaymentMethod,
        acquirerCode: String,
        evaluatedAt: Instant,
    ): AdmissionResult {
        val request = AuthorizationTermsRequest(authenticatedMid.value, merchantSiteId.value, paymentMethod.name, currency.value, acquirerCode)
        return when (val r = evaluateOrClassify(request, evaluatedAt)) {
            is AuthorizationTermsResult.Admitted -> AdmissionResult.Admitted(
                entitlementVersion = r.entitlementVersion,
                policyAssignmentVersion = policyAssignmentVersion(r.feeSchedule, r.settlementPolicy),
                route = AdmittedRoute(r.route.activationId, r.route.acquirerCode, r.route.routeRef, r.route.institutionMerchantRef),
                limitPolicy = limitOf(r.limit, authenticatedMid, currency),
                policyContentStatus = setOf(r.feeSchedule.contentStatus, r.settlementPolicy.contentStatus),
                validUntil = r.validUntil,
                evaluatedAt = r.evaluatedAt,
            )
            AuthorizationTermsResult.NotFoundOrForbidden -> AdmissionResult.NotFoundOrForbidden
            is AuthorizationTermsResult.Refused -> AdmissionResult.Refused(
                when (r.reason) {
                    AuthorizationTermsRefusal.MERCHANT_SUSPENDED -> AdmissionRefusalReason.MERCHANT_SUSPENDED
                    AuthorizationTermsRefusal.CONTRACT_NOT_EFFECTIVE -> AdmissionRefusalReason.CONTRACT_NOT_EFFECTIVE
                    AuthorizationTermsRefusal.METHOD_NOT_ENABLED -> AdmissionRefusalReason.METHOD_NOT_ENABLED
                    AuthorizationTermsRefusal.CURRENCY_NOT_ENABLED -> AdmissionRefusalReason.CURRENCY_NOT_ENABLED
                    AuthorizationTermsRefusal.CARD_COMPANY_NOT_ENABLED -> AdmissionRefusalReason.CARD_COMPANY_NOT_ENABLED
                },
            )
            is AuthorizationTermsResult.Unavailable -> AdmissionResult.Unavailable(
                when (r.reason) {
                    AuthorizationTermsUnavailable.SNAPSHOT_MISSING -> AdmissionUnavailableReason.SNAPSHOT_MISSING
                    AuthorizationTermsUnavailable.SNAPSHOT_EXPIRED -> AdmissionUnavailableReason.SNAPSHOT_EXPIRED
                    AuthorizationTermsUnavailable.VERSION_UNSUPPORTED -> AdmissionUnavailableReason.VERSION_UNSUPPORTED
                    AuthorizationTermsUnavailable.TERMS_NOT_PROPAGATED -> AdmissionUnavailableReason.TERMS_NOT_PROPAGATED
                    AuthorizationTermsUnavailable.POLICY_ASSIGNMENT_MISSING -> AdmissionUnavailableReason.POLICY_ASSIGNMENT_MISSING
                    AuthorizationTermsUnavailable.POLICY_ASSIGNMENT_AMBIGUOUS -> AdmissionUnavailableReason.POLICY_ASSIGNMENT_AMBIGUOUS
                    AuthorizationTermsUnavailable.ROUTE_AMBIGUOUS -> AdmissionUnavailableReason.ROUTE_AMBIGUOUS
                },
            )
        }
    }

    private fun limitOf(limit: LimitTerms, mid: MerchantAccountId, currency: CurrencyCode): LimitPolicyResolution = when (limit) {
        is LimitTerms.Resolved -> TransactionLimitPolicies.resolve(
            listOf(TransactionLimitPolicy(limit.policyId, mid, CurrencyCode(limit.currency), limit.perTransaction.toAmount(), limit.daily.toAmount())),
            mid,
            currency,
        )
        LimitTerms.Missing -> LimitPolicyResolution.Missing
        LimitTerms.Ambiguous -> LimitPolicyResolution.Ambiguous
    }

    private fun LimitBound.toAmount(): LimitAmount = when (this) {
        LimitBound.Unlimited -> LimitAmount.Unlimited
        is LimitBound.Limited -> LimitAmount.Limited(amountMinor)
    }

    private fun evaluateOrClassify(request: AuthorizationTermsRequest, evaluatedAt: Instant): AuthorizationTermsResult =
        try {
            query.evaluate(request, evaluatedAt)
        } catch (e: EntitlementEvaluationFailure.Deadlock) {
            throw PersistenceFailure.Deadlock(e, diagnosticOf(e))
        } catch (e: EntitlementEvaluationFailure.LockTimeout) {
            throw PersistenceFailure.LockTimeout(e, diagnosticOf(e))
        } catch (e: EntitlementEvaluationFailure.Unavailable) {
            throw PersistenceFailure.Unavailable(e, diagnosticOf(e))
        }

    private fun diagnosticOf(e: EntitlementEvaluationFailure): StoreDiagnostic? =
        if (e.vendorCode == null && e.sqlState == null) null else StoreDiagnostic(vendorCode = e.vendorCode, sqlState = e.sqlState)

    companion object {
        /** 정책 Assignment 근거 정규 문자열(명세 §6.3 관례, 160자 이하). */
        fun policyAssignmentVersion(fee: PolicyTerms, settlement: PolicyTerms): String =
            "pa1:fee=${fee.assignmentId}/${fee.policyVersionId};stl=${settlement.assignmentId}/${settlement.policyVersionId}"
    }
}

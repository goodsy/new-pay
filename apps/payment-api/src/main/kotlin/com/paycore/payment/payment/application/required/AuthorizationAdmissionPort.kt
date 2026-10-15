package com.paycore.payment.payment.application.required

import com.paycore.payment.payment.domain.AuthorizationAdmission
import com.paycore.payment.payment.domain.CurrencyCode
import com.paycore.payment.payment.domain.LimitPolicyResolution
import com.paycore.payment.payment.domain.MerchantAccountId
import com.paycore.payment.payment.domain.MerchantSiteId
import com.paycore.payment.payment.domain.PaymentMethod
import java.time.Instant

/**
 * 승인 시작 허용 판정 Port(AUTH-CONTRACT-001 §4.6, MASTER-SNAPSHOT-002 명세 §6). 구현 규칙은 [PaymentEligibilityPort]와 같다: **PAYMENT DB 로컬
 * 권리 투영만** 읽고 호출자의 승인 시작 TX1 안에서 부른다(쓰기·외부 호출·MASTER 조회·캐시 금지). 판정 불가는 예외가 아니라
 * [AdmissionResult.Unavailable](fail-closed). 저장소 일시 실패는 [PersistenceFailure]로 던진다.
 *
 * 호출자(AUTH-001 confirm Use Case)는 [evaluatedAt]과 `AuthorizationStart`의 `at`에 같은 시각을 넘긴다(D-AUTH-007 일 한도 날짜와 정책 선택이 같은
 * 시각에서 나온다). HTTP·저장 거절 매핑(503 vs 업무 거절, AUTH-001 n-2)은 이 Port가 정하지 않는다.
 */
interface AuthorizationAdmissionPort {
    /** @param acquirerCode 서버가 검증한 인증 결과의 매입사 코드(D-MS2-004 — 카드사 개통 키). */
    fun evaluate(
        authenticatedMid: MerchantAccountId,
        merchantSiteId: MerchantSiteId,
        currency: CurrencyCode,
        paymentMethod: PaymentMethod,
        acquirerCode: String,
        evaluatedAt: Instant,
    ): AdmissionResult
}

sealed interface AdmissionResult {
    /**
     * 승인 시작 조건 충족(한도 거절은 domain `AuthorizationStart`가 [limitPolicy]로 판정 — 없음·모호면 LimitPolicyUnavailable). [route]의
     * [AdmittedRoute.institutionMerchantRef]는 내부 식별자 등급(D-MS2-005).
     */
    data class Admitted(
        val entitlementVersion: String,
        /** 정규 문자열 `pa1:fee=<assignmentId>/<policyVersionId>;stl=<assignmentId>/<policyVersionId>`(명세 §6.3 관례). */
        val policyAssignmentVersion: String,
        val route: AdmittedRoute,
        val limitPolicy: LimitPolicyResolution,
        val policyContentStatus: Set<String>,
        val validUntil: Instant,
        val evaluatedAt: Instant,
    ) : AdmissionResult {
        /** domain [AuthorizationAdmission]으로 고정한다. [protocolVersion]은 PAYMENT 로컬 기관 프로파일(AUTH-001)에서 온다 — MASTER 데이터가 아니다. */
        fun admission(protocolVersion: String): AuthorizationAdmission =
            AuthorizationAdmission(entitlementVersion, policyAssignmentVersion, route.routeRef, protocolVersion, evaluatedAt)
    }

    data object NotFoundOrForbidden : AdmissionResult

    data class Refused(val reason: AdmissionRefusalReason) : AdmissionResult

    data class Unavailable(val reason: AdmissionUnavailableReason) : AdmissionResult
}

data class AdmittedRoute(val activationId: String, val acquirerCode: String, val routeRef: String, val institutionMerchantRef: String) {
    override fun toString(): String = "AdmittedRoute(activationId=$activationId, acquirerCode=$acquirerCode, routeRef=$routeRef)"
}

enum class AdmissionRefusalReason { MERCHANT_SUSPENDED, CONTRACT_NOT_EFFECTIVE, METHOD_NOT_ENABLED, CURRENCY_NOT_ENABLED, CARD_COMPANY_NOT_ENABLED }

enum class AdmissionUnavailableReason {
    SNAPSHOT_MISSING,
    SNAPSHOT_EXPIRED,
    VERSION_UNSUPPORTED,
    TERMS_NOT_PROPAGATED,
    POLICY_ASSIGNMENT_MISSING,
    POLICY_ASSIGNMENT_AMBIGUOUS,
    ROUTE_AMBIGUOUS,
}

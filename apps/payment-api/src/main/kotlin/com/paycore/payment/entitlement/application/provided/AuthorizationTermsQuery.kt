package com.paycore.payment.entitlement.application.provided

import java.time.Instant

/**
 * 승인 시작 허용 판정 조회(MASTER-SNAPSHOT-002 명세 §6.1). **호출자의 PAYMENT 로컬 TX(승인 시작 TX1)에 참여한다**(MANDATORY). PAYMENT DB 로컬 권리
 * 투영만 읽고(head `FOR SHARE`, 쓰기·외부 호출·MASTER 조회·캐시 없음), 판정 불가(데이터 없음·만료·v1)와 업무 거절(카드사 미개통 등)을 구분된 타입으로
 * 돌려준다. HTTP·저장 거절 매핑은 호출자(AUTH-001 confirm Use Case) 몫이다.
 *
 * 호출자는 [evaluatedAt]과 domain `AuthorizationStart.at`에 **같은 시각**을 넘긴다(정책 선택 = 그 UTC 시각, 일 한도 날짜 = 그 시각의 Asia/Seoul 날짜,
 * D-AUTH-007). 공개 타입만 쓴다(다른 모듈은 provided만 — domain 타입 비노출).
 */
interface AuthorizationTermsQuery {
    /** @throws EntitlementEvaluationFailure 저장소 일시 실패(교착·잠금 timeout·연결). 판정 결과가 아니다 — 호출자 TX는 rollback해야 한다. */
    fun evaluate(request: AuthorizationTermsRequest, evaluatedAt: Instant): AuthorizationTermsResult
}

/** [acquirerCode] = 서버 검증 인증 결과의 매입사 코드(D-MS2-004). */
data class AuthorizationTermsRequest(val mid: String, val siteId: String, val paymentMethod: String, val currency: String, val acquirerCode: String)

sealed interface AuthorizationTermsResult {
    data class Admitted(
        val entitlementVersion: String,
        val route: RouteTerms,
        val feeSchedule: PolicyTerms,
        val settlementPolicy: PolicyTerms,
        val limit: LimitTerms,
        val validUntil: Instant,
        val evaluatedAt: Instant,
    ) : AuthorizationTermsResult

    data object NotFoundOrForbidden : AuthorizationTermsResult

    data class Refused(val reason: AuthorizationTermsRefusal) : AuthorizationTermsResult

    data class Unavailable(val reason: AuthorizationTermsUnavailable) : AuthorizationTermsResult
}

enum class AuthorizationTermsRefusal { MERCHANT_SUSPENDED, CONTRACT_NOT_EFFECTIVE, METHOD_NOT_ENABLED, CURRENCY_NOT_ENABLED, CARD_COMPANY_NOT_ENABLED }

enum class AuthorizationTermsUnavailable {
    SNAPSHOT_MISSING,
    SNAPSHOT_EXPIRED,
    VERSION_UNSUPPORTED,
    TERMS_NOT_PROPAGATED,
    POLICY_ASSIGNMENT_MISSING,
    POLICY_ASSIGNMENT_AMBIGUOUS,
    ROUTE_AMBIGUOUS,
}

/**
 * 판정에 쓴 카드사 개통(Route). [institutionMerchantRef]는 내부 식별자 등급(D-MS2-005) — [toString]이 가린다. 로그·응답·예외 메시지에 원문 금지.
 * SIMULATOR/REAL의 권위는 [routeRef]가 가리키는 PAYMENT 로컬 기관 프로파일(AUTH-001)이며 모르는 routeRef는 그쪽에서 fail-closed.
 */
data class RouteTerms(val activationId: String, val acquirerCode: String, val routeRef: String, val institutionMerchantRef: String) {
    override fun toString(): String = "RouteTerms(activationId=$activationId, acquirerCode=$acquirerCode, routeRef=$routeRef)"
}

/**
 * 판정에 쓴 정책 Assignment와 그 정책 버전 식별(D-MD-018, D-MS2-003). [contentStatus] UNDEFINED·DEMO는 모두 내용 없는 버전 — TEST/Simulator 전용,
 * LIVE 허용 Gate에서 차단 대상(이 조회는 판정하지 않고 그대로 전달한다).
 */
data class PolicyTerms(val assignmentId: String, val policyVersionId: String, val policyCode: String, val versionNo: Long, val contentStatus: String)

/** 한도 값(명시 무제한만 무제한). */
sealed interface LimitBound {
    data object Unlimited : LimitBound

    data class Limited(val amountMinor: Long) : LimitBound
}

sealed interface LimitTerms {
    /** 적용 정책 정확히 1개. [policyId] = MASTER 정책 행 ID(= 버전). [scope] = CSA_DEFAULT / MID_OVERRIDE(내부 기록용). */
    data class Resolved(val policyId: String, val scope: String, val currency: String, val perTransaction: LimitBound, val daily: LimitBound) : LimitTerms

    data object Missing : LimitTerms

    data object Ambiguous : LimitTerms
}

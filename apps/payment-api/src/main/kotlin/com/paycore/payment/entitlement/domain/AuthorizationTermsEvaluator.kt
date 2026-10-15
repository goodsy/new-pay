package com.paycore.payment.entitlement.domain

import java.time.Duration
import java.time.Instant

/*
 * 승인 시작 허용 판정(MASTER-SNAPSHOT-002 명세 §6.2, AUTH-CONTRACT-001 §4.6). PAYMENT 로컬 권리 투영(schemaVersion 2)만으로 판정한다 — MASTER
 * 동기 조회 없음(허용 §4.2). 한도 거절 자체는 payment domain `AuthorizationStart`가 하고, 여기서는 적용 정책 1개를 해석해 넘긴다(D-AUTH-006).
 */

/** 승인 시작 판정 질문. [acquirerCode]는 서버가 검증한 인증 결과의 매입사 코드(D-MS2-004). environment는 입력이 아니다(D-AUTH-006 정책 공통, D-MS-001). */
data class AuthorizationTermsQuestion(
    val mid: String,
    val siteId: String,
    val paymentMethod: String,
    val currency: String,
    val acquirerCode: String,
)

/** 적용 한도 정책 해석(D-AUTH-006, D-MS2-001): 정확히 1개만 Resolved. 없음·모호는 승인 시작 차단(payment domain이 거절). */
sealed interface LimitPolicyChoice {
    data class Resolved(val policy: LimitPolicyEntry) : LimitPolicyChoice

    data object Missing : LimitPolicyChoice

    data object Ambiguous : LimitPolicyChoice
}

enum class TermsRefusalReason { MERCHANT_SUSPENDED, CONTRACT_NOT_EFFECTIVE, METHOD_NOT_ENABLED, CURRENCY_NOT_ENABLED, CARD_COMPANY_NOT_ENABLED }

/** 판정 불가(fail-closed). TERMS_NOT_PROPAGATED = head가 v1(승인 시작 조건 미전파). */
enum class TermsUnavailableReason {
    SNAPSHOT_MISSING,
    SNAPSHOT_EXPIRED,
    VERSION_UNSUPPORTED,
    TERMS_NOT_PROPAGATED,
    POLICY_ASSIGNMENT_MISSING,
    POLICY_ASSIGNMENT_AMBIGUOUS,
    ROUTE_AMBIGUOUS,
}

sealed interface AuthorizationTermsDecision {
    /**
     * 승인 시작 조건 충족(한도 거절 전). [validUntil] > 평가 시각. 판정 근거([entitlementVersion]·Assignment·Route·한도 정책 ID)는 Authorization에 고정한다.
     */
    data class Admitted(
        val entitlementVersion: String,
        val route: CardRouteEntry,
        val feeSchedule: PolicyAssignmentEntry,
        val settlementPolicy: PolicyAssignmentEntry,
        val limit: LimitPolicyChoice,
        val validUntil: Instant,
        val evaluatedAt: Instant,
    ) : AuthorizationTermsDecision

    /** Site 없음·다른 Merchant·Assignment 무효(권리 F1, 구분하지 않음). */
    data class NotFoundOrForbidden(val detail: RefusalDetail) : AuthorizationTermsDecision

    data class Refused(val reason: TermsRefusalReason, val detail: RefusalDetail?) : AuthorizationTermsDecision

    data class Unavailable(val reason: TermsUnavailableReason) : AuthorizationTermsDecision
}

/**
 * 판정 순서(명세 §6.2, 앞 단계가 결정하면 멈춤):
 *
 * | 단계 | 조건 | 결과 |
 * |---|---|---|
 * | A0 | 권리 U1~U3(누락·막힘·만료) / head가 v1 | Unavailable(… / TERMS_NOT_PROPAGATED) |
 * | A1 | 권리 F1·E1~E5 재평가(생성 시 판정을 대신하지 않는다) | NotFoundOrForbidden / Refused(권리 reason) |
 * | A2 | (수단, 매입사) 카드사 개통 없음·ACTIVE 아님 / 2개 이상 | Refused(CARD_COMPANY_NOT_ENABLED) / Unavailable(ROUTE_AMBIGUOUS) |
 * | A3 | 종류별(수수료·정산) 발급 CSA·통화·t의 Assignment 0 / ≥2 | Unavailable(POLICY_ASSIGNMENT_MISSING / _AMBIGUOUS) |
 * | A4 | 한도: t에 유효한 MID Override가 있으면 그것들, 없으면 발급 CSA 기본들(D-MS2-001 Override 우선·전체 대체) → 0 Missing·1 Resolved·≥2 Ambiguous | Admitted에 포함 |
 *
 * `validUntil = min(권리 validUntil, 쓴 Assignment·한도 구간의 끝, t 이후 시작하는 같은 통화 한도 후보·같은 (종류, 통화) Assignment 후보의 시작)` —
 * t 이후 시작하는 Override는 해석을 바꾸므로 경계다. 잠금 대기로 now ≥ validUntil이면 SNAPSHOT_EXPIRED(권리 §6.3과 같은 규칙).
 */
object AuthorizationTermsEvaluator {
    @Suppress("LongParameterList", "ReturnCount", "CyclomaticComplexMethod")
    fun evaluate(
        head: EntitlementHead?,
        content: EntitlementContent?,
        question: AuthorizationTermsQuestion,
        evaluatedAt: Instant,
        now: Instant,
        skew: Duration,
    ): AuthorizationTermsDecision {
        val t = evaluatedAt
        val entitlement = EntitlementEvaluator.evaluate(
            head, content, EligibilityQuestion(question.mid, question.siteId, question.paymentMethod, question.currency), t, now, skew,
        )
        if (entitlement is EntitlementDecision.Unavailable) return unavailable(entitlement.reason.toTerms())
        val c = checkNotNull(content) { "a usable head has its content" }
        if (c.schemaVersion < TERMS_SNAPSHOT_SCHEMA_VERSION) return unavailable(TermsUnavailableReason.TERMS_NOT_PROPAGATED)
        val allowed = when (entitlement) {
            is EntitlementDecision.Allowed -> entitlement
            is EntitlementDecision.NotFoundOrForbidden -> return AuthorizationTermsDecision.NotFoundOrForbidden(entitlement.detail)
            is EntitlementDecision.Refused -> return AuthorizationTermsDecision.Refused(entitlement.reason.toTerms(), entitlement.detail)
            is EntitlementDecision.Unavailable -> error("handled above")
        }
        val account = checkNotNull(c.account(question.mid)) { "an allowed MID is in the snapshot" }
        val terms = checkNotNull(account.terms) { "schema version 2 carries terms" }

        // A2: 카드사(매입사) 개통.
        val routes = terms.cardRoutes.filter { it.paymentMethod == question.paymentMethod && it.acquirerCode == question.acquirerCode }
        if (routes.size > 1) return unavailable(TermsUnavailableReason.ROUTE_AMBIGUOUS)
        val route = routes.singleOrNull()?.takeIf { it.status == ActivationStatus.ACTIVE }
            ?: return AuthorizationTermsDecision.Refused(TermsRefusalReason.CARD_COMPANY_NOT_ENABLED, null)

        // A3: 정책 Assignment(종류별 정확히 하나).
        val boundaries = mutableListOf<Instant>()
        fun assignmentOf(kind: PolicyKind): Pair<PolicyAssignmentEntry?, TermsUnavailableReason?> {
            val sameKey = terms.policyAssignments.filter { it.kind == kind && it.currency == question.currency && it.agreementId == account.issuingAgreementId }
            boundaries += sameKey.map { it.period.from }.filter { it.isAfter(t) }
            val effective = sameKey.filter { it.period.contains(t) }
            return when (effective.size) {
                0 -> null to TermsUnavailableReason.POLICY_ASSIGNMENT_MISSING
                1 -> effective.single().also { a -> a.period.to?.let { boundaries += it } } to null
                else -> null to TermsUnavailableReason.POLICY_ASSIGNMENT_AMBIGUOUS
            }
        }
        val (fee, feeProblem) = assignmentOf(PolicyKind.FEE_SCHEDULE)
        feeProblem?.let { return unavailable(it) }
        val (settlement, settlementProblem) = assignmentOf(PolicyKind.SETTLEMENT_POLICY)
        settlementProblem?.let { return unavailable(it) }

        // A4: 한도 정책 해석(Override 우선, D-MS2-001).
        val sameCurrency = terms.limitPolicies.filter {
            it.currency == question.currency && (it.scope == LimitScopeKind.MID_OVERRIDE || it.agreementId == account.issuingAgreementId)
        }
        boundaries += sameCurrency.map { it.period.from }.filter { it.isAfter(t) }
        val overrides = sameCurrency.filter { it.scope == LimitScopeKind.MID_OVERRIDE && it.period.contains(t) }
        val candidates = overrides.ifEmpty { sameCurrency.filter { it.scope == LimitScopeKind.CSA_DEFAULT && it.period.contains(t) } }
        val limit = when (candidates.size) {
            0 -> LimitPolicyChoice.Missing
            1 -> LimitPolicyChoice.Resolved(candidates.single()).also { r -> r.policy.period.to?.let { boundaries += it } }
            else -> LimitPolicyChoice.Ambiguous
        }

        val validUntil = (boundaries.filter { it.isAfter(t) } + allowed.validUntil).min()
        check(validUntil.isAfter(t)) { "admitted terms are valid after the evaluation time" }
        if (!now.isBefore(validUntil)) return unavailable(TermsUnavailableReason.SNAPSHOT_EXPIRED)
        return AuthorizationTermsDecision.Admitted(
            allowed.snapshotVersion, route, checkNotNull(fee), checkNotNull(settlement), limit, validUntil, t,
        )
    }

    private fun unavailable(reason: TermsUnavailableReason) = AuthorizationTermsDecision.Unavailable(reason)

    private fun UnavailableReason.toTerms(): TermsUnavailableReason = when (this) {
        UnavailableReason.SNAPSHOT_MISSING -> TermsUnavailableReason.SNAPSHOT_MISSING
        UnavailableReason.SNAPSHOT_EXPIRED -> TermsUnavailableReason.SNAPSHOT_EXPIRED
        UnavailableReason.VERSION_UNSUPPORTED -> TermsUnavailableReason.VERSION_UNSUPPORTED
    }

    private fun RefusalReason.toTerms(): TermsRefusalReason = when (this) {
        RefusalReason.MERCHANT_SUSPENDED -> TermsRefusalReason.MERCHANT_SUSPENDED
        RefusalReason.CONTRACT_NOT_EFFECTIVE -> TermsRefusalReason.CONTRACT_NOT_EFFECTIVE
        RefusalReason.METHOD_NOT_ENABLED -> TermsRefusalReason.METHOD_NOT_ENABLED
        RefusalReason.CURRENCY_NOT_ENABLED -> TermsRefusalReason.CURRENCY_NOT_ENABLED
    }
}

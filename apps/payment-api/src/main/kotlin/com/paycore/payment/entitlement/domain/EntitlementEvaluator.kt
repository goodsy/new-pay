package com.paycore.payment.entitlement.domain

import java.time.Duration
import java.time.Instant

/** 판정 결과(PAYMENT 내부 — CreatePayment Port adapter가 계약 §13 EligibilityResult로 옮긴다). */
sealed interface EntitlementDecision {
    /** 생성 가능. [validUntil] > 평가 시각(같거나 이른 Eligible은 만들지 않는다 — R12). */
    data class Allowed(val snapshotVersion: String, val currency: String, val minorUnit: Int, val validUntil: Instant) : EntitlementDecision

    /** Site 없음·다른 Merchant·Assignment 무효·유효 PRIMARY 없음(구분하지 않음, CP계약 §9.1). [detail]은 내부 로그용. */
    data class NotFoundOrForbidden(val detail: RefusalDetail) : EntitlementDecision

    /** NotEligible 고정 4종(CP계약 §13). [detail]·[suspendedScope]는 내부 로그·감사 전용(응답·metric label 아님, D-MD-019). */
    data class Refused(val reason: RefusalReason, val detail: RefusalDetail, val suspendedScope: SuspendedScope?) : EntitlementDecision

    data class Unavailable(val reason: UnavailableReason) : EntitlementDecision
}

enum class RefusalReason { MERCHANT_SUSPENDED, CONTRACT_NOT_EFFECTIVE, METHOD_NOT_ENABLED, CURRENCY_NOT_ENABLED }

enum class UnavailableReason { SNAPSHOT_MISSING, SNAPSHOT_EXPIRED, VERSION_UNSUPPORTED }

enum class SuspendedScope { BUSINESS, MERCHANT, MID }

/** 내부 로그용 세부 사유(가맹점 응답에는 나가지 않는다). */
enum class RefusalDetail {
    SITE_UNKNOWN, SITE_TERMINATED, ASSIGNMENT_NOT_EFFECTIVE, PRIMARY_NOT_EFFECTIVE, PARTY_SUSPENDED, MID_SUSPENDED, CONTRACT_WINDOW,
    SITE_REVIEW, ACTIVATION, SITE_CONFIRMATION, CURRENCY,
}

/**
 * 결제 생성 판정(MASTER-SNAPSHOT-001 명세 §6). 앞 단계가 결정하면 멈춘다:
 *
 * | 단계 | 조건 | 결과 |
 * |---|---|---|
 * | U1 | MID 소속 없음 / head 없음(revision 0) / head의 Snapshot에 그 MID 없음 | Unavailable SNAPSHOT_MISSING(미전파 MID — Coordinator 채택 해석) |
 * | U2 | head 막힘 / 미지원 schemaVersion | Unavailable VERSION_UNSUPPORTED |
 * | U3 | t ≥ leaseUntil − skew | Unavailable SNAPSHOT_EXPIRED |
 * | F1 | Site가 Snapshot에 없음·TERMINATED / (MID, Site) Assignment가 t에 유효(ACTIVE ∧ 기간 안)하지 않음 / t에 유효한 PRIMARY 없음 | NotFoundOrForbidden |
 * | E1 | Business·Merchant TERMINATED 또는 차단 / MID 차단·TERMINATED | MERCHANT_SUSPENDED(D-MD-019, D-MS-002(c)) |
 * | E2 | (MID, Site) 계약 적용 구간이 t를 포함하지 않음(효력 전·종료·Scope 밖) | CONTRACT_NOT_EFFECTIVE(D-MS-002(b)) |
 * | E3 | Site 심사가 APPROVED 아님 | METHOD_NOT_ENABLED(D-MS-002(a), 내부 SITE_REVIEW) |
 * | E4 | 결제수단 개통 ≠ ACTIVE 또는 (Site, 수단) 적용 확인 없음 | METHOD_NOT_ENABLED(D-MD-016·017) |
 * | E5 | t에 유효한 통화 활성화 없음 | CURRENCY_NOT_ENABLED(D-MD-018) |
 * | OK | — | Allowed(validUntil = min(leaseUntil − skew, 쓴 구간의 t 이후 첫 경계)) |
 *
 * 잠금 대기로 평가가 늦어지면(now > t) 판정은 t로 하고 now ≥ validUntil이면 SNAPSHOT_EXPIRED로 바꾼다 — 구간이 validUntil까지 상수이므로
 * "t에서 허용이고 now에서도 같은 판정"일 때만 Allowed다(명세 §6.3). environment는 입력이 아니다(D-MS-001).
 */
object EntitlementEvaluator {
    @Suppress("LongParameterList", "ReturnCount")
    fun evaluate(
        head: EntitlementHead?,
        content: EntitlementContent?,
        question: EligibilityQuestion,
        evaluatedAt: Instant,
        now: Instant,
        skew: Duration,
    ): EntitlementDecision {
        require(!skew.isNegative) { "clock skew margin must not be negative" }
        if (head == null || head.isEmpty) return EntitlementDecision.Unavailable(UnavailableReason.SNAPSHOT_MISSING)
        if (head.blocked != null || !isSupportedSnapshotSchema(head.schemaVersion)) {
            return EntitlementDecision.Unavailable(UnavailableReason.VERSION_UNSUPPORTED)
        }
        val leaseLimit = checkNotNull(head.lease).leaseUntil.minus(skew)
        if (!evaluatedAt.isBefore(leaseLimit)) return EntitlementDecision.Unavailable(UnavailableReason.SNAPSHOT_EXPIRED)
        // review n-1: 잠금 대기 뒤 now가 이미 lease 한도를 넘었으면 관계·권리 판정 전에 만료로 멈춘다(오래된 Snapshot으로 거절 사유도 드러내지 않는다).
        if (!now.isBefore(leaseLimit)) return EntitlementDecision.Unavailable(UnavailableReason.SNAPSHOT_EXPIRED)
        checkNotNull(content) { "a filled head has its stored content" }
        check(content.revision == head.revision && content.contentHash == head.contentHash) { "stored content must be the head revision" }
        val account = content.account(question.mid) ?: return EntitlementDecision.Unavailable(UnavailableReason.SNAPSHOT_MISSING)
        val t = evaluatedAt

        // F1: 관계(Site 소속·연결·PRIMARY).
        val site = content.sites.firstOrNull { it.siteId == question.siteId } ?: return notFound(RefusalDetail.SITE_UNKNOWN)
        if (site.lifecycle != Lifecycle.ACTIVE) return notFound(RefusalDetail.SITE_TERMINATED)
        val assignment = account.assignments.firstOrNull { it.siteId == site.siteId && it.status == AssignmentStatus.ACTIVE && it.period.contains(t) }
            ?: return notFound(RefusalDetail.ASSIGNMENT_NOT_EFFECTIVE)
        val primary = account.assignments.firstOrNull { it.role == AssignmentRole.PRIMARY && it.status == AssignmentStatus.ACTIVE && it.period.contains(t) }
            ?: return notFound(RefusalDetail.PRIMARY_NOT_EFFECTIVE)

        // E1: 운영 중지(Business·Merchant·MID — 응답은 한 reason, 범위는 내부 기록).
        val party = content.party
        suspendedScope(party, account)?.let { scope ->
            val detail = if (scope == SuspendedScope.MID) RefusalDetail.MID_SUSPENDED else RefusalDetail.PARTY_SUSPENDED
            return EntitlementDecision.Refused(RefusalReason.MERCHANT_SUSPENDED, detail, scope)
        }
        // E2: 계약 적용 구간.
        val window = account.contractWindows.firstOrNull { it.siteId == site.siteId && it.period.contains(t) }
            ?: return refused(RefusalReason.CONTRACT_NOT_EFFECTIVE, RefusalDetail.CONTRACT_WINDOW)
        // E3: Site PG 심사.
        if (site.reviewStatus != SiteReviewStatus.APPROVED) return refused(RefusalReason.METHOD_NOT_ENABLED, RefusalDetail.SITE_REVIEW)
        // E4: 개통 + Site 적용 확인.
        if (account.activations.none { it.paymentMethod == question.paymentMethod && it.status == ActivationStatus.ACTIVE }) {
            return refused(RefusalReason.METHOD_NOT_ENABLED, RefusalDetail.ACTIVATION)
        }
        if (account.siteConfirmations.none { it.siteId == site.siteId && it.paymentMethod == question.paymentMethod }) {
            return refused(RefusalReason.METHOD_NOT_ENABLED, RefusalDetail.SITE_CONFIRMATION)
        }
        // E5: 통화.
        val currency = account.currencies.firstOrNull { it.currency == question.currency && it.period.contains(t) }
            ?: return refused(RefusalReason.CURRENCY_NOT_ENABLED, RefusalDetail.CURRENCY)

        val nextBoundary = listOfNotNull(assignment.period.to, primary.period.to, window.period.to, currency.period.to).filter { it.isAfter(t) }.minOrNull()
        val validUntil = listOfNotNull(leaseLimit, nextBoundary).min()
        check(validUntil.isAfter(t)) { "an allowed decision is valid after the evaluation time" }
        if (!now.isBefore(validUntil)) return EntitlementDecision.Unavailable(UnavailableReason.SNAPSHOT_EXPIRED)
        return EntitlementDecision.Allowed(content.snapshotVersion, currency.currency, currency.minorUnit, validUntil)
    }

    private fun suspendedScope(party: Party, account: AccountEntry): SuspendedScope? = when {
        party.businessLifecycle != Lifecycle.ACTIVE || party.businessBlocked -> SuspendedScope.BUSINESS
        party.merchantLifecycle != Lifecycle.ACTIVE || party.merchantBlocked -> SuspendedScope.MERCHANT
        account.lifecycle != Lifecycle.ACTIVE || account.blocked -> SuspendedScope.MID
        else -> null
    }

    private fun notFound(detail: RefusalDetail) = EntitlementDecision.NotFoundOrForbidden(detail)

    private fun refused(reason: RefusalReason, detail: RefusalDetail) = EntitlementDecision.Refused(reason, detail, null)
}

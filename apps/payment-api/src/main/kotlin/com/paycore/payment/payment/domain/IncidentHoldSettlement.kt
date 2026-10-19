package com.paycore.payment.payment.domain

/**
 * 사건 보류 해제의 domain 연산(D-AUTH-002·D-AUTH-010·D-AUTH-011): control의 해제 기록과, 늦은 승인으로 다시 기록한(FORCED) 일 한도 점유의
 * 정리를 한 연산으로 묶는다. 해제 판정 NO_EFFECT → 점유 재해제, REAL_APPROVAL·DOUBLE_APPROVAL_CORRECTED → 소비(취소 미복원, D-AUTH-007).
 * 모든 검사를 먼저 하고 통과할 때만 바꾼다. 해제 실행 Use Case(운영자 인증·보정 실행·재대사 MATCH 증거 확인)는 RECON-CONTRACT-001·
 * Q-ADMIN-AUTH 이후이며 이 연산은 domain 불변식까지다.
 *
 * [authorization]·[usage]는 hold 대상 Payment의 승인과 그 점유 범위다. 승인 operation이 없던 hold(예: 승인 기록이 아직 없는 복원)는 둘 다
 * null로 넘긴다.
 */
object IncidentHoldSettlement {
    fun release(
        control: OrderPaymentControl,
        release: IncidentHoldRelease,
        authorization: PaymentAuthorization?,
        usage: DailyLimitUsage?,
    ) {
        require((authorization == null) == (usage == null)) { "authorization and limit usage are given together" }
        if (authorization != null && usage != null) {
            require(authorization.paymentId == release.hold.paymentId) { "authorization must belong to the held payment" }
            require(authorization.orderKey == control.orderKey) { "authorization must belong to this order" }
            require(usage.key == authorization.limitKey) { "limit usage must be the authorization's reservation scope" }
            checkNotNull(usage.reservationOf(authorization.id)) { "the held authorization has no limit reservation" }
        }
        control.releaseIncidentHold(release)
        if (authorization != null) reopenForUnexplainedApprovals(control, release, authorization)
        // 같은 Payment에 다른 활성 hold가 남아 있으면 FORCED 점유를 정리하지 않는다(활성 hold ⇔ FORCED 일치, review round2 m-6).
        val otherActive = control.activeIncidentHolds.any { it.paymentId == release.hold.paymentId }
        // AUTH-001 m-11(D-AUTH-010 "REAL이면 소비"): 이 Payment hold의 해제 기록 중 실제 승인 판정이 하나라도 있으면 기관 승인이 있었으므로 소비로 정리한다.
        val finding = if (control.hasRealApprovalRelease(release.hold.paymentId)) HoldReleaseFinding.REAL_APPROVAL_CONFIRMED else release.finding
        if (authorization != null && usage != null && !otherActive) usage.settleForced(authorization.id, finding)
    }

    /**
     * D-AUTH1-005(Q-AUTH1-05, 검토 #2 m-D)·D-AUTH1-006(Q-AUTH1-06 — 해제 판정과 무관, NO_EFFECT 포함): CONFLICTING·SUCCESS_AFTER_TERMINATION hold를 해제하면, 해제가 설명하지 못하는
     * 승인 관찰이 남는지 본다. NO_EFFECT 판정은 어떤 승인도 설명하지 않는다(설명 집합에 들어가지 않음 — D-AUTH-012).
     * 남으면 **같은 연산(같은 TX)** 에서 occurrence + 1 hold를 연다(fail-closed — 해제 자체는 성공). 새 hold의 출처는 설명되지 않은 첫 관찰의 (key, variant).
     * "설명"은 [AuthorizationResolution]의 재보고 판정과 같다: 확정 거래와 승인 조건 전체가 같거나, REAL·DOUBLE로 해제된 이 Payment hold를 연 관찰과 승인 조건 전체가 같다.
     * 다른 hold의 출처 관찰은 그 hold의 해제가 처분하므로 제외한다 — 남는 것은 활성 hold에 흡수됐던 승인이다(같은 키의 다른 variant 포함).
     */
    private fun reopenForUnexplainedApprovals(control: OrderPaymentControl, release: IncidentHoldRelease, authorization: PaymentAuthorization) {
        val reason = release.hold.reason
        if (reason != IncidentReason.CONFLICTING_INSTITUTION_RESULT && reason != IncidentReason.SUCCESS_AFTER_TERMINATION) return
        val paymentId = release.hold.paymentId
        val refs = authorization.observationRefs
        val explainedSources = control.realApprovalReleasedHoldSources(paymentId)
        val explainedTerms = buildSet {
            authorization.transaction?.terms?.let(::add)
            refs.filter { (_, ref) -> ref in explainedSources }.mapNotNullTo(this) { (o, _) -> o.approved }
        }
        val holdSources = control.incidentHolds.filter { it.paymentId == paymentId }.mapNotNull { it.sourceObservation }.toSet()
        val unexplained = refs.firstOrNull { (o, ref) ->
            o.outcome == InstitutionOutcome.APPROVED && o.approved !in explainedTerms && ref !in holdSources
        } ?: return
        control.reopenForUnexplainedApproval(release.hold, unexplained.second)
    }
}

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
        // 같은 Payment에 다른 활성 hold가 남아 있으면 FORCED 점유를 정리하지 않는다(활성 hold ⇔ FORCED 일치, review round2 m-6).
        val otherActive = control.activeIncidentHolds.any { it.paymentId == release.hold.paymentId }
        if (authorization != null && usage != null && !otherActive) usage.settleForced(authorization.id, release.finding)
    }
}

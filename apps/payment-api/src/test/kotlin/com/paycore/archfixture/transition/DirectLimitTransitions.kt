package com.paycore.archfixture.transition

import com.paycore.payment.payment.domain.AuthorizationId
import com.paycore.payment.payment.domain.DailyLimitUsage
import com.paycore.payment.payment.domain.HoldReleaseFinding

/**
 * ArchUnit 위반 fixture(시험 전용, 실행하지 않는다): domain 밖에서 일 한도 점유를 소비·해제·재점유·정리한다(승인 결과·hold 해제와 짝이 깨짐).
 * 대상: DailyLimitUsage.consume, release, reoccupyAfterLateApproval, settleForced.
 */
class DirectLimitTransitions {
    fun bypass(usage: DailyLimitUsage, id: AuthorizationId) {
        usage.consume(id)
        usage.release(id)
        usage.reoccupyAfterLateApproval(id, null)
        usage.settleForced(id, HoldReleaseFinding.NO_EFFECT_CONFIRMED)
    }
}

package com.paycore.archfixture.startauth

import com.paycore.payment.payment.domain.AuthorizationId
import com.paycore.payment.payment.domain.DailyLimitUsage
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.TransactionLimitPolicy

/** ArchUnit 위반 fixture(시험 전용, 실행하지 않는다): 승인 시작 밖에서 일 한도를 점유한다. 대상 = DailyLimitUsage.reserve. */
class DirectLimitReservation {
    fun bypass(usage: DailyLimitUsage, id: AuthorizationId, money: Money, policy: TransactionLimitPolicy) {
        usage.reserve(id, money, policy)
    }
}
